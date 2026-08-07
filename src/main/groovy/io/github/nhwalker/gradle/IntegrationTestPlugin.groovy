package io.github.nhwalker.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.SourceSet
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test

/**
 * Adds an {@code integrationTest} source set alongside {@code main} and {@code test}.
 *
 * <ul>
 *   <li>Sources live in {@code src/integrationTest/java} (and groovy/kotlin/resources
 *       when those plugins are applied).</li>
 *   <li>{@code integrationTestImplementation} / {@code integrationTestRuntimeOnly}
 *       extend the corresponding {@code test*} configurations, so shared test
 *       dependencies are declared once.</li>
 *   <li>An {@code integrationTest} task of type {@link Test} runs the suite and is
 *       wired into {@code check}, ordered after the unit {@code test} task.</li>
 * </ul>
 */
class IntegrationTestPlugin implements Plugin<Project> {

    public static final String SOURCE_SET_NAME = 'integrationTest'
    public static final String TASK_NAME = 'integrationTest'

    @Override
    void apply(Project project) {
        project.plugins.withType(JavaPlugin) {
            configure(project)
        }
        // Make sure java is present; applying it twice is a no-op.
        project.plugins.apply(JavaPlugin)
    }

    private static void configure(Project project) {
        SourceSetContainer sourceSets = project.extensions.getByType(SourceSetContainer)
        SourceSet main = sourceSets.named(SourceSet.MAIN_SOURCE_SET_NAME).get()

        SourceSet integrationTest = sourceSets.create(SOURCE_SET_NAME) {
            compileClasspath += main.output
            runtimeClasspath += main.output
        }

        project.configurations.named(integrationTest.implementationConfigurationName) {
            extendsFrom project.configurations.getByName(JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME)
        }
        project.configurations.named(integrationTest.runtimeOnlyConfigurationName) {
            extendsFrom project.configurations.getByName(JavaPlugin.TEST_RUNTIME_ONLY_CONFIGURATION_NAME)
        }

        def integrationTestTask = project.tasks.register(TASK_NAME, Test) {
            description = 'Runs the integration tests.'
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            testClassesDirs = integrationTest.output.classesDirs
            classpath = integrationTest.runtimeClasspath
            useJUnitPlatform()
            shouldRunAfter project.tasks.named(JavaPlugin.TEST_TASK_NAME)
        }

        project.tasks.named('check') {
            dependsOn integrationTestTask
        }
    }
}
