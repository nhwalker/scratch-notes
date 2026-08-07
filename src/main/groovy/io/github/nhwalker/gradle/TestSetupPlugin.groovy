package io.github.nhwalker.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.jvm.JvmTestSuite
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.gradle.testing.base.TestingExtension
import org.gradle.testing.jacoco.plugins.JacocoPlugin
import org.gradle.testing.jacoco.tasks.JacocoReport

/**
 * Sets up testing for a JVM project:
 *
 * <ul>
 *   <li>Configures the default {@code test} suite (unit tests) to use JUnit Jupiter.</li>
 *   <li>Registers an {@code integrationTest} JVM test suite ({@code src/integrationTest/java})
 *       that uses JUnit Jupiter, depends on the production classes, runs after {@code test},
 *       and is wired into {@code check}.</li>
 *   <li>Applies JaCoCo — every suite's test task produces coverage data, and a
 *       {@code jacocoIntegrationTestReport} task mirrors the built-in {@code jacocoTestReport}.</li>
 *   <li>Applies the Allure adapter — both suites write Allure raw results, exposed to
 *       aggregating projects via the {@code allureRawResultElements} variant.</li>
 * </ul>
 *
 * Projects applying this plugin are picked up automatically by the rollup plugin
 * ({@code io.github.nhwalker.test-rollup}).
 */
class TestSetupPlugin implements Plugin<Project> {

    public static final String INTEGRATION_TEST_SUITE_NAME = 'integrationTest'
    public static final String JACOCO_INTEGRATION_TEST_REPORT_TASK_NAME = 'jacocoIntegrationTestReport'
    public static final String ALLURE_ADAPTER_PLUGIN_ID = 'io.qameta.allure-adapter'

    @Override
    void apply(Project project) {
        project.plugins.apply(JavaPlugin)
        project.plugins.apply(JacocoPlugin)
        project.plugins.apply(ALLURE_ADAPTER_PLUGIN_ID)

        TestingExtension testing = project.extensions.getByType(TestingExtension)

        testing.suites.named(JavaPlugin.TEST_TASK_NAME, JvmTestSuite) { s ->
            s.useJUnitJupiter()
        }

        def integrationTest = testing.suites.register(INTEGRATION_TEST_SUITE_NAME, JvmTestSuite) { s ->
            s.useJUnitJupiter()
            s.dependencies { deps ->
                deps.implementation(deps.project())
            }
            s.targets.all { target ->
                target.testTask.configure { t ->
                    t.shouldRunAfter(project.tasks.named(JavaPlugin.TEST_TASK_NAME))
                }
            }
        }

        project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { t ->
            t.dependsOn(integrationTest)
        }

        project.tasks.register(JACOCO_INTEGRATION_TEST_REPORT_TASK_NAME, JacocoReport) { r ->
            r.group = LifecycleBasePlugin.VERIFICATION_GROUP
            r.description = 'Generates code coverage report for the integrationTest suite.'
            def integrationTestTask = project.tasks.named(INTEGRATION_TEST_SUITE_NAME).get()
            r.executionData(integrationTestTask)
            r.sourceSets(project.extensions.getByType(SourceSetContainer)
                .getByName(SourceSet.MAIN_SOURCE_SET_NAME))
            r.mustRunAfter(integrationTestTask)
        }
    }
}
