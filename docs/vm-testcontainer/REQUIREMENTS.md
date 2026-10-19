# QEMU VM Testcontainer: Requirements

> **Purpose:** the testable requirements the module must meet, for as long as the module exists. Automated tests point at these IDs and the Allure report is organised by them. This document outlives the implementation; [PLAN.md](PLAN.md) says *what* we build and *why*, and [PLAN-STEPS.md](PLAN-STEPS.md) walks through *building* it.
> **Names:** `${…}` names (for example `${LABEL_PREFIX}`) are the project parameters. They are defined in PLAN.md §0 and, once implemented, live in the files that table names (mostly `gradle.properties`). Type and method names (`QemuVmContainer`, `VmImage.fromUrl`, …) are fixed.

---

## 1. BLUF

- **8 epics → 22 features → 123 stories (3 retired).** Every story is one requirement row with an ID (`REQ-<FEATURE>-<NN>`), a short title, a "shall" statement, measurable acceptance criteria, the **evidence** a test must attach so a human can see the proof, how it is verified, and where it came from.
- **Tests point here with one annotation:** `@Requirement("REQ-EXEC-01")`. A JUnit extension turns that into the Allure Epic → Feature → Story hierarchy, links the test to this document, and puts the requirement text into the test's description (§3).
- **Evidence is attached to the Allure report:** container and serial logs for every VM test, plus per-requirement screenshots, screen recordings (WebM, played inline), audio (WAV + rendered waveform/spectrum image), command transcripts and inspect output (§3.3). The report is a CI artifact per job (§3.5).
- **Traceability is enforced by the build:** unknown IDs fail immediately; from the last milestone on, a *Test* requirement without a test fails too (§3.4).
- **Two requirements need a human** (rootful Podman, SELinux-enforcing host); their procedures are in §5 and they are not in the Allure report.

---

## 2. Conventions

| Item | Rule |
|------|------|
| ID format | `REQ-<FEATURE>-<NN>`, e.g. `REQ-HTTP-03`. `<FEATURE>` is the feature code of the table the row is in. IDs are never reused or renumbered. A dropped requirement stays in its table with `**Retired**` as the first word of its *Requirement* cell, followed by the reason |
| Epic / Feature / Story | Epic = a `### 4.<n> Epic: <name>` heading. Feature = a `#### Feature <CODE>: <name>` heading under it. Story = one requirement row. These three levels are exactly what the Allure report shows under *Behaviors* |
| "shall" | Mandatory. Every row is mandatory unless its title says *(optional component)* |
| Method | **Test:** = automated JUnit test (integration `T*` or unit `U*`; PLAN.md §13 defines each). **Check:** = an automated build/CI step that is not a JUnit test (image verification, hygiene grep, dependency check). **Inspection:** = review of code or build files. **Manual:** = a person runs the §5 procedure. A cell may list several |
| KVM / TCG | "under KVM" = `KvmMode.REQUIRE` on a host with usable `/dev/kvm`; "under TCG" = `KvmMode.DISABLE`. A timing limit marked *(scaled)* is multiplied by `tcgTimeoutFactor` (default 6) under TCG; one marked *(not scaled)* is the same everywhere; one with an explicit TCG value uses that |
| Reference guest | Unless a row says otherwise, integration tests run the reference guest (PLAN.md §10.3). Until milestone M3 the `:it` suite supplies it through `VmImage.fromPath` of the locally built qcow2; from M3 through `VmImage.referenceGuest()`. The criteria are identical either way |
| Valid WebM | `ScreenRecording.duration()` > 0 **and** the file contains the Matroska codec-ID bytes `V_VP8` |
| Valid WAV | Starts with `RIFF`, has `WAVE` at offset 8, and its `data` chunk size is > 0 |
| Source | The original ask or decision the row comes from: R1–R13, D1–D9 (PLAN.md §2), G1–G7 (PLAN.md §10.1), or a PLAN.md section |
| Met | A requirement is **met** when every test annotated with its ID passes in every CI job that executes that test. A test excluded from a job by its `kvm`/`tcg` tag or by task scoping (`compatTest`, `ociAuthTest`, `:lib:test`) is not applicable there. The traceability report (§3.4) lists, per ID, the tests and the jobs that ran them |

---

## 3. Traceability, Allure hierarchy and evidence

### 3.1 The `@Requirement` annotation

Lives in the `test-support` module (PLAN.md §5), package `${BASE_PACKAGE}.testsupport`:

```java
@Documented @Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(Requirements.class)
@ExtendWith(RequirementExtension.class)          // JUnit registers the extension wherever the annotation appears
public @interface Requirement { String value(); } // "REQ-EXEC-01"

@Documented @Inherited @Retention(RUNTIME) @Target({TYPE, METHOD})
@ExtendWith(RequirementExtension.class)          // not strictly needed (JUnit finds it through the container, PLAN.md §3.7) but harmless
public @interface Requirements { Requirement[] value(); }
```

Rules:

1. A test method carries one `@Requirement` per requirement it verifies. A class-level `@Requirement` applies to every test method in the class.
2. `@Disabled` is **forbidden** in all test source sets (CI greps for it and fails). Conditional skips use `Assumptions.assumeTrue(...)`, which Allure reports as *skipped* with the reason.
3. The `kvm` / `tcg` JUnit tags (`@Tag`) are unchanged; they select tests per CI job. Requirement IDs are **not** JUnit tags.

### 3.2 `RequirementExtension` and the catalog

`RequirementsCatalog` parses this file, which Gradle copies unchanged into `test-support`'s resources as `/vmtc-requirements/REQUIREMENTS.md` (single source: the file in `docs/`). Parsing rules, normative:

| Element | Rule |
|---------|------|
| Epic | A line matching `^### \d+\.\d+ Epic: (.+)$`; group 1 is the epic name. Only lines between the lines starting with `## 4.` and `## 5.` are parsed |
| Feature | A line matching `^#### Feature ([A-Z]+): (.+)$`; group 1 is the feature code, group 2 the name. The feature belongs to the most recent epic |
| Story row | A line matching `^\| (REQ-([A-Z]+)-\d{2})\b` inside a feature. Cells are split on `\|` (a pipe not preceded by a backslash); cell order is **ID, Title, Requirement, Acceptance criteria, Evidence, Method / verified by, Source**. Text after the ID in the first cell is ignored. The ID's feature code must equal the enclosing feature's code, else the parser fails |
| Retired | A row whose *Requirement* cell starts with `**Retired**` |
| Needs a test | A row whose *Method* cell matches `\bTest:` |

For each `@Requirement` on the test method and its class, `RequirementExtension.beforeTestExecution` does:

1. Look the ID up. Unknown or Retired → `fail("unknown or retired requirement " + id)` (the test fails; it cannot pass with a bad ID).
2. `Allure.epic(<epic name>)`, `Allure.feature("<CODE>: <feature name>")`, `Allure.story("<ID> <Title>")`, `Allure.label("requirement", <ID>)`.
3. `Allure.link(<ID>, "requirement", "${REQ_DOC_URL}#" + <feature anchor>)`, where the feature anchor is GitHub's heading anchor of the `#### Feature …` line (lower-case; spaces → `-`; characters other than letters, digits, `-` and `_` removed).
4. `Allure.description(...)` with the Markdown: `**<ID> <Title>**`, the Requirement text, `*Acceptance:*` + criteria, `*Evidence:*` + evidence cell. Several requirements → the blocks are concatenated.

Allure Java `${ALLURE_JAVA_VERSION}` (2.x line) is used: `Allure.addAttachment(...)` for attachments; `io.qameta.allure:allure-jupiter` as the JUnit 5 adapter; the AspectJ weaver agent is **not** needed because no `@Step`/`@Attachment` annotations are used (only the runtime API).

### 3.3 Evidence

Every requirement row names the evidence its tests attach. Names are fixed so a reviewer can find them; types come from the extension table.

**Standard VM evidence** — attached by the `:it` base class `VmIT` in `@AfterEach` for **every** `QemuVmContainer` the test registered, pass or fail, named `<n>-` + the name below when a test has more than one container:

| Attachment | Type | Content |
|------------|------|---------|
| `container.log` | `text/plain` | `getLogs()` of the runner container (entrypoint markers, QEMU stderr) |
| `serial.log` | `text/plain` | the guest's serial console (`/vm/out/serial.log`) |
| `vm-config.json` | `application/json` | `QemuVmContainer.describe()`: runner image and variant, image source (kind, redacted locator, cache key), requested and actual accelerator, KVM decision (device, annotation), CPUs, memory, screens, firmware, host port map, container id and labels |
| `timings.json` | `application/json` | milliseconds from `start()` to: container running, `VMTC_READY`, QGA answered, `start()` returned; plus the applied timeouts and the TCG factor |
| `warnings.txt` | `text/plain` | every WARN the library emitted during the test (captured through `Warnings.listen`, PLAN.md §9.11) |
| `screen<i>.png` | `image/png` | **on failure only**, one per screen, best effort |

