# maven-offline-cache — Design

> Status: v1 implemented in `maven-offline-cache/`; see §12 for what implementation and testing established.

## BLUF

`maven-offline-cache` is a standalone Java 21 tool (built with Maven, run with
`java -jar`) that reads one or more Gradle version catalogs (`libs.versions.toml`)
and produces a **Maven-layout directory** that both **Gradle (`--offline`)** and
**Maven (`-o`)** builds can use with no network access.

It uses **Maven Resolver** to fetch through the company **Nexus proxies** (Maven
Central + Gradle Plugin Portal), walks the dependency graph **keeping every
requested version**, reads **Gradle Module Metadata (`.module`)** to pick up
Gradle-only files, and downloads extra **classifiers/extensions** declared as
hints in the catalog's `[metadata.offline-cache]` table.

---

## 1. Goals and non-goals

**Goals**

- Input: Gradle version catalog(s) with **plain versions only**.
- Output: a standard Maven repository layout directory usable offline by Gradle and Maven.
- Include transitive dependencies, parent POMs, imported BOMs, Gradle plugin markers
  and their implementations.
- Include sources jars and selected platform classifiers (e.g. JavaFX, protoc, grpc).
- Verify integrity with checksums.

**Non-goals (v1)**

- Rich versions (`strictly`, `require`, `prefer`, `reject`, `rejectAll`) and version ranges
  in the catalog — reported as errors.
- `maven-metadata.xml` generation (dynamic versions not supported offline).
- Javadoc jars.
- Automatic discovery of classifiers that no metadata references (hints cover these).
- POM profile expansion (dropped; hints cover JavaFX).
- Central Search API discovery.

## 2. Why Maven Resolver instead of Gradle

An all-Gradle design was considered first. It was dropped because Gradle required
working around:

| Gradle issue | With Maven Resolver |
|---|---|
| Variant attributes needed, otherwise "ambiguous variant" failures | Not applicable |
| POM-only modules (e.g. `protoc`) may fail resolution | Fetch `.pom` normally |
| Parent POMs / BOMs not exposed by the API | Downloaded by `readArtifactDescriptor` (to be confirmed in testing) |
| Plugin markers need plugin-API attributes | Marker is just a POM |
| Configuration-cache constraints, Gradle 9 API churn | Plain Java |

The cost is that two Gradle behaviours must be handled explicitly (see §5):
`.module`-only files, and Gradle's highest-version-wins conflict resolution.

## 3. Architecture

```
libs.versions.toml (1..n)
        │
        ▼
CatalogReader ── libraries + plugin markers ──► seed g:a:v set
        │       + [metadata.offline-cache] hints
        ▼
┌──────────────────────── Walker (BFS) ────────────────────────┐
│ for each g:a:v (every requested version is kept):            │
│  ├─ readArtifactDescriptor → POM deps (compile/runtime),     │
│  │                            parents + BOMs saved to outDir │
│  ├─ .module (if present) → files + deps of ALL variants,     │
│  │                          constraint versions (§5.3)       │
│  ├─ main artifact (unless packaging = pom)                   │
│  └─ hint probes: classifiers × extension, plus sources       │
└──────────────────────────────┬───────────────────────────────┘
                               ▼
     Mirror: download via Nexus, verify checksums, write md5/sha1/sha256/sha512
                               ▼
     Cleanup: remove _remote.repositories, *.lastUpdated, resolver-status.properties
                               ▼
     outDir (Maven layout)  +  report.json  +  console summary
```

Suggested packages (plain Java, JUnit-testable):

- `catalog` — TOML parsing (`tomlj` or `jackson-dataformat-toml`), hint parsing.
- `walk` — BFS graph walk, POM and `.module` reading.
- `mirror` — downloads, checksum verification/generation, file filters.
- `report` — severity classification, JSON + console output.
- `cli` — argument parsing, wiring.

## 4. Input

### 4.1 Catalog entries

