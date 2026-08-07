package com.example.e2e.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.ApplicationPlugin;
import org.gradle.api.plugins.JavaApplication;
import org.gradle.api.tasks.JavaExec;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a project containing JUnit 5 e2e tests into a runnable application
 * distribution ({@code distZip}/{@code installDist}) that can be carried
 * across an air gap and executed with only a JRE.
 *
 * The launcher main class lives in the companion {@code com.example.e2e:runner}
 * library, which this plugin injects — consumer projects contain only tests.
 */
public class E2eDistributionPlugin implements Plugin<Project> {

    /**
     * The plugin and runner are versioned and published together; a real
     * implementation would read this version from a resource generated at
     * build time instead of hardcoding it.
     */
    private static final String RUNNER_COORDINATES = "com.example.e2e:runner:1.0.0";

    private static final String RUNNER_MAIN_CLASS = "com.example.e2e.runner.E2eTestRunner";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(ApplicationPlugin.class);

        E2eDistributionExtension extension = project.getExtensions()
                .create("e2eDistribution", E2eDistributionExtension.class);

        project.getDependencies().add("implementation", RUNNER_COORDINATES);

        JavaApplication application = project.getExtensions().getByType(JavaApplication.class);
        application.getMainClass().set(RUNNER_MAIN_CLASS);

        // Bake extension values into the start scripts as -D defaults, so the
        // unzipped distribution runs correctly with zero arguments. Operators
        // can still override any of them via E2E_TESTS_OPTS / JAVA_OPTS.
        project.afterEvaluate(p -> {
            List<String> jvmArgs = new ArrayList<>();
            application.getApplicationDefaultJvmArgs().forEach(jvmArgs::add);
            if (extension.getTestPackage().isPresent()) {
                jvmArgs.add("-De2e.test.package=" + extension.getTestPackage().get());
            }
            extension.getSystemProperties().get()
                    .forEach((key, value) -> jvmArgs.add("-D" + key + "=" + value));
            application.setApplicationDefaultJvmArgs(jvmArgs);
        });

        // `gradlew run -De2e.target.url=...` should behave like the packaged
        // app, so forward e2e.* system properties from the Gradle invocation.
        project.getTasks().named(ApplicationPlugin.TASK_RUN_NAME, JavaExec.class, run -> {
            System.getProperties().forEach((key, value) -> {
                String name = key.toString();
                if (name.startsWith("e2e.")) {
                    run.systemProperty(name, value);
                }
            });
        });
    }
}
