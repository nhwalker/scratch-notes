# QEMU VM Testcontainer: Implementation Steps

> **Audience:** the agent (or engineer) implementing [PLAN.md](PLAN.md) in the target repository.
> **What this is:** an ordered checklist. Each step says what to do, which files it touches, where the normative spec is, how to prove the step is done, and which requirements ([REQUIREMENTS.md](REQUIREMENTS.md)) it serves. Tick the boxes as you go and commit this file with them ticked.
> **Names:** `${…}` are the PLAN.md §0 parameters. In shell snippets, `<value>` means "type the real value here" (for example `<group>` = the value of `group` in `gradle.properties`).

---

## BLUF

- **7 phases:** Phase 0 adopts the plan; Phases 1–6 are milestones M1–M6 of PLAN.md §14.
- **Every step ends with a *Verify* block.** Do not start the next step until it passes. If it cannot pass, stop and record why in the Deviation log (§D) instead of improvising around the plan.
- **Spec lives in PLAN.md; acceptance and evidence live in REQUIREMENTS.md.** This file never redefines either; it tells you the order and how to check your work. If this file and PLAN.md disagree, PLAN.md wins; log the conflict.
- **Every test carries `@Requirement("REQ-…")`** for each requirement it proves and attaches the evidence that requirement's row names. The Allure report (`./gradlew allureReport`) is how a reviewer sees the proof.
- **Each phase ends with a gate:** its tests green on KVM and TCG in CI, traceability check green, the Allure reports uploaded, deviations logged.

---

## How to work through this file

1. **One step at a time, in order.** Steps inside a phase depend on earlier ones unless marked *(parallel-safe)*.
2. **Tick boxes** (`- [x]`) when the action is done *and* its Verify passed.
3. **Commit per step:** message `step <n.m>: <title>`. Push at least at every phase gate so CI runs.
4. **Annotate tests:** `@Requirement("REQ-X-NN")` per requirement a test verifies (REQUIREMENTS.md §3.1). The *Requirements* line of each step lists them. Never `@Disabled`; use `assumeTrue`.
5. **Attach evidence:** every integration test extends `VmIT` (standard evidence is automatic) and attaches the requirement-specific evidence named in REQUIREMENTS.md through `Evidence`/`VmEvidence`, with exactly those attachment names.
6. **Timing-sensitive tests** (boot times, recording duration) are judged in CI on GitHub-hosted runners; a slower local machine is not a reason to relax a limit. Log it instead.
7. **Unverified items** are marked **[U]** in PLAN.md. When a step exercises one, record the outcome in the Deviation log (worked as written, or what had to change).
8. **Never** do the things PLAN.md §1 rules a–l forbid, even "temporarily".

---

## Progress overview

| Phase | Milestone | Content | Gate |
|-------|-----------|---------|------|
| 0 | — | Adopt the plan: names, versions, dev host | §0.G |
| 1 | M1 | Build, test-support/Allure, images, guest disk, core library, exec, copy, KVM handling, CI | §1.G |
| 2 | M2 | Screens, VNC, screenshots, keyboard, UEFI | §2.G |
| 3 | M3 | Image sources (OCI, full HTTP, reference guest), registry fixture | §3.G |
| 4 | M4 | Screen recording, audio | §4.G |
| 5 | M5 | Hot-plug | §5.G |
| 6 | M6 | Cleanup, JUnit extension, full traceability, release readiness | §6.G |

---

## Phase 0 — Adopt the plan

### Step 0.1 — Bring the documents into the target repository
- [ ] Copy `PLAN.md`, `REQUIREMENTS.md` and `PLAN-STEPS.md` to `docs/vm-testcontainer/` in the target repository.
- [ ] Commit them unchanged first (so later edits show as diffs).

**Verify:** `ls docs/vm-testcontainer/` lists the three files; `git log --oneline -1 -- docs/vm-testcontainer` shows the import commit.

### Step 0.2 — Set the project parameters
- [ ] Decide the real value for every row of PLAN.md §0 (coordinates, package, registry, image names, prefixes, `${REQ_DOC_URL}`).
- [ ] Edit the **Example value** column of PLAN.md §0 only. Do not edit `${…}` references anywhere else.

**Verify:** for each example value you replaced, `grep -rn -F '<old value>' docs/vm-testcontainer/PLAN.md` returns nothing (the other two documents never contain the example values).

### Step 0.3 — Refresh versions
- [ ] Look up the current releases of Gradle, Testcontainers 2.x, JUnit **5.x** (not 6), Jackson 2.x, commons-compress, xz, zstd-jni, SLF4J, AssertJ, Allure Java **2.x** (not 3) and Allure Report 2.x on Maven Central / their release pages (PLAN.md §16 item 2).
- [ ] If a newer **patch** or **minor** exists, update §0. A new **major** (Testcontainers 3, Jackson 3, JUnit 6, Allure Java 3) is out of scope: keep the planned major and log it (PLAN.md D10).

**Verify:** `git show --stat HEAD` lists `docs/vm-testcontainer/PLAN.md`, and the commit message has one `<name> <version> <source URL> <date>` line per version row.

### Step 0.4 — Record open decisions
- [ ] Open the Deviation log (§D) and confirm its seeded items: R2 (decided: private registry only), R3 (SELinux hosts), and §8.4 UEFI boot (T22).

**Verify:** §D lists the three items with owner "unassigned" or a name.

### Step 0.5 — Prepare a development host
- [ ] x86-64 Linux with Podman ≥ 4.9 (rootless), JDK `${JAVA_RELEASE}`, a local Gradle installation (any recent version, only to generate the wrapper), `git`, `curl`, `openssl`, `python3`.
- [ ] Optional but recommended: usable `/dev/kvm` (`ls -l /dev/kvm`; your user must be able to open it read/write).
- [ ] Start the Podman API service and export the environment the tests use:

  ```bash
  mkdir -p "$XDG_RUNTIME_DIR/podman"
  podman system service --time=0 "unix://$XDG_RUNTIME_DIR/podman/podman.sock" &
  export DOCKER_HOST="unix://$XDG_RUNTIME_DIR/podman/podman.sock" TESTCONTAINERS_RYUK_DISABLED=true
  ```

**Verify:**
- `curl -s --unix-socket "$XDG_RUNTIME_DIR/podman/podman.sock" http://d/_ping` prints `OK`.
- `podman info --format '{{.Host.Security.Rootless}}'` prints `true`.
- With KVM: `test -r /dev/kvm -a -w /dev/kvm && echo usable` prints `usable`.

### Step 0.6 — Read the critical parts of the plan
- [ ] PLAN.md §1 (rules a–l), §4, §7.1, §8.1, §9.2, §9.6; REQUIREMENTS.md §2–§3.

**Verify:** none (reading). Tick when done.

### 0.G — Phase 0 gate
- [ ] Steps 0.1–0.6 ticked; parameter edits committed and pushed.

---

## Phase 1 (M1) — Build, test-support, images, core library

### Step 1.1 — Gradle skeleton
- [ ] Run `gradle wrapper --gradle-version <GRADLE_VERSION>` to create the wrapper; use `./gradlew` from here on.
- [ ] `settings.gradle`: `rootProject.name = '${ROOT_PROJECT}'`, `include 'lib', 'junit5', 'test-support', 'images', 'it'`.
- [ ] `gradle.properties` with every key of PLAN.md §5 (group, version, artifactLib, artifactJunit, basePackage, imageRegistry, imageRunnerName, imageGuestName, imageTagSuffix, labelPrefix, syspropPrefix, envPrefix, cacheDirName, javaRelease, ubiImage, reqDocUrl) plus `traceabilityRequireFullCoverage=false`.
- [ ] `gradle/libs.versions.toml` with the §0 versions: testcontainers, junit-bom, junit-platform-launcher (no version; the BOM supplies it), jackson-databind, commons-compress, xz, zstd-jni, slf4j, assertj, allure-bom, allure-commandline.
- [ ] Empty `build.gradle` per subproject and a root `build.gradle`.

**Files:** `settings.gradle`, `gradle.properties`, `gradle/libs.versions.toml`, `gradle/wrapper/*`, `build.gradle`, `{lib,junit5,test-support,images,it}/build.gradle`
**Spec:** PLAN.md §5, §11.1
**Verify:** `./gradlew projects` lists `:images`, `:it`, `:junit5`, `:lib`, `:test-support`.