**Requirement-specific evidence** — attached by the test itself through `Evidence` (test-support) and `VmEvidence` (`:it`):

| Helper | Attachment type | Notes |
|--------|-----------------|-------|
| `Evidence.text(name, String)` | `text/plain` | |
| `Evidence.json(name, Object)` | `application/json` | Jackson pretty-printed |
| `Evidence.file(name, Path)` | by extension: `.png` `image/png`, `.webm` `video/webm`, `.wav` `audio/wav`, `.log`/`.txt` `text/plain`, `.json` `application/json`, `.html` `text/html`, other `application/octet-stream` | Files > 64 MiB are never attached; use `digest` |
| `Evidence.digest(name, Path)` | `text/plain` | `<size> bytes, sha256 <hex>` |
| `VmEvidence.exec(name, GuestExecResult, argv)` (`:it`; delegates to `Evidence.exec(name, exitCode, signal, stdout, stderr, outTruncated, errTruncated, argv)` in test-support, which has no `:lib` dependency) | `text/plain` | `$ argv` / `exit=` / `signal=` / `--- stdout` / `--- stderr` |
| `VmEvidence.screenshot(name, VmScreen)` | `image/png` | |
| `VmEvidence.audio(baseName, Path wav)` | `audio/wav` + `image/png` + `text/plain` | `<base>.wav` (download), `<base>-analysis.png` (1200×600: waveform on top, magnitude spectrum below, RMS and dominant frequency printed on the image), `<base>-analysis.txt` (sample rate, bits, channels, duration, RMS, dominant Hz). The report does not play audio inline, so the image is the visible proof |
| `VmEvidence.inspect(name, container)` | `application/json` | `inspectContainerCmd` result (`HostConfig.Devices`, `Annotations`, `Binds`, `Mounts`, `PortBindings`, `PublishAllPorts`, `Labels`) |
| `VmEvidence.qmp(name, QmpClient, command, args)` | `application/json` | the command and its `return` |

Use `Allure.step("…")` around the phases of long integration tests; it is recommended, not required.

### 3.4 Traceability check

`checkRequirementTraceability` (root Gradle task; a `JavaExec` of `RequirementsCheck` in test-support, so the Gradle check and the runtime extension share one parser):

1. Parse this file (§3.2 rules).
2. Scan every test source set of every module (`lib/src/test/java`, `junit5/src/test/java`, `it/src/test/java`, `test-support/src/test/java`) for `@Requirement("…")` (regex `@Requirement\("(REQ-[A-Z]+-\d{2})"\)`) and for `@Disabled`.
3. **Fail** on any ID that does not exist or is Retired, and on any `@Disabled`.
4. **Fail** on a *Needs a test* row without any `@Requirement` when `gradle.properties` → `traceabilityRequireFullCoverage=true` (mandatory from milestone M6 and for every release); otherwise only list them.
5. Write `build/reports/requirements/traceability.md`: per ID → epic, feature, title, method, test classes#methods (or "NONE"), and the CI jobs that can run them (derived from `kvm`/`tcg` tags and the owning task).

The check does not verify that the test named in the *Method* cell (e.g. `T18`) is the one carrying the annotation; reviewers compare the report against the row.

### 3.5 Report generation and location

- Each `Test` task writes Allure results to `<module>/build/allure-results` (`allure.properties` on the test classpath: `allure.results.directory=build/allure-results`).
- `./gradlew allureReport` downloads Allure Report `${ALLURE_REPORT_VERSION}` (Maven Central zip `io.qameta.allure:allure-commandline`), unpacks it to `build/allure-cli/`, writes `environment.properties` (engine, engine version, accelerator mode, OS, Java, commit) into the first results directory, and runs `allure generate --clean --single-file -o build/allure-report <every module's allure-results>`.
- `build/allure-report/index.html` is a single file that opens directly from disk (`--single-file`), so a downloaded CI artifact needs no web server.
- CI uploads `build/allure-report/` as artifact `allure-report-<job>[-<matrix>]` and the raw `**/build/allure-results/` as `allure-results-<job>[-<matrix>]`, both `if: always()`. Reports are **not** published anywhere else.

---

## 4. Requirements by epic

### 4.1 Epic: Platform & Engine

#### Feature PLAT: Platform and scope

| ID | Title | Requirement | Acceptance criteria | Evidence | Method / verified by | Source |
|----|-------|-------------|---------------------|----------|----------------------|--------|
| REQ-PLAT-01 | x86-64 Linux hosts | The module shall support x86-64 Linux hosts | The CI `integration` matrix runs on `ubuntu-24.04` and `ubuntu-26.04` (both x86-64) and is green | — (CI run) | Check: CI matrix | D1 |
| REQ-PLAT-02 | Linux guests meeting the contract | The module shall run Linux x86-64 guests that meet the guest contract (PLAN.md §10.1, G1–G7). Other guest OSes are out of scope | The reference guest (Rocky 9) passes T2; every other `T*` test exercises it | standard VM evidence; `guest-info.txt` = `exec("/bin/sh","-c","cat /etc/os-release; uname -a")` | Test: T2 | D2, D3, D12, D13 |
| REQ-PLAT-03 | Java release | The library shall compile to Java `${JAVA_RELEASE}` bytecode | The class-file major version of `QemuVmContainer.class` equals `44 + ${JAVA_RELEASE}` (65 for Java 21) | `class-version.txt` | Test: U14 | D4 |

#### Feature ENG: Container engine

| ID | Title | Requirement | Acceptance criteria | Evidence | Method / verified by | Source |
|----|-------|-------------|---------------------|----------|----------------------|--------|
| REQ-ENG-01 | Rootless Podman via compat API | The library shall work against rootless Podman through its Docker-compatible API (`DOCKER_HOST`), with Ryuk disabled | In T2: `dockerClient.infoCmd().exec()` → `ServerVersion` or `OperatingSystem` contains `podman` (case-insensitive); `DOCKER_HOST` points under `$XDG_RUNTIME_DIR`; no container whose name starts with `testcontainers-ryuk` exists; T2 passes on both CI runners (Podman 4.9.x and 5.7.x at the time of writing) | `engine-info.json` (the `Info` object) | Test: T2 | R1 |
| REQ-ENG-02 | Rootful Podman | The library shall work against rootful Podman | T3 and T4 pass against a rootful `podman.socket` | §5 record | Manual (§5) | R1 |
| REQ-ENG-03 | No engine CLI from the library | Only the Testcontainers/docker-java client shall talk to the engine; the library shall never execute `podman`/`docker` | `lib/src/main` contains no `ProcessBuilder`, `Runtime.getRuntime().exec`, or the strings `"podman"`/`"docker"` as a command | — | Check: `checkParameterHygiene` grep | PLAN §4 |

### 4.2 Epic: Runner Image & Acceleration

#### Feature IMG: Runner image

