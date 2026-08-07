# scratch-notes

space for test and playing with different things

## integration-test-plugin

A Gradle plugin (written in Groovy, built with Gradle 9.2.1) that registers an
`integrationTest` [JVM test suite](https://docs.gradle.org/current/userguide/jvm_test_suite_plugin.html)
on any Java project.

### What it does

Applying `io.github.nhwalker.integration-test`:

- Applies the `java` plugin if it isn't already applied (which brings in
  `jvm-test-suite`).
- Registers an `integrationTest` suite in the `testing` extension. The suite
  brings its own source set (`src/integrationTest/java`, plus
  `groovy`/`kotlin`/`resources` when those plugins are present),
  configurations, and `integrationTest` task.
- Configures the suite to use JUnit Jupiter and to depend on the project's
  production classes (`implementation project()`).
- Orders the suite's task after the unit `test` task and wires it into
  `check`.

### Usage

```groovy
plugins {
    id 'java'
    id 'io.github.nhwalker.integration-test' version '0.1.0'
}
```

Suites keep their dependencies separate by design — declare integration-test
dependencies on the suite:

```groovy
testing {
    suites {
        integrationTest {
            dependencies {
                implementation 'org.testcontainers:testcontainers:1.20.4'
            }
        }
    }
}
```

Then run:

```sh
./gradlew integrationTest   # just the integration tests
./gradlew check             # unit tests, then integration tests
```

### Building the plugin

```sh
./gradlew build
```

Functional tests (Spock + Gradle TestKit) live in
`src/test/groovy` and verify the suite, its source set, classpath wiring, and
task ordering against a real Gradle build.
