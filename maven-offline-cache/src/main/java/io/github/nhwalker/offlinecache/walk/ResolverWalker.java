package io.github.nhwalker.offlinecache.walk;

import io.github.nhwalker.offlinecache.Gav;
import io.github.nhwalker.offlinecache.RunConfig;
import io.github.nhwalker.offlinecache.catalog.Catalog;
import io.github.nhwalker.offlinecache.catalog.CatalogEntry;
import io.github.nhwalker.offlinecache.catalog.ClassifierHint;
import io.github.nhwalker.offlinecache.report.Issue;
import io.github.nhwalker.offlinecache.report.Report;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResolutionException;
import org.eclipse.aether.resolution.VersionRangeResult;
import org.eclipse.aether.transfer.ArtifactNotFoundException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maven Resolver implementation of {@link Walker} (design doc §5).
 *
 * <p>Breadth-first over coordinates with <b>no conflict resolution</b>: every version requested
 * anywhere is visited. The output directory is Resolver's local repository, so every file Resolver
 * downloads (including parent POMs and imported BOMs read while building the effective POM) lands
 * there in Maven layout.
 *
 * <p>Per coordinate:
 * <ol>
 *   <li>POM (required) → packaging, Gradle-metadata marker.</li>
 *   <li>Effective-POM dependencies via {@code readArtifactDescriptor}: compile/runtime scope only,
 *       optional ones skipped, exclusions ignored (superset). Classifier/extension dependencies
 *       are fetched as required artifacts. Relocations are followed (warning).</li>
 *   <li>Main artifact (required unless packaging is {@code pom}).</li>
 *   <li>{@code .module} when the POM has Gradle's marker: files, dependencies, constraints and
 *       {@code available-at} targets of JVM-relevant variants.</li>
 *   <li>Optional probes: hint classifiers and {@code sources}.</li>
 * </ol>
 * After the queue drains, constraint versions for modules already in the graph are added and the
 * walk continues until nothing new appears (§5.3).
 */
public final class ResolverWalker implements Walker {

    private static final Set<String> FOLLOWED_SCOPES = Set.of("", "compile", "runtime");

    private final RunConfig config;

    public ResolverWalker(RunConfig config) {
        this.config = config;
    }

    @Override
    public Set<Gav> walk(Catalog catalog, Report report) {
        RepositorySystem system = ResolverSessionFactory.newRepositorySystem();
        try (RepositorySystemSession.CloseableSession session = ResolverSessionFactory.newSession(
                system, config, new ChecksumReportingListener(report))) {
            Run run = new Run(system, session, ResolverSessionFactory.remoteRepositories(config), catalog, report);
            return run.execute();
        } finally {
            system.shutdown();
        }
    }

    /** State of one walk. */
    private static final class Run {
        private final RepositorySystem system;
        private final RepositorySystemSession session;
        private final List<RemoteRepository> repos;
        private final Catalog catalog;
        private final Report report;

        private final Deque<Gav> queue = new ArrayDeque<>();
        private final Map<Gav, String> requestedBy = new LinkedHashMap<>();
        private final Set<Gav> visited = new LinkedHashSet<>();
        private final Set<String> visitedModules = new HashSet<>();
        private final Set<String> fetched = new HashSet<>();
        private final List<GradleModule.ModuleDependency> constraints = new ArrayList<>();
        private final Set<ClassifierHint> usedHints = new HashSet<>();

        Run(RepositorySystem system, RepositorySystemSession session, List<RemoteRepository> repos,
            Catalog catalog, Report report) {
            this.system = system;
            this.session = session;
            this.repos = repos;
            this.catalog = catalog;
            this.report = report;
        }

        Set<Gav> execute() {
            for (CatalogEntry e : catalog.entries()) {
                enqueue(e.gav(), "catalog " + e.kind().name().toLowerCase() + " '" + e.alias() + "'");
            }
            boolean added;
            do {
                while (!queue.isEmpty()) {
                    process(queue.removeFirst());
                }
                added = applyConstraints();
            } while (added);

            for (ClassifierHint h : catalog.hints()) {
                if (!usedHints.contains(h)) {
                    report.add(Issue.warning("hint-unmatched", h.key(),
                            "hint from " + h.source() + " matched no artifact in this run"));
                }
            }
            return Set.copyOf(visited);
        }

