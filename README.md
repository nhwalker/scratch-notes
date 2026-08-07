# scratch-notes

space for test and playing with different things

## integration-test-plugin

A Gradle plugin (written in Groovy, built with Gradle 9.2.1) that adds an
`integrationTest` source set to any Java project.

### What it does

Applying `io.github.nhwalker.integration-test`:

- Applies the `java` plugin if it isn't already applied.
- Creates an `integrationTest` source set — put sources in
  `src/integrationTest/java` (plus `groovy`/`kotlin`/`resources` when those
  plugins are present). Its compile and runtime classpaths include the `main`
  output.
- Makes `integrationTestImplementation` and `integrationTestRuntimeOnly`
  extend `testImplementation` and `testRuntimeOnly`, so shared test
  dependencies are declared once.
- Registers an `integrationTest` task (type `Test`, JUnit Platform) that runs
  after `test` and is wired into `check`.

### Usage

```groovy
plugins {
    id 'java'
    id 'io.github.nhwalker.integration-test' version '0.1.0'
}

dependencies {
    // inherited by integrationTest via testImplementation
    testImplementation platform('org.junit:junit-bom:5.12.2')
    testImplementation 'org.junit.jupiter:junit-jupiter'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'

    // only for integration tests
    integrationTestImplementation 'org.testcontainers:testcontainers:1.20.4'
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
`src/test/groovy` and verify the source set, classpath wiring, and task
ordering against a real Gradle build.