### Step 1.2 — Build conventions and publishing
- [ ] Java toolchain `${JAVA_RELEASE}`; `options.compilerArgs += ['-Xlint:all,-processing,-requires-automatic,-requires-transitive-automatic,-this-escape', '-Werror']` in every module.
- [ ] `:lib`: `java-library` + `maven-publish`, `artifactId = artifactLib`; dependencies exactly as PLAN.md §9.1 (api: Testcontainers; implementation: jackson-databind, commons-compress, xz, zstd-jni; testImplementation: JUnit BOM + `junit-jupiter`, AssertJ, slf4j-simple, `project(':test-support')`; testRuntimeOnly: `junit-platform-launcher`).
- [ ] `:junit5`: `java-library` + `maven-publish`, `artifactId = artifactJunit`, `api project(':lib')`, `api 'org.junit.jupiter:junit-jupiter-api'`, same test dependencies.
- [ ] `:test-support`: `java-library`, **not** published; dependencies per PLAN.md §11.4 (`api platform(allure-bom)`, `api allure-jupiter`, `api allure-java-commons`, `api junit-jupiter-api`, `api jackson-databind`).
- [ ] `:lib` gets `src/main/java/module-info.java` (module name = `${BASE_PACKAGE}`); `requires transitive org.testcontainers;` etc. Exports are filled in step 1.10.
- [ ] Every `Test` task: `useJUnitPlatform()`.

**Spec:** PLAN.md §9.1, §11.1, §11.4
**Verify:**
- `./gradlew build` succeeds (no sources yet is fine).
- `./gradlew publishToMavenLocal` creates `~/.m2/repository/<group as path>/<artifactLib>/<version>/<artifactLib>-<version>.pom` and the same for `<artifactJunit>`, and `grep -c '<artifactId><artifactLib></artifactId>' <pom>` prints `1`.
- `./gradlew :lib:dependencies --configuration runtimeClasspath` shows no `land.oras` and only the §9.1 direct dependencies.

**Requirements:** REQ-BLD-02, REQ-BLD-03, REQ-PLAT-03, REQ-OCI-06

### Step 1.3 — Generated constants
- [ ] Task `:lib:generateConstants` writes `VmtcConstants.java` exactly per PLAN.md §11.3 into `build/generated/sources/vmtc/java/<basePackage path>/`; register the directory as a `main` source dir; `compileJava` depends on it; declare the `gradle.properties` values as task inputs.
- [ ] `RunnerVariant` enum (`SLIM`, `MEDIA`) must exist for it to compile (create it now in `${BASE_PACKAGE}`).
- [ ] **U5 `VmtcConstantsTest`:** the `:lib:test` task passes each relevant `gradle.properties` value as a system property (`systemProperty 'expected.version', version` …); the test asserts every constant and both `defaultRunnerImage(...)` strings and `referenceGuestImage()`, attaching `constants.txt`. (U5 cannot carry `@Requirement` yet — test-support comes in 1.5; add the annotation there.)

**Spec:** PLAN.md §11.3
**Verify:** `./gradlew :lib:test --tests '*VmtcConstantsTest'` passes; changing `version` in `gradle.properties` and re-running regenerates the file (check its content), then revert.
**Requirements:** REQ-BLD-01 (U5)

### Step 1.4 — Parameter-hygiene and dependency checks
- [ ] Root task `checkParameterHygiene` implementing **every** rule of PLAN.md §0 "Parameter-hygiene check" and §11.5: `git grep --untracked -n -w -F -- <value>` for `group`, `basePackage`, `imageRegistry`, `imageRunnerName`, `imageGuestName`, `labelPrefix`; allowed: `gradle.properties`, any `build/` path, `docs/vm-testcontainer/*.md`, `README.md`; `group` and `basePackage` additionally allowed in `*.java`. Plus the `lib/src/main` greps (`ProcessBuilder`, `Runtime.getRuntime().exec`, `throw new RuntimeException(`, `throw new UncheckedIOException(`), the `ci.yml` cron grep (skip with a WARN until the file exists in step 1.24), and the `@Disabled` grep over all test source sets. Print each offending `file:line`.
- [ ] Root task `checkDependencies` per PLAN.md §11.5: compares `:lib`'s declared `api` and `implementation` dependencies (`group:name`) with the §9.1 table and fails on any difference.

**Spec:** PLAN.md §0, §11.5
**Verify:**
- Both tasks pass on the clean tree.
- Create `scratch.txt` containing the `imageRegistry` value (do **not** `git add` it) → `checkParameterHygiene` fails naming `scratch.txt:1`; delete it.
- Add `implementation 'com.google.guava:guava:33.4.0-jre'` to `lib/build.gradle` → `checkDependencies` fails naming it; revert.

**Requirements:** REQ-BLD-01, REQ-BLD-03, REQ-ENG-03, REQ-ERR-01 (check part), REQ-OCI-06

### Step 1.5 — `test-support`: `@Requirement`, catalog, extension, evidence, traceability check
- [ ] `Requirement`, `Requirements` exactly as REQUIREMENTS.md §3.1.
- [ ] `RequirementsCatalog`: parser implementing the REQUIREMENTS.md §3.2 table (epic, feature, story row, retired, needs-a-test). Input: the resource `/vmtc-requirements/REQUIREMENTS.md` (Gradle `processResources` copies `../docs/vm-testcontainer/REQUIREMENTS.md` there; declare the file as an input).
- [ ] `RequirementExtension implements BeforeTestExecutionCallback`: the four actions of §3.2 (fail on unknown/retired; epic/feature/story/label; link; description).
- [ ] `Evidence`: `text`, `json`, `file` (type by extension table), `digest`, `exec` per REQUIREMENTS.md §3.3, all delegating to `Allure.addAttachment(name, type, InputStream, extension)`. `exec` takes `(String name, int exitCode, Integer signal, String stdout, String stderr, boolean outTrunc, boolean errTrunc, String... argv)` so test-support does not depend on `:lib`.
- [ ] `AllureEnvironment.write(Path resultsDir, Map<String,String>)` → `environment.properties`.
- [ ] `RequirementsCheck` (`main`): REQUIREMENTS.md §3.4 steps 1–5; arguments `<REQUIREMENTS.md> <requireFullCoverage:true|false> <reportFile> <sourceRoot>...`.
- [ ] Root task `checkRequirementTraceability` = `JavaExec` of `RequirementsCheck` with the four test source roots of §3.4 and `traceabilityRequireFullCoverage` from `gradle.properties`.
- [ ] `src/test/resources/allure.properties` in `:lib`, `:junit5`, `:it` (create the directories now): `allure.results.directory=build/allure-results`.
- [ ] Add `@Requirement("REQ-BLD-01")` to U5 (step 1.3).
- [ ] Unit tests for the catalog in `test-support/src/test/java`: every row of the real REQUIREMENTS.md parses; feature-code mismatch and malformed rows fail; anchor computation (`#### Feature EXEC: Run commands` → `feature-exec-run-commands`).

**Spec:** REQUIREMENTS.md §3.1–§3.4; PLAN.md §11.4, §11.5
**Verify:**
- `./gradlew :test-support:test checkRequirementTraceability` passes; `build/reports/requirements/traceability.md` lists every *Test* requirement except REQ-BLD-01 as `NONE`.
- Add a test with `@Requirement("REQ-NOPE-01")` → the task fails naming it; remove it.
- Temporarily set `traceabilityRequireFullCoverage=true` → the task fails listing the missing IDs; revert.
- `./gradlew :lib:test` then `ls lib/build/allure-results/*-result.json | head -1` exists, and `grep -l '"name":"story"' lib/build/allure-results/*-result.json` finds U5's result with `REQ-BLD-01 Single source for parameters`.

**Requirements:** REQ-BLD-06, REQ-BLD-07 (part)

### Step 1.6 — `allureReport` task
- [ ] Root task `allureReport` per PLAN.md §11.4: detached configuration `allureCli` (`io.qameta.allure:allure-commandline:<ALLURE_REPORT_VERSION>@zip`), `Sync` into `build/allure-cli/`, `AllureEnvironment` output, `Exec` of `bin/allure generate --clean --single-file -o build/allure-report <existing results dirs>`; never fails on a missing results dir.

**Spec:** PLAN.md §11.4
**Verify:** after `./gradlew :lib:test allureReport`, `build/allure-report/index.html` exists and is > 1 MB; opening it in a browser from disk shows *Behaviors → Guest Contract, Build & Tooling → BLD: Build, publishing, retargeting and reporting → REQ-BLD-01 …* with U5 and its `constants.txt` attachment.
**Requirements:** REQ-BLD-07

