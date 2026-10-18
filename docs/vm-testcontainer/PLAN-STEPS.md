# QEMU VM Testcontainer: Implementation Steps

> **Audience:** the agent (or engineer) implementing [PLAN.md](PLAN.md) in the target repository.
> **What this is:** an ordered checklist. Each step says what to do, which files it touches, where the normative spec is, how to prove the step is done, and which requirements ([REQUIREMENTS.md](REQUIREMENTS.md)) it serves. Tick the boxes as you go and commit this file with them ticked.
> **Names:** `${…}` are the PLAN.md §0 parameters. `<syspropPrefix>`, `<envPrefix>` etc. in commands mean the *values* from `gradle.properties`.

---

## BLUF

- **7 phases:** Phase 0 adopts the plan; Phases 1–6 are milestones M1–M6 of PLAN.md §14.
- **Every step ends with a *Verify* block.** Do not start the next step until it passes. If it cannot pass, stop and record why in the Deviation log (§D) instead of improvising around the plan.
- **Spec lives in PLAN.md; acceptance lives in REQUIREMENTS.md.** This file never redefines either; it tells you the order and how to check your work. If this file and PLAN.md disagree, PLAN.md wins; log the conflict.
- **Each phase ends with a gate:** its tests green on KVM and TCG in CI, traceability check green, deviations logged.

---

## How to work through this file

1. **One step at a time, in order.** Steps inside a phase depend on earlier ones unless marked *(parallel-safe)*.
2. **Tick boxes** (`- [x]`) when the action is done *and* its Verify passed.
3. **Commit per step:** message `step <n.m>: <title>`. Push at least at every phase gate so CI runs.
4. **Tag tests** with every requirement they verify: `@Tag("REQ-…")` (REQUIREMENTS.md §3). The *Requirements* line of a step lists them.
5. **Timing-sensitive tests** (boot times, recording duration) run in CI on GitHub-hosted runners; a slower local machine is not a reason to relax a limit. Log it instead.
6. **Unverified items** are marked **[U]** in PLAN.md. When a step exercises one, record the outcome in the Deviation log (worked as written, or what had to change).
7. **Never** do the things PLAN.md §1 rules a–i forbid, even "temporarily".

---

## Progress overview

| Phase | Milestone | Content | Gate |
|-------|-----------|---------|------|
| 0 | — | Adopt the plan: names, versions, dev host | §0.G |
| 1 | M1 | Build, images, guest disk, core library, exec, copy, KVM handling, CI | §1.G |
| 2 | M2 | Screens, VNC, screenshots, keyboard, UEFI | §2.G |
| 3 | M3 | Image sources (OCI, full HTTP, baked, reference guest), registry fixture | §3.G |
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
- [ ] Decide the real value for every row of PLAN.md §0 (coordinates, package, registry, image names, prefixes).
- [ ] Edit the **Example value** column of PLAN.md §0 only. Do not edit `${…}` references anywhere else.

**Verify:** for each example value you replaced (for example `io.github.nhwalker`), `grep -rn -F '<old value>' docs/vm-testcontainer/` returns nothing.

### Step 0.3 — Refresh versions
- [ ] Look up the current releases of Gradle, Testcontainers 2.x, JUnit 5, Jackson 2.x, commons-compress, xz, zstd-jni, SLF4J, AssertJ on their release pages / Maven Central (PLAN.md §16 item 2).
- [ ] If a newer **patch** or **minor** exists, update §0. A new **major** (e.g. Testcontainers 3, Jackson 3) is out of scope: keep the planned major and log it.

**Verify:** every version row in §0 has a source and date noted in the commit message.

### Step 0.4 — Record open decisions
- [ ] Open the Deviation log (§D) and add the open items: PLAN.md §15 R2 (licensing of public images), R3 (SELinux hosts), and §8.4 UEFI paths.
- [ ] R2 blocks only `pushImages` to a **public** registry. Everything else proceeds.

**Verify:** §D lists the three items with owner "unassigned" or a name.

### Step 0.5 — Prepare a development host
- [ ] x86-64 Linux with Podman ≥ 4.9 (rootless), JDK `${JAVA_RELEASE}`, `git`, `curl`.
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
- [ ] PLAN.md §1 (rules a–i), §4, §7.1, §8.1, §9.2, §9.6.

**Verify:** none (reading). Tick when done.

### 0.G — Phase 0 gate
- [ ] Steps 0.1–0.6 ticked; parameter edits committed and pushed.

---

## Phase 1 (M1) — Build, images, core library

### Step 1.1 — Gradle skeleton
- [ ] `settings.gradle`: `rootProject.name = '${ROOT_PROJECT}'`, `include 'lib', 'junit5', 'images', 'it'`.
- [ ] `gradle.properties` with every key of PLAN.md §5 (group, version, artifactLib, artifactJunit, basePackage, imageRegistry, imageRunnerName, imageGuestName, imageTagSuffix, labelPrefix, syspropPrefix, envPrefix, cacheDirName, javaRelease, ubiImage) plus `traceabilityRequireFullCoverage=false`.
- [ ] `gradle/libs.versions.toml` with the §0 library versions (testcontainers, junit-bom, jackson-databind, commons-compress, xz, zstd-jni, slf4j, assertj).
- [ ] Gradle wrapper `${GRADLE_VERSION}`.
- [ ] Empty `build.gradle` per subproject.

**Files:** `settings.gradle`, `gradle.properties`, `gradle/libs.versions.toml`, `gradle/wrapper/*`, `{lib,junit5,images,it}/build.gradle`
**Spec:** PLAN.md §5, §11.1
**Verify:** `./gradlew projects` lists `:images`, `:it`, `:junit5`, `:lib`.

### Step 1.2 — Build conventions and publishing
- [ ] Java toolchain `${JAVA_RELEASE}`; `options.compilerArgs += ['-Xlint:all,-processing', '-Werror']`.
- [ ] `:lib`: `java-library` + `maven-publish`, `artifactId = artifactLib`; dependencies exactly as PLAN.md §9.1 (api: Testcontainers; implementation: jackson-databind, commons-compress, xz, zstd-jni; tests: JUnit BOM, AssertJ, slf4j-simple).
- [ ] `:junit5`: `java-library` + `maven-publish`, `artifactId = artifactJunit`, depends on `:lib` and `junit-jupiter-api`.
- [ ] `:lib` gets `src/main/java/module-info.java` (module name = `${BASE_PACKAGE}`), exports filled in step 1.10.
- [ ] All `Test` tasks: `useJUnitPlatform()`.

