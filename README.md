# scratch-notes

space for test and playing with different things

## my-formatter Gradle plugin

A small Gradle plugin (Java) that standardizes code formatting. Applying it:

- applies the [Spotless](https://github.com/diffplug/spotless) plugin (8.7.0) and
  configures a Java format that:
  - uses the **Palantir Java formatter** (modern, lambda-friendly, 120 columns),
  - prepends a license header (currently **placeholder** text — replace it in
    `MyFormatterPlugin.LICENSE_HEADER`),
  - removes unused imports, formats annotations, trims trailing whitespace, ends
    files with a newline, and honors `// spotless:off` / `// spotless:on` fences;
- if the `eclipse` plugin is also applied, turns up the built-in Eclipse JDT
  compiler warnings (unused code, null hazards, raw types, resource leaks,
  missing `@Override`, etc.) by merging settings into
  `.settings/org.eclipse.jdt.core.prefs`.

Built and tested with **Gradle 9.2.1** (`./gradlew build`).

### Usage

```groovy
plugins {
    id 'java'
    id 'eclipse'      // optional — enables the JDT warning tightening
    id 'my-formatter'
}
```

Then run `./gradlew spotlessApply` to format, or `./gradlew spotlessCheck` to verify.