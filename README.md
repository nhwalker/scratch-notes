# scratch-notes

space for test and playing with different things

## my-style-plugin Gradle plugin

A small Gradle plugin (Java) that standardizes code formatting. Applying it:

- applies the [Spotless](https://github.com/diffplug/spotless) plugin (8.7.0) and
  configures a Java format that:
  - uses the **Palantir Java formatter** (modern, lambda-friendly, 120 columns),
  - adds a **placeholder** license header (first line `Category: FIXME`; replace
    the text in `MyStylePlugin.LICENSE_HEADER`) **only to files that don't
    already have a header** — detection is generous (any leading `/* */` or `//`
    comment counts), so once you fill in the FIXME or change the company name the
    header is left untouched,
  - removes unused imports, formats annotations, trims trailing whitespace, ends
    files with a newline, and honors `// spotless:off` / `// spotless:on` fences;
- applies the built-in **Checkstyle** plugin (Checkstyle 13.6.0) with a bundled
  config that enforces good *practices* — naming, imports (no star/unused),
  coding hazards (`==` on strings, fall-through, empty catches, missing switch
  default, …), design (final/utility classes), `@Override`, etc. — while
  **leaving all formatting to Spotless** (no whitespace/indent/wrapping/brace-
  placement rules). The config ships inside the plugin jar, so consumers don't
  need their own `checkstyle.xml`. Method-name checking is relaxed for the test
  source set (`src/test`), so descriptive test method names (`should_do_x_when_y`)
  are allowed. Suppress locally with `@SuppressWarnings("checkstyle:<id>")`;
- applies **Error Prone** (5.1.0 plugin / `error_prone_core` 2.50.0) with **NullAway**
  (0.13.7) for compile-time null-safety, gated on the `java` plugin. NullAway uses the
  *opt-out* model: every package this module has source for is checked (the package
  set is auto-discovered by scanning your source roots — no config needed), and a
  null-safety violation **fails the build**. Opt a class or method out with JSpecify's
  `@NullUnmarked`; annotate nullable values with `@Nullable`. JSpecify (1.0.0) is added
  as `compileOnly`/`testCompileOnly`. **Classpath note:** `error_prone_core` and
  `nullaway` live only on the compiler's annotation-processor path — they are **not** on
  your `compileClasspath`/`runtimeClasspath` and never leak to downstream consumers; the
  only compile-classpath addition is the small JSpecify annotations jar (compile-only,
  not at runtime). Projects whose package root differs from what's scanned can override
  `options.errorprone.option("NullAway:AnnotatedPackages", ...)` in their own build.
  On top of Error Prone's defaults, a curated set of normally-disabled checks is turned
  on: high-confidence correctness bugs as **errors** (`TimeUnitMismatch`,
  `EqualsBrokenForNull`, `FunctionalInterfaceClash`) and advisory checks as **warnings**
  (the `*MissingNullable` family that pairs with NullAway, `EqualsGetClass`,
  `InconsistentOverloads`, `CheckedExceptionNotThrown`). **Generated code is
  exempt from every check:** anything under the `build/` directory (protobuf,
  Immutables, gRPC, etc.) is excluded from Error Prone (`excludedPaths`, which
  also covers NullAway and the error-level checks) and Checkstyle (a
  `BeforeExecutionExclusionFileFilter`), NullAway additionally skips
  `@Generated` classes, and Spotless only ever touches `src/**`;
- turns on javac's own `-Xlint` warnings on every `JavaCompile`
  (`-Xlint:all,-processing,-serial,-path,-options` — everything minus the
  noisy/meta categories), catching compiler-level issues like `unchecked`,
  `rawtypes`, `deprecation`, `this-escape` and `try`. These are left as
  warnings: `-Werror` is intentionally not enabled because it would also
  escalate the deliberately warning-level Error Prone checks into build
  failures;
- applies **SpotBugs** (tool 4.10.2) with the **FindSecBugs** (1.14.0) security
  detectors and enables the **HTML, XML and SARIF** reports. It runs *advisory*
  (`ignoreFailures = true`) so findings surface via the reports rather than
  breaking the build. Generated code is excluded via a `@Generated` annotation
  filter — note SpotBugs is a bytecode tool, so it can only skip
  `CLASS`/`RUNTIME`-retained `*.Generated` annotations (e.g. Immutables); the
  standard `SOURCE`-retained `javax/jakarta` ones and annotation-less output
  (protobuf) can't be matched, which is the other reason it runs advisory. The
  SpotBugs/FindSecBugs artifacts stay on dedicated configurations — never the
  consumer's compile/runtime classpath;
- if the `eclipse` plugin is also applied, merges settings into
  `.settings/org.eclipse.jdt.core.prefs` to:
  - turn up the built-in Eclipse JDT compiler warnings (unused code, null
    hazards, raw types, resource leaks, missing `@Override`, etc.), and
  - set the JDT formatter/indentation to match Palantir (4-space indents using
    spaces, 8-space continuation indent, 120-column lines) so the Eclipse editor
    indents the same way `spotlessApply` formats.

Built and tested with **Gradle 9.2.1** (`./gradlew build`).

### Usage

```groovy
plugins {
    id 'java'
    id 'eclipse'      // optional — enables the JDT warning tightening
    id 'my-style-plugin'
}
```

Then run `./gradlew spotlessApply` to format, or `./gradlew spotlessCheck` to verify.
Run `./gradlew checkstyleMain checkstyleTest` (or just `./gradlew check`) to run
Checkstyle. The Checkstyle tool is resolved from the project's repositories, so
make sure one (e.g. `mavenCentral()`) is declared. Error Prone / NullAway run as part
of `compileJava` (also from your repositories), so `mavenCentral()` covers them too.

### CI

`gitlab-cicd-example.yml` is a ready-to-adapt GitLab pipeline that runs all of
these checks and exposes GitLab-native reports where they exist — Checkstyle and
**SpotBugs/FindSecBugs** as **Code Quality** reports (MR widget + inline diff
annotations, converted from each tool's XML; GitLab merges them) and tests as a
**JUnit** report. Spotless, Error Prone/NullAway and `-Xlint` are pass/fail gates
(details in the job log). Copy it to `.gitlab-ci.yml` to use it.

It is written for a **multi-project build** whose subprojects are separate Java
libraries: the task invocations fan out to every module, and the report globs,
artifact paths and converters collect from `**/build/...` across all modules
(aggregating each Code Quality report into one per job, with module-prefixed
paths like `moduleA/src/main/java/...`). It works unchanged for a single-project
build too.

The Checkstyle/SpotBugs jobs call a companion script, **`gitlab-report-formats.py`**
(stdlib-only), to convert each tool's XML into the CodeClimate JSON GitLab ingests —
`python3 gitlab-report-formats.py <checkstyle|spotbugs>`. Copy both files to your repo
root.