**Spec:** PLAN.md §9.1, §11.1
**Verify:**
- `./gradlew build` succeeds.
- `./gradlew publishToMavenLocal` creates `~/.m2/repository/<group as path>/<artifactLib>/<version>/<artifactLib>-<version>.pom` and the same for `<artifactJunit>`.
- `./gradlew :lib:dependencies --configuration runtimeClasspath` shows no `land.oras` (REQ-OCI-06) and only the §9.1 direct dependencies.

**Requirements:** REQ-BLD-02, REQ-BLD-03, REQ-PLAT-03, REQ-OCI-06

### Step 1.3 — Generated constants
- [ ] Task `:lib:generateConstants` writes `VmtcConstants.java` exactly per PLAN.md §11.3 into `build/generated/sources/vmtc/java/<basePackage path>/`; register the directory as a `main` source dir; `compileJava` depends on it; declare `gradle.properties` values as task inputs.
- [ ] `RunnerVariant` enum (`SLIM`, `MEDIA`) must exist for it to compile (create it now in `${BASE_PACKAGE}`).
- [ ] **U5 `VmtcConstantsTest`:** the `:lib:test` task passes each relevant `gradle.properties` value as a system property (`systemProperty 'expected.version', version` …); the test asserts every constant and both `defaultRunnerImage(...)` strings and `referenceGuestImage()`.

**Spec:** PLAN.md §11.3
**Verify:** `./gradlew :lib:test --tests '*VmtcConstantsTest'` passes; changing `version` in `gradle.properties` and re-running regenerates the file (check its content), then revert.
**Requirements:** REQ-BLD-01 (U5)

### Step 1.4 — Parameter-hygiene check
- [ ] Root task `checkParameterHygiene`: for each of `group`, `basePackage`, `imageRegistry`, `imageRunnerName`, `imageGuestName`, `labelPrefix` (values from `gradle.properties`), run `git grep -n -w -F -- <value>` and fail if any hit is outside: `gradle.properties`, `build/`, `**/build/generated/**`, `docs/vm-testcontainer/PLAN.md` (§0 holds the values by design), and `README.md` (user documentation must show real coordinates; step 6.6). The Java package directories derived from `basePackage` are paths, not file contents, so they do not match.
- [ ] Print each offending `file:line`.

**Spec:** PLAN.md §0 "Parameter-hygiene check", §12.3
**Verify:** passes on the clean tree; add the group ID string to a scratch file, run → fails naming that file; remove the scratch file.
**Requirements:** REQ-BLD-01

### Step 1.5 — Requirement-traceability check
- [ ] Root task `checkRequirementTraceability` implementing REQUIREMENTS.md §3 (parse the §4 tables; scan `@Tag("REQ-…")` in every test source set; unknown/Retired → fail; missing tests → fail only if `traceabilityRequireFullCoverage=true`; write `build/reports/requirements/traceability.md`).
- [ ] Parse only first-column IDs between the lines starting with `## 4.` and `## 5.`; a row whose Requirement cell contains `**Retired**` is retired; a requirement "needs a test" when its *Method / verified by* cell contains `Test:`.

**Verify:**
- With no tests yet: the task passes and the report lists every Test-method requirement as missing.
- Add a test tagged `@Tag("REQ-NOPE-01")` → task fails naming it; remove it.
- Temporarily set `traceabilityRequireFullCoverage=true` → fails listing missing IDs; revert.

**Requirements:** REQ-BLD-06

### Step 1.6 — Runner image sources
- [ ] `images/runner/repos/epel.repo` = PLAN.md Appendix A.1, `images/runner/repos/rocky.repo` = Appendix A.2 (byte-exact).
- [ ] `images/runner/Containerfile` = PLAN.md §6.3 (byte-exact).
- [ ] `images/runner/scripts/vmtc-entrypoint`: implement PLAN.md §7.1 steps 0–10 and the QEMU command line of §8.1 (array-built, no `eval`). Include UEFI per §8.4.
- [ ] `images/runner/scripts/vmtc-rec`: implement PLAN.md §7.2 (used from M4, but the Containerfile needs the file now).
- [ ] If `shellcheck` is available, run it on both scripts and fix findings.

**Spec:** PLAN.md §6.2, §6.3, §7, §8.1, §8.4, Appendix A.1–A.2
**Verify:** `bash -n images/runner/scripts/vmtc-entrypoint images/runner/scripts/vmtc-rec` succeeds.
**Requirements:** REQ-IMG-01, REQ-IMG-02, REQ-IMG-05

### Step 1.7 — Image build and verification tasks
- [ ] In `images/build.gradle`: `buildRunnerSlim`, `buildRunnerMedia`, `verifyRunnerImages` per PLAN.md §11.2 (engine from `-PcontainerEngine`, default `podman`; declare inputs = `images/runner/**`, outputs = an image-ID marker file under `build/`).
- [ ] `verifyRunnerImages` runs checks 1–8 of PLAN.md §6.4 in each image (`<engine> run --rm --entrypoint /bin/bash <image> -c '<check>'`), check 6 on the host.

**Spec:** PLAN.md §6.1, §6.4, §11.2
**Verify:** `./gradlew :images:verifyRunnerImages` succeeds; `podman images` shows both tags; build time per image is in the order of a minute (PLAN.md §12.2; log large differences).
**Requirements:** REQ-IMG-01, REQ-IMG-02, REQ-IMG-03, REQ-IMG-04

### Step 1.8 — Smoke-test the entrypoint by hand (no Java yet)
- [ ] Download the stock guest and boot it in the slim runner:

  ```bash
  mkdir -p /tmp/vmtc-smoke && cd /tmp/vmtc-smoke
  curl -fL -o base.qcow2 "${GUEST_BASE_URL}" && echo "${GUEST_BASE_SHA256}  base.qcow2" | sha256sum -c -
  SLIM=<imageRegistry>/<imageRunnerName>:<version>-<imageTagSuffix>
  podman run -d --name vmtc-smoke [--device /dev/kvm] -v "$PWD/base.qcow2:/vm/base/disk.img:ro" "$SLIM"
  podman logs -f vmtc-smoke   # expect VMTC_ACCEL kvm|tcg, VMTC_QEMU_STARTING <epoch>, VMTC_READY
  ```

