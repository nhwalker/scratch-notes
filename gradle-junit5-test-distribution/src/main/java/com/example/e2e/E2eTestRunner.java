package com.example.e2e;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import org.junit.platform.reporting.legacy.xml.LegacyXmlReportGeneratingListener;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Entry point of the distributable e2e suite. Discovers every JUnit 5 test
 * in {@code com.example.e2e.tests}, runs it, writes JUnit XML reports, and
 * exits non-zero when anything fails — so the launch script's exit code can
 * drive whatever automation invokes the suite on the air-gapped side.
 *
 * Configuration is passed as system properties, e.g.:
 *   bin/e2e-tests                                    (uses defaults)
 *   E2E_TESTS_OPTS="-De2e.target.url=https://sut.internal \
 *                   -De2e.reports.dir=/data/reports" bin/e2e-tests
 */
public final class E2eTestRunner {

    private static final String TEST_PACKAGE =
            System.getProperty("e2e.test.package", "com.example.e2e.tests");

    public static void main(String[] args) throws Exception {
        Path reportsDir = Paths.get(System.getProperty("e2e.reports.dir", "test-reports"));
        Files.createDirectories(reportsDir);

        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectPackage(TEST_PACKAGE))
                .build();

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

    private E2eTestRunner() {
    }
}
