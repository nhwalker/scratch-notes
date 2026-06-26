package io.github.nhwalker.mystyle;

import com.diffplug.gradle.spotless.SpotlessExtension;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Year;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.ltgt.gradle.errorprone.CheckSeverity;
import net.ltgt.gradle.errorprone.ErrorProneOptions;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.plugins.quality.CheckstyleExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.plugins.ide.eclipse.model.EclipseModel;

/**
 * The {@code my-style-plugin} plugin.
 *
 * <p>Applying this plugin to a project will:
 *
 * <ul>
 *   <li>apply the Spotless plugin and configure a Java format that uses the
 *       Palantir Java formatter, a license header, and a set of highly readable
 *       clean-up steps;
 *   <li>apply the Checkstyle plugin with a bundled config that enforces good
 *       practices (and deliberately leaves formatting to Spotless); and
 *   <li>tighten the built-in Eclipse JDT compiler warnings &mdash; but only if
 *       the {@code eclipse} plugin is also applied to the project.
 * </ul>
 */
public class MyStylePlugin implements Plugin<Project> {

    /** Plugin id of the Spotless Gradle plugin. */
    private static final String SPOTLESS_PLUGIN_ID = "com.diffplug.spotless";

    /** Checkstyle tool version pinned for the bundled config. */
    private static final String CHECKSTYLE_VERSION = "13.6.0";

    /** Classpath location of the bundled Checkstyle config. */
    private static final String CHECKSTYLE_CONFIG_RESOURCE = "/my-style/checkstyle.xml";

    /** Error Prone Gradle plugin id. */
    private static final String ERRORPRONE_PLUGIN_ID = "net.ltgt.errorprone";

    /** Error Prone engine + checks, added to the consumer's {@code errorprone} config. */
    private static final String ERROR_PRONE_CORE = "com.google.errorprone:error_prone_core:2.50.0";

    /** NullAway check, added to the consumer's {@code errorprone} config. */
    private static final String NULLAWAY = "com.uber.nullaway:nullaway:0.13.7";

    /** JSpecify nullness annotations ({@code @Nullable}, {@code @NullUnmarked}, ...). */
    private static final String JSPECIFY = "org.jspecify:jspecify:1.0.0";

    /**
     * Placeholder license header, added only to files that have no header yet.
     * The {@code $YEAR} token is replaced with the current year when the header
     * is inserted. Replace this text with your project's real header.
     */
    private static final String LICENSE_HEADER =
            """
            /*
             * Category: FIXME
             *
             * Copyright (C) $YEAR PLACEHOLDER ORGANIZATION. All rights reserved.
             *
             * PLACEHOLDER LICENSE HEADER -- replace this text with the real
             * license header for your project before publishing.
             */
            """;

    /**
     * Matches a file that already starts with <em>some</em> header: any leading
     * whitespace followed by the start of a block ({@code /*}) or line
     * ({@code //}) comment. Intentionally generous &mdash; once a developer fills
     * in {@code Category: FIXME} or changes the company name, the file still has
     * a header here, so we leave it untouched rather than reverting it.
     */
    private static final Pattern EXISTING_HEADER = Pattern.compile("\\s*(/\\*|//)");

    @Override
    public void apply(Project project) {
        configureSpotless(project);
        configureCheckstyle(project);
        configureErrorProne(project);
        configureEclipseWhenPresent(project);
    }

