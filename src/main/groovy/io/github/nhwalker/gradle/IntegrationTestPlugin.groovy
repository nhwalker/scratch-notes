package io.github.nhwalker.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.jvm.JvmTestSuite
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.gradle.testing.base.TestingExtension

/**
 * Registers an {@code integrationTest} JVM test suite via Gradle's
 * {@code jvm-test-suite} plugin (the {@code testing} extension).
 *
 * <ul>
 *   <li>Sources live in {@code src/integrationTest/java} (and groovy/kotlin/resources
 *       when those plugins are applied); the suite brings its own source set,
 *       configurations, and {@code integrationTest} task.</li>
 *   <li>The suite uses JUnit Jupiter and depends on the project's production
 *       classes ({@code implementation project()}).</li>
 *   <li>The suite's test task is ordered after the unit {@code test} task and
 *       wired into {@code check}.</li>
 * </ul>
 *
 * Extra dependencies go on the suite, e.g.
 * <pre>
 * testing.suites.integrationTest.dependencies {
 *     implementation 'org.testcontainers:testcontainers:1.20.4'
 * }
 * </pre>
 */
class IntegrationTestPlugin implements Plugin<Project> {

    public static final String SUITE_NAME = 'integrationTest'

    @Override
    void apply(Project project) {
        // The java plugin applies jvm-test-suite; applying it twice is a no-op.
        project.plugins.apply(JavaPlugin)

        TestingExtension testing = project.extensions.getByType(TestingExtension)
        def suite = testing.suites.register(SUITE_NAME, JvmTestSuite) { s ->
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
            t.dependsOn(suite)
        }
    }
}
