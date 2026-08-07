package com.example.e2e.runner;

import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TagFilter;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import org.junit.platform.reporting.legacy.xml.LegacyXmlReportGeneratingListener;

import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared entry point for every distribution built with the
 * {@code com.example.e2e-distribution} plugin. Written once, here — consumer
 * projects contain only tests.
 *
 * Discovers and runs JUnit Platform tests, prints a summary, writes JUnit XML
 * reports, and exits non-zero on failure. Configured via system properties
 * (the plugin bakes project-level defaults into the launch scripts; operators
 * override at run time with E2E_TESTS_OPTS):
 *
 * <ul>
 *   <li>{@code e2e.test.package} — package to scan; default: whole classpath</li>
 *   <li>{@code e2e.reports.dir} — XML report directory; default {@code test-reports}</li>
 *   <li>{@code e2e.include.tags} / {@code e2e.exclude.tags} — comma-separated tag filters</li>
 * </ul>
 */
public final class E2eTestRunner {

    public static void main(String[] args) throws Exception {
        Path reportsDir = Paths.get(System.getProperty("e2e.reports.dir", "test-reports"));
        Files.createDirectories(reportsDir);

        LauncherDiscoveryRequestBuilder builder = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectors());

        String includeTags = System.getProperty("e2e.include.tags", "").trim();
        if (!includeTags.isEmpty()) {
            builder.filters(TagFilter.includeTags(includeTags.split(",")));
        }
        String excludeTags = System.getProperty("e2e.exclude.tags", "").trim();
        if (!excludeTags.isEmpty()) {
            builder.filters(TagFilter.excludeTags(excludeTags.split(",")));
        }
        LauncherDiscoveryRequest request = builder.build();

        PrintWriter out = new PrintWriter(System.out, true);
        SummaryGeneratingListener summary = new SummaryGeneratingListener();
        LegacyXmlReportGeneratingListener xmlReports =
                new LegacyXmlReportGeneratingListener(reportsDir, out);

        Launcher launcher = LauncherFactory.create();
        launcher.execute(request, summary, xmlReports);

        TestExecutionSummary result = summary.getSummary();
        result.printTo(out);
        result.printFailuresTo(out);
        out.printf("XML reports written to: %s%n", reportsDir.toAbsolutePath());

        System.exit(result.getTotalFailureCount() == 0 ? 0 : 1);
    }

    private static List<? extends DiscoverySelector> selectors() {
        String testPackage = System.getProperty("e2e.test.package", "").trim();
        if (!testPackage.isEmpty()) {
            return List.of(DiscoverySelectors.selectPackage(testPackage));
        }
        // No package configured: scan every classpath root. Engines ignore
        // jars without test classes, so this is safe, just slower.
        Set<Path> roots = Arrays.stream(
                        System.getProperty("java.class.path").split(File.pathSeparator))
                .map(Paths::get)
                .filter(Files::exists)
                .collect(Collectors.toSet());
        return DiscoverySelectors.selectClasspathRoots(roots);
    }

    private E2eTestRunner() {
    }
}