- [ ] Ping the guest agent through the in-image `socat` (retry until the guest has booted: ~15 s KVM, ~70 s TCG):

  ```bash
  podman exec vmtc-smoke sh -c "printf '{\"execute\":\"guest-ping\"}\n' | socat -t5 - TCP:127.0.0.1:4445"
  # expect: {"return": {}}
  ```

- [ ] `time podman stop -t 15 vmtc-smoke` → returns well under 15 s (the entrypoint trap quits QEMU via the internal QMP socket); then `podman rm vmtc-smoke`.
- [ ] Error paths: run without the `-v` → logs `VMTC_ERROR no base disk`, exit code 2 (`podman wait`). Run with `-e VM_ACCEL=kvm` and no device → `VMTC_ERROR kvm requested but /dev/kvm unusable`, exit code 3.

**Verify:** all four bullets behave as stated. Record the measured times in the step's commit message.
**Requirements:** REQ-LIFE-04 (manual precursor), PLAN.md §7.1

### Step 1.9 — Reference guest build
- [ ] `images/guest/guest.properties` with `baseUrl=${GUEST_BASE_URL}` and `baseSha256=${GUEST_BASE_SHA256}`.
- [ ] `images/guest/customize.sh` implementing PLAN.md §10.3 steps 1–4 with the signature given there (`set -euo pipefail`; add `:z`/`,z` to the volume options only when `/sys/fs/selinux/enforce` contains `1`).
- [ ] Gradle `downloadGuestBase` (download to `build/guest/base.qcow2`, verify SHA-256, skip when the file already verifies) and `buildGuestDisk` per PLAN.md §11.2.

**Spec:** PLAN.md §10.3, §11.2
**Verify:**
- `./gradlew :images:buildGuestDisk` succeeds.
- `grep -a -E 'VMTC_CUSTOMIZE_DONE|DNF_RC=0' images/build/guest/work/out/serial.log` prints both lines.
- `images/build/guest/work/run/rocky9-vmtc.qcow2` exists (≈ 0.8 GB, PLAN.md §10.3).
- `podman image exists <imageRegistry>/<imageGuestName>:<version>` succeeds.
- KVM build takes about 1.5 min (PLAN.md §12.2).

**Requirements:** REQ-GUEST-04 (artefact), REQ-GUEST-05

### Step 1.10 — Core types and module exports
- [ ] Create every package and type of PLAN.md §9.1 that M1 needs: `QemuVmContainer` (stub), `VmImage` (sealed; `fromPath`, `fromUrl` for now), `KvmMode`, `Accelerator`, `Firmware`, `ExecRequest`, `GuestExecResult`, `VmtcException` and **all** subclasses listed in §9.1 (incl. `ImageAuthException`), `qmp.QmpException`.
- [ ] All exceptions extend `VmtcException extends RuntimeException`.
- [ ] `module-info.java`: export every package except `${BASE_PACKAGE}.internal`. Create empty `package-info.java` for packages without types yet so the exports compile.
- [ ] **U14 `ApiShapeTest`:** reflection over the public exception types (unchecked, extend `VmtcException`); read `ModuleDescriptor` of the `:lib` module and assert the export set.

**Spec:** PLAN.md §9.1, §9.11
**Verify:** `./gradlew :lib:test --tests '*ApiShapeTest'` passes.
**Requirements:** REQ-ERR-01, REQ-BLD-04 (U14)

### Step 1.11 — Runtime overrides
- [ ] `internal.Overrides`: resolves each PLAN.md §9.1 key from system property `<syspropPrefix>.<key>`, then environment `<envPrefix><KEY_UPPER_SNAKE>`, then the builder value, then the default. Take the environment as an injected `Map<String,String>` (default `System.getenv()`) so tests can control it.
- [ ] **U6 `OverridesTest`:** table-driven over every key and each precedence level. (The `referenceGuest()` resolution case is added in step 3.5.)

**Spec:** PLAN.md §9.1
**Verify:** `./gradlew :lib:test --tests '*OverridesTest'` passes.
**Requirements:** REQ-CFG-01 (U6)

### Step 1.12 — JSON and QMP client
- [ ] `internal.Json` (Jackson `ObjectMapper` wrapper), `internal.Backoff`.
- [ ] `qmp.QmpClient`: TCP, newline-delimited JSON; read greeting, send `qmp_capabilities`; one request in flight; skip `event` messages while waiting for `return`/`error` and expose them as `QmpEvent`s; `error` → `QmpException(class, desc)`; configurable response timeout; `human-monitor-command` helper.
- [ ] **U1 `QmpClientTest`** against an in-JVM fake QMP server (a `ServerSocket` thread scripted per test).

**Spec:** PLAN.md §4 principle 2, §9.11, §3.2 (single TCP client)
**Verify:** `./gradlew :lib:test --tests '*QmpClientTest'` passes.
**Requirements:** REQ-ERR-02 (U1)

### Step 1.13 — Guest agent client
- [ ] `qga.GuestAgentClient`: per PLAN.md §9.4 protocol and §9.2 step 10 handshake (`guest-sync-delimited`, skip `0xFF`, match id; `guest-ping`); `ReentrantLock`; reconnect once on I/O error; map `CommandNotFound` + "has been disabled" to `GuestCommandDisabledException`; exec (`guest-exec` + `guest-exec-status` polling 50 ms ×1.5 → 500 ms cap) and file primitives (`guest-file-open/read/write/close`, 1 MiB chunks).
- [ ] **U2 `GuestAgentClientTest`** with a fake QGA server: resync, id mismatch handling, base64 round-trip, polling back-off, truncation flags, reconnect, disabled-command mapping.

**Spec:** PLAN.md §9.2 steps 10–11, §9.4, §9.5
**Verify:** `./gradlew :lib:test --tests '*GuestAgentClientTest'` passes.
**Requirements:** REQ-EXEC-05, REQ-GUEST-01 (U2)

### Step 1.14 — KVM detection and decision
- [ ] `internal.KvmDetector` (`present`, `usable`, `worldAccessible`) per PLAN.md §9.6.
- [ ] A pure function `KvmDecision decide(KvmMode, detector state)` returning: device yes/no, annotation yes/no, `VM_ACCEL` value, or the exception/WARN message. **No code path may produce `GroupAdd keep-groups`.**
- [ ] One-WARN-per-JVM latch for PREFER fallback.
- [ ] **U8 `KvmDecisionTest`:** every row of PLAN.md §8.2 × the three §9.6 detector situations; exact message strings; the post-start fallback message (container reported `VMTC_ACCEL tcg` or exit 3).

