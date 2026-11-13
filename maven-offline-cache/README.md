# maven-offline-cache

Builds an offline Maven-layout repository from Gradle version catalogs (`libs.versions.toml`).
See the design: [`../docs/maven-offline-cache-design.md`](../docs/maven-offline-cache-design.md).

> **Status: v1.** Catalog reading, hints, the dependency walk/download, reporting, checksums,
> cleanup and the CLI are implemented. See design doc §12 for findings and known limits.

**Known limit:** artifacts a build tool resolves only at build time (e.g. the Kotlin Gradle plugin's
`kotlin-build-tools-impl`) are not declared in any metadata. Add them to a catalog — catalogs
are merged, so a small "extras" catalog works:

```toml
[libraries]
kotlin-build-tools-impl = { module = "org.jetbrains.kotlin:kotlin-build-tools-impl", version = "2.0.0" }
```

## Build

```bash
mvn verify            # tests + target/maven-offline-cache.jar (runnable fat jar)
```

Requires JDK 21. Proxies: standard JVM flags, e.g.
`java -Dhttps.proxyHost=proxy -Dhttps.proxyPort=8080 -jar …`.

## Run

```bash
export NEXUS_USERNAME=... NEXUS_PASSWORD=...      # optional
java -jar target/maven-offline-cache.jar \
  --catalog gradle/libs.versions.toml \
  --central-url https://nexus.example/repository/maven-central/ \
  --plugins-url https://nexus.example/repository/gradle-plugins/ \
  --out build/offline-repo

# Only parse the catalog(s) and print seeds + hints:
java -jar target/maven-offline-cache.jar --catalog gradle/libs.versions.toml --out x --dry-run
```

## Classifier hints (in the catalog)

```toml
[metadata.offline-cache]
include-sources = true

[metadata.offline-cache.hints]
"org.openjfx:javafx-*"       = { classifiers = ["linux", "win", "mac"] }
"com.google.protobuf:protoc" = { classifiers = ["linux-x86_64", "windows-x86_64", "osx-x86_64"], extension = "exe" }
```

## Layout

| Package | Contents |
|---|---|
| `catalog` | `CatalogReader` (TOML → seeds + hints), `ClassifierHint` |
| `walk` | `ResolverWalker` (BFS walk), `GradleModule` (`.module` parsing), `PomInfo`, `ResolverSessionFactory`, `ChecksumReportingListener` |
| `mirror` | `Checksums` (md5/sha1/sha256/sha512 sidecars), `OutputCleaner` |
| `report` | `Severity`, `Issue`, `Report` (exit code, report.json, console summary) |
| `cli` | `Main` (picocli) |