    /** Applies Spotless and configures a readable Palantir-based Java format. */
    private void configureSpotless(Project project) {
        project.getPluginManager().apply(SPOTLESS_PLUGIN_ID);

        SpotlessExtension spotless = project.getExtensions().getByType(SpotlessExtension.class);
        spotless.java(java -> {
            // Format the conventional Java source locations.
            java.target("src/**/*.java");

            // Let `// spotless:off` / `// spotless:on` fences opt code out.
            java.toggleOffOn();

            // Drop unused imports before formatting. Palantir itself owns the
            // *ordering* of imports, so we deliberately do not add an
            // importOrder() step (it would fight Palantir and break
            // idempotency).
            java.removeUnusedImports();

            // The core formatter: Palantir's modern, lambda-friendly,
            // 120-column Java style. formatJavadoc keeps Javadoc tidy too.
            java.palantirJavaFormat().formatJavadoc(true);

            // Keep type-use annotations on the right line after formatting.
            java.formatAnnotations();

            // Readable hygiene: no trailing whitespace, exactly one final
            // newline.
            java.trimTrailingWhitespace();
            java.endWithNewline();

            // Add the placeholder header last so it sits above the (now sorted)
            // package/import block -- but only on files that are missing a
            // header. Using a custom step (instead of licenseHeader, which would
            // normalize every file back to the canonical text) is what lets a
            // filled-in FIXME or a changed company name survive. The bump call
            // tells Spotless this custom step's behaviour is versioned.
            java.bumpThisNumberIfACustomStepChanges(1);
            java.custom("addHeaderIfMissing", MyStylePlugin::addHeaderIfMissing);
        });
    }

    /**
     * Spotless step that prepends {@link #LICENSE_HEADER} (with {@code $YEAR}
     * resolved) only when the file does not already start with a header. Files
     * that already have any leading comment are returned unchanged, which is what
     * makes the step idempotent and preserves edited headers.
     */
    private static String addHeaderIfMissing(String content) {
        if (EXISTING_HEADER.matcher(content).lookingAt()) {
            return content;
        }
        String header = LICENSE_HEADER.replace("$YEAR", String.valueOf(Year.now().getValue()));
        // LICENSE_HEADER already ends with a newline; the extra one leaves a
        // blank line between the header and the package declaration.
        return header + "\n" + content;
    }

    /**
     * Applies the (built-in) Checkstyle plugin, pins the tool version, and feeds
     * it the bundled config that enforces good practices while leaving formatting
     * to Spotless. The config travels inside this plugin's jar, so consumers do
     * not need to copy a {@code checkstyle.xml} into their projects.
     */
    private void configureCheckstyle(Project project) {
        project.getPluginManager().apply("checkstyle");

        CheckstyleExtension checkstyle = project.getExtensions().getByType(CheckstyleExtension.class);
        checkstyle.setToolVersion(CHECKSTYLE_VERSION);
        checkstyle.setConfig(
                project.getResources().getText().fromString(loadResource(CHECKSTYLE_CONFIG_RESOURCE)));
    }