**Spec:** PLAN.md §8.2, §9.6
**Verify:** `./gradlew :lib:test --tests '*KvmDecisionTest'` passes.
**Requirements:** REQ-KVM-02, REQ-KVM-03, REQ-KVM-04, REQ-KVM-05, REQ-KVM-06 (U8)

### Step 1.15 — Builder validation and timeouts
- [ ] Validate in each builder method (throw `IllegalArgumentException` immediately): CPUs (boot ≥ 1, max ≥ boot), `withQemuArgs` (no whitespace in any arg), `withTcgTimeoutFactor` (> 0), `withHotplugPorts` (0..8). Screens, VNC password and recording fps are added in steps 2.1 and 4.2.
- [ ] Effective-timeout helper: multiply by `tcgTimeoutFactor` under TCG, except the 60 s container-start wait.
- [ ] **U9 `ConfigValidationTest`** for the above.

**Spec:** PLAN.md §8.2, §9.2
**Verify:** `./gradlew :lib:test --tests '*ConfigValidationTest'` passes.
**Requirements:** REQ-CFG-02, REQ-KVM-07 (U9)

### Step 1.16 — Image cache, `fromPath` and `baked()`
- [ ] `image.VmImageCache` per PLAN.md §9.3.1: root from the `cacheDir` override; `<key>.lock` with `FileChannel.lock()`; tmp file + `ATOMIC_MOVE`; touch on use; `prune(Duration)`.
- [ ] `fromPath`: key = SHA-256 of content, memoized in `index.json` by (absolutePath, size, mtimeMillis); `Files.copy`, never a link.
- [ ] `VmImage.baked()` (PLAN.md §9.3.4): no cache, no bind. Needed now by T29.
- [ ] **U3 `VmImageCacheTest`:** two threads same key; no leftover tmp files; inode differs from the source (`Files.getAttribute(p, "unix:ino")`); memo hit avoids rehash (count reads via a spy or timing-free counter); prune.

**Spec:** PLAN.md §9.3.1, §9.3.2, §9.3.4
**Verify:** `./gradlew :lib:test --tests '*VmImageCacheTest'` passes.
**Requirements:** REQ-DISK-06, REQ-DISK-07, REQ-DISK-08, REQ-DISK-09 (U3)

### Step 1.17 — Basic HTTP download
- [ ] `image.ImageDownloader` with PLAN.md §9.3.5 steps 1–6 **without** credentials and decompression: `HttpClient` with `Redirect.NEVER`, manual redirects (≤ 5), status handling, `DigestInputStream`, SHA-256 check, atomic move. `UrlOptions` with `sha256` and `timeout` only for now.
- [ ] `VmImage.fromUrl(URI)` and `fromUrl(URI, UrlOptions)`; cache key per §9.3.1.
- [ ] Start **`HttpDownloadTest`** (T26) in `:lib` with: success with checksum, (c) wrong checksum, (g) too many redirects. Tag only the requirements these cases cover (REQ-HTTP-04, REQ-HTTP-07); the rest is added in step 3.3.

**Spec:** PLAN.md §9.3.5
**Verify:** `./gradlew :lib:test --tests '*HttpDownloadTest'` passes.
**Requirements:** REQ-HTTP-04, REQ-HTTP-07 (T26 partial)

### Step 1.18 — Cloud-init seed
- [ ] `cloudinit.CloudInitSeed` per PLAN.md §10.2: `builder()` (empty), `Builder.stockRocky()`, `packages`, `runcmd`, `writeFile`, `rootPassword`, `trustCaFile`, `build()`; `forStockRocky()`; `userData()` / `metaData()` byte output; `VMTC_SEED_DONE` always last.
- [ ] **U10 `CloudInitSeedTest`:** `forStockRocky().userData()` equals the §10.2 YAML byte for byte (store the expected text as a test resource copied from PLAN.md); builder ordering.

**Spec:** PLAN.md §10.2
**Verify:** `./gradlew :lib:test --tests '*CloudInitSeedTest'` passes.
**Requirements:** REQ-GUEST-03 (U10)

### Step 1.19 — `QemuVmContainer` start and stop
- [ ] Constructors and every builder method of PLAN.md §9.2 that M1 needs (`withVmImage`, `withKvm`, `withCpus`, `withMaxCpus`, `withMemoryMb`, `withMemoryHotplug`, `withHotplugPorts`, `withFirmware`, `withCloudInit`, timeouts, `withSerialLogConsumer`, `withQemuArgs`).
- [ ] `configure()` / `containerIsStarting` / `containerIsStarted`: implement §9.2 start steps 1–13 **in that order**, including the seeded-start rule in step 10. Skip step 9 (VNC password) until step 2.2.
- [ ] `internal.VmWaitStrategy`, `internal.PortBindings` (loopback for 4444/4445), `internal.SerialLogTailer`, the four labels, the env of §7.1.
- [ ] `stop()` per §9.2 (recordings part becomes real in step 4.4).
- [ ] `accelerator()`, `qmp()`, `guestAgent()`, `saveSerialLog`.

**Spec:** PLAN.md §9.2, §8.2, §9.6, §7.1 env table
**Verify:** compiles; behaviour is verified by the `:it` tests of step 1.23.
**Requirements:** REQ-LIFE-01..08, REQ-KVM-01..06 (verified in 1.23)

### Step 1.20 — Exec
- [ ] `exec(String…)`, `exec(Duration, String…)`, `exec(ExecRequest)`, `execShell(String)` per PLAN.md §9.4, including timeout kill and `GuestExecTimeoutException`.

**Spec:** PLAN.md §9.4
**Verify:** via T3/T32 in step 1.23.
**Requirements:** REQ-EXEC-01..04

### Step 1.21 — File copy
- [ ] `copyToVm(Path|byte[], String)`, `copyFromVm`, `readFromVm` per PLAN.md §9.5 (mkdir -p parent first; close in `finally`).

**Spec:** PLAN.md §9.5
**Verify:** via T4 in step 1.23.
**Requirements:** REQ-FILE-01..03

