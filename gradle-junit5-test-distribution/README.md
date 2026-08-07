# Portable JUnit 5 e2e test suite

A Gradle example that packages a JUnit 5 test suite as a **self-contained,
runnable application distribution** — a zip you can carry across an air gap
and run against a real system with nothing but a JRE on the far side.

## How it works

- The tests live in `src/main/java` (the *main* source set, not `src/test`),
  so they compile into the application jar and all JUnit dependencies are
  copied into the distribution's `lib/` directory.
- `E2eTestRunner` is a small `main()` that uses the JUnit Platform Launcher
  API to discover and run every test in `com.example.e2e.tests`, print a
  summary, write JUnit XML reports, and exit non-zero on failure.
- The Gradle `application` plugin generates `bin/e2e-tests` launch scripts
  (Unix + Windows) and the `distZip`/`distTar` tasks bundle everything.

## Build (connected side)

```sh
./gradlew distZip
# -> build/distributions/e2e-tests-1.0.0.zip
```

To try the suite locally without unzipping:

```sh
./gradlew runSuite -De2e.target.url=http://localhost:8080
```

## Run (air-gapped side)

Copy the zip across, then:

```sh
unzip e2e-tests-1.0.0.zip
cd e2e-tests-1.0.0
E2E_TESTS_OPTS="-De2e.target.url=https://system-under-test.internal" bin/e2e-tests
echo $?   # 0 = all tests passed, 1 = failures (see test-reports/*.xml)
```

Requires a JRE 17+ on the target machine (the build compiles for Java 17
bytecode regardless of which newer JDK builds it).

On Windows: `bin\e2e-tests.bat` with `set E2E_TESTS_OPTS=...`.

### Configuration knobs (system properties)

| Property           | Default                    | Purpose                          |
|--------------------|----------------------------|----------------------------------|
| `e2e.target.url`   | `http://localhost:8080`    | Base URL of the system under test |
| `e2e.reports.dir`  | `test-reports`             | Where JUnit XML reports are written |
| `e2e.test.package` | `com.example.e2e.tests`    | Package scanned for tests        |

## Layout

```
build.gradle
settings.gradle
src/main/java/com/example/e2e/E2eTestRunner.java     <- main(), drives JUnit Platform
src/main/java/com/example/e2e/tests/SmokeE2eTest.java <- your e2e tests go here
```

## Alternative: ConsoleLauncher

If you'd rather not maintain a runner class, depend on
`org.junit.platform:junit-platform-console` and set
`mainClass = 'org.junit.platform.console.ConsoleLauncher'`, then pass
`execute --scan-classpath` as launch arguments. The custom runner used here
is only slightly more code and gives you clean exit codes, fixed defaults,
and a place to add suite-level setup (auth, health-wait, teardown).
