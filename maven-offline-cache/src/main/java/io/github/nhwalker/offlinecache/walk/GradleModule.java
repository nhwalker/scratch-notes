package io.github.nhwalker.offlinecache.walk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nhwalker.offlinecache.Gav;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The parts of a Gradle Module Metadata ({@code .module}) file the walk needs (design doc §5.2, §5.3).
 *
 * <p>Only JVM-relevant variants are used (design D3): variants whose
 * {@code org.jetbrains.kotlin.platform.type} is something other than {@code jvm} / {@code common},
 * or whose {@code org.gradle.jvm.environment} is {@code android}, are skipped.
 *
 * @param files            files of used variants, in the module's own directory
 * @param unsupportedFiles file URLs that are not in the module's directory / naming scheme
 * @param dependencies     dependencies of used variants
 * @param constraints      dependency constraints of used variants
 * @param availableAt      modules that used variants redirect to ({@code available-at})
 * @param skippedVariants  names of variants skipped as non-JVM
 */
public record GradleModule(List<ModuleFile> files, List<String> unsupportedFiles,
                           List<ModuleDependency> dependencies, List<ModuleDependency> constraints,
                           List<Gav> availableAt, List<String> skippedVariants) {

    /** A file in the module's directory, as classifier + extension. */
    public record ModuleFile(String classifier, String extension, String url) {
    }

    /**
     * A dependency or constraint. {@code version} may be null (no version given) or a range.
     * {@code classifier}/{@code extension} come from {@code thirdPartyCompatibility.artifactSelector}
     * and are null when absent.
     */
    public record ModuleDependency(String group, String module, String version,
                                   String classifier, String extension) {
    }

    private static final String KOTLIN_PLATFORM = "org.jetbrains.kotlin.platform.type";
    private static final String JVM_ENVIRONMENT = "org.gradle.jvm.environment";
    private static final Set<String> JVM_KOTLIN_PLATFORMS = Set.of("jvm", "common");

    public static GradleModule parse(Path file, Gav owner) throws IOException {
        JsonNode root = new ObjectMapper().readTree(file.toFile());
        List<ModuleFile> files = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        List<ModuleDependency> deps = new ArrayList<>();
        List<ModuleDependency> constraints = new ArrayList<>();
        List<Gav> availableAt = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (JsonNode variant : root.path("variants")) {
            String name = variant.path("name").asText("?");
            JsonNode attributes = variant.path("attributes");
            if (!isJvmRelevant(attributes)) {
                skipped.add(name);
                continue;
            }
            for (JsonNode f : variant.path("files")) {
                String url = f.path("url").asText(f.path("name").asText(""));
                ModuleFile mf = toModuleFile(owner, url);
                if (mf == null) {
                    if (!unsupported.contains(url)) unsupported.add(url);
                } else if (!files.contains(mf)) {
                    files.add(mf);
                }
            }
            for (JsonNode d : variant.path("dependencies")) {
                deps.add(toDependency(d));
            }
            for (JsonNode c : variant.path("dependencyConstraints")) {
                constraints.add(toDependency(c));
            }
            JsonNode at = variant.path("available-at");
            if (at.isObject()) {
                String g = at.path("group").asText("");
                String m = at.path("module").asText("");
                String v = at.path("version").asText("");
                if (!g.isEmpty() && !m.isEmpty() && !v.isEmpty()) {
                    Gav target = new Gav(g, m, v);
                    if (!availableAt.contains(target)) availableAt.add(target);
                }
            }
        }
        return new GradleModule(files, unsupported, deps, constraints, availableAt, skipped);
    }

    static boolean isJvmRelevant(JsonNode attributes) {
        JsonNode platform = attributes.path(KOTLIN_PLATFORM);
        if (!platform.isMissingNode() && !JVM_KOTLIN_PLATFORMS.contains(platform.asText())) {
            return false;
        }
        return !"android".equals(attributes.path(JVM_ENVIRONMENT).asText(""));
    }

    private static ModuleDependency toDependency(JsonNode d) {
        JsonNode version = d.path("version");
        String v = firstText(version, "strictly", "requires", "prefers");
        JsonNode selector = d.path("thirdPartyCompatibility").path("artifactSelector");
        String classifier = null;
        String extension = null;
        if (selector.isObject()) {
            classifier = textOrNull(selector, "classifier");
            extension = textOrNull(selector, "extension");
            if (extension == null) {
                extension = textOrNull(selector, "type");
            }
        }
        return new ModuleDependency(d.path("group").asText(), d.path("module").asText(), v, classifier, extension);
    }

    /**
     * Maps a file URL to classifier + extension, assuming Maven naming
     * {@code artifactId-version[-classifier].ext} in the same directory. Returns null otherwise.
     */
    static ModuleFile toModuleFile(Gav owner, String url) {
        String prefix = owner.artifactId() + "-" + owner.version();
        if (url.isEmpty() || url.contains("/") || !url.startsWith(prefix)) {
            return null;
        }
        String rest = url.substring(prefix.length());
        if (rest.startsWith(".") && rest.length() > 1) {
            return new ModuleFile("", rest.substring(1), url);
        }
        if (rest.startsWith("-")) {
            String r = rest.substring(1);
            int dot = r.indexOf('.');
            if (dot > 0 && dot < r.length() - 1) {
                return new ModuleFile(r.substring(0, dot), r.substring(dot + 1), url);
            }
        }
        return null;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String f : fields) {
            String t = textOrNull(node, f);
            if (t != null) return t;
        }
        return null;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return n.isTextual() && !n.asText().isBlank() ? n.asText() : null;
    }
}
