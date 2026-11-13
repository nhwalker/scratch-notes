# maven-offline-cache

Builds an offline Maven-layout repository from Gradle version catalogs (`libs.versions.toml`).
See the design: [`../docs/maven-offline-cache-design.md`](../docs/maven-offline-cache-design.md).

> **Status: scaffold.** Catalog reading, hints, reporting, checksums, cleanup and the CLI work.
> The dependency walk / download (`ResolverWalker`) is not implemented yet and reports a
> `not-implemented` error.

## Build

```bash
mvn verify            # tests + target/maven-offline-cache.jar (runnable fat jar)
```

Requires JDK 21.

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
| `walk` | `Walker` interface, `ResolverWalker` (TODO), `ResolverSessionFactory` |
| `mirror` | `Checksums` (md5/sha1/sha256/sha512 sidecars), `OutputCleaner` |
| `report` | `Severity`, `Issue`, `Report` (exit code, report.json, console summary) |
| `cli` | `Main` (picocli) |