| Form | Handling |
|---|---|
| `alias = "g:a:v"` | seed `g:a:v` |
| `{ module = "g:a", version.ref = "x" }` | look up `[versions].x` |
| `{ group = "g", name = "a", version = "v" }` | seed `g:a:v` |
| library with no version | **warning**, skipped (version expected from a BOM at build time) |
| rich version / range | **error** |
| `[plugins]` `{ id = "p", version = "v" }` | seed marker `p:p.gradle.plugin:v` (resolves to the plugin implementation) |
| `[bundles]` | ignored (aliases only) |

Multiple catalogs: `--catalog a.toml --catalog b.toml`; seeds and hints are merged (union).

### 4.2 Classifier / extension hints — `[metadata.offline-cache]`

Gradle's catalog parser rejects unknown top-level tables and unknown keys in
library/plugin/version tables, but does **not** validate the `[metadata]` table
beyond `format.version` (verified in Gradle's `TomlCatalogFileParser` source on
`master`; guarded by a real-Gradle test, §9). Hints therefore live there and
**only** there.

```toml
[metadata]
format.version = "1.1"

[metadata.offline-cache]
include-sources = true          # default true

[metadata.offline-cache.hints]
# key: catalog alias OR group:artifact glob; value: classifiers + optional extension (default "jar")
"org.openjfx:javafx-*"         = { classifiers = ["linux", "win", "mac"] }
"com.google.protobuf:protoc"   = { classifiers = ["linux-x86_64", "windows-x86_64", "osx-x86_64"], extension = "exe" }
"io.grpc:protoc-gen-grpc-java" = { classifiers = ["linux-x86_64", "windows-x86_64", "osx-x86_64"], extension = "exe" }
```

Rules:

- Keys may be a **catalog alias** or a **`group:artifact` glob**. Globs are needed because
  artifacts that need classifiers often arrive transitively (e.g. `javafx-graphics`).
- All matching hints are **additive** (union of classifiers).
- `sources` is probed for every g:a:v unless `include-sources = false` (built-in default).
- Across catalogs, hints are merged (union).

## 5. Resolution behaviour

### 5.1 Every requested version is kept

Maven mediates by *nearest wins*; Gradle by *highest wins*. A Gradle consumer can
therefore need a version a Maven resolution would discard. The walker performs **no
conflict resolution**: every version requested anywhere in the graph is downloaded
(superset suitable for both consumers).

### 5.2 Dependency sources

- POM dependencies in `compile` and `runtime` scope; `test`, `provided` and `optional`
  are not followed (matches Maven and Gradle transitive behaviour).
- POM dependencies that name a classifier are fetched with that classifier
  (e.g. `netty-tcnative-boringssl-static` → `-linux-x86_64`, `-windows-x86_64`, …).
- `.module` files: the dependencies **and files** of **all variants** are included
  (e.g. `kotlin-gradle-plugin` → `-gradle70.jar` … `-gradle85.jar`).
- Version ranges *inside POMs* resolve to the highest available matching version.
- POM `<relocation>` is followed (warning).

### 5.3 Gradle platform constraints

When a `.module` file's `dependencyConstraints` names a different version of a module
**already in the graph**, that version is added too. Constraints for modules not in the
graph are ignored. Completeness of this rule is not guaranteed; the offline acceptance
test is the final check.

## 6. Repositories and credentials

| Setting | CLI | Env var fallback |
|---|---|---|
| Nexus Maven Central proxy URL | `--central-url` | `NEXUS_CENTRAL_URL` |
| Nexus Gradle Plugin Portal proxy URL | `--plugins-url` | `NEXUS_PLUGINS_URL` |
| Username / password | — | `NEXUS_USERNAME`, `NEXUS_PASSWORD` |

Credentials are never written to output or logs.

## 7. Output

- Standard Maven layout under `--out`.
- Per file: artifact plus `.md5`, `.sha1`, `.sha256`, `.sha512` (upstream copies kept,
  missing ones generated).
- Excluded: `*-javadoc.jar`, `maven-metadata*.xml`, resolver bookkeeping files.
- Re-runs are incremental: files already present with matching checksum are skipped.
- Multiple runs / catalogs merge into the same directory.