        private void enqueue(Gav gav, String by) {
            if (!requestedBy.containsKey(gav)) {
                requestedBy.put(gav, by);
                queue.addLast(gav);
            }
        }

        // ------------------------------------------------------------------ per coordinate

        private void process(Gav gav) {
            if (!visited.add(gav)) {
                return;
            }
            visitedModules.add(gav.module());
            String by = requestedBy.get(gav);

            // 1. POM
            Fetch pom = fetch(gav, "", "pom");
            if (pom.outcome == Outcome.MISSING) {
                report.add(Issue.error("pom-not-found", gav.toString(), "POM not found (requested by " + by + ")"));
                return;
            }
            if (pom.outcome != Outcome.FOUND) {
                return; // transfer error already reported (or duplicate)
            }
            PomInfo info;
            try {
                info = PomInfo.read(pom.path);
            } catch (IOException e) {
                report.add(Issue.error("pom-invalid", gav.toString(), e.getMessage()));
                return;
            }

            // 2. Effective-POM dependencies (also downloads parents and imported BOMs)
            if (!readDescriptor(gav)) {
                return; // relocated or broken; already handled
            }

            // 3. Main artifact
            String mainExt = info.mainExtension();
            if (mainExt == null) {
                report.add(Issue.info("pom-only", gav.toString(), "packaging pom: no main artifact"));
            } else {
                Fetch main = fetch(gav, "", mainExt);
                if (main.outcome == Outcome.MISSING) {
                    report.add(Issue.error("main-artifact-missing", gav.toString(),
                            "packaging '" + info.packaging() + "' but " + fileName(gav, "", mainExt) + " not found"));
                }
            }

            // 4. Gradle Module Metadata
            boolean hasModuleFiles = false;
            if (info.gradleMetadata()) {
                hasModuleFiles = processModule(gav);
            } else {
                report.add(Issue.info("no-module", gav.toString(), "POM has no Gradle metadata marker"));
            }

            // 5. Optional probes
            probeHints(gav);
            if (catalog.includeSources() && (mainExt != null || hasModuleFiles)) {
                Fetch sources = fetch(gav, "sources", "jar");
                if (sources.outcome == Outcome.MISSING) {
                    report.add(Issue.info("no-sources", gav.toString(), "no sources jar"));
                }
            }
        }

        /** @return false when processing of this coordinate should stop (relocation, broken POM) */
        private boolean readDescriptor(Gav gav) {
            ArtifactDescriptorResult result;
            try {
                result = system.readArtifactDescriptor(session, new ArtifactDescriptorRequest(
                        new DefaultArtifact(gav.groupId(), gav.artifactId(), "", "pom", gav.version()), repos, null));
            } catch (ArtifactDescriptorException e) {
                report.add(Issue.error("pom-invalid", gav.toString(),
                        "effective POM could not be built (missing parent or imported BOM?): " + rootMessage(e)));
                return false;
            }
            for (Exception e : result.getExceptions()) {
                report.add(Issue.error("pom-invalid", gav.toString(), rootMessage(e)));
            }
            if (!result.getRelocations().isEmpty()) {
                Artifact target = result.getArtifact();
                Gav to = new Gav(target.getGroupId(), target.getArtifactId(), target.getVersion());
                report.add(Issue.warning("relocated", gav.toString(), "relocated to " + to + "; following"));
                enqueue(to, "relocation of " + gav);
                return false;
            }
            for (Dependency d : result.getDependencies()) {
                if (d.isOptional() || !FOLLOWED_SCOPES.contains(d.getScope())) {
                    continue;
                }
                Artifact a = d.getArtifact();
                String version = resolveVersion(a.getGroupId(), a.getArtifactId(), a.getVersion(), gav);
                if (version == null) {
                    continue;
                }
                Gav dep = new Gav(a.getGroupId(), a.getArtifactId(), version);
                enqueue(dep, gav.toString());
                fetchDeclaredArtifact(dep, a.getClassifier(), a.getExtension(), gav,
                        "classifier-dependency-missing");
            }
            return true;
        }