| ID | Title | Requirement | Acceptance criteria | Evidence | Method / verified by | Source |
|----|-------|-------------|---------------------|----------|----------------------|--------|
| REQ-IMG-01 | UBI base | The runner images shall be built `FROM ${UBI_IMAGE}` and stay UBI-based | In each image: `/etc/os-release` has `ID="rhel"` and `VERSION_ID` equal to the tag of `${UBI_IMAGE}` (`9.8`); `rpm -q redhat-release` succeeds; `rpm -qa 'rocky-*'` prints nothing | `verifyRunnerImages` output in the CI step log | Check: `verifyRunnerImages` checks 4, 9 | R2 |
| REQ-IMG-02 | Repo order UBI → EPEL → Rocky | Packages shall resolve from UBI first, then EPEL, then Rocky `${ROCKY_RELEASE}` as fallback | Config: every `[section]` of `ubi.repo` has `priority=10`; `epel.repo` `priority=20`; every section of `rocky.repo` `priority=90` and the `excludepkgs=rocky-release* rocky-logos* rocky-gpg-keys* rocky-repos*` line; every added `.repo` has `gpgcheck=1`. Behaviour: `dnf -q repoquery --installed --qf '%{from_repo}' <pkg>` prints `rocky-fallback-appstream` for `qemu-kvm-core`, `epel9` for `tini`, an `ubi-9-*` repo id for `jq` (packages the Containerfile installed; base-layer packages print an opaque id) | `verifyRunnerImages` output | Check: `verifyRunnerImages` checks 7, 10 | R11 |
| REQ-IMG-03 | Slim and media variants | Two variants shall exist: slim (no GStreamer) and media (slim + GStreamer), tagged `${IMAGE_RUNNER}:${VERSION}-${IMAGE_TAG_SUFFIX}` and `…-media` | Label `${LABEL_PREFIX}.variant` = `slim` / `media`; media: `gst-inspect-1.0` finds `rfbsrc`, `vp8enc`, `webmmux`, `videorate`, `videoconvert`; slim: `command -v gst-launch-1.0` fails | `verifyRunnerImages` output | Check: `verifyRunnerImages` checks 3, 6, 8 | D6 |
| REQ-IMG-04 | QEMU and devices present | The images shall provide `/usr/libexec/qemu-kvm` with TCG and every device the library uses | `-accel help` lists `tcg`; `-device help` lists `VGA`, `secondary-vga`, `ich9-intel-hda`, `hda-duplex`, `virtio-serial-pci`, `virtserialport`, `qemu-xhci`, `usb-storage`, `usb-kbd`, `usb-tablet`, `usb-mouse`, `pcie-root-port`, `virtio-blk-pci`, `virtio-net-pci`, `virtio-scsi-pci`, `scsi-cd`, `pc-dimm` | `verifyRunnerImages` output | Check: `verifyRunnerImages` checks 1, 2 | R5 |
| REQ-IMG-05 | PID 1 reaps orphans | PID 1 in the runner container shall be `tini`, so stopped recorders never remain zombies | Check: `podman image inspect --format '{{index .Config.Entrypoint 0}}'` = `/usr/bin/tini` for both variants. Test: `cat /proc/1/comm` in the container prints `tini`; within 2 s *(not scaled)* of `ScreenRecording.close()` no `/proc/*/status` line reads `State:\tZ (zombie)` | `pid1.txt`, `process-states.txt` (the `/proc/*/status` scan) | Check: `verifyRunnerImages` check 5; Test: T24 | PLAN §1 rule a |

#### Feature KVM: Acceleration

| ID | Title | Requirement | Acceptance criteria | Evidence | Method / verified by | Source |
|----|-------|-------------|---------------------|----------|----------------------|--------|
| REQ-KVM-01 | REQUIRE uses KVM | With `KvmMode.REQUIRE` on a host where `/dev/kvm` is usable, the VM shall run under KVM | `accelerator()` = `KVM`; QMP `query-kvm` → `enabled: true`; inspect shows `/dev/kvm` in `HostConfig.Devices` | `query-kvm.json`, `inspect.json` | Test: T1 (`kvm`) | R12 |
| REQ-KVM-02 | REQUIRE without KVM fails clearly | With `KvmMode.REQUIRE` where `/dev/kvm` is absent or unusable, `start()` shall throw `KvmUnavailableException` | On a host where `KvmDetector.usable()` is false (asserted with `assumeFalse(usable)`), `start()` throws exactly `KvmUnavailableException` (not wrapped) whose message equals the PLAN.md §9.6 text for `!present()` or for `present() && !usable()`; U8 covers all three §9.6 messages | `exception.txt` (class + message) | Test: T20 (`tcg`), U8 | R12 |
| REQ-KVM-03 | PREFER is the default | `KvmMode.PREFER` shall be the default: KVM when usable, otherwise TCG with exactly one WARN per JVM carrying the applicable §9.6 message | Without `withKvm`: on a KVM host `accelerator()` = `KVM`; on a host without usable KVM `start()` succeeds, `accelerator()` = `TCG`, `HostConfig.Devices` null or empty, and `warnings.txt` holds the §9.6 message exactly once across two starts (U8 asserts once-per-JVM through the `Warnings` listener) | `inspect.json`, `warnings.txt` | Test: T1 `accelPreferDefault` (`kvm`), `accelPreferDefaultTcg` (`tcg`); U8 | R12 |
| REQ-KVM-04 | DISABLE forces TCG | With `KvmMode.DISABLE` the VM shall run under TCG and the container shall get no `/dev/kvm` device | `accelerator()` = `TCG`; `query-kvm.enabled` = false; `HostConfig.Devices` null or empty | `query-kvm.json`, `inspect.json` | Test: T1 (`tcg`), U8 | R12 |
| REQ-KVM-05 | Group-restricted /dev/kvm | For a `/dev/kvm` that is usable but not world read/write, the library shall add the annotation `run.oci.keep_original_groups=1` and shall never set `GroupAdd keep-groups` | Compat scenarios (PLAN-STEPS.md step 1.25): **A** (`0666`) → `accelerator()=KVM`, no annotation; **B** (`0660 root:kvm`, user in `kvm`, no `containers.conf`) → `Annotations` contains the key; Podman ≥ 5.7: KVM; Podman < 5.7: `PREFER` → `TCG` + the §9.6 group-restricted WARN, `REQUIRE` → `KvmUnavailableException` with that text; **C** (B + `containers.conf` annotation) → KVM on both. In every scenario `HostConfig.GroupAdd` is null or empty. U8 covers the decision table | `inspect.json`, `warnings.txt`, `scenario.txt` (scenario, Podman version, `ls -l /dev/kvm`, `id`) | Test: U8, T35 | R12, PLAN §1 rule h |
| REQ-KVM-06 | Fallback is explained | When KVM was intended but the container reported `VMTC_ACCEL tcg` or exited 3, the library shall log (PREFER) or throw (REQUIRE) the §9.6 group-restricted remediation text, followed by the container's own `VMTC_ERROR` line when there is one | U8 asserts the exact strings; T35 scenario B on Podman < 5.7 observes them live | `warnings.txt` / `exception.txt`, `container.log` | Test: U8, T35 | R12 |
| REQ-KVM-07 | TCG timeout scaling | Under TCG the library shall multiply these **defaults** by `tcgTimeoutFactor` (default 6.0): guest-agent readiness 180 s, exec 60 s, hot-plug unplug 30 s. Explicit values given by the caller shall never be scaled. The 60 s container-start wait, recorder stop (10 s), QMP `quit` at stop (5 s) and the serial-log poll are host-side and never scaled | U9: with `Accelerator.TCG` and factor 6 the three effective defaults are 1080 s, 360 s, 180 s and the container-start wait stays 60 s; with factor 2.5 they scale accordingly; `withGuestAgentTimeout(10 s)` and `exec(2 s, …)` stay 10 s and 2 s | `timeouts.txt` (the computed table) | Test: U9 | PLAN §8.2 |

### 4.3 Epic: VM Lifecycle, Exec & Files

#### Feature LIFE: Lifecycle