### Step 1.7 — Runner image sources
- [ ] `images/runner/repos/epel.repo` = PLAN.md Appendix A.1, `images/runner/repos/rocky.repo` = Appendix A.2 (byte-exact).
- [ ] `images/runner/Containerfile` = PLAN.md §6.3 (byte-exact).
- [ ] `images/runner/scripts/vmtc-entrypoint`: implement PLAN.md §7.1 **steps 0–11 in that order** (defaults with `${VAR:-default}`, trap installed before QEMU starts, the `wait` loop) and the QEMU command line of §8.1 (array-built, no `eval`). Include UEFI per §8.4.
- [ ] `images/runner/scripts/vmtc-rec`: implement PLAN.md §7.2 (used from M4, but the Containerfile needs the file now), including the `Duration:` → decimal-seconds conversion.
- [ ] If `shellcheck` is available, run it on both scripts and fix findings.

**Spec:** PLAN.md §6.2, §6.3, §7, §8.1, §8.4, Appendix A.1–A.2
**Verify:** `bash -n images/runner/scripts/vmtc-entrypoint images/runner/scripts/vmtc-rec` succeeds; `grep -n 'trap on_term' images/runner/scripts/vmtc-entrypoint` appears **before** the line that starts QEMU.
**Requirements:** REQ-IMG-01, REQ-IMG-02, REQ-IMG-05

### Step 1.8 — Image build, verification and push tasks
- [ ] In `images/build.gradle`: `buildRunnerSlim`, `buildRunnerMedia`, `verifyRunnerImages`, `pushImages` per PLAN.md §11.2 (engine from `-PcontainerEngine`, default `podman`; `-PnoCache=true` support; declare inputs = `images/runner/**`, outputs = an image-ID marker file under `build/`).
- [ ] `verifyRunnerImages` runs checks 1–10 of PLAN.md §6.4 in each image (`<engine> run --rm --entrypoint /bin/bash <image> -c '<check>'`), checks 5 and 6 on the host. Check 10 queries only packages the Containerfile installed (`qemu-kvm-core`, `tini`, `jq`); base-layer packages give an opaque id (PLAN.md §6.4).

**Spec:** PLAN.md §6.1, §6.4, §11.2
**Verify:** `./gradlew :images:verifyRunnerImages` succeeds and prints each check's result; `podman images` shows both tags; `./gradlew :images:pushImages` reports the task as `SKIPPED` without `-PpushImages=true`. Build time per image is in the order of a minute (PLAN.md §12.2; log large differences).
**Requirements:** REQ-IMG-01, REQ-IMG-02, REQ-IMG-03, REQ-IMG-04, REQ-IMG-05 (check part)

### Step 1.9 — Smoke-test the entrypoint by hand (no Java yet)
- [ ] Download the stock guest and boot it in the slim runner:

  ```bash
  GUEST_BASE_URL='<guestBaseUrl from PLAN.md §0>'; GUEST_BASE_SHA256='<guestBaseSha256>'
  SLIM='<imageRegistry>/<imageRunnerName>:<version>-<imageTagSuffix>'
  mkdir -p /tmp/vmtc-smoke && cd /tmp/vmtc-smoke
  curl -fL -o base.qcow2 "$GUEST_BASE_URL" && echo "$GUEST_BASE_SHA256  base.qcow2" | sha256sum -c -
  podman run -d --name vmtc-smoke [--device /dev/kvm] -v "$PWD/base.qcow2:/vm/base/disk.img:ro" "$SLIM"
  timeout 60 podman logs -f vmtc-smoke   # expect VMTC_ACCEL kvm|tcg, VMTC_QEMU_STARTING <epoch>, VMTC_READY; Ctrl-C or wait for the timeout
  ```

- [ ] Ping the guest agent through the in-image `socat` (retry until the guest has booted: ~15 s KVM, ~70 s TCG):

  ```bash
  podman exec vmtc-smoke sh -c "printf '{\"execute\":\"guest-ping\"}\n' | socat -t5 - TCP:127.0.0.1:4445"
  # expect: {"return": {}}
  ```

- [ ] `time podman stop -t 15 vmtc-smoke` → returns well under 15 s (the trap quits QEMU via the internal QMP socket); then `podman rm vmtc-smoke`.
- [ ] Error paths: run **without** the `-v` → logs `VMTC_ERROR no base disk`, exit code 2 (`podman wait`). Run **with** the `-v` mount, `-e VM_ACCEL=kvm` and **no** `--device` → `VMTC_ERROR kvm requested but /dev/kvm unusable`, exit code 3.

**Verify:** all four bullets behave as stated. Record the measured times in the step's commit message.
**Requirements:** REQ-LIFE-04 (manual precursor), PLAN.md §7.1

### Step 1.10 — Reference guest build
- [ ] `images/guest/guest.properties` with `baseUrl=<guestBaseUrl>` and `baseSha256=<guestBaseSha256>`.
- [ ] `images/guest/customize.sh` implementing PLAN.md §10.3 steps 1–4 with the signature given there (`set -euo pipefail`; `VM_ACCEL=auto`; `/vm/out` mount; add `:z`/`,z` to the volume options only when `/sys/fs/selinux/enforce` contains `1`), followed by the REQ-GUEST-05 format checks.
- [ ] Gradle `downloadGuestBase` and `buildGuestDisk` per PLAN.md §11.2.

**Spec:** PLAN.md §10.3, §11.2
**Verify:**
- `./gradlew :images:buildGuestDisk` succeeds.
- `grep -a -E 'VMTC_CUSTOMIZE_DONE|DNF_RC=0' images/build/guest/work/out/serial.log` prints both lines.
- `images/build/guest/work/run/rocky9-vmtc.qcow2` exists (≈ 0.8 GB, PLAN.md §10.3).
- `podman image exists <imageRegistry>/<imageGuestName>:<version>` succeeds; the task output shows the format checks (one layer, uid/gid 107).
- KVM build takes about 1.5 min (PLAN.md §12.2).

**Requirements:** REQ-GUEST-04 (artefact), REQ-GUEST-05 (check part)

### Step 1.11 — Core types and module exports
- [ ] Create every package and type of PLAN.md §9.1 that M1 needs, as **compilable stubs where the behaviour comes later**: `QemuVmContainer` (stub), `VmImage` with its four nested records and the `fromPath`/`fromUrl`/`baked` factories (others throw `UnsupportedOperationException` until Phase 3), `KvmMode`, `Accelerator`, `Firmware`, `ExecRequest`, `GuestExecResult`, `Warnings`, `VmtcException` and **all** subclasses listed in §9.1 (each with `serialVersionUID`), `qmp.QmpException`; and one public type per remaining exported package so `module-info` can export it (`image.VmImageCache` shell, `qga.GuestAgentClient` shell, `display.VmScreen`, `display.VncEndpoint`, `display.RecordingOptions`, `display.KeyboardInput`, `display.ScreenRecording`, `audio.AudioRecording`, `hotplug.DeviceSpec` with its nested records, `hotplug.Devices`, `hotplug.DiskBus`, `hotplug.HotplugHandle`, `hotplug.CdromDrive`, `cloudinit.CloudInitSeed` shell). Interfaces may be empty; records may be complete.
- [ ] `module-info.java`: export every package except `${BASE_PACKAGE}.internal`.
- [ ] **U14 `ApiShapeTest`:** reflection over the public exception types (unchecked, extend `VmtcException`); `ModuleDescriptor.read(QemuVmContainer.class.getResourceAsStream("/module-info.class"))` → export set; class-file major version of `QemuVmContainer.class` (read bytes 6–7) = 44 + `${JAVA_RELEASE}`. Evidence: `exception-types.txt`, `module-exports.txt`, `class-version.txt`.

**Spec:** PLAN.md §9.1, §9.11
**Verify:** `./gradlew :lib:jar :lib:test --tests '*ApiShapeTest'` passes (the jar step proves every exported package has a class).
**Requirements:** REQ-ERR-01, REQ-BLD-04, REQ-PLAT-03 (U14)

### Step 1.12 — Runtime overrides and `Warnings`
- [ ] `internal.Overrides`: resolves each PLAN.md §9.1 key with precedence **builder > system property > environment > default** (D9); the environment is an injected `Map<String,String>` (default `System.getenv()`); unparsable values → `IllegalArgumentException("<key>: …")`.
- [ ] `Warnings` per PLAN.md §9.11 (`warn`, `warnOnce`, `listen`).
- [ ] **U6 `OverridesTest`:** table-driven over every key and each precedence level; the three invalid values. Evidence `precedence-table.txt`. (The `referenceGuest()` case is added in step 3.5.)

**Spec:** PLAN.md §9.1, §9.11
**Verify:** `./gradlew :lib:test --tests '*OverridesTest'` passes.
**Requirements:** REQ-CFG-01 (U6)

