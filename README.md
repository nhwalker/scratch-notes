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
  need their own `checkstyle.xml`. Suppress locally with
  `@SuppressWarnings("checkstyle:<id>")`;
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
make sure one (e.g. `mavenCentral()`) is declared.