| ID | Title | Requirement | Acceptance criteria | Evidence | Method / verified by | Source |
|----|-------|-------------|---------------------|----------|----------------------|--------|
| REQ-LIFE-01 | One VM per container | Each `QemuVmContainer` shall run exactly one QEMU process in one container | `inspectContainerCmd(getContainerId())` carries the session label; `execInContainer("pgrep","-c","-x","qemu-kvm")` prints `1` | `qemu-processes.txt` (`ps -eo pid,comm,args` in the container) | Test: T2 | PLAN §4 |
| REQ-LIFE-02 | Ready means the agent answers | `start()` shall return only when the guest agent answers, within 30 s under KVM and 180 s under TCG for the reference guest on a warm host (runner image present, disk cached) | T2 runs after the suite's warm-up start; `start()` to return ≤ 30 s (KVM) / ≤ 180 s (TCG); immediately after return `exec("/usr/bin/true")` returns exit 0 on the first attempt | `timings.json` | Test: T2 | R3 |
| REQ-LIFE-03 | Base disk never written | The base disk shall never be written; each start shall use a fresh overlay | SHA-256 of the cache file is unchanged after the guest wrote 1 MiB to its root disk and ran `sync`; a second container from the same image does not see the file | `base-digest-before.txt`, `base-digest-after.txt`, `second-vm-ls.txt` | Test: T18 | PLAN §4 |
| REQ-LIFE-04 | Start failure is explained | If the container cannot start the VM, `start()` shall throw `VmtcException` (unwrapped) whose message contains the entrypoint's `VMTC_ERROR` text and the last 50 container-log lines | Slim runner with `VmImage.baked()` but no `/vm/base/disk.img` → `VmtcException` with `no base disk`; U16: the message builder given 60 lines includes exactly lines 11–60, given 5 lines all 5 | `exception.txt`, `container.log` | Test: T29, U16 | PLAN §9.2 |
| REQ-LIFE-05 | Agent timeout is explained | If the guest agent is not ready within the guest-agent timeout, `start()` shall throw `GuestAgentTimeoutException` (unwrapped) containing the last 50 serial-log lines | (a) 16 MiB raw zero disk, `withGuestAgentTimeout(10 s)`: exception thrown ≤ 10 s + 10 s after `VMTC_READY` appeared in the container log; (b) reference guest, `withGuestAgentTimeout(1 s)`: the message contains a serial line matching `Linux version` or `login:` (serial output exists before the agent is up) | `exception.txt`, `serial.log` | Test: T30 | PLAN §9.2 |
| REQ-LIFE-06 | stop() finalizes recordings | `stop()` shall finalize every open screen and audio recording before the container stops | A screen recording and an audio recording left open at `stop()` produce a valid WebM and a valid WAV at their host paths | `screen0.webm`, `audio.wav` + analysis | Test: T31 | PLAN §9.2 |
| REQ-LIFE-07 | Serial console access | Serial console output shall be available live (`withSerialLogConsumer`) and on demand (`saveSerialLog`) | `exec("/bin/sh","-c","echo VMTC_MARK_<uuid> > /dev/ttyS0")`: the consumer receives that line within 5 s *(not scaled)* of `exec` returning; the consumer also received at least one line emitted before `start()` returned (matching `Linux version`); `saveSerialLog(path)` returns `path` and the file contains the mark | `consumer-lines.txt`, `serial.log` | Test: T37 | PLAN §9.2 |
| REQ-LIFE-08 | Control ports on loopback | QMP and QGA host ports shall be published on `127.0.0.1` only, and no other container port shall be published | Inspect: `PortBindings` for `4444/tcp` and `4445/tcp` have `HostIp` `127.0.0.1`; `PublishAllPorts` is false; the set of published ports equals {4444, 4445, 5900..5900+n−1} | `inspect.json` | Test: T6 | PLAN §4 |
| REQ-LIFE-09 | UEFI boot | `withFirmware(Firmware.UEFI)` shall boot a UEFI-capable guest under UEFI | Guest agent ready; `exec("/usr/bin/test","-d","/sys/firmware/efi")` exits 0 | `efi.txt`, `screen0.png` | Test: T22 | PLAN §8.4 |
| REQ-LIFE-10 | Container labels | Every container started by `QemuVmContainer` shall carry labels `${LABEL_PREFIX}.managed=true`, `.version=<VmtcConstants.VERSION>`, `.session=<UUID fixed per JVM>` and `.created=<epoch seconds>` | Inspect shows all four; `.created` is within 60 s of the test's clock; two containers started in one JVM share `.session` | `inspect.json` (both containers) | Test: T2 | PLAN §9.2 |
| REQ-LIFE-11 | CPU and memory sizing | The VM shall boot with the configured vCPUs and memory and expose the configured hot-plug ceilings | `withCpus(3).withMaxCpus(6).withMemoryMb(1536).withMemoryHotplug(2,"6G")`: guest `nproc` = 3; `MemTotal` between 1300 and 1536 MiB; QMP `query-hotpluggable-cpus` has 6 entries; `query-memory-size-summary` → `base-memory` = 1536 MiB. `withMemoryMb(mb)` with `maxmem` smaller than `mb` → `IllegalArgumentException` at `start()` | `nproc.txt`, `meminfo.txt`, `hotpluggable-cpus.json`, `memory-size-summary.json` | Test: T36 | PLAN §9.2 |
| REQ-LIFE-12 | Protocol accessors | `qmp()`, `guestAgent()` and `screen(i).vnc()` shall expose working endpoints | After `start()`: `qmp().execute("query-status")` → `status: running`; `guestAgent().ping()` succeeds; `vnc().host()` equals `getHost()` when the bind address is `127.0.0.1` or `0.0.0.0`, else the configured address; `vnc().port()` equals `getMappedPort(5900+i)`; `vnc().uri()` is `vnc://<host>:<port>` | `query-status.json`, `vnc-endpoints.txt` | Test: T6 | PLAN §9.2, §9.7 |

#### Feature EXEC: Run commands

| ID | Title | Requirement | Acceptance criteria | Evidence | Method / verified by | Source |
|----|-------|-------------|---------------------|----------|----------------------|--------|
| REQ-EXEC-01 | Exit code, stdout, stderr | `exec(argv)` shall run a program in the guest and return exit code, stdout and stderr | `/usr/bin/uname -m` → exit 0, stdout `x86_64\n`; `/bin/sh -c 'echo out; echo err >&2; exit 3'` → (3, `out\n`, `err\n`) | `exec-uname.txt`, `exec-streams.txt` | Test: T3 | R3 |
| REQ-EXEC-02 | Env and stdin | `exec(ExecRequest)` shall pass environment variables and stdin bytes | `ExecRequest` with env `FOO=bar`, stdin `hi`, argv `/bin/sh -c 'echo $FOO; cat'` → stdout `bar\nhi` | `exec-env-stdin.txt` | Test: T3 | R3 |
| REQ-EXEC-03 | Shell convenience | `execShell(script)` shall run `/bin/sh -c script` | `execShell("echo a \| tr a b")` → stdout `b\n`, exit 0 | `exec-shell.txt` | Test: T3 | R3 |
| REQ-EXEC-04 | Timeout kills | A command exceeding its timeout shall be killed and raise `GuestExecTimeoutException` | `exec(Duration.ofSeconds(2), "/usr/bin/sleep", "61")` throws within 2 s + 5 s *(not scaled)*; afterwards `exec("/usr/bin/pgrep","-f","sleep 61")` exits 1 | `exception.txt`, `pgrep.txt` | Test: T32 | R3 |
| REQ-EXEC-05 | Truncation flags | Output truncated by the guest agent shall be reported through `stdoutTruncated` / `stderrTruncated` | Fake QGA returning `out-truncated: true` → `stdoutTruncated()` true; `err-truncated` likewise | — | Test: U2 | R3 |
| REQ-EXEC-06 | Signals and bad UTF-8 | A command killed by a signal shall report `signal()` and `exitCode() == -1`; non-UTF-8 output shall decode with U+FFFD replacement | `exec("/bin/sh","-c","kill -9 $$")` → `signal() == 9`, `exitCode() == -1`; U2: stdout bytes `0xFF 0x41` decode to `�A` | `exec-signal.txt` | Test: T3, U2 | R3 |

#### Feature FILE: File copy

| ID | Title | Requirement | Acceptance criteria | Evidence | Method / verified by | Source |
|----|-------|-------------|---------------------|----------|----------------------|--------|
| REQ-FILE-01 | Byte-exact round trip | `copyToVm` and `copyFromVm` shall copy files byte-exactly in both directions | Random 5 MB and 50 MB files: SHA-256 equal after host → guest → host; guest-side `sha256sum` equals the host digest | `copy-digests.txt` (sizes, host and guest digests, durations and MB/s) | Test: T4 | R4 |
| REQ-FILE-02 | Parents are created | `copyToVm` shall create missing parent directories | `exec("/usr/bin/test","!","-e","/tmp/vmtc")` exits 0 first; copy to `/tmp/vmtc/a/b/c.bin` succeeds; guest `sha256sum` matches | `copy-parents.txt` | Test: T4 | R4 |
| REQ-FILE-03 | Byte arrays | `copyToVm(byte[], …)` and `readFromVm` shall round-trip byte arrays, including empty ones | 0-byte and 3-byte arrays round-trip equal | `copy-bytes.txt` | Test: T4 | R4 |

### 4.4 Epic: Disk Sources

#### Feature DISK: VM disk sources and cache