### Step 1.22 — Integration-test module
- [ ] `it/build.gradle`: depends on `:lib`; JUnit, AssertJ, slf4j-simple; task `integrationTest` (Test type) with `dependsOn ':images:verifyRunnerImages', ':images:buildGuestDisk'`; JUnit tag expression from the job (`-PitTags=kvm|tcg`: run `any` + that tag; untagged counts as `any`); forward `-D<syspropPrefix>.kvm`; pass `<syspropPrefix>.it.guestQcow2` = absolute path of `images/build/guest/work/run/rocky9-vmtc.qcow2`; exclude `OciAuthIT` and `KvmCompatIT`.
- [ ] `TestImages.guest()` → `VmImage.fromPath(Path.of(System.getProperty("<syspropPrefix>.it.guestQcow2")))` for now (PLAN.md §13 rules).
- [ ] Shared helpers: a base class that stops containers in `@AfterEach` and saves the serial log of failed tests to `it/build/vm-artifacts/` (until the JUnit extension exists).

**Spec:** PLAN.md §11.1, §13 rules
**Verify:** `./gradlew :it:integrationTest -PitTags=tcg` runs (zero tests is fine at this point) and the dependency tasks execute.

### Step 1.23 — M1 integration tests
- [ ] Write T1, T2, T3, T4, T20, T25, T29, T30, T32 exactly as PLAN.md §13.1 describes, each tagged with its *Verifies* IDs and `kvm`/`tcg` where stated.
- [ ] Run on KVM: `./gradlew :it:integrationTest -PitTags=kvm -D<syspropPrefix>.kvm=REQUIRE`.
- [ ] Run on TCG: `./gradlew :it:integrationTest -PitTags=tcg -D<syspropPrefix>.kvm=DISABLE` (expect ~1 min per boot; T25 takes 5–8 min under TCG because of the seed).

**Spec:** PLAN.md §13.1
**Verify:** all listed tests pass in both runs. Compare T2 times with REQUIREMENTS.md REQ-LIFE-02.
**Requirements:** REQ-KVM-01..04, REQ-LIFE-01..05, REQ-EXEC-01..04, REQ-FILE-01..03, REQ-GUEST-03, REQ-DISK-01, REQ-DISK-02, REQ-ENG-01, REQ-PLAT-02

### Step 1.24 — CI workflow
- [ ] `.github/workflows/ci.yml` per PLAN.md §12.1 with jobs `unit` and `integration` (matrix `{ubuntu-24.04, kvm}`, `{ubuntu-24.04, tcg}`, `{ubuntu-26.04, kvm}`), triggers `push`, `pull_request`, weekly `schedule`.
- [ ] `integration` steps 1–6 of §12.1; pass `-PitTags=<accel>` and `-D<syspropPrefix>.kvm=<REQUIRE|DISABLE>`. **Do not** apply the udev rule in the TCG job (T20 depends on that).
- [ ] Cache `images/build/guest/base.qcow2` keyed by `baseSha256`.
- [ ] Upload `it/build/vm-artifacts/**` and `it/build/reports/**` with `if: always()`.

**Spec:** PLAN.md §12.1, §12.2
**Verify:** push; all jobs green. KVM integration finishes well inside 30 min; TCG inside 90 min.
**Requirements:** REQ-PLAT-01, REQ-ENG-01, REQ-BLD-05, REQ-BLD-06

### Step 1.25 — KVM compatibility job
- [ ] `it/fixtures/kvm-scenario.sh <A|B|C>`: port of the host-setup part of the spike's `run-scenario.sh` (PLAN.md §17):
  - **A:** udev rule `KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"`, reload + trigger; start `podman system service` normally.
  - **B:** remove that rule; `chown root:kvm /dev/kvm; chmod 0660 /dev/kvm; usermod -aG kvm $USER`; start the service **in a fresh login session** (`sudo -iu "$USER" env XDG_RUNTIME_DIR="$XDG_RUNTIME_DIR" podman system service --time=0 unix://<sock>`), so `kvm` is a *supplementary* group.
  - **C:** as B, plus `~/.config/containers/containers.conf` containing `[containers]` / `annotations = ["run.oci.keep_original_groups=1"]` before the service starts.
  - First stop a service left by a previous scenario (`pkill -f 'podman system service --time=0 unix://<sock>'`) and remove `~/.config/containers/containers.conf`.
  - Write `DOCKER_HOST=unix://<sock>`, `TESTCONTAINERS_RYUK_DISABLED=true`, `<envPrefix>IT_KVM_SCENARIO=<S>` and `<envPrefix>IT_PODMAN_VERSION=$(podman --version | awk '{print $3}')` to `$GITHUB_ENV` (or print them for local use).
- [ ] `KvmCompatIT` (task `:it:compatTest`, `PREFER` mode): `fromPath(<16 MiB raw zeros>)`, `withGuestAgentTimeout(5 s)`, capture container logs with `withLogConsumer`; expect `GuestAgentTimeoutException` and assert the `VMTC_ACCEL` line: A → `kvm`; B → `kvm` on Podman ≥ 5.7, `tcg` (plus the §9.6 group-restricted WARN) below 5.7; C → `kvm`. A second case with `REQUIRE` expects `KvmUnavailableException` with the group-restricted message exactly where the first case expects `tcg`.
- [ ] **Run the test JVM like a logged-in user.** For B and C, run Gradle in a fresh login session too, so the JVM has `kvm` as a supplementary group and `KvmDetector.usable()` is true: `sudo -iu "$USER" env XDG_RUNTIME_DIR="$XDG_RUNTIME_DIR" JAVA_HOME="$JAVA_HOME" DOCKER_HOST="$DOCKER_HOST" TESTCONTAINERS_RYUK_DISABLED=true <envPrefix>IT_KVM_SCENARIO=<S> <envPrefix>IT_PODMAN_VERSION=<v> bash -c 'cd "$GITHUB_WORKSPACE" && ./gradlew --no-daemon :it:compatTest'`. Without this the CI step's shell lacks the group, the library correctly decides "not usable", and the annotation path is never exercised. **[U]**: the spike ran only the Podman service and CLI this way, not the test JVM; log the outcome.
- [ ] CI job `compat` (matrix ubuntu-24.04, ubuntu-26.04): for S in A, B, C: run the script, then `:it:compatTest` as above (plain invocation is fine for A).

**Spec:** PLAN.md §9.6, §12.1
**Verify:** `compat` green on both runners; the observed matrix equals PLAN.md §9.6.
**Requirements:** REQ-KVM-05, REQ-KVM-06

