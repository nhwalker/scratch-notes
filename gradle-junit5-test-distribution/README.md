# Gradle plugin: package JUnit 5 e2e suites as portable distributions

A reusable Gradle plugin that turns any project full of JUnit 5 tests into a
**self-contained, runnable application distribution** — a zip you carry across
an air gap and run against a real system with nothing but a JRE 17+.

The launcher is written **once**, in a small runner library published alongside
the plugin, and injected into every consumer automatically — the same pattern
Spring Boot uses with `spring-boot-loader`. Consumer projects contain only
tests and a few lines of DSL.

## Layout

```
e2e-distribution-plugin/          the reusable half (own repo in real life)
  plugin/                         Gradle plugin: applies `application`, injects
                                  the runner, sets mainClass, adds the
                                  e2eDistribution { } DSL
  runner/                         shared launcher library: JUnit Platform
                                  Launcher API, XML reports, exit codes

example-project/                  a consumer: ONLY tests + build config
  build.gradle                    applies the plugin, picks the engine,
                                  configures defaults
  src/main/java/.../e2e/          the e2e tests (main source set, so they and
                                  the JUnit engine ship in the distribution)
```

In this repo the two are wired together with a Gradle composite build
(`includeBuild` in `example-project/settings.gradle`). In real use, publish
`com.example.e2e:plugin` and `com.example.e2e:runner` to your internal Maven
repo and delete the `includeBuild` lines — consumers need only the plugin id.

## What a consumer looks like

```groovy
plugins {
    id 'com.example.e2e-distribution'
}

dependencies {
    implementation 'org.junit.jupiter:junit-jupiter'   // version from the runner's BOM
}

application {
    applicationName = 'e2e-tests'
}

e2eDistribution {
    testPackage = 'com.example.myapp.e2e'
    systemProperties = ['e2e.target.url': 'http://localhost:8080']  // baked defaults
}
```

## Build (connected side)

```sh
cd example-project
./gradlew distZip
# -> build/distributions/e2e-tests-1.0.0.zip

# or try it locally first:
./gradlew run -De2e.target.url=http://localhost:8080
```

## Run (air-gapped side)

```sh
unzip e2e-tests-1.0.0.zip
cd e2e-tests-1.0.0
E2E_TESTS_OPTS="-De2e.target.url=https://system-under-test.internal" bin/e2e-tests
echo $?   # 0 = all passed, 1 = failures (details in test-reports/*.xml)
```

On Windows: `bin\e2e-tests.bat` with `set E2E_TESTS_OPTS=...`.

### Runtime configuration (system properties)

Defaults set in `e2eDistribution { }` are baked into the launch scripts;
operators override any of them via `E2E_TESTS_OPTS`.

| Property           | Default                 | Purpose                              |
|--------------------|-------------------------|--------------------------------------|
| `e2e.test.package` | scan whole classpath    | Package scanned for tests            |
| `e2e.reports.dir`  | `test-reports`          | Where JUnit XML reports are written  |
| `e2e.include.tags` | (none)                  | Comma-separated JUnit tags to run    |
| `e2e.exclude.tags` | (none)                  | Comma-separated JUnit tags to skip   |
| `e2e.target.url`   | consumer-defined        | Example app-level config; add your own |

Tag filtering lets one distribution serve multiple purposes, e.g.
`-De2e.include.tags=smoke` for a quick post-deploy check vs. the full suite.

## Why not JUnit's built-in ConsoleLauncher?

`org.junit.platform.console.ConsoleLauncher` is a fine built-in main, but the
`application` plugin's start scripts can't bake in default *program* arguments
(only JVM args), so every operator invocation would need
`execute --scan-classpath --reports-dir=...` typed out. Since the runner lives
in the plugin's shared library — written once, not per project — owning those
~80 lines buys zero-argument launches, clean exit codes, and a natural home
for future suite-level setup (auth, health-wait, teardown) across every
consumer at once.