### Downloads

- JDK `java.net.http.HttpClient` via Maven Resolver transport (no extra HTTP library if possible).
- 4 parallel downloads (configurable), 3 retries with backoff.
- Downloads verified against upstream `.sha1` (and `.sha256` when present).

## 8. Reporting and failure policy

Rule of thumb: **if a consumer build could break, it is an error; optional or expected
absences are not.**

### Errors (run fails at the end)

- Catalog entry POM not found.
- Transitive dependency POM not found.
- Parent POM or imported BOM not found.
- Main artifact missing when packaging requires one (`jar`, `bundle`, …).
- File listed in a `.module` variant missing.
- Classifier dependency declared in a POM missing.
- Checksum mismatch.
- Authentication failure, or network error after retries.
- Catalog parse error, or non-plain version (rich version / range).

### Warnings (reported, exit 0)

- Hint matched an artifact but **none** of its classifiers exist.
- Hint pattern matched **no** artifact in the run.
- Catalog library entry has no version.
- POM relocation followed.

### Info (report.json only)

- No sources jar.
- Hint matched and *some* classifiers were missing.
- No `.module` file.
- No main artifact for `packaging = pom` (e.g. protoc, BOMs).
- (Planned: "checksum missing upstream". Not detectable — see §12; sidecars are generated, summarised as `checksums-generated`.)
- File already present with matching checksum → skipped.

### Exit behaviour

- Any error → non-zero exit after processing everything.
- `--allow-failures` → errors downgraded to warnings, exit 0.
- `--fail-on-warning` → warnings cause a non-zero exit (strict CI).

### Report

- `report.json`: one record per g:a:v and per file, with level, reason, source
  (catalog alias, POM dependency, `.module`, constraint, hint).
- Console: totals per level plus all errors and warnings.

## 9. Test plan

```
Unit (JUnit 5)
  └─ in-process fake repository (com.sun.net.httpserver)
       ├─ catalog parsing: all entry forms, plugins, hints, errors
       ├─ walk: parent chain, imported BOM, cycles, every-version retention,
       │        .module variants/files, constraints, relocation
       ├─ probing: hints × extension, sources, 404 handling, pom-only modules
       ├─ checksums: match / mismatch / missing → generated
       └─ severity classification

Integration (real repositories / Nexus)
  └─ sample catalog: javafx-controls, protobuf-java + protoc,
     grpc + protoc-gen-grpc-java, netty-tcnative, kotlin.jvm plugin

Acceptance (no network)
  ├─ Gradle 9.x consumer: --offline, repository = file://out → build passes
  ├─ Maven consumer: -o, mirror = file://out → build passes
  └─ Real Gradle 9.x build accepts a catalog containing [metadata.offline-cache]
```

## 10. Usage (planned)

```bash
export NEXUS_USERNAME=... NEXUS_PASSWORD=...
java -jar maven-offline-cache.jar \
  --catalog gradle/libs.versions.toml \
  --central-url https://nexus.example/repository/maven-central/ \
  --plugins-url https://nexus.example/repository/gradle-plugins/ \
  --out build/offline-repo
```

Offline consumers:

- **Maven** — `settings.xml` mirror: `<mirrorOf>*</mirrorOf>`, `<url>file:///path/offline-repo</url>`.
- **Gradle** — `maven { url = uri("file:///path/offline-repo") }` in both
  `pluginManagement.repositories` and `dependencyResolutionManagement.repositories`,
  run with `--offline`.

## 11. Verified facts and open risks

**Verified (against repo1.maven.org / Gradle source, 2026-11):**

- JavaFX publishes POM-only metadata; platform classifiers chosen via OS profiles
  (`${javafx.platform}`).
- `protoc` and `protoc-gen-grpc-java` are `packaging=pom` with `.exe` classifier artifacts only.
- `netty-tcnative-boringssl-static` main POM depends on its own classifier jars.
- `kotlin-gradle-plugin` lists Gradle-version-specific jars only in its `.module` file.
- The Kotlin JVM plugin marker is on Maven Central.
- Gradle's catalog parser validates top-level tables and library/plugin/version keys,
  but not the contents of `[metadata]` (other than `format.version`).

