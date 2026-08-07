package io.github.nhwalker.gradle

import io.qameta.allure.gradle.base.metadata.AllureResultType
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Usage
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.TestReportAggregationPlugin
import org.gradle.api.provider.MapProperty
import org.gradle.api.reporting.ReportingExtension
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.AggregateTestReport
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.gradle.testing.jacoco.plugins.JacocoCoverageReport
import org.gradle.testing.jacoco.plugins.JacocoReportAggregationPlugin

import javax.inject.Inject

/**
 * Rolls up test results from every project in the build that applies the
 * test-setup plugin ({@code io.github.nhwalker.test-setup}). Apply this to the
 * root project (or a dedicated aggregator project).
 *
 * <p>Participating projects are discovered automatically — any project applying
 * the test-setup plugin is added as an aggregation dependency. Registered tasks:
 *
 * <ul>
 *   <li>{@code testAggregateReport} / {@code integrationTestAggregateReport} —
 *       merged JUnit HTML reports per suite.</li>
 *   <li>{@code testCodeCoverageReport} / {@code integrationTestCodeCoverageReport} —
 *       merged JaCoCo coverage reports per suite.</li>
 *   <li>{@code allureCollectResults} — collects Allure raw results into
 *       {@code build/allure-results}, one subdirectory per project, ready for the
 *       Allure CLI ({@code allure serve build/allure-results/*}). Report
 *       generation is intentionally left to the CLI.</li>
 * </ul>
 *
 * <p>The {@code testRollup} lifecycle task runs all of them. Run with
 * {@code --continue} to still get reports when some tests fail.
 */
class TestRollupPlugin implements Plugin<Project> {

    public static final String TEST_SETUP_PLUGIN_ID = 'io.github.nhwalker.test-setup'
    public static final String ALLURE_RESULTS_CONFIGURATION_NAME = 'allureResults'
    public static final String ALLURE_RESULTS_CLASSPATH_CONFIGURATION_NAME = 'allureResultsClasspath'
    public static final String ALLURE_COLLECT_TASK_NAME = 'allureCollectResults'
    public static final String ROLLUP_TASK_NAME = 'testRollup'

    /** Usage attribute value the Allure adapter plugin puts on its raw-results variant. */
    private static final String ALLURE_USAGE_NAME = 'Allure'

    /** Same attribute the Allure adapter plugin puts on its raw-results variant. */
    private static final Attribute<AllureResultType> ALLURE_RESULT_TYPE_ATTRIBUTE =
        Attribute.of('io.qameta.allure', AllureResultType)

    private static final List<String> SUITE_NAMES =
        [JavaPlugin.TEST_TASK_NAME, TestSetupPlugin.INTEGRATION_TEST_SUITE_NAME].asImmutable()

    @Override
    void apply(Project project) {
        project.plugins.apply(TestReportAggregationPlugin)
        project.plugins.apply(JacocoReportAggregationPlugin)

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

        def allureResults = project.configurations.create(ALLURE_RESULTS_CONFIGURATION_NAME) { c ->
            c.description = 'Projects contributing Allure raw results to the rollup.'
            c.canBeConsumed = false
            c.canBeResolved = false
        }
        def allureResultsClasspath = project.configurations.create(ALLURE_RESULTS_CLASSPATH_CONFIGURATION_NAME) { c ->
            c.description = 'Resolves Allure raw result directories from participating projects.'
            c.canBeConsumed = false
            c.canBeResolved = true
            c.visible = false
            c.extendsFrom(allureResults)
            c.attributes { attrs ->
                attrs.attribute(Category.CATEGORY_ATTRIBUTE,
                    project.objects.named(Category, Category.DOCUMENTATION))
                attrs.attribute(Usage.USAGE_ATTRIBUTE,
                    project.objects.named(Usage, ALLURE_USAGE_NAME))
                attrs.attribute(ALLURE_RESULT_TYPE_ATTRIBUTE, AllureResultType.RAW)
            }
        }

        def incomingArtifacts = allureResultsClasspath.incoming.artifacts
        def collectTask = project.tasks.register(ALLURE_COLLECT_TASK_NAME, CollectAllureResultsTask) { t ->
            t.group = LifecycleBasePlugin.VERIFICATION_GROUP
            t.description = 'Collects Allure raw results from all test-setup projects, one subdirectory per project.'
            t.resultFiles.from(incomingArtifacts.artifactFiles)
            t.resultDirectories.set(incomingArtifacts.resolvedArtifacts.map { artifacts ->
                artifacts.groupBy { subDirectoryFor(it.id.componentIdentifier) }
                    .collectEntries { dirName, group -> [dirName, group*.file as Set] }
            })
            t.outputDirectory.set(project.layout.buildDirectory.dir('allure-results'))
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
                project.dependencies.add(ALLURE_RESULTS_CONFIGURATION_NAME, candidate)
            }
        }

        project.tasks.register(ROLLUP_TASK_NAME) { t ->
            t.group = LifecycleBasePlugin.VERIFICATION_GROUP
            t.description = 'Generates aggregated JUnit and JaCoCo reports and collects Allure results across all test-setup projects.'
            t.dependsOn(reportTasks)
            t.dependsOn(collectTask)
        }
    }

    private static String subDirectoryFor(ComponentIdentifier id) {
        if (id instanceof ProjectComponentIdentifier) {
            def path = id.projectPath
            return path == ':' ? 'root' : path.substring(1).replace(':', '-')
        }
        return id.displayName.replaceAll('[^A-Za-z0-9._-]', '_')
    }

    /**
     * Copies each participating project's Allure raw results directory into
     * {@code outputDirectory/<project>}, preserving per-directory metadata files
     * (executor.json, environment.properties, categories.json) that a flat merge
     * would clobber.
     */
    abstract static class CollectAllureResultsTask extends DefaultTask {

        /** The resolved result directories; wires task dependencies and up-to-date checks. */
        @InputFiles
        @PathSensitive(PathSensitivity.RELATIVE)
        abstract ConfigurableFileCollection getResultFiles()

        /** Result directories grouped by target subdirectory name. */
        @Internal
        abstract MapProperty<String, Set<File>> getResultDirectories()

        @OutputDirectory
        abstract DirectoryProperty getOutputDirectory()

        @Inject
        protected abstract FileSystemOperations getFileSystemOperations()

        @TaskAction
        void collect() {
            File out = outputDirectory.get().asFile
            fileSystemOperations.delete { d -> d.delete(out) }
            out.mkdirs()
            resultDirectories.get().each { dirName, dirs ->
                fileSystemOperations.copy { c ->
                    c.from(dirs)
                    c.into(new File(out, dirName))
                }
            }
        }
    }
}