        /** Fetches a non-default artifact a dependency names (classifier and/or extension). Required. */
        private void fetchDeclaredArtifact(Gav dep, String classifier, String extension, Gav from, String code) {
            String c = classifier == null ? "" : classifier;
            String ext = extension == null || extension.isBlank() ? "jar" : extension;
            if (isJavadoc(c) || "pom".equals(ext) || (c.isEmpty() && "jar".equals(ext))) {
                return; // main jar / POM are handled when the coordinate itself is processed
            }
            Fetch f = fetch(dep, c, ext);
            if (f.outcome == Outcome.MISSING) {
                report.add(Issue.error(code, dep.toString(),
                        fileName(dep, c, ext) + " not found (declared by " + from + ")"));
            }
        }

        /** @return true if the module lists at least one file */
        private boolean processModule(Gav gav) {
            Fetch f = fetch(gav, "", "module");
            if (f.outcome == Outcome.MISSING) {
                report.add(Issue.warning("module-missing", gav.toString(),
                        "POM has Gradle's metadata marker but no .module file was found"));
                return false;
            }
            if (f.outcome == Outcome.FAILED) {
                return false;
            }
            GradleModule module;
            try {
                module = GradleModule.parse(f.path, gav);
            } catch (IOException e) {
                report.add(Issue.error("module-invalid", gav.toString(), "unreadable .module: " + e.getMessage()));
                return false;
            }
            if (!module.skippedVariants().isEmpty()) {
                report.add(Issue.info("module-variant-skipped", gav.toString(),
                        "non-JVM variants skipped: " + module.skippedVariants()));
            }
            for (String url : module.unsupportedFiles()) {
                report.add(Issue.warning("module-file-unsupported", gav.toString(),
                        "file '" + url + "' is outside the module directory or naming scheme; not downloaded"));
            }
            for (GradleModule.ModuleFile mf : module.files()) {
                if (isJavadoc(mf.classifier())) {
                    continue;
                }
                Fetch file = fetch(gav, mf.classifier(), mf.extension());
                if (file.outcome == Outcome.MISSING) {
                    report.add(Issue.error("module-file-missing", gav.toString(),
                            mf.url() + " is listed in the .module file but not found"));
                }
            }
            for (Gav target : module.availableAt()) {
                enqueue(target, "available-at in " + gav);
            }
            for (GradleModule.ModuleDependency d : module.dependencies()) {
                if (d.version() == null) {
                    continue; // version comes from a platform/constraint elsewhere
                }
                String version = resolveVersion(d.group(), d.module(), d.version(), gav);
                if (version == null) {
                    continue;
                }
                Gav dep = new Gav(d.group(), d.module(), version);
                enqueue(dep, gav + " (.module)");
                if (d.classifier() != null || d.extension() != null) {
                    fetchDeclaredArtifact(dep, d.classifier(), d.extension(), gav, "classifier-dependency-missing");
                }
            }
            constraints.addAll(module.constraints());
            return !module.files().isEmpty();
        }

        private void probeHints(Gav gav) {
            for (ClassifierHint h : catalog.hints()) {
                if (!h.matches(gav.groupId(), gav.artifactId())) {
                    continue;
                }
                usedHints.add(h);
                List<String> missing = new ArrayList<>();
                int found = 0;
                for (String classifier : h.classifiers()) {
                    if (isJavadoc(classifier)) {
                        continue;
                    }
                    Fetch f = fetch(gav, classifier, h.extension());
                    if (f.outcome == Outcome.MISSING) {
                        missing.add(classifier);
                    } else if (f.outcome != Outcome.FAILED) {
                        found++;
                    }
                }
                if (found == 0 && !missing.isEmpty()) {
                    report.add(Issue.warning("hint-no-classifiers", gav.toString(),
                            "hint '" + h.key() + "' matched but none of " + h.classifiers()
                                    + " exist with extension " + h.extension()));
                } else if (!missing.isEmpty()) {
                    report.add(Issue.info("hint-classifier-missing", gav.toString(),
                            "hint '" + h.key() + "': not published for " + missing));
                }
            }
        }

        /** Adds constraint versions for modules already in the graph. @return true if anything was queued */
        private boolean applyConstraints() {
            boolean added = false;
            for (GradleModule.ModuleDependency c : List.copyOf(constraints)) {
                if (c.version() == null || !visitedModules.contains(c.group() + ":" + c.module())) {
                    continue;
                }
                if (isRange(c.version())) {
                    continue; // ranges in constraints are not followed
                }
                Gav gav = new Gav(c.group(), c.module(), c.version());
                if (!requestedBy.containsKey(gav)) {
                    enqueue(gav, "dependency constraint");
                    added = true;
                }
            }
            return added;
        }

