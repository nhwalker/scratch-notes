package io.github.nhwalker.myformatter;

import com.diffplug.gradle.spotless.SpotlessExtension;
import java.time.Year;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.plugins.ide.eclipse.model.EclipseModel;

/**
 * The {@code my-formatter} plugin.
 *
 * <p>Applying this plugin to a project will:
 *
 * <ul>
 *   <li>apply the Spotless plugin and configure a Java format that uses the
 *       Palantir Java formatter, a license header, and a set of highly readable
 *       clean-up steps; and
 *   <li>tighten the built-in Eclipse JDT compiler warnings &mdash; but only if
 *       the {@code eclipse} plugin is also applied to the project.
 * </ul>
 */
public class MyFormatterPlugin implements Plugin<Project> {

    /** Plugin id of the Spotless Gradle plugin. */
    private static final String SPOTLESS_PLUGIN_ID = "com.diffplug.spotless";

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
            java.custom("addHeaderIfMissing", MyFormatterPlugin::addHeaderIfMissing);
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

        // @SuppressWarnings should be honoured, and unused/unhandled tokens
        // flagged so suppressions stay meaningful.
        p.put(prefix + "suppressWarnings", "enabled");
        p.put(prefix + "unusedWarningToken", "warning");
        p.put(prefix + "unhandledWarningToken", "warning");

        return p;
    }
}