### Step 1.13 — JSON and QMP client
- [ ] `internal.Json` (Jackson `ObjectMapper` wrapper), `internal.Backoff` (injectable sleeper).
- [ ] `qmp.QmpClient`: TCP, newline-delimited JSON; read greeting, send `qmp_capabilities`; one request in flight; skip `event` messages while waiting for `return`/`error` and expose them as `QmpEvent`s; `error` → `QmpException(class, desc)`; configurable response timeout; `execute(String command)` / `execute(String command, Map args)` returning the `return` node; `hmp(String command)` helper (`human-monitor-command`).
- [ ] **U1 `QmpClientTest`** against an in-JVM fake QMP server (a `ServerSocket` thread scripted per test).

**Spec:** PLAN.md §4 principle 2, §9.11, §3.2 (single TCP client)
**Verify:** `./gradlew :lib:test --tests '*QmpClientTest'` passes.
**Requirements:** REQ-ERR-02 (U1)

### Step 1.14 — Guest agent client
- [ ] `qga.GuestAgentClient`: per PLAN.md §9.4 protocol and §9.2 step 10 handshake (`guest-sync-delimited`, skip `0xFF`, match id; `guest-ping`; public `ping()`); `ReentrantLock`; reconnect once on I/O error and continue; map `CommandNotFound` + "has been disabled" to `GuestCommandDisabledException`; exec (`guest-exec` + `guest-exec-status` polling 50 ms ×1.5 → 500 ms cap; `env` as `K=V` list; `exitCode = -1` when only `signal` is present; UTF-8 with replacement) and file primitives (`guest-file-open/read/write/close`, 1 MiB chunks).
- [ ] **U2 `GuestAgentClientTest`** with a fake QGA server: every item in PLAN.md §13.2 U2.

**Spec:** PLAN.md §9.2 steps 10–11, §9.4, §9.5
**Verify:** `./gradlew :lib:test --tests '*GuestAgentClientTest'` passes.
**Requirements:** REQ-EXEC-05, REQ-EXEC-06 (unit part), REQ-GUEST-01 (unit part) (U2)

### Step 1.15 — KVM detection and decision
- [ ] `internal.KvmDetector` (`present`, `usable`, `worldAccessible`) per PLAN.md §9.6.
- [ ] `internal.KvmDecision decide(KvmMode, detector state)` returning: device yes/no, annotation yes/no, `VM_ACCEL` value, or the exception/WARN message. **No code path may produce `GroupAdd keep-groups`.**
- [ ] `internal.StartupMessages`: the three §9.6 messages as constants/methods (`kvmNotPresent()`, `kvmNotUsable()`, `kvmGroupRestricted()`), `tail(List<String>, int)`.
- [ ] **U8 `KvmDecisionTest`:** every row of PLAN.md §8.2 × the three §9.6 detector situations; exact message strings; the post-start fallback message; once-per-JVM WARN through `Warnings.listen`.

**Spec:** PLAN.md §8.2, §9.6, §9.11
**Verify:** `./gradlew :lib:test --tests '*KvmDecisionTest'` passes.
**Requirements:** REQ-KVM-02, REQ-KVM-03, REQ-KVM-04, REQ-KVM-05, REQ-KVM-06 (U8)

### Step 1.16 — Builder methods, validation and timeouts
- [ ] Add to the `QemuVmContainer` stub every builder method of PLAN.md §9.2 as **fields + validation only** (behaviour comes in 1.20): `withVmImage`, `withKvm`, `withCpus`, `withMaxCpus`, `withMemoryMb`, `withMemoryHotplug`, `withScreens`, `withVncBindAddress`, `withHotplugPorts`, `withFirmware`, `withCloudInit`, `withGuestAgentTimeout`, `withExecTimeout`, `withTcgTimeoutFactor`, `withSerialLogConsumer`, `withQemuArgs`. Ranges per §9.2 → `IllegalArgumentException` in the call.
- [ ] `internal.Timeouts` (or a method set): effective timeouts per REQ-KVM-07 — scaled defaults for guest-agent/exec/unplug when `accelerator()==TCG`, explicit values never scaled, container-start wait fixed.
- [ ] **U9 `ConfigValidationTest`** for both, with `timeouts.txt` evidence.

**Spec:** PLAN.md §8.2, §9.2; REQUIREMENTS.md REQ-KVM-07
**Verify:** `./gradlew :lib:test --tests '*ConfigValidationTest'` passes.
**Requirements:** REQ-CFG-02, REQ-DISP-01, REQ-KVM-07 (U9)

### Step 1.17 — Image cache, `fromPath` and `baked()`
- [ ] `image.Qcow2Header` (magic, `backingFile(Path)` → `Optional<String>`) per PLAN.md §9.3.1.
- [ ] `image.VmImageCache` per §9.3.1: per-key `ReentrantLock` + `FileChannel.lock()`; tmp file + `ATOMIC_MOVE`; `index.json` with the given schema under `index.lock`; touch on use; `prune(Duration)` incl. side files and index rows; the SELinux flag (`selinuxEnforcing`, injectable) decides `SelinuxContext`.
- [ ] `image.SparseCopy.copy(Path src, Path dst)` per PLAN.md §9.3.1 (1 MiB blocks, skip all-zero blocks by positioning, set the final length).
- [ ] `fromPath`: backing-file rejection; key = SHA-256; memo; `SparseCopy`, never a link.
- [ ] `VmImage.baked()` behaviour: no cache, no bind (PLAN.md §9.3.4). Needed by T29.
- [ ] **U3 `VmImageCacheTest`:** two threads; two JVMs (a helper `main` in `lib/src/test/java` launched with `ProcessBuilder` using the test classpath); no leftover tmp files; inode differs from the source; memo hit avoids a second copy (count via a spy stream); `prune`; SELinux flag; `Qcow2Header` on crafted headers; `SparseCopy` allocated-size check via `stat -c '%b %B'` (REQ-DISK-14). Evidence `cache-listing.txt`, `bind.txt`.

**Spec:** PLAN.md §9.3.1, §9.3.2, §9.3.4
**Verify:** `./gradlew :lib:test --tests '*VmImageCacheTest'` passes.
**Requirements:** REQ-DISK-06, REQ-DISK-07, REQ-DISK-08, REQ-DISK-09, REQ-DISK-10 (unit part), REQ-DISK-14 (U3)

### Step 1.18 — Basic HTTP download
- [ ] `image.ImageDownloader` with PLAN.md §9.3.5 steps 1–7 **without** credentials and decompression: `HttpClient` with `Redirect.NEVER`, manual redirects (≤ 5, `ImageDownloadException` on the 6th), status handling, `DigestInputStream`, SHA-256 check, atomic move, retries with an injectable `Backoff`. `UrlOptions` with `sha256`, `timeout` and `defaults()` for now.
- [ ] `VmImage.fromUrl(URI)` and `fromUrl(URI, UrlOptions)`; cache key per §9.3.1.
- [ ] Start **`HttpDownloadTest`** (T26) in `:lib` with cases (c) wrong checksum, (g) 5 vs 6 redirects, (h) retry counts (zero backoff). Annotate only REQ-HTTP-04, REQ-HTTP-07, REQ-HTTP-08 now; the rest comes in step 3.3.

**Spec:** PLAN.md §9.3.5
**Verify:** `./gradlew :lib:test --tests '*HttpDownloadTest'` passes.
**Requirements:** REQ-HTTP-04, REQ-HTTP-07, REQ-HTTP-08 (T26 partial)

### Step 1.19 — Cloud-init seed
- [ ] `cloudinit.CloudInitSeed` per PLAN.md §10.2: `builder()` (empty), `Builder.stockRocky()`, `packages`, `runcmd`, `writeFile`, `rootPassword`, `trustCaFile`, `build()` (always appends the `VMTC_SEED_DONE` echo); `userData()` / `metaData()` as `byte[]`; `forStockRocky()`.
- [ ] **U10 `CloudInitSeedTest`:** `forStockRocky().userData()` equals the §10.2 YAML byte for byte (store the expected text as a test resource copied from PLAN.md, including the `\$?`); every REQ-GUEST-06 rule; `builder().build()` still ends with the echo. Evidence `user-data.yaml`.
- [ ] Sanity check the expected resource with a YAML parser: `python3 -c 'import yaml,sys; print(yaml.safe_load(open(sys.argv[1])))' <resource>` must print the runcmd list (PLAN.md §10.2 explains why `\$?` is right).

**Spec:** PLAN.md §10.2
**Verify:** `./gradlew :lib:test --tests '*CloudInitSeedTest'` passes; the YAML sanity command prints the list.
**Requirements:** REQ-GUEST-03, REQ-GUEST-06 (unit part) (U10)