| ID | Title | Requirement | Acceptance criteria | Evidence | Method / verified by | Source |
|----|-------|-------------|---------------------|----------|----------------------|--------|
| REQ-DISK-01 | Host path, qcow2 or raw | `VmImage.fromPath` shall accept a qcow2 or raw file as the base disk | qcow2: the reference guest boots (T18); raw: a raw file is detected as `raw` and QEMU starts (`VMTC_READY` in the container log, T30) | `vm-config.json`, `container.log` | Test: T18, T30 | R5 |
| REQ-DISK-02 | HTTP source boots | `VmImage.fromUrl` shall boot a disk downloaded over HTTP(S) | In-JVM `HttpServer` serving the reference qcow2 → boots (T18); the stock Rocky base image served the same way → boots with a seed (T25) | `download.txt` (URL, bytes, seconds, digest) | Test: T18, T25 | R13 |
| REQ-DISK-03 | OCI source boots | `VmImage.fromOci` / `fromContainerDisk` shall boot a disk published as a containerDisk image (PLAN.md §10.4), by tag and by digest | Reference guest by tag (T18) and `@sha256:` (T28) → boots | `vm-config.json`, `pull.txt` (image id, digest) | Test: T18, T28 | R13 |
| REQ-DISK-04 | Exactly one disk file | A containerDisk with zero or more than one regular file under `/disk/` shall be rejected with `IllegalArgumentException("containerDisk must contain exactly one file under /disk/")` | Fixture images with 0 files (`FROM scratch` + `WORKDIR /disk`) and with 2 files → that exception (unwrapped) at `start()` | `exception.txt` | Test: T18 | R13 |
| REQ-DISK-05 | Baked disk | `VmImage.baked()` shall boot a disk baked into a derived runner image at `/vm/base/disk.img`, with no bind mount | Derived image `FROM <slim>` + `COPY disk.qcow2 /vm/base/disk.img` → boots; inspect shows no mount with destination `/vm/base/disk.img` | `inspect.json` | Test: T18 | PLAN §9.3.4 |
| REQ-DISK-06 | Cache reuse (path, URL) | `fromPath` and `fromUrl` sources shall be materialised once and reused | Second `start()` with the same source: the cache file's inode and mtime (except the touch) are unchanged; `fromPath` performs no second copy (U3 counts); `fromUrl` with a SHA-256 sends **no** HTTP request (server request log empty) | `cache-listing.txt` (before/after: name, inode, size), `http-requests.txt` | Test: T18, U3 | PLAN §9.3.1 |
| REQ-DISK-07 | Concurrent materialisation | Concurrent writers of one cache entry shall produce one complete file | (a) two threads in one JVM; (b) two JVMs (`ProcessBuilder` launching a helper `main`) materialising the same key: exactly one `<key>.img`, identical content, both callers get it, no `*.tmp-*` left | `cache-listing.txt` | Test: U3 | PLAN §9.3.1 |
| REQ-DISK-08 | Copy, never link | `fromPath` shall copy the user's file into the cache, never hard-link it | Cache file inode ≠ source inode (`Files.getAttribute(p, "unix:ino")`) | — | Test: U3 | PLAN §9.3.1 |
| REQ-DISK-09 | Prune | `VmImageCache.prune(olderThan)` shall delete only entries not used within `olderThan`: the `<key>.img`, its `<key>.meta.json` and `<key>.lock`, and the `index.json` rows pointing at it | Entries touched by use survive; untouched older entries and their side files are deleted; `index.json` no longer references them | `cache-listing.txt` | Test: U3 | PLAN §9.3.1 |
| REQ-DISK-10 | SELinux hosts | On SELinux-enforcing hosts the cache bind shall be relabelled (`:z`) and the VM shall boot | U3: with an injected `selinuxEnforcing=true` the bind uses `SelinuxContext.SHARED`, with `false` none. Boot: T3 and T4 pass on a Fedora/RHEL host in enforcing mode | `bind.txt`; §5 record | Test: U3; Manual (§5) | PLAN §15 R3 |
| REQ-DISK-11 | referenceGuest() resolution | `VmImage.referenceGuest()` shall resolve to the `guestImage` override if set, else `${IMAGE_GUEST}:${VERSION}` | U6 asserts both resolutions; T23 boots it | `vm-config.json` | Test: U6, T23 | D3 |
| REQ-DISK-12 | Cache reuse (OCI) | `fromOci` shall reuse the cache entry while the image ID is unchanged and create a new entry when a tag moves | Same tag twice → same inode, no new `<key>.img`; after the tag moves → one more `<key>.img` | `cache-listing.txt`, `pull.txt` | Test: T28 | PLAN §9.3.6 |
| REQ-DISK-13 | No backing files | `fromPath` shall reject a qcow2 whose `qemu-img info` shows a `backing-filename` with `IllegalArgumentException` naming the backing file | Fixture overlay with a backing file → that exception at `start()` | `exception.txt`, `qemu-img-info.json` | Test: T18 | PLAN §9.3.1 |
| REQ-DISK-14 | Sparse copies | `fromPath` shall preserve sparseness when copying into the cache: zero-filled regions of the source become holes in the cache file | U3: a 64 MiB raw file with 1 MiB of random data at offset 0 and zeros elsewhere → cache file content identical, apparent size 64 MiB, allocated blocks (`stat -c '%b*%B'`) below 4 MiB; a source whose last 8 MiB are zeros keeps the 64 MiB length | `cache-listing.txt` (apparent and allocated sizes) | Test: U3 | PLAN §9.3.1 |

#### Feature HTTP: HTTP(S) downloads

All HTTP tests use in-JVM `HttpServer`s; no network.

| ID | Title | Requirement | Acceptance criteria | Evidence | Method / verified by | Source |
|----|-------|-------------|---------------------|----------|----------------------|--------|
| REQ-HTTP-01 | Credential kinds | Downloads shall support Basic, Bearer and custom-header credentials | Each kind succeeds against a server that requires exactly it and rejects the others | `http-requests.txt` (method, path, headers with secrets masked, status) | Test: T26 (b) | R13 |
| REQ-HTTP-02 | Auth failure is clear and redacted | HTTP 401/403 shall raise `ImageAuthException`; its message shall not contain user-info, the query string or any secret value | Missing and wrong credentials → `ImageAuthException`; with URL `http://user:pw@host/p?token=x` the message contains neither `user:pw`, `token=x`, nor the Basic/Bearer/custom secret | `exception.txt` | Test: T26 (a), U12 | R13 |
| REQ-HTTP-03 | Credentials stay on the origin | Credential headers shall be sent only to the original `scheme://authority`, including across redirects | For each of Basic, Bearer, custom header: a redirect to another port receives no credential header; a same-origin redirect does | `http-requests.txt` (both servers) | Test: T26 (e) | R13 |
| REQ-HTTP-04 | SHA-256 over downloaded bytes | When a SHA-256 is given it shall be checked over the bytes as downloaded (before decompression); a mismatch shall raise `ImageChecksumException(expected, actual)` and leave no cache file | For a `.gz` URL: the digest of the compressed bytes → success; the digest of the decompressed bytes → `ImageChecksumException` whose `actual` is the compressed digest; no `<key>.img` and no `*.tmp-*` remain | `exception.txt`, `cache-listing.txt` | Test: T26 (c) | R13 |
| REQ-HTTP-05 | No checksum → warn and revalidate | Without a SHA-256 the library shall emit one WARN per URL and revalidate cache hits with `If-None-Match` / `If-Modified-Since` | 304 → cache reused, no body transferred; 200 → entry replaced; server unreachable → cache reused plus a WARN; the no-integrity WARN appears once for two resolutions of the same URL | `http-requests.txt`, `warnings.txt` | Test: T26 (f) | R13 |
| REQ-HTTP-06 | Decompression | `Compression.AUTO` shall decompress by the **final** URL's path suffix `.gz`, `.xz`, `.zst`; explicit `GZIP`/`XZ`/`ZSTD`/`NONE` shall override | Each format yields the original bytes; `AUTO` with an unknown suffix stores the bytes unchanged; `NONE` on a `.gz` URL stores the compressed bytes; `GZIP` on a URL without `.gz` decompresses; a redirect from `/img` to `/img.gz` with `AUTO` decompresses | `download.txt` | Test: T26 (d) | R13 |
| REQ-HTTP-07 | Redirect limit | At most 5 redirects shall be followed; more shall raise `ImageDownloadException` whose message contains `too many redirects` | 5 chained redirects → success; 6 → that exception | `http-requests.txt`, `exception.txt` | Test: T26 (g) | R13 |
| REQ-HTTP-08 | Retries | I/O errors and 5xx shall be retried up to 3 attempts in total; 401, 403, 404 and a checksum mismatch shall not be retried | 503, 503, 200 → success with 3 requests; 503 ×3 → `ImageDownloadException`, exactly 3 requests; connection closed before headers ×2 then 200 → success; 404 → 1 request; 401 → 1 request; wrong SHA-256 → 1 request. The backoff is injectable so the test runs without real sleeps | `http-requests.txt` | Test: T26 (h) | R13 |
| REQ-HTTP-09 | Secrets do not print | `Secret.toString()` shall print `***` | Unit assertion | — | Test: U12 | R13 |
| REQ-HTTP-10 | Request timeout and lazy env | `UrlOptions.timeout` shall bound the wait for response headers and for each read; a stalled response shall raise `ImageDownloadException` after the retries of REQ-HTTP-08. `Secret.fromEnv` shall read the variable only when the value is first needed | Server sleeps > timeout before headers → exception, 3 requests; `Secret.fromEnv("X")` created with `X` unset does not throw until `value()` | `http-requests.txt`, `exception.txt` | Test: T26 (i), U12 | R13 |

#### Feature OCI: OCI sources