### 1.G — Phase 1 gate
- [ ] `unit`, `integration` (3 entries) and `compat` jobs green on the pushed head.
- [ ] Tests U1–U3, U5, U6, U8–U10, U14, T1–T4, T20, T25, T29, T30, T32 present, tagged, passing.
- [ ] `checkRequirementTraceability` report shows those requirements covered.
- [ ] Deviation log updated.

---

## Phase 2 (M2) — Screens, VNC, keyboard, UEFI

### Step 2.1 — Screens and VNC configuration
- [ ] `withScreens(n)` (1..4), `withVncBindAddress`, `withVncPassword` (1..8 printable ASCII, no whitespace) with validation; extend U9.
- [ ] Env `VM_SCREENS`, `VNC_PASSWORD_ENABLED`; port exposure and bindings per PLAN.md §9.2 step 4 (VNC ports use the configured address).

**Spec:** PLAN.md §8.3, §9.2 step 4
**Verify:** `./gradlew :lib:test --tests '*ConfigValidationTest'` passes with the new cases.
**Requirements:** REQ-DISP-01, REQ-VNC-04 (U9)

### Step 2.2 — VNC password at start
- [ ] §9.2 step 9: after QMP connects, `set_password {protocol:"vnc", password, display:"vnc<i>"}` for **every** screen (rule e).

**Spec:** PLAN.md §8.3, §1 rule e
**Verify:** via T6/T7.
**Requirements:** REQ-VNC-03

### Step 2.3 — `VmScreen`, `VncEndpoint`, screenshots, `forceModeset`
- [ ] Implement PLAN.md §9.7 `VmScreen` (`index`, `vnc`, `screenshot()`, `screenshot(Path)`, `forceModeset()`); `screens()`, `screen(i)`.
- [ ] Classpath resource `<basePackage path>/display/vmtc-fb-activate.py` = Appendix A.4 (byte-exact).
- [ ] `startRecording` methods: throw `UnsupportedOperationException` for now (real in step 4.2).

**Spec:** PLAN.md §9.7, Appendix A.4
**Verify:** via T5, T6.
**Requirements:** REQ-DISP-02, REQ-DISP-03, REQ-VNC-01, REQ-VNC-02

### Step 2.4 — Keyboard
- [ ] `KeyboardInput.type` and `press` per PLAN.md §9.7 and Appendix A.3 (30 ms between characters).
- [ ] **U4 `KeyboardInputTest`:** every row of Appendix A.3; unsupported character; `press` chord as one `send-key` (assert against a fake QMP).

**Spec:** PLAN.md §9.7, Appendix A.3
**Verify:** `./gradlew :lib:test --tests '*KeyboardInputTest'` passes.
**Requirements:** REQ-KEY-01, REQ-KEY-02 (U4)

### Step 2.5 — UEFI
- [ ] `withFirmware(Firmware.UEFI)` → `VM_FIRMWARE=uefi`; entrypoint already handles §8.4.
- [ ] **[U] check first:** `podman run --rm --entrypoint rpm <slim> -ql edk2-ovmf | grep -E 'OVMF_(CODE|VARS)\.fd$'`. If the paths differ from §8.4, update PLAN.md §8.4 and the entrypoint together, and log it.

**Spec:** PLAN.md §8.4
**Verify:** via T22.
**Requirements:** REQ-LIFE-09

### Step 2.6 — M2 integration tests
- [ ] Write T5, T6, T7, T12, T22 per PLAN.md §13.1 (T7 uses the media runner).
- [ ] Run on KVM and TCG.

**Verify:** all pass in both runs; if T22 fails because the reference guest cannot boot under UEFI, log it and propose (in §D) either a UEFI-capable test disk or retiring REQ-LIFE-09 — do not silently drop the test.
**Requirements:** REQ-DISP-02, REQ-DISP-03, REQ-VNC-01..03, REQ-LIFE-07, REQ-LIFE-08, REQ-KEY-01, REQ-LIFE-09

### 2.G — Phase 2 gate
- [ ] CI green; U4, T5–T7, T12, T22 present, tagged, passing; deviations logged.

---

## Phase 3 (M3) — Image sources

### Step 3.1 — containerDisk extraction
- [ ] `image.ContainerDiskExtractor` per PLAN.md §9.3.3: always-pull policy, local fallback with WARN, pull-error mapping (`ImageAuthException` vs `VmtcException`), explicit-credential pull via `pullImageCmd(...).withAuthConfig(...)`, `inspectImageCmd` → image ID, create-not-start, `copyArchiveFromContainerCmd("/disk/")`, exactly-one-file rule, `removeContainerCmd(...).withForce(true)` in `finally`.
- [ ] Cache key `sha256(imageId + "/" + fileName)`.

**Spec:** PLAN.md §9.3.1, §9.3.3
**Verify:** via T18 (c, e) and T27/T28.
**Requirements:** REQ-DISK-03, REQ-DISK-04, REQ-OCI-02..05

### Step 3.2 — `fromOci` / `fromContainerDisk`
- [ ] `VmImage.fromOci(String)`, `fromOci(String, OciOptions)`, `fromContainerDisk(DockerImageName)`; `OciOptions`, `RegistryCredentials.basic(user, Secret)`; parsing and scheme rejection per PLAN.md §9.3.6.
- [ ] **U11 `OciReferenceTest`.**

**Spec:** PLAN.md §9.3.6
**Verify:** `./gradlew :lib:test --tests '*OciReferenceTest'` passes.
**Requirements:** REQ-OCI-01 (U11)

### Step 3.3 — Full HTTP download
- [ ] Complete PLAN.md §9.3.5: `Secret` (incl. `fromEnv`), `HttpCredentials.basic/bearer/header`, same-origin-only credential headers, `Compression` (AUTO by suffix, explicit modes) via commons-compress (+ xz, zstd-jni), retries (3 attempts, 2 s / 8 s, injectable backoff), revalidation with `<key>.meta.json`, one WARN per URL without checksum, URL redaction in messages.
- [ ] Complete **T26 `HttpDownloadTest`** cases (a)–(h) per PLAN.md §13.2; generate `.xz`/`.zst` fixtures in the test with commons-compress output streams.
- [ ] **U12 `SecretAndRedactionTest`.**

**Spec:** PLAN.md §9.3.5
**Verify:** `./gradlew :lib:test --tests '*HttpDownloadTest' --tests '*SecretAndRedactionTest'` passes.
**Requirements:** REQ-HTTP-01..09 (T26, U12)