### Step 1.20 — `QemuVmContainer`: configure, wait strategy, `start()` override
- [ ] `configure()` per PLAN.md §9.2 steps 1–6: image resolution (`VmImageCache.resolve`, a `switch` over the sealed `VmImage`), bind via `addFileSystemBind(..., BindMode.READ_ONLY, SelinuxContext)`, KVM decision → devices/annotations, ports with `PublishAllPorts=false` (rule l), labels, env (§7.1), seed files.
- [ ] Start-time validation (`withVmImage` present, `maxmem ≥ mem`, override parsing).
- [ ] Runner variant detection from the image label for the `DockerImageName` constructor (missing → SLIM + WARN).
- [ ] `internal.VmWaitStrategy` implementing the §9.2 step 7 table with `StartupMessages`.
- [ ] `start()` override (rule e): unwrap `ContainerLaunchException` to the first `VmtcException` in the cause chain.
- [ ] Cleanup seam: a package-private static `Supplier<Integer> cleanupHook` invoked once per JVM before the first `configure()` (real implementation in step 6.1; a no-op stub now).
- [ ] **U16 `StartupMessagesTest`:** `tail`; the wait-strategy table against fake log lines; `start()` unwrapping with a subclass whose `configure()` throws `KvmUnavailableException`.
- [ ] **U17 `CleanupTriggerTest`:** counts the hook invocations across two `start()`s of such short-circuiting containers; `cleanup=false` → zero.

**Spec:** PLAN.md §9.2 (steps 1–7, override, validation, variant), §9.6, §7.1 env table, §9.12
**Verify:** `./gradlew :lib:test --tests '*StartupMessagesTest' --tests '*CleanupTriggerTest'` passes.
**Requirements:** REQ-LIFE-04 (unit part), REQ-ERR-03 (unit part), REQ-CLN-02 (U16, U17)

### Step 1.21 — `QemuVmContainer`: post-start sequence, exec, copy, stop
- [ ] `containerIsStarted`: PLAN.md §9.2 steps 8, 10–14 in that order (QMP connect + `VMTC_ACCEL` + PREFER WARN + timeout derivation; seed wait; QGA handshake loop; RPC-filter check; SELinux check with the non-SELinux skip; serial tailer with replay; `timings()`).
- [ ] `internal.SerialLogTailer`, `internal.PortBindings`, `describe()`, `accelerator()`, `variant()`, `qmp()`, `guestAgent()`, `saveSerialLog`.
- [ ] `exec(String…)`, `exec(Duration, String…)`, `exec(ExecRequest)`, `execShell(String)` per §9.4, including timeout kill and `GuestExecTimeoutException`.
- [ ] `copyToVm(Path|byte[], String)`, `copyFromVm`, `readFromVm` per §9.5.
- [ ] `stop()` per §9.2 (the recordings part becomes real in step 4.4).

**Spec:** PLAN.md §9.2, §9.4, §9.5
**Verify:** compiles; behaviour is verified by the `:it` tests of step 1.23.
**Requirements:** REQ-LIFE-01, 02, 04, 05, 07, 08, 10, 11, 12; REQ-EXEC-01..04, 06; REQ-FILE-01..03 (verified in 1.23 and later)

### Step 1.22 — Integration-test module and `VmIT`
- [ ] `it/build.gradle`: depends on `:lib`, `:test-support`, `:junit5` (from step 6.2 on), JUnit, AssertJ, slf4j-simple, `junit-platform-testkit` (for T34 later); the `Test` tasks of PLAN.md §11.1 (`integrationTest`, `compatTest`, `ociAuthTestA..D` + `ociAuthTest`) with `failOnNoDiscoveredTests = false`, the tag mapping (`excludeTags`), `providers.gradleProperty` forwarding, `dependsOn ':images:verifyRunnerImages', ':images:buildGuestDisk'`, and the two `*.it.*Qcow2` system properties.
- [ ] `it/src/test/java/…/TestImages.guest()` → `VmImage.fromPath(Path.of(System.getProperty("<syspropPrefix>.it.guestQcow2")))` for now.
- [ ] `VmIT` base class: `@BeforeAll` warm-up (`new VmImageCache(cacheDir).resolve(TestImages.guest(), DockerClientFactory.instance().client())` once, so the cache is populated before any timed start); `register(container)`; `@AfterEach` attaches the standard evidence (REQUIREMENTS.md §3.3, via `Warnings.listen` started in `@BeforeEach`) and stops registered containers; on failure also one screenshot per screen (best effort).
- [ ] `VmEvidence` (`screenshot`, `audio` with the waveform/spectrum PNG renderer, `inspect`, `qmp`) per REQUIREMENTS.md §3.3. The audio analysis (RMS, dominant frequency by DFT over ≤ 65536 samples) lives here so T11 and the evidence share it.

**Spec:** PLAN.md §11.1; REQUIREMENTS.md §3.3
**Verify:** `./gradlew :it:integrationTest -P<syspropPrefix>.kvm=DISABLE` runs (zero tests at this point is fine because `failOnNoDiscoveredTests=false`) and the dependency tasks execute.

### Step 1.23 — M1 integration tests
- [ ] Write T1, T2, T3, T4, T20, T25, T29, T30, T32, T36, T37 exactly as PLAN.md §13.1 describes, each extending `VmIT`, annotated with its *Verifies* IDs and tagged `kvm`/`tcg` where stated, attaching the evidence its requirements name.
- [ ] Run on KVM: `./gradlew :it:integrationTest -P<syspropPrefix>.kvm=REQUIRE`.
- [ ] Run on TCG: `./gradlew :it:integrationTest -P<syspropPrefix>.kvm=DISABLE` (expect ~1 min per boot; T25 takes 5–8 min under TCG because of the seed). T20 is skipped (assumption) on a host with usable KVM; it is only meaningful in the CI tcg job.
- [ ] `./gradlew allureReport` and open the report: every test shows under its epic/feature/story with `container.log`, `serial.log`, `vm-config.json`, `timings.json`, `warnings.txt` and its specific evidence.

**Spec:** PLAN.md §13.1; REQUIREMENTS.md §4
**Verify:** all listed tests pass in both runs (T20 skipped locally is acceptable); the report shows the attachments. Compare T2 times with REQ-LIFE-02.
**Requirements:** REQ-KVM-01, 03, 04; REQ-LIFE-01, 02, 04, 05, 07, 10, 11; REQ-EXEC-01..04, 06; REQ-FILE-01..03; REQ-GUEST-06 (integration part), REQ-GUEST-07; REQ-DISK-01, 02; REQ-ENG-01; REQ-PLAT-02; REQ-CFG-03; REQ-ERR-03

### Step 1.24 — CI workflow
- [ ] `.github/workflows/ci.yml` per PLAN.md §12.1 with jobs `unit` and `integration` (matrix `{ubuntu-24.04, kvm}`, `{ubuntu-24.04, tcg}`, `{ubuntu-26.04, kvm}`), triggers `push`, `pull_request`, weekly `schedule` (passing `-PnoCache=true`).
- [ ] `integration` steps 1–7 of §12.1; pass `-P<syspropPrefix>.kvm=<REQUIRE|DISABLE>`. **Do not** apply the udev rule in the TCG job (T20 depends on that).
- [ ] Cache `images/build/guest/base.qcow2` keyed by `baseSha256`.
- [ ] Upload the Allure report and results, `it/build/vm-artifacts/**`, `it/build/reports/**`, and (unit) `build/reports/requirements/traceability.md`, all `if: always()`.

**Spec:** PLAN.md §12.1, §12.2
**Verify:** push; all jobs green; T20 passes (not skipped) in the tcg job. Download `allure-report-integration-ubuntu-24.04-kvm` and open `index.html` from disk: it renders with the attachments. KVM integration finishes well inside 30 min; TCG inside 90 min.
**Requirements:** REQ-PLAT-01, REQ-ENG-01, REQ-BLD-05, REQ-BLD-06, REQ-BLD-07, REQ-KVM-02 (T20 in CI)