| ID | Title | Requirement | Acceptance criteria | Evidence | Method / verified by | Source |
|----|-------|-------------|---------------------|----------|----------------------|--------|
| REQ-OCI-01 | Reference parsing | `oci://<registry>/<repo>[:tag\|@sha256:<hex>]` shall be parsed; a missing tag means `latest`; any other scheme (including `oras://`) or no scheme shall be rejected with `IllegalArgumentException` | Table-driven: registry with port, tag, digest, no tag, `oras://`, bare name | `parse-table.txt` | Test: U11 | R13 |
| REQ-OCI-02 | Engine-side credentials | Without explicit credentials, pulls shall use the engine-side credentials: Docker `config.json` (and credential helpers it configures, through Testcontainers' `RegistryAuthLocator` [D, not separately tested]) and, on Podman, `podman login`'s `auth.json` | Private htpasswd TLS registry: `config.json` only → boots; `podman login` only → boots | `pull.txt`, `credentials-state.txt` (which credential files existed) | Test: T27 (b), (d) | R13 |
| REQ-OCI-03 | Explicit credentials | `OciOptions.credentials(RegistryCredentials.basic(user, Secret))` shall authenticate the pull without any stored credentials | No stored credentials + explicit credentials → boots | `pull.txt`, `credentials-state.txt` | Test: T27 (c) | R13 |
| REQ-OCI-04 | Auth failure is clear | A pull rejected for authentication, with no local copy of the image, shall raise `ImageAuthException` | Image removed locally, no credentials → `ImageAuthException` (unwrapped) at `start()` | `exception.txt` | Test: T27 (a) | R13 |
| REQ-OCI-05 | Tags move, digests pin | Every use of a tag shall pull, so a moved tag yields the new disk; a digest reference shall always yield the same disk | v1 pushed as `:t`, start → container `sha256sum /vm/base/disk.img` = v1; v2 pushed as `:t`, start → v2; `@sha256:<v1 digest>` → v1 | `disk-digests.txt`, `pull.txt`, `registry-access.log` (manifest requests per start) | Test: T28 | R13 |
| REQ-OCI-06 | No registry client in the JVM | The library shall contain no JVM-side registry client (no ORAS SDK); its dependencies shall be exactly PLAN.md §9.1 | `checkDependencies` compares `:lib` `api` and `implementation` direct dependencies with the §9.1 list | `checkDependencies` output | Check: `checkDependencies` | R13, PLAN §15 L7 |
| REQ-OCI-07 | Local fallback | If the pull fails but the image exists locally, `start()` shall use the local image and emit exactly one WARN containing `using local image <ref>`; without a local image the pull error is reported (REQ-OCI-04) | T18 (c) with a never-pushed local tag boots and `warnings.txt` has the line once; T27 (a) with the image removed throws | `warnings.txt`, `pull.txt` | Test: T18, T27 | PLAN §9.3.3 |

### 4.5 Epic: Display, VNC & Keyboard

#### Feature DISP: Screens and screenshots

| ID | Title | Requirement | Acceptance criteria | Evidence | Method / verified by | Source |
|----|-------|-------------|---------------------|----------|----------------------|--------|
| REQ-DISP-01 | 1..4 screens | `withScreens(n)` shall accept 1..4 (default 1) and reject other values with `IllegalArgumentException` | Unit validation for 0, 1, 4, 5 | — | Test: U9 | D5 |
| REQ-DISP-02 | Per-screen screenshots | `screen(i).screenshot()` shall capture screen *i* | n=2: head 0 is 1280×800 with ≥ 0.5 % non-black pixels (console text); head 1 before modeset is 640×480; `screenshot(Path)` writes a PNG that decodes to the same dimensions | `screen0.png`, `screen1-before.png` | Test: T5 | R6 |
| REQ-DISP-03 | forceModeset | `forceModeset()` shall make a secondary screen scan out on a console-only guest and return only once the new mode is visible | Head 1 is 1280×800 in a `screenshot()` taken **immediately** after `forceModeset()` returns (no sleep); `forceModeset()` on head 0 is a no-op that does not throw | `screen1-after.png`, `fb-activate.txt` (the script's output) | Test: T5 | R6, PLAN §15 L2 |

#### Feature VNC: VNC output

| ID | Title | Requirement | Acceptance criteria | Evidence | Method / verified by | Source |
|----|-------|-------------|---------------------|----------|----------------------|--------|
| REQ-VNC-01 | One VNC server per screen | Each screen shall have its own VNC server, exposed to library users through `screen(i).vnc()`, for up to 4 screens | `withScreens(4)`: `screens().size()` = 4; QMP `query-vnc-servers` lists 4 servers; a raw RFB handshake from the host to each `vnc()` returns `RFB 003.008` and offers security type 1 (None) | `query-vnc-servers.json`, `rfb-handshakes.txt` (hex of each exchange) | Test: T6 | R9 |
| REQ-VNC-02 | Bind address | VNC ports shall bind to `127.0.0.1` by default; `withVncBindAddress` shall change the host IP | Default: `PortBindings` for `590x/tcp` have `HostIp` `127.0.0.1`; with `withVncBindAddress("0.0.0.0")`: `HostIp` `0.0.0.0` | `inspect.json` | Test: T6 | R9 |
| REQ-VNC-03 | — | **Retired** (D8): VNC authentication is out of scope; test VMs are short-lived on an isolated network and VNC binds to loopback by default | — | — | — | D8 |
| REQ-VNC-04 | — | **Retired** (D8): see REQ-VNC-03 | — | — | — | D8 |
| REQ-VNC-05 | Exclusive clients do not evict recorders | A library user's exclusive VNC client shall not disconnect recorders | A raw RFB client completes the handshake with ClientInit `shared-flag=0` mid-recording, holds 3 s, disconnects; the recording is a valid WebM with duration ≥ wall time − 1 s | `screen0.webm`, `rfb-client.txt` | Test: T9 | PLAN §1 rule d |

#### Feature KEY: Keyboard

| ID | Title | Requirement | Acceptance criteria | Evidence | Method / verified by | Source |
|----|-------|-------------|---------------------|----------|----------------------|--------|
| REQ-KEY-01 | Typing text | `keyboard().type(text)` shall type US-layout text per PLAN.md Appendix A.3; a text containing an unsupported character shall raise `IllegalArgumentException` **before** any key is sent | U4: every row of A.3 → expected qcodes; `type("ab€")` → exception and the fake QMP saw zero `send-key`. T12: console login and a typed command succeed (`TYPED_OK` on the serial log within 60 s) | `screen0-after-typing.png`, `serial.log` | Test: U4, T12 | R6 |
| REQ-KEY-02 | Chords | `keyboard().press(qcodes…)` shall send the keys as one chord | U4: one `send-key` with all qcodes | — | Test: U4 | R6 |

### 4.6 Epic: Recording

#### Feature REC: Screen recording

| ID | Title | Requirement | Acceptance criteria | Evidence | Method / verified by | Source |
|----|-------|-------------|---------------------|----------|----------------------|--------|
| REQ-REC-01 | One WebM per screen | `screen(i).startRecording(path)` shall record that screen's VNC output to one VP8 WebM file per recording | n=2, both screens recorded while the guest prints to `/dev/tty1`; wall time = from `startRecording()` return to `close()` call; each file is a valid WebM, `width()` = 1280 (after `forceModeset` on head 1), \|`duration()` − wall\| < 1 s | `screen0.webm`, `screen1.webm` (play inline), `recording-stats.txt` | Test: T8 | R8 |
| REQ-REC-02 | Fast stop | `ScreenRecording.close()` shall return within 2 s *(not scaled)*, including the copy to the host | Measured from `close()` call to return for each recording in T8 | `recording-stats.txt` | Test: T8 | R8 |
| REQ-REC-03 | Static screens record | A static (unchanging) screen shall still produce a full-length recording | 10 s unchanged screen → 9 s ≤ `duration()` ≤ 11 s | `screen0-static.webm` | Test: T10 | R8, PLAN §1 rule b |
| REQ-REC-04 | Slim has no recording | Screen recording on the slim runner shall raise `UnsupportedOperationException("screen recording requires the media runner image")` | Slim runner → exception with that exact message | `exception.txt` | Test: T33 | D6 |
| REQ-REC-05 | — | **Retired** (D8): VNC passwords are out of scope, so there is no secret to keep off command lines | — | — | — | D8 |
| REQ-REC-06 | Recorder failures are reported | A recorder that does not start, that exits before `close()`, or whose file is invalid, shall raise `RecordingException` including the recorder's output | U15: `vmtc-rec start` output without `VMTC_REC_STARTED` → `RecordingException` with that output; `VMTC_REC_INVALID` + log lines on stop → `RecordingException` containing the lines; `VMTC_REC_DIED` + log lines → `RecordingException`; `VMTC_REC_KILLED` then `VMTC_REC_OK` → success plus one WARN; `VMTC_REC_OK duration=12.346 width=1280 height=800` parses | `parse-table.txt` | Test: U15 | PLAN §9.8 |
| REQ-REC-07 | Options and concurrency | `RecordingOptions` shall control frame rate and bitrate, and several recordings of one screen shall run at once | `RecordingOptions.fps(5)` → the file's video track reports 5 fps (`gst-discoverer-1.0` in the container: `Frame rate: 5/1`); two simultaneous recordings of screen 0 both produce valid WebMs | `screen0-5fps.webm`, `discoverer.txt`, `screen0-a.webm`, `screen0-b.webm` | Test: T8 | PLAN §9.8 |

#### Feature AUD: Audio recording

| ID | Title | Requirement | Acceptance criteria | Evidence | Method / verified by | Source |
|----|-------|-------------|---------------------|----------|----------------------|--------|
| REQ-AUD-01 | Separate WAV capture | `startAudioRecording(path)` shall record guest audio output to a separate WAV file | Guest `/usr/bin/speaker-test -t sine -f 1000 -c 2 -l 1` → valid WAV 44.1 kHz / 16-bit / 2 ch, RMS > 100, dominant frequency 900–1100 Hz | `audio.wav`, `audio-analysis.png`, `audio-analysis.txt` | Test: T11 | R7 |
| REQ-AUD-02 | One capture at a time | Only one audio recording shall be active at a time; after `close()` a new one shall start | Second `startAudioRecording` while one is open → `IllegalStateException`; after `close()` a new recording succeeds | `exception.txt` | Test: T11 | R7 |
| REQ-AUD-03 | close() stops the capture | `AudioRecording.close()` shall stop the capture it started | After `close()`, QMP `human-monitor-command info capture` returns no capture lines; U7 parses the verified `info capture` format and the one-line fallback | `info-capture-before.txt`, `info-capture-after.txt` | Test: T11, U7 | R7 |

### 4.7 Epic: Hot-plug

#### Feature HOT: Hot-plug simulation

Unless stated, "appears"/"gone" means within 10 s *(scaled)*.

| ID | Title | Requirement | Acceptance criteria | Evidence | Method / verified by | Source |
|----|-------|-------------|---------------------|----------|----------------------|--------|
| REQ-HOT-01 | USB storage | USB storage shall hot-plug and unplug | `lsblk -o NAME,TRAN,SIZE` gains a `usb` disk of 16 MiB; after `unplug()` it is gone | `lsblk-before.txt`, `lsblk-plugged.txt`, `lsblk-after.txt`, `hotplug-timings.txt` | Test: T13 | R10 |
| REQ-HOT-02 | USB HID | USB keyboard, tablet and mouse shall hot-plug and unplug | Per device: `/proc/bus/input/devices` gains one `N: Name=` entry containing `QEMU`; gone after unplug | `input-devices-*.txt` | Test: T13 | R10 |
| REQ-HOT-03 | Virtio disk (PCIe) | A virtio disk shall hot-plug and unplug, from a host image or as a new blank disk | New `vdX` appears; `newBlankDisk("64M", VIRTIO)` shows `lsblk -b` size 67108864; gone after unplug | `lsblk-*.txt` | Test: T14 | R10 |
| REQ-HOT-04 | NIC (PCIe) | A NIC shall hot-plug and unplug, with an optional MAC | `nic("52:54:00:12:34:56")` → `ip -o link` gains an interface with that MAC; gone after unplug | `ip-link-*.txt` | Test: T14 | R10 |
| REQ-HOT-05 | Root ports are finite | When all hot-plug root ports are in use, a further PCIe hot-plug shall raise `IllegalStateException`; unplugging frees the port | `withHotplugPorts(1)`: second virtio disk → exception; after `unplug()` of the first, a third plug succeeds | `exception.txt` | Test: T14 | R10 |
| REQ-HOT-06 | CD media | CD media shall be insertable and ejectable | `blkid /dev/sr0` shows the ISO label `VMTCCD`; mounted content matches the fixture file; after `eject()` `blkid /dev/sr0` exits non-zero | `cdrom-*.txt` | Test: T15 | R10 |
| REQ-HOT-07 | vCPU (KVM only) | A vCPU shall hot-plug under KVM; under TCG `hotplug(Devices.cpu())` shall raise `UnsupportedOperationException` | KVM: `nproc` +1. TCG: exception, nothing added | `nproc-*.txt` / `exception.txt` | Test: T16 | R10, PLAN §15 L1 |
| REQ-HOT-08 | Memory | Memory shall hot-plug | `MemTotal` grows by more than 400 MiB after `memory(512)` | `meminfo-before.txt`, `meminfo-after.txt` | Test: T17 | R10 |
| REQ-HOT-09 | Unplug timeout | An unplug the guest does not complete within the timeout shall raise `HotplugTimeoutException("guest did not release <id>")` | Fake QMP whose `qom-list` keeps the device → exception after the (injected, short) timeout | — | Test: U13 | R10 |
| REQ-HOT-10 | Read-only disks and handle semantics | Disks plugged with `readOnly=true` shall be read-only in the guest; `unplug()` on cpu/memory handles shall raise `UnsupportedOperationException` and their `close()` shall be a no-op; `close()` on other handles shall unplug | `usbStorage(img, true)`: `dd if=/dev/zero of=/dev/<dev> count=1` fails and the host image's SHA-256 is unchanged; cpu/memory handles as stated; `close()` on a USB handle removes the device | `readonly-dd.txt`, `image-digest.txt` | Test: T13, T16, T17 | R10 |

### 4.8 Epic: Guest Contract, Build & Tooling

#### Feature GUEST: Guest contract and seeding

| ID | Title | Requirement | Acceptance criteria | Evidence | Method / verified by | Source |
|----|-------|-------------|---------------------|----------|----------------------|--------|
| REQ-GUEST-01 | Blocked RPCs are detected | A guest whose agent blocks exec/file RPCs shall be reported with `GuestCommandDisabledException` (unwrapped) at `start()` | Stock Rocky image, no seed → that exception; U2 maps `CommandNotFound` + `has been disabled` | `exception.txt`, `guest-info.json` | Test: T19, U2 | G3 |
| REQ-GUEST-02 | Confined agent is detected | A guest whose agent domain is SELinux-enforcing shall be reported with `GuestAgentConfinedException` (unwrapped) at `start()` | Stock Rocky with only G3 applied → that exception | `exception.txt`, `id-Z.txt` | Test: T19 | G4 |
| REQ-GUEST-03 | Stock Rocky seed content | `CloudInitSeed.forStockRocky()` shall produce exactly the PLAN.md §10.2 user-data (G2–G6) | `userData()` equals the §10.2 text byte for byte; `metaData()` has `instance-id` and `local-hostname: vm` | `user-data.yaml` | Test: U10 | G2–G6 |
| REQ-GUEST-04 | Reference guest without seed | The reference guest `${IMAGE_GUEST}:${VERSION}` shall boot with no seed and meet G2–G6 | No seed; QGA ready within REQ-LIFE-02's limit; `exec` works; `id -Z` shows `virt_qemu_ga_t` yet `ls /lib/modules/$(uname -r)` succeeds; `amixer -c 0 get Master` shows `[on]` | `amixer.txt`, `id-Z.txt`, `timings.json` | Test: T23 | D3 |
| REQ-GUEST-05 | containerDisk format | The reference guest shall be published in the containerDisk format of PLAN.md §10.4 | Check in `buildGuestDisk`: `podman image inspect` shows one layer and empty `Config.Cmd`/`Entrypoint`; the tar from `podman cp` of `/disk/` has one regular file, uid/gid 107. Test: `fromContainerDisk` boots it (T18) | `buildGuestDisk` output | Check: `buildGuestDisk`; Test: T18 | R13 |
| REQ-GUEST-06 | Seed builder | `CloudInitSeed.builder()` shall start empty; `stockRocky()` shall add the §10.2 built-ins; `packages` shall merge into one `dnf` line placed right after `update-ca-trust`; `runcmd` lines shall follow in call order; `writeFile`/`trustCaFile` shall emit `write_files` entries (and `trustCaFile` adds `update-ca-trust`); `rootPassword` shall emit the `chpasswd` block; `build()` shall always append `echo VMTC_SEED_DONE > /dev/ttyS0` last | U10 asserts each rule on generated YAML; T25 adds `writeFile("/etc/vmtc-test","x\n")` and reads the file back after start | `user-data.yaml`, `exec-readfile.txt` | Test: U10, T25 | PLAN §10.2 |
| REQ-GUEST-07 | Seeded start waits for the seed | With a seed, `start()` shall return only after the guest printed `VMTC_SEED_DONE` | `saveSerialLog` immediately after `start()` contains `VMTC_SEED_DONE`; `exec` succeeds on the first attempt; `amixer -c 0 get Master` shows `[on]` | `serial.log`, `timings.json` | Test: T25 | PLAN §9.2 |

#### Feature CFG: Configuration

| ID | Title | Requirement | Acceptance criteria | Evidence | Method / verified by | Source |
|----|-------|-------------|---------------------|----------|----------------------|--------|
| REQ-CFG-01 | Overrides replace defaults only | Each runtime override key (PLAN.md §9.1) shall be settable as system property `${SYSPROP_PREFIX}.<key>` and environment variable `${ENV_PREFIX}<KEY>`; precedence **builder call > system property > environment variable > default** (D9). An unparsable override value shall raise `IllegalArgumentException` naming the key at `start()` | Table-driven over every key and every precedence level (environment injected through a `Map` seam); `kvm=maybe`, `tcgTimeoutFactor=0`, `cleanup=yes` → exception naming the key | `precedence-table.txt` | Test: U6 | PLAN §9.1 |
| REQ-CFG-02 | Builder ranges | Builder arguments outside their documented ranges shall raise `IllegalArgumentException` at the builder call | hot-plug ports 0..8; CPUs boot ≥ 1 and max ≥ boot; memory slots ≥ 0; `withQemuArgs` arg containing whitespace; recording fps 1..30; `withTcgTimeoutFactor` > 0 (screens: REQ-DISP-01) | — | Test: U9 | PLAN §9.2 |
| REQ-CFG-03 | Extra QEMU arguments | `withQemuArgs(String...)` shall append the arguments to the QEMU command line | `withQemuArgs("-name","vmtc-extra")` → QMP `query-name` returns `vmtc-extra` | `query-name.json` | Test: T1 | PLAN §9.2 |

#### Feature ERR: Errors

| ID | Title | Requirement | Acceptance criteria | Evidence | Method / verified by | Source |
|----|-------|-------------|---------------------|----------|----------------------|--------|
| REQ-ERR-01 | One unchecked hierarchy | Every exception type declared in the library's public packages shall be unchecked and extend `VmtcException`; the library shall not throw bare `RuntimeException`/`UncheckedIOException` | U14: reflection over the public exception types; Check: `lib/src/main` has no `throw new RuntimeException(` or `throw new UncheckedIOException(` | `exception-types.txt` | Test: U14; Check: `checkParameterHygiene` grep | PLAN §9.11 |
| REQ-ERR-02 | QMP errors carry class and desc | `QmpException` shall carry the QMP error `class` and `desc` | Fake QMP error → both fields set | — | Test: U1 | PLAN §9.11 |
| REQ-ERR-03 | start() throws the real exception | `QemuVmContainer.start()` shall rethrow the library's own `VmtcException` subclass directly, never wrapped in Testcontainers' `ContainerLaunchException` | T20, T29, T30, T19 assert `isExactlyInstanceOf`; U16: a wait strategy throwing `KvmUnavailableException` surfaces as that type from `start()` of a container with a fake docker client | `exception.txt` | Test: T20, T29, T30, U16 | PLAN §9.2 |

#### Feature CLN: Cleanup

| ID | Title | Requirement | Acceptance criteria | Evidence | Method / verified by | Source |
|----|-------|-------------|---------------------|----------|----------------------|--------|
| REQ-CLN-01 | Orphan removal rules | `VmtcCleanup.removeOrphans()` shall force-remove containers (any state) labelled `${LABEL_PREFIX}.managed=true` whose `.session` differs from the current one **and** whose `.created` is more than 1 h old, return the number removed, and remove nothing when the `cleanup` override is `false` | Three created (not started) containers: other session + 2 h old → removed; other session + 10 min old → kept; current session + 2 h old → kept; return value 1. With `cleanup=false` → 0 removed | `containers-before.json`, `containers-after.json` | Test: T21 | PLAN §9.12 |
| REQ-CLN-02 | Cleanup runs once per JVM | `start()` shall invoke orphan cleanup exactly once per JVM, before the first container starts, and not at all when `cleanup=false` | U17 with an injectable cleanup seam: two `start()`s → one invocation; `cleanup=false` → zero | — | Test: U17 | PLAN §9.12 |

#### Feature JUN: JUnit 5 extension

| ID | Title | Requirement | Acceptance criteria | Evidence | Method / verified by | Source |
|----|-------|-------------|---------------------|----------|----------------------|--------|
| REQ-JUN-01 | Artifacts on failure *(optional component)* | With `@VmTest`, a failing test shall leave, per running `QemuVmContainer`, the serial log and one screenshot per screen under `build/vm-artifacts/<class>/<method>/`, and shall finalise every open screen and audio recording at its own host path, **before** `@AfterEach` runs | `EngineTestKit` runs a nested `@VmTest` class whose test opens a screen recording and fails: `serial.log`, `screen0.png` and the WebM exist and are non-empty; an `@AfterEach` in that class observes the files already present | `artifact-listing.txt`, `screen0.png` | Test: T34 | PLAN §9.13 |

#### Feature BLD: Build, publishing, retargeting and reporting

| ID | Title | Requirement | Acceptance criteria | Evidence | Method / verified by | Source |
|----|-------|-------------|---------------------|----------|----------------------|--------|
| REQ-BLD-01 | Single source for parameters | Each project parameter shall be stored in exactly one file; changing it there shall retarget artifacts, packages, images, labels, prefixes and the cache directory | `checkParameterHygiene` passes (PLAN.md §12.3 rules); U5 asserts generated constants equal `gradle.properties` | `constants.txt` | Check: `checkParameterHygiene`; Test: U5 | PLAN §0 |
| REQ-BLD-02 | Maven coordinates | The library shall be published as `${GROUP_ID}:${ART_LIB}:${VERSION}` and the extension as `${GROUP_ID}:${ART_JUNIT}:${VERSION}` | After `publishToMavenLocal` the POM at `~/.m2/repository/<group path>/${ART_LIB}/${VERSION}/${ART_LIB}-${VERSION}.pom` exists with matching `groupId`/`artifactId`/`version`; same for `${ART_JUNIT}` | CI step output | Check: CI `unit` job step | D4 |
| REQ-BLD-03 | Dependency surface | `:lib`'s `api` dependency shall be Testcontainers only and its `implementation` dependencies exactly the PLAN.md §9.1 list | `checkDependencies` compares the direct dependency sets with the list and fails on any difference | `checkDependencies` output | Check: `checkDependencies` | PLAN §9.1 |
| REQ-BLD-04 | Module exports | `module-info` shall export every package except `${BASE_PACKAGE}.internal` | U14 reads the descriptor from the compiled `module-info.class` | `module-exports.txt` | Test: U14 | PLAN §9.1 |
| REQ-BLD-05 | Weekly rebuild | CI shall rebuild the runner images against fresh repositories weekly | `ci.yml` contains `schedule:` with `cron: '0 6 * * 1'`; the scheduled run builds images with `--no-cache` | — | Check: `checkParameterHygiene` grep of `ci.yml` | R1, PLAN §15 R1 |
| REQ-BLD-06 | Traceability enforced | Requirement traceability (§3.4) shall be enforced in CI | `checkRequirementTraceability` runs in the `unit` job and fails the build on violations; from M6 with `traceabilityRequireFullCoverage=true` | `traceability.md` (uploaded) | Check: CI `unit` job | this document |
| REQ-BLD-07 | Allure report with evidence | Every CI job that runs tests shall produce an Allure report organised by epic/feature/story with the evidence of §3.3 attached | After the test step, `./gradlew allureReport` succeeds; `build/allure-report/index.html` exists; `**/build/allure-results/*-attachment.*` includes at least one `.png`, one `.webm` (media jobs) and one `.wav`; both artifacts are uploaded `if: always()` | the report itself | Check: CI step | this document |

---

## 5. Requirements not verifiable on GitHub-hosted runners

| ID | Why not automated | Manual procedure (before each minor release) |
|----|-------------------|-----------------------------------------------|
| REQ-ENG-02 | GitHub runners use rootless Podman for the user; a rootful socket is possible but not part of the planned CI | `sudo systemctl start podman.socket`; `sudo env DOCKER_HOST=unix:///run/podman/podman.sock TESTCONTAINERS_RYUK_DISABLED=true ./gradlew :it:integrationTest --tests '*ExecIT' --tests '*CopyIT'`; record host OS, Podman version, date and outcome in PLAN-STEPS.md §D |
| REQ-DISK-10 (boot part) | GitHub Ubuntu runners use AppArmor, not SELinux | On Fedora or RHEL with `getenforce` = `Enforcing`, rootless Podman: `./gradlew :it:integrationTest --tests '*ExecIT' --tests '*CopyIT'`; record as above |

---

## 6. In Summary

- Epic → Feature → Story is the document's own structure (§4 headings and rows) and the Allure report's; one `@Requirement("REQ-…")` per story on each test connects them.
- Every row says what to assert **and** what to attach, so a reviewer can open the report and see the proof (logs, screenshots, inline videos, waveform images, transcripts).
- The build keeps tags and rows in sync (§3.4); the report is a per-job CI artifact that opens from disk (§3.5).
- Two requirements need a person (§5); everything else is automated.
