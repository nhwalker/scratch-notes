# scratch-notes

space for test and playing with different things

## Gradle test plugins

Two Gradle plugins (written in Groovy, built with Gradle 9.2.1):

| Plugin | Apply to | Purpose |
|--------|----------|---------|
| `io.github.nhwalker.test-setup` | each subproject | Unit + integration test suites with JaCoCo and Allure |
| `io.github.nhwalker.test-rollup` | root (or aggregator) project | Aggregated JUnit, JaCoCo, and Allure reports |

### test-setup

Applying `io.github.nhwalker.test-setup` to a project:

- Applies the `java` plugin if it isn't already applied.
- Configures the default `test` suite (unit tests) to use JUnit Jupiter.
- Registers an `integrationTest` [JVM test suite](https://docs.gradle.org/current/userguide/jvm_test_suite_plugin.html)
  (`src/integrationTest/java`) using JUnit Jupiter, depending on the production
  classes, ordered after `test`, and wired into `check`.
- Applies JaCoCo — both suites produce coverage data; `jacocoTestReport` and
  `jacocoIntegrationTestReport` generate per-project coverage reports.
- Applies the [Allure adapter](https://github.com/allure-framework/allure-gradle) —
  both suites write Allure raw results.

```groovy
plugins {
    id 'io.github.nhwalker.test-setup' version '0.1.0'
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

### test-rollup

Applying `io.github.nhwalker.test-rollup` to the root project of a
multi-project build:

```groovy
plugins {
    id 'io.github.nhwalker.test-rollup' version '0.1.0'
}
```

Every project in the build that applies `test-setup` is **discovered
automatically** and added as an aggregation dependency — no manual dependency
wiring. The plugin registers:

| Task | Output |
|------|--------|
| `testAggregateReport` | merged unit-test HTML report (`build/reports/tests/test/aggregated-results`) |
| `integrationTestAggregateReport` | merged integration-test HTML report (`build/reports/tests/integrationTest/aggregated-results`) |
| `testCodeCoverageReport` | merged unit-test JaCoCo report (`build/reports/jacoco/testCodeCoverageReport`) |
| `integrationTestCodeCoverageReport` | merged integration-test JaCoCo report (`build/reports/jacoco/integrationTestCodeCoverageReport`) |
| `allureAggregateReport` | merged Allure report (`build/reports/allure-report/allureAggregateReport`) |
| `testRollup` | lifecycle task that generates all of the above |

```sh
./gradlew testRollup             # run all suites everywhere, generate all reports
./gradlew testRollup --continue  # still get reports when some tests fail
```

Note: participant auto-discovery uses cross-project configuration, the
standard pattern for aggregation today, but it is incompatible with Gradle's
incubating Project Isolation feature.

### Building the plugins

```sh
./gradlew build
```

Functional tests (Spock + Gradle TestKit) live in `src/test/groovy`:
`TestSetupPluginSpec` verifies suites, coverage data, and Allure results on a
single project; `TestRollupPluginSpec` verifies aggregation across a
multi-project build, including that projects without `test-setup` are left
out of the rollup.