### Step 1.25 — KVM compatibility job
- [ ] `it/fixtures/kvm-scenario.sh <A|B|C>` (self-contained; the inline list is the spec):
  - Stop a service from a previous scenario (`pkill -f 'podman system service --time=0 unix://<sock>' || true`) and remove `~/.config/containers/containers.conf`.
  - **A:** write the udev rule `KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"` to `/etc/udev/rules.d/99-kvm4all.rules`, `udevadm control --reload-rules && udevadm trigger --name-match=kvm`; start `podman system service --time=0 unix://<sock> &` normally.
  - **B:** remove that rule; `sudo chown root:kvm /dev/kvm; sudo chmod 0660 /dev/kvm; sudo usermod -aG kvm "$USER"`; start the service **in a fresh login session** so `kvm` is a *supplementary* group: `setsid sudo -iu "$USER" env XDG_RUNTIME_DIR="$XDG_RUNTIME_DIR" podman system service --time=0 unix://<sock> >/dev/null 2>&1 &`; then poll `curl -s --unix-socket <sock> http://d/_ping` until `OK` (max 30 s).
  - **C:** as B, plus `~/.config/containers/containers.conf` containing `[containers]` / `annotations = ["run.oci.keep_original_groups=1"]` written **before** the service starts.
  - Write `DOCKER_HOST=unix://<sock>`, `TESTCONTAINERS_RYUK_DISABLED=true`, `<envPrefix>IT_KVM_SCENARIO=<S>` and `<envPrefix>IT_PODMAN_VERSION=$(podman --version | awk '{print $3}')` to `$GITHUB_ENV` (or print `export …` lines for local use).
- [ ] **Run the test JVM like a logged-in user for B and C**, so `KvmDetector.usable()` is true: `sudo -iu "$USER" env XDG_RUNTIME_DIR="$XDG_RUNTIME_DIR" JAVA_HOME="$JAVA_HOME" DOCKER_HOST="$DOCKER_HOST" TESTCONTAINERS_RYUK_DISABLED=true <envPrefix>IT_KVM_SCENARIO=<S> <envPrefix>IT_PODMAN_VERSION=<v> bash -c 'cd "$GITHUB_WORKSPACE" && ./gradlew --no-daemon :it:compatTest'`. Without this the CI shell lacks the group, the library correctly decides "not usable", and the annotation path is never exercised. Verified in the spike (CI run 16): the CI-shell JVM reports `/dev/kvm` readable=false, the `sudo -iu` JVM readable=true writable=true, on both runners.
- [ ] `KvmCompatIT` (T35) per PLAN.md §13.1, attaching `scenario.txt`, `inspect.json`, `warnings.txt`.
- [ ] CI job `compat` (matrix ubuntu-24.04, ubuntu-26.04): `./gradlew :images:verifyRunnerImages`; for S in A, B, C: run the script, then `:it:compatTest` as above (plain invocation is fine for A); `allureReport`; uploads.

**Spec:** PLAN.md §9.6, §12.1, §13.1 T35; REQUIREMENTS.md REQ-KVM-05
**Verify:** `compat` green on both runners; the observed matrix equals PLAN.md §9.6; the Allure report of each scenario shows `inspect.json` with the expected `Annotations` and empty `GroupAdd`.
**Requirements:** REQ-KVM-05, REQ-KVM-06

### 1.G — Phase 1 gate
- [ ] `unit`, `integration` (3 entries) and `compat` jobs green on the pushed head.
- [ ] Tests U1–U3, U5, U6, U8–U10, U14, U16, U17, T1–T4, T20, T25, T29, T30, T32, T35, T36, T37 present, annotated, passing.
- [ ] `traceability.md` shows those requirements covered; Allure reports downloadable for every job.
- [ ] Deviation log updated.

---

## Phase 2 (M2) — Screens, VNC, keyboard, UEFI

### Step 2.1 — Screens and VNC configuration
- [ ] `withScreens(n)` and `withVncBindAddress` become effective: env `VM_SCREENS`; port exposure and bindings per PLAN.md §9.2 step 4 (VNC ports use the configured address, `PublishAllPorts=false`).

**Spec:** PLAN.md §8.3, §9.2 step 4
**Verify:** via T6.
**Requirements:** REQ-VNC-01, REQ-VNC-02, REQ-LIFE-08

### Step 2.2 — `VmScreen`, `VncEndpoint`, screenshots, `forceModeset`
- [ ] Implement PLAN.md §9.7 `VmScreen` (`index`, `vnc`, `screenshot()`, `screenshot(Path)`, `forceModeset()` with the head-0 no-op); `screens()`, `screen(i)`.
- [ ] Classpath resource `<basePackage path>/display/vmtc-fb-activate.py` = Appendix A.4 (byte-exact).
- [ ] `startRecording` methods: throw `UnsupportedOperationException` for now (real in step 4.2).

**Spec:** PLAN.md §9.7, Appendix A.4
**Verify:** via T5, T6.
**Requirements:** REQ-DISP-02, REQ-DISP-03, REQ-VNC-01, REQ-LIFE-12

### Step 2.3 — Keyboard
- [ ] `KeyboardInput.type` (validate-then-send) and `press` per PLAN.md §9.7 and Appendix A.3 (30 ms between characters); `QemuVmContainer.keyboard()`.
- [ ] **U4 `KeyboardInputTest`:** every row of Appendix A.3; unsupported character → exception and zero `send-key` on the fake QMP; `press` chord.

**Spec:** PLAN.md §9.7, Appendix A.3
**Verify:** `./gradlew :lib:test --tests '*KeyboardInputTest'` passes.
**Requirements:** REQ-KEY-01, REQ-KEY-02 (U4)

### Step 2.4 — UEFI
- [ ] `withFirmware(Firmware.UEFI)` → `VM_FIRMWARE=uefi`; the entrypoint already handles §8.4.
- [ ] Confirm the files in **your** image: `podman run --rm --entrypoint rpm <slim> -ql edk2-ovmf | grep -E 'OVMF_(CODE|VARS)\.fd$'` prints both `/usr/share/edk2/ovmf/OVMF_CODE.fd` and `/usr/share/edk2/ovmf/OVMF_VARS.fd` (PLAN.md §3.2). If not, update §8.4 and the entrypoint together and log it.

**Spec:** PLAN.md §8.4
**Verify:** via T22.
**Requirements:** REQ-LIFE-09

### Step 2.5 — M2 integration tests
- [ ] Write T5, T6, T12, T22 per PLAN.md §13.1.
- [ ] Run on KVM and TCG; regenerate the Allure report and check the screenshots render.

**Verify:** all pass in both runs. If T22 fails because the reference guest cannot boot under UEFI, log it in §D and propose either a UEFI-capable test disk or retiring REQ-LIFE-09 — do not silently drop the test.
**Requirements:** REQ-DISP-02, REQ-DISP-03, REQ-VNC-01, REQ-VNC-02, REQ-LIFE-08, REQ-LIFE-09, REQ-LIFE-12, REQ-KEY-01

### 2.G — Phase 2 gate
- [ ] CI green; U4, T5, T6, T12, T22 present, annotated, passing; deviations logged.

---

## Phase 3 (M3) — Image sources

### Step 3.1 — containerDisk extraction
- [ ] `image.ContainerDiskExtractor` per PLAN.md §9.3.3: always-pull policy, local fallback with WARN, pull-error mapping (`ImageAuthException` vs `VmtcException`), explicit-credential pull via `pullImageCmd(...).withAuthConfig(...)`, `inspectImageCmd` → image ID, create-not-start, `copyArchiveFromContainerCmd("/disk/")` with `NotFoundException` → `IllegalArgumentException`, exactly-one-file rule, `removeContainerCmd(...).withForce(true)` in `finally`.
- [ ] Cache key `sha256(imageId + "/" + fileName)`.

**Spec:** PLAN.md §9.3.1, §9.3.3
**Verify:** via T18 (c, e) and T27/T28.
**Requirements:** REQ-DISK-03, REQ-DISK-04, REQ-DISK-12, REQ-OCI-02..05, REQ-OCI-07

### Step 3.2 — `fromOci` / `fromContainerDisk`
- [ ] `VmImage.fromOci(String)`, `fromOci(String, OciOptions)`, `fromContainerDisk(DockerImageName)`, `fromContainerDisk(DockerImageName, OciOptions)`; `OciOptions`, `RegistryCredentials.basic(user, Secret)`; parsing and scheme rejection per PLAN.md §9.3.6.
- [ ] **U11 `OciReferenceTest`** with `parse-table.txt` evidence.

**Spec:** PLAN.md §9.3.6
**Verify:** `./gradlew :lib:test --tests '*OciReferenceTest'` passes.
**Requirements:** REQ-OCI-01 (U11)