### Step 3.4 — `baked()` success path
- [ ] `baked()` exists since step 1.16 (T29 covers its failure path). Add the T18 (d) fixture: `podman build` of `FROM <slim>` + `COPY disk.qcow2 /vm/base/disk.img`, tagged `localhost/vmtc-it-baked:<version>`.

**Spec:** PLAN.md §9.3.4
**Verify:** via T18 (d) in step 3.8.
**Requirements:** REQ-DISK-05

### Step 3.5 — `referenceGuest()`
- [ ] `VmImage.referenceGuest()` → `fromContainerDisk(override 'guestImage' or VmtcConstants.referenceGuestImage())`.
- [ ] Extend **U6** with the resolution case.
- [ ] Switch `TestImages.guest()` to `VmImage.referenceGuest()` (the local image from `buildGuestDisk` is used through the local fallback of step 3.1 until images are pushed).

**Spec:** PLAN.md §9.3, §13 rules
**Verify:** U6 passes; re-run the M1 and M2 integration tests on KVM and TCG → still green.
**Requirements:** REQ-DISK-11 (U6)

### Step 3.6 — Test-registry fixture
- [ ] `it/fixtures/test-registry.sh start|stop` (port of the registry part of the spike's `run-oras-ci.sh` and `run-pull-auth.sh`, PLAN.md §17):
  - add `127.0.0.1 registry.test` to `/etc/hosts` if missing (sudo);
  - self-signed cert: `openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj /CN=registry.test -addext "subjectAltName=DNS:registry.test,DNS:localhost,IP:127.0.0.1"`;
  - htpasswd (bcrypt) via `podman run --rm --entrypoint htpasswd docker.io/library/httpd:2 -Bbn <user> <password>`;
  - `podman run -d --name vmtc-it-registry -p 5443:5000` `docker.io/library/registry:2` with `REGISTRY_AUTH=htpasswd`, `REGISTRY_AUTH_HTPASSWD_REALM`, `REGISTRY_AUTH_HTPASSWD_PATH`, `REGISTRY_HTTP_TLS_CERTIFICATE`, `REGISTRY_HTTP_TLS_KEY`;
  - wait until `curl --cacert <cert> https://registry.test:5443/v2/` returns `401`;
  - install the CA for rootless Podman: `~/.config/containers/certs.d/registry.test:5443/ca.crt`;
  - export `<envPrefix>IT_REGISTRY=registry.test:5443`, `<envPrefix>IT_REGISTRY_USER`, `<envPrefix>IT_REGISTRY_PASSWORD` (to `$GITHUB_ENV` in CI);
  - `stop` removes the container and the `certs.d` entry.

**Verify:** after `start`: `podman login registry.test:5443 -u <user> -p <password>` succeeds; `podman logout registry.test:5443`; `stop` cleans up.

### Step 3.7 — OCI integration tests
- [ ] `OciAuthIT` with T27 (`pull`, scenarios a–d) and T28 (`tagMove`, scenario b) per PLAN.md §13.1. Fixture pushes use the CLI (`podman tag`, `podman login`, `podman push`, `podman logout`), then remove the local copy (`podman rmi -f`) and both credential files before each scenario.
- [ ] Gradle `ociAuthTestA`..`ociAuthTestD` + aggregate `ociAuthTest` per PLAN.md §11.1; fail (not skip) if `<envPrefix>IT_REGISTRY` is missing.
- [ ] CI job `oci-auth` per PLAN.md §12.1.

**Verify:** `oci-auth` job green. Record in §D whether T27 (c) — explicit `AuthConfig` — worked as written (it is **[U]**).
**Requirements:** REQ-OCI-02..05, REQ-DISK-03

### Step 3.8 — Remaining M3 integration tests
- [ ] T18 (a)–(f), T19, T23 per PLAN.md §13.1.
- [ ] Run on KVM and TCG.

**Verify:** all pass in both runs.
**Requirements:** REQ-DISK-01..06, REQ-LIFE-03, REQ-GUEST-01, REQ-GUEST-02, REQ-GUEST-04, REQ-GUEST-05, REQ-DISK-11

### 3.G — Phase 3 gate
- [ ] CI green incl. `oci-auth`; U11, U12, T18, T19, T23, T26, T27, T28 present, tagged, passing; deviations logged.

---

## Phase 4 (M4) — Screen recording and audio

### Step 4.1 — Smoke-test `vmtc-rec` by hand
- [ ] Start a media-runner container with the reference guest (Java or `podman run` as in step 1.8), then:

  ```bash
  podman exec <c> vmtc-rec start smoke 0 10 1000000   # → VMTC_REC_STARTED <pid>
  sleep 5
  podman exec <c> vmtc-rec stop smoke 10              # → VMTC_REC_OK duration=… width=… height=…
  podman exec <c> sh -c 'cat /proc/[0-9]*/status | grep "^State:"'   # no "Z (zombie)"
  ```

**Verify:** outputs as commented.
**Requirements:** REQ-REC-01, REQ-IMG-05 (manual precursor)

### Step 4.2 — `ScreenRecording`
- [ ] `RecordingOptions` (fps 1..30 default 10; bitrate default 1 000 000; extend U9), `VmScreen.startRecording(...)` per PLAN.md §9.8 (password via `ExecConfig` env, never argv), `ScreenRecording` (`close`, `file`, `duration`, `width`, `height`), slim-runner `UnsupportedOperationException` text per §6.1.
- [ ] Registry of open recordings on the container (needed by `stop()` and the JUnit extension).
- [ ] **U15 `RecorderCommandTest`:** argv has no password; parsing of `VMTC_REC_OK`, `VMTC_REC_KILLED`+`VMTC_REC_OK`, `VMTC_REC_INVALID` → `RecordingException` with log tail.

**Spec:** PLAN.md §6.1, §7.2, §9.8
**Verify:** `./gradlew :lib:test --tests '*RecorderCommandTest' --tests '*ConfigValidationTest'` passes.
**Requirements:** REQ-REC-04, REQ-REC-05, REQ-REC-06 (U15)

### Step 4.3 — Audio
- [ ] `AudioRecording` per PLAN.md §9.9 (`wavcapture` via HMP, `info capture` parsing, `stopcapture`, copy out, RIFF/WAVE + non-empty `data` validation; one at a time).
- [ ] **U7 `InfoCaptureParserTest`.**

**Spec:** PLAN.md §9.9
**Verify:** `./gradlew :lib:test --tests '*InfoCaptureParserTest'` passes.
**Requirements:** REQ-AUD-03 (U7)

### Step 4.4 — `stop()` finalizes recordings
- [ ] `stop()` step 1 of PLAN.md §9.2: close every open screen and audio recording (best effort, log failures) before QMP `quit`.

**Verify:** via T31.
**Requirements:** REQ-LIFE-06

### Step 4.5 — M4 integration tests
- [ ] T8, T9, T10, T11, T24, T31, T33 per PLAN.md §13.1.
- [ ] Run on KVM and TCG.

**Verify:** all pass in both runs.
**Requirements:** REQ-REC-01..04, REQ-VNC-05, REQ-AUD-01, REQ-AUD-02, REQ-IMG-05, REQ-LIFE-06

### 4.G — Phase 4 gate
- [ ] CI green; U7, U15, T8–T11, T24, T31, T33 present, tagged, passing; deviations logged.

---

## Phase 5 (M5) — Hot-plug

### Step 5.1 — Device specs and handles
- [ ] `hotplug.Devices`, sealed `DeviceSpec`, `DiskBus`, `HotplugHandle`, `CdromDrive` per PLAN.md §9.10; naming scheme; root-port allocator (lowest free `hp<k>`, `IllegalStateException` when none); image-format detection via `qemu-img info`; files copied to `/vm/run/<id>.img|.iso`.
- [ ] Every plug/unplug sequence exactly as the §9.10 table; unplug polling every 250 ms on `qom-list /machine/peripheral`; default timeout 30 s × TCG factor; `HotplugTimeoutException`.
- [ ] `Devices.cpu()` under TCG → `UnsupportedOperationException` at `hotplug()`.
- [ ] **U13 `HotplugUnplugTimeoutTest`** with a fake QMP.

**Spec:** PLAN.md §9.10, §15 L1
**Verify:** `./gradlew :lib:test --tests '*HotplugUnplugTimeoutTest'` passes.
**Requirements:** REQ-HOT-09 (U13)

### Step 5.2 — M5 integration tests
- [ ] T13–T17 per PLAN.md §13.1.
- [ ] Run on KVM and TCG.

**Verify:** all pass in both runs (T16 `cpuKvm` only on KVM, `cpuTcg` only on TCG).
**Requirements:** REQ-HOT-01..08

### 5.G — Phase 5 gate
- [ ] CI green; U13, T13–T17 present, tagged, passing; deviations logged.

---

## Phase 6 (M6) — Cleanup, JUnit extension, release readiness

### Step 6.1 — Orphan cleanup
- [ ] `VmtcCleanup.removeOrphans()` per PLAN.md §9.12 (all container states; other session; `created` > 1 h); called once per JVM on first `start()` unless the `cleanup` override is `false`.
- [ ] T21 per PLAN.md §13.1.

**Verify:** T21 passes.
**Requirements:** REQ-CLN-01

### Step 6.2 — JUnit 5 extension
- [ ] `:junit5`: `@VmTest` registering `VmArtifactsExtension` per PLAN.md §9.13 (`TestWatcher.testFailed`; fields of the test instance and static fields incl. superclasses; stop open recordings; save serial log; one screenshot per screen; `build/vm-artifacts/<class>/<method>/`).
- [ ] `:it` adds `:junit5` and `org.junit.platform:junit-platform-testkit` (version from the JUnit BOM).
- [ ] T34 per PLAN.md §13.1. Replace the step 1.22 helper's artifact saving with `@VmTest` where it fits.

**Verify:** T34 passes.
**Requirements:** REQ-JUN-01

### Step 6.3 — Full traceability
- [ ] Set `traceabilityRequireFullCoverage=true`.
- [ ] Fix every gap the report lists: add the missing test or tag, or (only with agreement) retire the requirement in REQUIREMENTS.md with a reason.

**Verify:** `./gradlew checkRequirementTraceability` passes; `build/reports/requirements/traceability.md` lists at least one test for every *Test* requirement.
**Requirements:** REQ-BLD-06

### Step 6.4 — Manual procedures
- [ ] Run the two procedures of REQUIREMENTS.md §5 (rootful Podman; SELinux-enforcing host) or record in §D that they are pending and who will run them.

**Verify:** results (host OS, Podman version, outcome) recorded in §D.
**Requirements:** REQ-ENG-02, REQ-DISK-10

### Step 6.5 — CI hardening
- [ ] Confirm timeouts (§12.1), artifact uploads, weekly schedule, and that a failing test's VM artifacts are downloadable from the run.
- [ ] Deliberately break one assertion on a branch, push, confirm the artifacts appear, then revert.

**Verify:** the failing run shows `vm-artifacts` with serial log and screenshots.
**Requirements:** REQ-BLD-05

### Step 6.6 — User documentation
- [ ] `README.md` (or the target repo's docs location): quick start (`QemuVmContainer` + `referenceGuest()`), Podman setup (`DOCKER_HOST`, Ryuk disabled, `/dev/kvm` and the §9.6 remediation text), image sources incl. credentials and the §10.4 publishing format, VNC usage, recording/audio, hot-plug, limits (PLAN.md §15 L1–L7).
- [ ] Real coordinates and image names may appear in `README.md` only (it is on the hygiene allow-list, step 1.4). If the project is retargeted later, `README.md` is the one extra file to update.

**Verify:** a reader can run the quick start on a fresh host by following only the README (have someone, or a fresh agent session, try it).

### Step 6.7 — Publish (only when decided)
- [ ] `pushImages` with `-PpushImages=true` to the chosen registry — **only** after R2 is resolved for public registries.
- [ ] Publish the Maven artifacts per the target repository's release process.

**Verify:** `podman pull` of the three image tags and resolution of `${GROUP_ID}:${ART_LIB}:${VERSION}` from the chosen repository succeed on a clean machine.

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
| 1 | | 0.4 | R2: licensing of public images (UBI + Rocky/EPEL content) | — | open |
| 2 | | 0.4 | R3: SELinux-enforcing host acceptance (REQ-DISK-10) | — | open |
| 3 | | 0.4 | UEFI firmware paths (PLAN.md §8.4, T22) | — | open |

---

## In Summary

- Work top to bottom; tick boxes only after *Verify* passes; commit per step.
- PLAN.md is the spec, REQUIREMENTS.md the acceptance; this file is the route. Conflicts go to the Deviation log, and PLAN.md wins until it is updated.
- Six milestone gates, each proven by CI on KVM and TCG; Phase 6 turns on full requirement coverage.