        // ------------------------------------------------------------------ helpers

        /** Plain versions pass through; ranges resolve to the highest available version. */
        private String resolveVersion(String group, String artifact, String version, Gav from) {
            if (version == null || version.isBlank()) {
                return null;
            }
            if (!isRange(version)) {
                return version;
            }
            try {
                VersionRangeResult r = system.resolveVersionRange(session, new VersionRangeRequest(
                        new DefaultArtifact(group, artifact, "", "pom", version), repos, null));
                if (r.getHighestVersion() == null) {
                    report.add(Issue.error("version-range-unresolved", group + ":" + artifact + ":" + version,
                            "no version matches (declared by " + from + ")"));
                    return null;
                }
                String chosen = r.getHighestVersion().toString();
                report.add(Issue.info("version-range-resolved", group + ":" + artifact + ":" + version,
                        "using " + chosen + " (declared by " + from + ")"));
                return chosen;
            } catch (VersionRangeResolutionException e) {
                report.add(Issue.error("version-range-unresolved", group + ":" + artifact + ":" + version,
                        rootMessage(e)));
                return null;
            }
        }

        /** Javadoc is excluded (design §7), including Gradle-variant javadoc like {@code gradle71-javadoc}. */
        static boolean isJavadoc(String classifier) {
            return classifier != null && (classifier.equals("javadoc") || classifier.endsWith("-javadoc"));
        }

        static boolean isRange(String version) {
            return version.startsWith("[") || version.startsWith("(") || version.endsWith("+")
                    || version.startsWith("latest.");
        }

        private Fetch fetch(Gav gav, String classifier, String extension) {
            String key = gav + ":" + classifier + ":" + extension;
            if (!fetched.add(key)) {
                return Fetch.DUPLICATE;
            }
            DefaultArtifact artifact = new DefaultArtifact(
                    gav.groupId(), gav.artifactId(), classifier, extension, gav.version());
            ArtifactResult result;
            try {
                result = system.resolveArtifact(session, new ArtifactRequest(artifact, repos, null));
            } catch (ArtifactResolutionException e) {
                result = e.getResults().isEmpty() ? null : e.getResults().get(0);
                if (result == null) {
                    report.add(Issue.error("transfer-failed", fileName(gav, classifier, extension), rootMessage(e)));
                    return Fetch.FAILED_FETCH;
                }
            }
            if (result.isResolved()) {
                if (result.getRepository() instanceof LocalRepository) {
                    report.add(Issue.info("already-present", fileName(gav, classifier, extension),
                            "already in output directory; not downloaded again"));
                }
                return new Fetch(Outcome.FOUND, result.getArtifact().getPath());
            }
            Exception notNotFound = result.getExceptions().stream()
                    .filter(ex -> !(ex instanceof ArtifactNotFoundException))
                    .findFirst()
                    .orElse(null);
            if (notNotFound != null) {
                report.add(Issue.error("transfer-failed", fileName(gav, classifier, extension), rootMessage(notNotFound)));
                return Fetch.FAILED_FETCH;
            }
            return Fetch.MISSING_FETCH;
        }

        private static String fileName(Gav gav, String classifier, String extension) {
            return gav.groupId().replace('.', '/') + "/" + gav.artifactId() + "/" + gav.version() + "/"
                    + gav.artifactId() + "-" + gav.version()
                    + (classifier == null || classifier.isEmpty() ? "" : "-" + classifier) + "." + extension;
        }

        private static String rootMessage(Throwable t) {
            StringBuilder sb = new StringBuilder(String.valueOf(t.getMessage()));
            Throwable cause = t.getCause();
            Set<Throwable> seen = new HashSet<>();
            while (cause != null && seen.add(cause)) {
                if (cause.getMessage() != null && !sb.toString().contains(cause.getMessage())) {
                    sb.append(" <- ").append(cause.getMessage());
                }
                cause = cause.getCause();
            }
            return sb.toString();
        }
    }

    private enum Outcome { FOUND, MISSING, FAILED, DUPLICATE }

    private record Fetch(Outcome outcome, Path path) {
        static final Fetch DUPLICATE = new Fetch(Outcome.DUPLICATE, null);
        static final Fetch MISSING_FETCH = new Fetch(Outcome.MISSING, null);
        static final Fetch FAILED_FETCH = new Fetch(Outcome.FAILED, null);
    }
}