### Step 3.3 — Full HTTP download
- [ ] Complete PLAN.md §9.3.5: `Secret` (incl. lazy `fromEnv`), `HttpCredentials.basic/bearer/header`, same-origin-only credential headers, `Compression` (AUTO by the final URL's suffix, explicit modes) via commons-compress (+ xz, zstd-jni), the read-stall watchdog (verified in the spike: close the body stream from a watchdog thread), revalidation with `<key>.meta.json` (one attempt), one WARN per URL without checksum, URL redaction in messages.
- [ ] Complete **T26 `HttpDownloadTest`** cases (a)–(i) per PLAN.md §13.2; generate `.xz`/`.zst` fixtures in the test with commons-compress output streams; request logs as `http-requests.txt`.
- [ ] **U12 `SecretAndRedactionTest`.**

**Spec:** PLAN.md §9.3.5
**Verify:** `./gradlew :lib:test --tests '*HttpDownloadTest' --tests '*SecretAndRedactionTest'` passes.
**Requirements:** REQ-HTTP-01..10 (T26, U12)

### Step 3.4 — `baked()` success-path fixture
- [ ] `baked()` exists since step 1.17 (T29 covers its failure path). Add the T18 (d) fixture: `podman build` of `FROM <slim>` + `COPY disk.qcow2 /vm/base/disk.img`, tagged `localhost/vmtc-it-baked:<version>`.

**Spec:** PLAN.md §9.3.4
**Verify:** via T18 (d) in step 3.8.
**Requirements:** REQ-DISK-05

### Step 3.5 — `referenceGuest()`
- [ ] `VmImage.referenceGuest()` → `fromContainerDisk(override 'guestImage' or VmtcConstants.referenceGuestImage())`.
- [ ] Extend **U6** with the resolution case.
- [ ] Switch `TestImages.guest()` to `VmImage.referenceGuest()` (the local image from `buildGuestDisk` is used through the local fallback of step 3.1 until images are pushed; expect the `using local image` WARN in `warnings.txt`).

**Spec:** PLAN.md §9.3, §11.1
**Verify:** U6 passes; re-run the M1 and M2 integration tests on KVM and TCG → still green.
**Requirements:** REQ-DISK-11 (U6)

### Step 3.6 — Test-registry fixture
- [ ] `it/fixtures/test-registry.sh start|stop` (self-contained; this list is the spec):
  - `start`: `WORK=it/build/test-registry`; `mkdir -p $WORK/certs $WORK/auth`.
  - Add `127.0.0.1 registry.test` to `/etc/hosts` if missing (`sudo`).
  - Self-signed cert: `openssl req -x509 -newkey rsa:2048 -nodes -days 2 -keyout $WORK/certs/registry.key -out $WORK/certs/registry.crt -subj /CN=registry.test -addext "subjectAltName=DNS:registry.test,DNS:localhost,IP:127.0.0.1"`.
  - htpasswd (bcrypt): `podman run --rm --entrypoint htpasswd docker.io/library/httpd:2 -Bbn vmtc s3cret > $WORK/auth/htpasswd`.
  - `podman run -d --name vmtc-it-registry -p 5443:5000 -v $WORK/certs:/certs[:z] -v $WORK/auth:/auth[:z] -e REGISTRY_AUTH=htpasswd -e REGISTRY_AUTH_HTPASSWD_REALM=vmtc -e REGISTRY_AUTH_HTPASSWD_PATH=/auth/htpasswd -e REGISTRY_HTTP_TLS_CERTIFICATE=/certs/registry.crt -e REGISTRY_HTTP_TLS_KEY=/certs/registry.key docker.io/library/registry:2`.
  - Wait until `curl -s -o /dev/null -w '%{http_code}' --cacert $WORK/certs/registry.crt https://registry.test:5443/v2/` prints `401` (max 30 s).
  - Trust the CA for rootless Podman: `mkdir -p ~/.config/containers/certs.d/registry.test:5443 && cp $WORK/certs/registry.crt ~/.config/containers/certs.d/registry.test:5443/ca.crt`.
  - Export `<envPrefix>IT_REGISTRY=registry.test:5443`, `<envPrefix>IT_REGISTRY_USER=vmtc`, `<envPrefix>IT_REGISTRY_PASSWORD=s3cret`, `<envPrefix>IT_REGISTRY_LOG=$WORK/registry-access.log` (to `$GITHUB_ENV` in CI, else print `export` lines). `podman logs vmtc-it-registry > $WORK/registry-access.log` is refreshed by the test fixture before attaching it.
  - `stop`: `podman rm -f vmtc-it-registry`; remove the `certs.d` entry.

**Verify:** after `start`: `podman login registry.test:5443 -u vmtc -p s3cret` succeeds; `podman logout registry.test:5443`; `stop` cleans up.

### Step 3.7 — OCI integration tests
- [ ] `OciAuthIT` with T27 (`pull`, scenarios a–d) and T28 (`tagMove`, scenario b) per PLAN.md §13.1. The one-time push uses `podman tag`, `podman login`, `podman push --digestfile`, `podman logout`; before each scenario remove the local copy (`podman rmi -f`) and both credential files. Attach `pull.txt`, `credentials-state.txt`, `disk-digests.txt`, `cache-listing.txt`, `registry-access.log`.
- [ ] Gradle `ociAuthTestA`..`ociAuthTestD` + aggregate `ociAuthTest` per PLAN.md §11.1; fail (not skip) if `<envPrefix>IT_REGISTRY` is missing.
- [ ] CI job `oci-auth` per PLAN.md §12.1 (incl. `base.qcow2` cache, `verifyRunnerImages`/`buildGuestDisk` through the task dependencies, `allureReport`, uploads).

**Verify:** `oci-auth` job green. (The explicit-`AuthConfig` pull itself was verified in the spike, CI run 16; T27 (c) adds the boot.)
**Requirements:** REQ-OCI-02..05, REQ-OCI-07, REQ-DISK-03, REQ-DISK-12

### Step 3.8 — Remaining M3 integration tests
- [ ] T18 (a)–(g), T19, T23 per PLAN.md §13.1 (fixtures for the 0-file, 2-file, baked and backing-file images are built with `podman build` / `qemu-img` in a runner container).
- [ ] Run on KVM and TCG.

**Verify:** all pass in both runs.
**Requirements:** REQ-DISK-01..06, REQ-DISK-11, REQ-DISK-13, REQ-LIFE-03, REQ-GUEST-01, REQ-GUEST-02, REQ-GUEST-04, REQ-GUEST-05, REQ-OCI-07

### 3.G — Phase 3 gate
- [ ] CI green incl. `oci-auth`; U11, U12, T18, T19, T23, T26, T27, T28 present, annotated, passing; deviations logged.

---

## Phase 4 (M4) — Screen recording and audio

### Step 4.1 — Smoke-test `vmtc-rec` by hand
- [ ] Start a media-runner container with the reference guest (Java or `podman run` as in step 1.9), then:

  ```bash
  podman exec <c> vmtc-rec start smoke 0 10 1000000   # → VMTC_REC_STARTED <pid>
  sleep 5
  podman exec <c> vmtc-rec stop smoke 10              # → VMTC_REC_OK duration=5.xxx width=1280 height=800 (or 640 480 before a modeset)
  podman exec <c> sh -c 'grep -H "^State:" /proc/[0-9]*/status'   # no "Z (zombie)"
  ```

**Verify:** outputs as commented; `duration=` has exactly three decimals.
**Requirements:** REQ-REC-01, REQ-IMG-05 (manual precursor)

### Step 4.2 — `ScreenRecording`
- [ ] `RecordingOptions` (fps 1..30 default 10; bitrate default 1 000 000; extend U9), `VmScreen.startRecording(...)` per PLAN.md §9.8 (`variant()` check, `VMTC_REC_STARTED` check), `ScreenRecording` (`close`, `file`, `duration`, `width`, `height`, idempotent close).
- [ ] Registry of open recordings on the container (needed by `stop()` and the JUnit extension).
- [ ] **U15 `RecorderCommandTest`:** every case of REQ-REC-06, with `parse-table.txt`.

**Spec:** PLAN.md §6.1, §7.2, §9.8
**Verify:** `./gradlew :lib:test --tests '*RecorderCommandTest' --tests '*ConfigValidationTest'` passes.
**Requirements:** REQ-REC-06 (U15); REQ-CFG-02 (fps range)

### Step 4.3 — Audio
- [ ] `AudioRecording` and `startAudioRecording` per PLAN.md §9.9 (`wavcapture` with defaults via HMP, `info capture` parsing, `stopcapture`, copy out, RIFF/WAVE + non-empty `data` validation; one at a time; idempotent close).
- [ ] **U7 `InfoCaptureParserTest`.**

**Spec:** PLAN.md §9.9
**Verify:** `./gradlew :lib:test --tests '*InfoCaptureParserTest'` passes.
**Requirements:** REQ-AUD-03 (unit part) (U7)

### Step 4.4 — `stop()` finalizes recordings
- [ ] `stop()` step 1 of PLAN.md §9.2: close every open screen and audio recording (best effort, failures through `Warnings`) before QMP `quit`.

**Verify:** via T31.
**Requirements:** REQ-LIFE-06

### Step 4.5 — M4 integration tests
- [ ] T8, T9, T10, T11, T24, T31, T33 per PLAN.md §13.1 (media tests use `RunnerVariant.MEDIA`; T8 drives `/dev/tty1`).
- [ ] Run on KVM and TCG; open the Allure report and play a `screen0.webm` inline; check `audio-analysis.png` shows the 1 kHz peak.

**Verify:** all pass in both runs; the report renders the videos and the audio analysis image.
**Requirements:** REQ-REC-01..04, REQ-REC-07, REQ-VNC-05, REQ-AUD-01..03, REQ-IMG-05, REQ-LIFE-06

### 4.G — Phase 4 gate
- [ ] CI green; U7, U15, T8–T11, T24, T31, T33 present, annotated, passing; deviations logged.

---

## Phase 5 (M5) — Hot-plug

### Step 5.1 — Device specs and handles
- [ ] `hotplug.Devices`, the sealed `DeviceSpec` records, `DiskBus`, `HotplugHandle` (`unplug()`, `unplug(Duration)`, idempotent `close()` semantics per PLAN.md §9.10), `CdromDrive`; `QemuVmContainer.hotplug(DeviceSpec)` and `cdrom()`; naming scheme; root-port allocator (lowest free `hp<k>`, `IllegalStateException` when none, freed on unplug); image-format detection via `qemu-img info` in the container; files copied to `/vm/run/<id>.img|.iso`; `read-only` propagated to `blockdev-add`.
- [ ] Every plug/unplug sequence exactly as the §9.10 table; unplug polling every 250 ms on `qom-list /machine/peripheral`; default timeout 30 s × TCG factor; `HotplugTimeoutException`.
- [ ] `Devices.cpu()` under TCG → `UnsupportedOperationException` at `hotplug()`.
- [ ] **U13 `HotplugUnplugTimeoutTest`** with a fake QMP.

**Spec:** PLAN.md §9.10, §15 L1
**Verify:** `./gradlew :lib:test --tests '*HotplugUnplugTimeoutTest'` passes.
**Requirements:** REQ-HOT-09 (U13)

### Step 5.2 — M5 integration tests
- [ ] T13–T17 per PLAN.md §13.1, with the before/plugged/after listings as evidence.
- [ ] Run on KVM and TCG.

**Verify:** all pass in both runs (T16 `cpuKvm` only on KVM, `cpuTcg` only on TCG).
**Requirements:** REQ-HOT-01..08, REQ-HOT-10

### 5.G — Phase 5 gate
- [ ] CI green; U13, T13–T17 present, annotated, passing; deviations logged.

---

## Phase 6 (M6) — Cleanup, JUnit extension, release readiness

### Step 6.1 — Orphan cleanup
- [ ] `VmtcCleanup.removeOrphans()` per PLAN.md §9.12 (returns 0 when `cleanup=false`; all container states; other session; `created` > 1 h; returns the count) and wire it into the step 1.20 seam.
- [ ] T21 per PLAN.md §13.1 with `containers-before.json` / `containers-after.json`.

**Verify:** T21 passes; U17 still passes.
**Requirements:** REQ-CLN-01, REQ-CLN-02

### Step 6.2 — JUnit 5 extension
- [ ] `:junit5`: `@VmTest` registering `VmArtifactsExtension` implemented as **`AfterTestExecutionCallback`** per PLAN.md §9.13 (fields of the test instance and static fields incl. superclasses; close open recordings; save serial log; one screenshot per screen; `build/vm-artifacts/<class>/<method>/`).
- [ ] T34 in `junit5/src/test/java` per PLAN.md §13.1: the nested `@VmTest` class is tagged `testkit-only` and excluded from the module's normal `test` task; `EngineTestKit` runs it explicitly. The `:junit5:test` task gets the same engine environment as `:it` (it starts a VM) and `dependsOn ':images:verifyRunnerImages', ':images:buildGuestDisk'` — move it to the `integration` CI job.
- [ ] Replace the `VmIT` failure-screenshot code with `@VmTest` where it fits (optional).

**Verify:** T34 passes; `junit5/build/vm-artifacts/<class>/<method>/` contains `serial.log`, `screen0.png` and the WebM after the run.
**Requirements:** REQ-JUN-01

### Step 6.3 — Full traceability
- [ ] Set `traceabilityRequireFullCoverage=true`.
- [ ] Fix every gap the report lists: add the missing test or annotation, or (only with agreement) retire the requirement in REQUIREMENTS.md with a reason.

**Verify:** `./gradlew checkRequirementTraceability` passes; `build/reports/requirements/traceability.md` lists at least one test for every *Test* requirement.
**Requirements:** REQ-BLD-06

### Step 6.4 — Manual procedures
- [ ] Run the two procedures of REQUIREMENTS.md §5 (rootful Podman; SELinux-enforcing host) or record in §D that they are pending and who will run them.

**Verify:** results (host OS, Podman version, date, outcome) recorded in §D.
**Requirements:** REQ-ENG-02, REQ-DISK-10 (boot part)

### Step 6.5 — CI hardening
- [ ] Confirm timeouts (PLAN.md §12.1), artifact uploads, the weekly schedule with `-PnoCache=true`, and that a failing test's VM artifacts and Allure report are downloadable from the run.
- [ ] Deliberately break one assertion on a branch, push, confirm the Allure report shows the failed test with its evidence and the `vm-artifacts` upload exists, then revert.

**Verify:** the failing run shows the red test in the report with `container.log`, `serial.log` and a screenshot attached.
**Requirements:** REQ-BLD-05, REQ-BLD-07

### Step 6.6 — User documentation
- [ ] `README.md` (or the target repo's docs location): quick start (`QemuVmContainer` + `referenceGuest()`), Podman setup (`DOCKER_HOST`, Ryuk disabled, `/dev/kvm` and the §9.6 remediation text), image sources incl. credentials and the §10.4 publishing format, VNC usage (unauthenticated, loopback by default, D8), recording/audio, hot-plug, how to open the Allure report artifact, limits (PLAN.md §15 L1–L8).
- [ ] Real coordinates and image names may appear in `README.md` only (it is on the hygiene allow-list).

**Verify:** the README's quick-start code block, pasted into a fresh `:it` test, compiles and passes T2's criteria.

### Step 6.7 — Publish (blocked: destination not yet chosen)
- [ ] **Do not publish.** The Maven publishing destination (Maven Central, GitHub Packages, an internal repository) has not been decided (PLAN.md §16 item 8). Until it is, `publishToMavenLocal` is the only publishing task that runs, and `pushImages` stays guarded by `-PpushImages=true`.
- [ ] When the destination is decided, add a §D entry and extend this step with: the `publishing { repositories { … } }` block, credentials via CI secrets, and (Maven Central only) POM metadata, GPG signing and sources/javadoc jars.
- [ ] `pushImages` targets the organisation's **private** registry only (PLAN.md §15 R2); never a public one.

**Verify:** the release head still passes the §6.G gate; nothing has been published anywhere.

### 6.G — Phase 6 gate (done)
- [ ] All CI jobs green on the release head: `unit`, `integration` ×3, `compat` ×2, `oci-auth`.
- [ ] Traceability with full coverage passes.
- [ ] Every [U] item in PLAN.md exercised by the steps above has an outcome in §D, and PLAN.md is updated where reality differed.
- [ ] §D has no open item without an owner.

---

## D. Deviation log

Record every place where reality differed from PLAN.md, every [U] outcome, and every open decision. Keep entries short.

| # | Date | Step | What happened / decision | PLAN.md or REQUIREMENTS.md updated? | Owner / status |
|---|------|------|--------------------------|-------------------------------------|----------------|
| 1 | 2026-10-18 | 0.4 | R2 decided: UBI stays (enterprise policy); images go only to a private registry inside the organisation, never public. Residual question for the policy owner: EULA §2 trademark-removal wording vs intra-organisation sharing | PLAN.md §15 R2, §0, §6.4 check 11 (license label inherited) | closed (residual: policy owner) |
| 2 | | 0.4 | R3: SELinux-enforcing host acceptance (REQ-DISK-10) | — | open |
| 3 | | 0.4 | UEFI boot of the reference guest (PLAN.md §8.4, T22); the firmware files are confirmed present | — | open |

---

## In Summary

- Work top to bottom; tick boxes only after *Verify* passes; commit per step.
- PLAN.md is the spec, REQUIREMENTS.md the acceptance and evidence; this file is the route. Conflicts go to the Deviation log, and PLAN.md wins until it is updated.
- Six milestone gates, each proven by CI on KVM and TCG with an Allure report per job; Phase 6 turns on full requirement coverage.