    /** Reads a UTF-8 resource bundled in this plugin's jar into a String. */
    private static String loadResource(String resourcePath) {
        try (InputStream in = MyStylePlugin.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new GradleException("Bundled resource not found on classpath: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GradleException("Failed to read bundled resource: " + resourcePath, e);
        }
    }

    /**
     * Applies Error Prone + NullAway to the project's Java compilation. Gated on the
     * {@code java} plugin so that source sets, {@code JavaCompile} tasks, and the
     * {@code errorprone} configuration exist. The engine and NullAway are added to the
     * {@code errorprone} configuration (the compiler's annotation-processor path) and so
     * never reach the consumer's compile/runtime classpath; only the small JSpecify
     * annotations jar lands on the compile-only classpath.
     */
    private void configureErrorProne(Project project) {
        project.getPluginManager().withPlugin("java", applied -> {
            project.getPluginManager().apply(ERRORPRONE_PLUGIN_ID);

            DependencyHandler deps = project.getDependencies();
            deps.add("errorprone", ERROR_PRONE_CORE);
            deps.add("errorprone", NULLAWAY);
            deps.add("compileOnly", JSPECIFY);
            deps.add("testCompileOnly", JSPECIFY);

            // "Mine" = the packages this module actually has source for. Computed once,
            // here at configuration time, into a plain String -> config-cache safe.
            String annotatedPackages = discoverAnnotatedPackages(project);
            if (annotatedPackages.isEmpty()) {
                project.getLogger()
                        .warn("my-style-plugin: no source packages or project.group found; "
                                + "NullAway is disabled for {}.", project.getPath());
            }

            project.getTasks().withType(JavaCompile.class).configureEach(task -> {
                ErrorProneOptions ep = ((ExtensionAware) task.getOptions())
                        .getExtensions()
                        .getByType(ErrorProneOptions.class);
                ep.getDisableWarningsInGeneratedCode().set(true);
                if (annotatedPackages.isEmpty()) {
                    // Nothing to anchor NullAway to; leave the rest of Error Prone on.
                    ep.check("NullAway", CheckSeverity.OFF);
                } else {
                    // Check everything under our packages; opt out with @NullUnmarked.
                    ep.check("NullAway", CheckSeverity.ERROR);
                    ep.option("NullAway:AnnotatedPackages", annotatedPackages);
                    ep.option("NullAway:JSpecifyMode", "true");
                    ep.option("NullAway:HandleTestAssertionLibraries", "true");
                }
            });
        });
    }

    /**
     * Discovers the project's own top-level Java packages by scanning the source roots
     * of every source set, then reduces them to the minimal set of prefixes (a package
     * is dropped when a shorter kept package already covers it). Returns a comma-joined
     * list suitable for NullAway's {@code AnnotatedPackages}, or {@code project.group}
     * (else an empty string) when no source is found.
     */
    private static String discoverAnnotatedPackages(Project project) {
        JavaPluginExtension javaExt = project.getExtensions().findByType(JavaPluginExtension.class);
        Set<String> packages = new TreeSet<>();
        if (javaExt != null) {
            for (SourceSet sourceSet : javaExt.getSourceSets()) {
                for (File root : sourceSet.getJava().getSrcDirs()) {
                    collectPackages(root.toPath(), packages);
                }
            }
        }

        Set<String> minimal = new TreeSet<>();
        for (String pkg : packages) { // TreeSet => shorter prefixes are visited first
            if (minimal.stream().noneMatch(kept -> pkg.equals(kept) || pkg.startsWith(kept + "."))) {
                minimal.add(pkg);
            }
        }
        if (!minimal.isEmpty()) {
            return String.join(",", minimal);
        }
        String group = String.valueOf(project.getGroup());
        return group.isEmpty() ? "" : group;
    }

    /**
     * Walks a single source root, adding the package of every {@code .java} file (derived
     * from its directory relative to {@code root}). Skips {@code module-info.java} and any
     * default-package file (which would otherwise yield an everything-matching prefix).
     */
    private static void collectPackages(Path root, Set<String> packages) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("module-info.java"))
                    .forEach(p -> {
                        Path relativeDir = root.relativize(p).getParent();
                        if (relativeDir != null) { // null => default package, skip
                            packages.add(relativeDir.toString().replace(File.separatorChar, '.'));
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan source root: " + root, e);
        }
    }

    /**
     * Registers a callback that tightens Eclipse JDT warnings, firing only if
     * the {@code eclipse} plugin is (or later becomes) applied.
     */
    private void configureEclipseWhenPresent(Project project) {
        project.getPluginManager().withPlugin("eclipse", applied -> {
            EclipseModel eclipse = project.getExtensions().getByType(EclipseModel.class);
            // Merge our settings into .settings/org.eclipse.jdt.core.prefs. The
            // formatter keys make the Eclipse Java editor indent the same way
            // Palantir does, so editing in Eclipse and running spotlessApply
            // agree instead of fighting each other.
            eclipse.getJdt().getFile().withProperties(props -> {
                props.putAll(jdtWarningSettings());
                props.putAll(jdtPalantirFormatterSettings());
            });
        });
    }

    /**
     * Eclipse JDT formatter settings that mirror the Palantir Java style for the
     * things that matter for indentation: 4-space indents (spaces, never tabs),
     * an 8-space continuation indent, and a 120-column line width. With these in
     * {@code org.eclipse.jdt.core.prefs} the Eclipse editor types and auto-indents
     * exactly as Palantir formats.
     */
    private static Map<String, String> jdtPalantirFormatterSettings() {
        Map<String, String> p = new LinkedHashMap<>();
        String prefix = "org.eclipse.jdt.core.formatter.";

        // Indent with spaces, 4 per level (Palantir uses spaces, not tabs).
        p.put(prefix + "tabulation.char", "space");
        p.put(prefix + "tabulation.size", "4");
        p.put(prefix + "indentation.size", "4");
        p.put(prefix + "use_tabs_only_for_leading_indentations", "false");
        p.put(prefix + "indent_empty_lines", "false");

        // Continuation lines indent by two units (2 x 4 = 8 spaces), matching
        // Palantir's +8 continuation indent.
        p.put(prefix + "continuation_indentation", "2");
        p.put(prefix + "continuation_indentation_for_array_initializer", "2");

        // 120-column lines, like Palantir.
        p.put(prefix + "lineSplit", "120");
        p.put(prefix + "comment.line_length", "120");

        return p;
    }

    /**
     * Curated set of Eclipse JDT compiler problem severities, dialled up from
     * their defaults to surface genuinely useful warnings without drowning the
     * developer in noise.
     */
    private static Map<String, String> jdtWarningSettings() {
        Map<String, String> p = new LinkedHashMap<>();
        String prefix = "org.eclipse.jdt.core.compiler.problem.";

        // Dead / unused code.
        p.put(prefix + "unusedImport", "warning");
        p.put(prefix + "unusedLocal", "warning");
        p.put(prefix + "unusedPrivateMember", "warning");
        p.put(prefix + "unusedParameter", "warning");
        p.put(prefix + "unusedDeclaredThrownException", "warning");
        p.put(prefix + "unusedLabel", "warning");
        p.put(prefix + "deadCode", "warning");
        p.put(prefix + "emptyStatement", "warning");
        p.put(prefix + "unnecessaryElse", "warning");
        p.put(prefix + "unnecessaryTypeCheck", "warning");

        // Correctness hazards.
        p.put(prefix + "noEffectAssignment", "warning");
        p.put(prefix + "nullReference", "warning");
        p.put(prefix + "potentialNullReference", "warning");
        p.put(prefix + "redundantNullCheck", "warning");
        p.put(prefix + "comparingIdentical", "warning");
        p.put(prefix + "finallyBlockNotCompletingNormally", "warning");
        p.put(prefix + "hiddenCatchBlock", "warning");
        p.put(prefix + "incompleteEnumSwitch", "warning");
        p.put(prefix + "switchDefault", "warning");
        p.put(prefix + "fallthroughCase", "warning");

        // Resource leaks.
        p.put(prefix + "unclosedCloseable", "warning");
        p.put(prefix + "potentiallyUnclosedCloseable", "warning");

        // Generics / type safety.
        p.put(prefix + "uncheckedTypeOperation", "warning");
        p.put(prefix + "rawTypeReference", "warning");
        p.put(prefix + "unsafeTypeOperation", "warning");

        // Naming / shadowing.
        p.put(prefix + "fieldHiding", "warning");
        p.put(prefix + "localVariableHiding", "warning");
        p.put(prefix + "typeParameterHiding", "warning");
        p.put(prefix + "overridingPackageDefaultMethod", "warning");

        // Annotations & API hygiene.
        p.put(prefix + "missingOverrideAnnotation", "warning");
        p.put(prefix + "missingOverrideAnnotationForInterfaceMethodImplementation", "enabled");
        p.put(prefix + "missingDeprecatedAnnotation", "warning");
        p.put(prefix + "annotationSuperInterface", "warning");
        p.put(prefix + "deprecation", "warning");
        p.put(prefix + "deprecationInDeprecatedCode", "enabled");
        p.put(prefix + "missingSerialVersion", "warning");
        p.put(prefix + "varargsArgumentNeedCast", "warning");
        p.put(prefix + "discouragedReference", "warning");
        p.put(prefix + "forbiddenReference", "error");

        // Keep these escalated to errors -- they are illegal in newer sources.
        p.put(prefix + "assertIdentifier", "error");
        p.put(prefix + "enumIdentifier", "error");

        // @SuppressWarnings should be honoured. Allow unrecognized tokens
        // (e.g. "checkstyle:..." or other tools' tokens) by ignoring
        // unhandledWarningToken -- otherwise Eclipse flags every non-JDT token.
        // unusedWarningToken stays a warning so JDT's own tokens don't rot.
        p.put(prefix + "suppressWarnings", "enabled");
        p.put(prefix + "unusedWarningToken", "warning");
        p.put(prefix + "unhandledWarningToken", "ignore");

        return p;
    }
}
