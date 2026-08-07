package io.github.nhwalker.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.TestReportAggregationPlugin
import org.gradle.api.reporting.ReportingExtension
import org.gradle.api.tasks.testing.AggregateTestReport
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.gradle.testing.jacoco.plugins.JacocoCoverageReport
import org.gradle.testing.jacoco.plugins.JacocoReportAggregationPlugin

/**
 * Rolls up test results from every project in the build that applies the
 * test-setup plugin ({@code io.github.nhwalker.test-setup}). Apply this to the
 * root project (or a dedicated aggregator project).
 *
 * <p>Participating projects are discovered automatically — any project applying
 * the test-setup plugin is added as an aggregation dependency. Registered reports:
 *
 * <ul>
 *   <li>{@code testAggregateReport} / {@code integrationTestAggregateReport} —
 *       merged JUnit HTML reports per suite.</li>
 *   <li>{@code testCodeCoverageReport} / {@code integrationTestCodeCoverageReport} —
 *       merged JaCoCo coverage reports per suite.</li>
 *   <li>{@code allureAggregateReport} — merged Allure report (from the Allure
 *       aggregate-report plugin).</li>
 * </ul>
 *
 * <p>The {@code testRollup} lifecycle task generates all of them. Run with
 * {@code --continue} to still get reports when some tests fail.
 */
class TestRollupPlugin implements Plugin<Project> {

    public static final String TEST_SETUP_PLUGIN_ID = 'io.github.nhwalker.test-setup'
    public static final String ALLURE_AGGREGATE_REPORT_PLUGIN_ID = 'io.qameta.allure-aggregate-report'
    public static final String ALLURE_AGGREGATE_CONFIGURATION_NAME = 'allureAggregateReport'
    public static final String ALLURE_AGGREGATE_REPORT_TASK_NAME = 'allureAggregateReport'
    public static final String ROLLUP_TASK_NAME = 'testRollup'

    private static final List<String> SUITE_NAMES =
        [JavaPlugin.TEST_TASK_NAME, TestSetupPlugin.INTEGRATION_TEST_SUITE_NAME].asImmutable()

    @Override
    void apply(Project project) {
        project.plugins.apply(TestReportAggregationPlugin)
        project.plugins.apply(JacocoReportAggregationPlugin)
        project.plugins.apply(ALLURE_AGGREGATE_REPORT_PLUGIN_ID)

        ReportingExtension reporting = project.extensions.getByType(ReportingExtension)
        def reportTasks = []
        SUITE_NAMES.each { suiteName ->
            def testReport = reporting.reports.create("${suiteName}AggregateReport", AggregateTestReport) { r ->
                r.testSuiteName = suiteName
            }
            def coverageReport = reporting.reports.create("${suiteName}CodeCoverageReport", JacocoCoverageReport) { r ->
                r.testSuiteName = suiteName
            }
            reportTasks << testReport.reportTask
            reportTasks << coverageReport.reportTask
        }

        project.rootProject.allprojects { candidate ->
            if (candidate == project) {
                return
            }
            candidate.plugins.withId(TEST_SETUP_PLUGIN_ID) {
                project.dependencies.add(
                    TestReportAggregationPlugin.TEST_REPORT_AGGREGATION_CONFIGURATION_NAME, candidate)
                project.dependencies.add(
                    JacocoReportAggregationPlugin.JACOCO_AGGREGATION_CONFIGURATION_NAME, candidate)
                project.dependencies.add(ALLURE_AGGREGATE_CONFIGURATION_NAME, candidate)
            }
        }

        project.tasks.register(ROLLUP_TASK_NAME) { t ->
            t.group = LifecycleBasePlugin.VERIFICATION_GROUP
            t.description = 'Generates aggregated JUnit, JaCoCo, and Allure reports across all test-setup projects.'
            t.dependsOn(reportTasks)
            t.dependsOn(project.tasks.named(ALLURE_AGGREGATE_REPORT_TASK_NAME))
        }
    }
}