**Verified by implementation and tests (2026-11):**

- `readArtifactDescriptor` saves parent POMs and imported BOMs into the output directory
  (unit test against a fake repository; real run: `org.openjfx:javafx:21` parent present).
- Gradle 9.9.1 accepts a catalog containing `[metadata.offline-cache]` (offline acceptance build).

**Unverified / risks:**

- Completeness of the platform-constraint rule (§5.3).
- A future Gradle version validating `[metadata]` (guarded by acceptance test).
- Nexus "not found" caching behaviour for probe misses (depends on Nexus configuration).
- Resolver config keys for threads/retries (`aether.connector.basic.threads`,
  `aether.transport.http.retryHandler.count`): Resolver ignores unknown keys, so a wrong name
  would silently use Resolver's default.

## 12. Implementation findings (v1)

**Behaviour added or changed during implementation**

- **Proxy:** Resolver ignores JVM proxy settings by default; the tool sets Resolver's
  `JreProxySelector`, so standard `-Dhttps.proxyHost` / `-Dhttps.proxyPort` / `-Dhttp.nonProxyHosts`
  apply.
- **Repository prefix filter disabled:** Resolver 2.x downloads `.meta/prefixes.txt` and silently
  filters requests by it. Behind a Nexus *group* repository, Central's list could hide other
  members' artifacts, so the filter (and the groupId filter) is off; `.meta/` is removed by cleanup.
- **Gradle-variant javadoc** (e.g. `-gradle71-javadoc.jar`) is excluded like plain javadoc.
- **Missing upstream checksums cannot be detected:** with the `warn` checksum policy, Resolver
  accepts a file that has no upstream checksum without firing any event (verified). Mismatches
  are still detected and reported once per file (Resolver retries a corrupted download once).
- **JVM variant filter for `.module`:** variants with a Kotlin platform other than `jvm`/`common`,
  or `org.gradle.jvm.environment = android`, are skipped (reported as info).

**Limits found in acceptance testing**

- **Build-time plugin dependencies:** the Kotlin Gradle plugin resolves
  `org.jetbrains.kotlin:kotlin-build-tools-impl` when compiling; no POM/`.module` declares it, so no
  metadata-based tool can find it. Fix: list such artifacts in a catalog (a second "extras" catalog
  works, since catalogs are merged). With that one addition the Gradle 9.9.1 `--offline` build of
  a Kotlin project passed.
- **POM-declared classifiers are all fetched:** e.g. netty-tcnative's POM depends on its aarch64
  jars too, so they are included even though hints target x86_64 (they are required by consumers).
- **Superset size:** keeping every requested version and all JVM `.module` variants pulls in extras
  (e.g. `kotlin-test`'s TestNG/JUnit variants). The sample catalog produced 337 files / 340 MB.

**Acceptance results (sample catalog + extras catalog)**

| Consumer | Setup | Result |
|---|---|---|
| Gradle 9.9.1 | `--offline`, fresh Gradle home, only `file://out` as repository, Kotlin plugin + libraries, `build` | passed |
| Maven 3.9.11 | `central` mirrored to `file://out`, fresh local repo, `dependency:resolve` (Maven's own plugins from a separate online repository) | passed, 36 artifacts |

**Environment note:** during testing, Maven Central intermittently returned HTTP 404 (and 429) for
files that exist. Such runs report errors; re-running is incremental (only missing files are
retried) and converged to 0 errors on the third run. Through a Nexus proxy this should not occur,
but a re-run is the remedy if it does.

## In summary

- Gradle catalog in → Maven-layout directory out, via Maven Resolver and Nexus.
- Keep every requested version and read `.module` files so Gradle consumers work too.
- Classifiers come from metadata automatically where possible, otherwise from hints in
  `[metadata.offline-cache]`.
- Clear error/warning/info rules; offline acceptance builds are the final proof.
