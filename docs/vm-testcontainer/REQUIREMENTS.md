# QEMU VM Testcontainer: Requirements

> **Purpose:** the testable requirements the module must meet, for as long as the module exists. Automated tests point at these IDs and the Allure report is organised by them. This document outlives the implementation; [PLAN.md](PLAN.md) says *what* we build and *why*, and [PLAN-STEPS.md](PLAN-STEPS.md) walks through *building* it.
> **Names:** `${…}` names (for example `${LABEL_PREFIX}`) are the project parameters. They are defined in PLAN.md §0 and, once implemented, live in the files that table names (mostly `gradle.properties`). Type and method names (`QemuVmContainer`, `VmImage.fromUrl`, …) are fixed.

---

## 1. BLUF

- **8 epics → 24 features → 143 stories (4 retired).** Every story is one requirement row with an ID (`REQ-<FEATURE>-<NN>`), a short title, a "shall" statement, measurable acceptance criteria, the **evidence** a test must attach so a human can see the proof, how it is verified, and where it came from.
- **Tests point here with one annotation:** `@Requirement("REQ-EXEC-01")`. A JUnit extension turns that into the Allure Epic → Feature → Story hierarchy, links the test to this document, and puts the requirement text into the test's description (§3).
- **Evidence is attached to the Allure report:** container and serial logs for every VM test, plus per-requirement screenshots, screen recordings (WebM, played inline), audio (WAV + rendered waveform/spectrum image), command transcripts and inspect output (§3.3). The report is a CI artifact per job (§3.5).
- **Traceability is enforced by the build:** unknown IDs fail immediately; from the last milestone on, a *Test* requirement without a test fails too (§3.4).
- **Two requirements need a human** (rootful Podman, SELinux-enforcing host); their procedures are in §5 and they are not in the Allure report.
- **Black-box first:** a guest owes nothing by default (Tier 0, PLAN.md §10.1). Requirements that need `exec`/`copy` state the guest access they run under (SSH, agent, or both); `GuestAccess.none()` is the default and fails fast (Feature ACC).

---

## 2. Conventions

| Item | Rule |
|------|------|
| ID format | `REQ-<FEATURE>-<NN>`, e.g. `REQ-HTTP-03`. `<FEATURE>` is the feature code of the table the row is in. IDs are never reused or renumbered. A dropped requirement stays in its table with `**Retired**` as the first word of its *Requirement* cell, followed by the reason |
| Epic / Feature / Story | Epic = a `### 4.<n> Epic: <name>` heading. Feature = a `#### Feature <CODE>: <name>` heading under it. Story = one requirement row. These three levels are exactly what the Allure report shows under *Behaviors* |
| "shall" | Mandatory. Every row is mandatory unless its title says *(optional component)* |
| Method | **Test:** = automated JUnit test (integration `T*` or unit `U*`; PLAN.md §13 defines each). **Check:** = an automated build/CI step that is not a JUnit test (image verification, hygiene grep, dependency check). **Inspection:** = review of code or build files. **Manual:** = a person runs the §5 procedure. A cell may list several |
| KVM / TCG | "under KVM" = `KvmMode.REQUIRE` on a host with usable `/dev/kvm`; "under TCG" = `KvmMode.DISABLE`. A timing limit marked *(scaled)* is multiplied by `tcgTimeoutFactor` (default 6) under TCG; one marked *(not scaled)* is the same everywhere; one with an explicit TCG value uses that |
| Reference guest | Unless a row says otherwise, integration tests run the reference guest (PLAN.md §10.3) with **SSH access as root** (`TestAccess.sshRoot()`, PLAN.md §11.1). Until milestone M3 the `:it` suite supplies it through `VmImage.fromPath` of the locally built qcow2; from M3 through `VmImage.referenceGuest()`. The criteria are identical either way |
| Black-box guest | Rows marked *(black-box)* run the agent-less, cloud-init-less guest (`${IMAGE_GUEST_BLACKBOX}`, PLAN.md §10.3), supplied the same way (`TestImages.blackBox()`) |
| Transports | *(transports)* on a row means the test is parametrised over `sshRoot`, `sshVmtcSudo` and `agent` (PLAN.md §11.1) and the criteria hold for each; *(agent)* means `GuestAccess.guestAgent()` only; otherwise SSH as root |
| Valid WebM | `ScreenRecording.duration()` > 0 **and** the file contains the Matroska codec-ID bytes `V_VP8` |
| Valid WAV | Starts with `RIFF`, has `WAVE` at offset 8, and its `data` chunk size is > 0 |
| Source | The original ask or decision the row comes from: R1–R13, D1–D20 (PLAN.md §2), G1–G8 / Tier 0–2 (PLAN.md §10.1), or a PLAN.md section. A risk of PLAN.md §15 is always written `PLAN §15 R<n>` (that table has its own R-numbering) |
| Met | A requirement is **met** when every test annotated with its ID passes in every CI job that executes that test. A test excluded from a job by its `kvm`/`tcg` tag or by task scoping (`compatTest`, `ociAuthTest`, `:lib:test`, `:lib:shadedSmokeTest`, `:junit5:extensionIT`) is not applicable there. The traceability report (§3.4) lists, per ID, the tests and the jobs that ran them |

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

**Standard VM evidence** — attached by the `:it` base class `VmIT` in `@AfterEach` for **every** `QemuVmContainer` the test registered (register **before** `start()`), pass or fail, including containers whose `start()` threw (then with whatever `getLogs()`, `describe()` and `timings()` hold), named `<n>-` + the name below when a test has more than one container:

| Attachment | Type | Content |
|------------|------|---------|
| `container.log` | `text/plain` | `getLogs()` of the runner container (entrypoint markers, QEMU stderr) |
| `serial.log` | `text/plain` | the guest's serial console (`/vm/out/serial.log`) |
| `vm-config.json` | `application/json` | `QemuVmContainer.describe()`: runner image, recorder image (if started), image source (kind, redacted locator, cache key), **guest access** (`none`/`ssh`/`guestAgent`; for SSH the user, port, `sudo`, host-key policy and the accepted host key; never a password or key), **readiness**, **port forwards** (guest port → container port → host port), requested and actual accelerator, KVM decision (device, annotation), CPUs, memory, screens, firmware, host port map, container id, network alias and labels |
| `timings.json` | `application/json` | `QemuVmContainer.timings()`: milliseconds from `start()` to `containerRunning`, `vmtcReady`, `sshBanner` (SSH readiness only), `guestReady` (absent for `bootOnly`), `started`; plus `guestReadyTimeoutMs`, `execTimeoutMs`, `tcgTimeoutFactorX1000`. After a failed `start()` only the keys reached so far |
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
5. Write `build/reports/requirements/traceability.md`: per ID → epic, feature, title, method, test classes#methods (or "NONE"), and the CI jobs that can run them, derived by this rule: source root `lib/src/test` or `test-support/src/test` → `unit` (class name `ShadedJarTest` → `unit` via `shadedSmokeTest`); `junit5/src/test` → `integration` (every matrix entry); `it/src/test`: class `KvmCompatIT` → `compat`, class `OciAuthIT` → `oci-auth`, `@Tag("kvm")` → `integration` KVM entries, `@Tag("tcg")` → `integration` TCG entry, otherwise every `integration` entry.

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
| REQ-PLAT-02 | Linux guests, tiered contract | The module shall run any x86-64 Linux guest with virtio drivers as a black-box (Tier 0 of PLAN.md §10.1) and shall need more from the guest only for the access the test configures (Tier 1a SSH, Tier 1b agent, Tier 2 cloud-init). Other guest OSes are out of scope | The black-box guest passes T38 with nothing but sshd (Tier 1a) and T39 (a) with nothing at all (Tier 0); the reference guest passes T2 under both transports (`sshRoot`, `agent`); every other `T*` test exercises one of them | standard VM evidence; `guest-info.txt` = `exec("/bin/sh","-c","cat /etc/os-release; uname -a")` (T2, T38) | Test: T2, T38, T39 | D2, D3, D12, D13, D15 |
| REQ-PLAT-03 | Java release | The library shall compile to Java `${JAVA_RELEASE}` bytecode | The class-file major version of `QemuVmContainer.class` equals `44 + ${JAVA_RELEASE}` (65 for Java 21) | `class-version.txt` | Test: U14 | D4 |

#### Feature ENG: Container engine

| ID | Title | Requirement | Acceptance criteria | Evidence | Method / verified by | Source |
|----|-------|-------------|---------------------|----------|----------------------|--------|
| REQ-ENG-01 | Rootless Podman via compat API | The library shall work against rootless Podman through its Docker-compatible API (`DOCKER_HOST`), with Ryuk disabled | In T2: `dockerClient.infoCmd().exec()` → `ServerVersion` or `OperatingSystem` contains `podman` (case-insensitive); `DOCKER_HOST` points under `$XDG_RUNTIME_DIR`; no container whose name starts with `testcontainers-ryuk` exists; T2 passes on both CI runners (Podman 4.9.x and 5.7.x at the time of writing) | `engine-info.json` (the `Info` object) | Test: T2 | R1 |
| REQ-ENG-02 | Rootful Podman | The library shall work against rootful Podman | T3 and T4 pass against a rootful `podman.socket` | §5 record | Manual (§5) | R1 |
| REQ-ENG-03 | No engine CLI from the library | Only the Testcontainers/docker-java client shall talk to the engine; the library shall never execute `podman`/`docker` | `lib/src/main` contains no `ProcessBuilder` and no `Runtime.getRuntime().exec` (the PLAN.md §11.5 grep list; the words `podman`/`docker` may appear in messages) | — | Check: `checkParameterHygiene` grep | PLAN §4 |

### 4.2 Epic: Runner Image & Acceleration

#### Feature IMG: Runner image

| ID | Title | Requirement | Acceptance criteria | Evidence | Method / verified by | Source |
|----|-------|-------------|---------------------|----------|----------------------|--------|
| REQ-IMG-01 | UBI base | The runner and recorder images shall be built `FROM ${UBI_IMAGE}` and stay UBI-based | In each image: `/etc/os-release` has `ID="rhel"` and `VERSION_ID` equal to the tag of `${UBI_IMAGE}` (`9.8`); `rpm -q redhat-release` succeeds; `rpm -qa 'rocky-*'` prints nothing | `verifyRunnerImages` output in the CI step log | Check: `verifyRunnerImages` checks 4, 9 | R2 |
| REQ-IMG-02 | Repo order UBI → EPEL → Rocky | Packages shall resolve from UBI first, then EPEL, then Rocky `${ROCKY_RELEASE}` as fallback, in both images | Config (both images): every `[section]` of `ubi.repo` has `priority=10`; `epel.repo` `priority=20`; every section of `rocky.repo` `priority=90` and the `excludepkgs=rocky-release* rocky-logos* rocky-gpg-keys* rocky-repos*` line; every added `.repo` has `gpgcheck=1`. Behaviour: `dnf -q repoquery --installed --qf '%{from_repo}' <pkg>` prints, in the runner, `rocky-fallback-appstream` for `qemu-kvm-core`, `epel9` for `tini`, an `ubi-9-*` repo id for `jq`; in the recorder, `rocky-fallback-appstream` for `gstreamer1-plugins-good`, `epel9` for `tini`, an `ubi-9-*` id for `gstreamer1` (packages the Containerfiles installed; base-layer packages print an opaque id) | `verifyRunnerImages` output | Check: `verifyRunnerImages` checks 7, 10 | R11 |
| REQ-IMG-03 | Runner and recorder roles | Two images shall exist: the runner (QEMU, no GStreamer), tagged `${IMAGE_RUNNER}:${VERSION}-${IMAGE_TAG_SUFFIX}`, and the recorder (GStreamer, no QEMU), tagged `${IMAGE_RECORDER}:${VERSION}-${IMAGE_TAG_SUFFIX}`, each labelled with its role | Label `${LABEL_PREFIX}.role` = `runner` / `recorder`; recorder: `gst-inspect-1.0` finds `rfbsrc`, `vp8enc`, `webmmux`, `videorate`, `videoconvert`; runner: `command -v gst-launch-1.0` fails | `verifyRunnerImages` output | Check: `verifyRunnerImages` checks 3, 6, 8 | D17 |
| REQ-IMG-04 | QEMU and devices present | The images shall provide `/usr/libexec/qemu-kvm` with TCG and every device the library uses | `-accel help` lists `tcg`; `-device help` lists `VGA`, `secondary-vga`, `ich9-intel-hda`, `hda-duplex`, `virtio-serial-pci`, `virtserialport`, `qemu-xhci`, `usb-storage`, `usb-kbd`, `usb-tablet`, `usb-mouse`, `pcie-root-port`, `virtio-blk-pci`, `virtio-net-pci`, `virtio-scsi-pci`, `scsi-cd`, `pc-dimm` | `verifyRunnerImages` output | Check: `verifyRunnerImages` checks 1, 2 | R5 |
| REQ-IMG-05 | PID 1 reaps orphans | PID 1 in the runner and in the recorder container shall be `tini`, so stopped recorders never remain zombies | Check: `podman image inspect --format '{{index .Config.Entrypoint 0}}'` = `/usr/bin/tini` for both images. Test: `cat /proc/1/comm` prints `tini` in the runner and in the recorder container; within 2 s *(not scaled)* of `ScreenRecording.close()` no `/proc/*/status` line in the recorder container reads `State:\tZ (zombie)` | `pid1.txt`, `process-states.txt` (the `/proc/*/status` scan) | Check: `verifyRunnerImages` check 5; Test: T24 | PLAN §1 rule a |
| REQ-IMG-06 | Recorder image is self-contained | The recorder image shall carry `vmtc-rec`, `gst-discoverer-1.0` and the GStreamer elements of PLAN.md §7.2, and shall need no ports and no QEMU | Check: `test -x /usr/local/bin/vmtc-rec`, `command -v gst-discoverer-1.0`, `vmtc-rec status nothing` prints `stopped`; `! command -v qemu-kvm`. Test: T43 inspects the running recorder container: no published ports, image = the recorder image | `verifyRunnerImages` output; `recorder-inspect.json` | Check: `verifyRunnerImages` checks 3, 11; Test: T43 | D17 |

#### Feature KVM: Acceleration

| ID | Title | Requirement | Acceptance criteria | Evidence | Method / verified by | Source |
|----|-------|-------------|---------------------|----------|----------------------|--------|
| REQ-KVM-01 | REQUIRE uses KVM | With `KvmMode.REQUIRE` on a host where `/dev/kvm` is usable, the VM shall run under KVM | `accelerator()` = `KVM`; QMP `query-kvm` → `enabled: true`; inspect shows `/dev/kvm` in `HostConfig.Devices` | `query-kvm.json`, `inspect.json` | Test: T1 (`kvm`) | R12 |
| REQ-KVM-02 | REQUIRE without KVM fails clearly | With `KvmMode.REQUIRE` where `/dev/kvm` is absent or unusable, `start()` shall throw `KvmUnavailableException` | On a host where `new KvmDetector().usable()` is false (asserted with `assumeFalse(usable)`), `start()` throws exactly `KvmUnavailableException` (not wrapped) whose message equals the PLAN.md §9.6 text for `!present()` or for `present() && !usable()`; U8 covers all three §9.6 messages | `exception.txt` (class + message) | Test: T20 (`tcg`), U8 | R12 |
| REQ-KVM-03 | PREFER is the default | `KvmMode.PREFER` shall be the default: KVM when usable, otherwise TCG with exactly one WARN per JVM carrying the applicable §9.6 message | Without `withKvm`: on a KVM host `accelerator()` = `KVM`; on a host without usable KVM `start()` succeeds, `accelerator()` = `TCG`, `HostConfig.Devices` null or empty, and `warnings.txt` holds the §9.6 message exactly once across two starts (U8 asserts once-per-JVM through the `Warnings` listener) | `inspect.json`, `warnings.txt` | Test: T1 `accelPreferDefault` (`kvm`), `accelPreferDefaultTcg` (`tcg`); U8 | R12 |
| REQ-KVM-04 | DISABLE forces TCG | With `KvmMode.DISABLE` the VM shall run under TCG and the container shall get no `/dev/kvm` device | `accelerator()` = `TCG`; `query-kvm.enabled` = false; `HostConfig.Devices` null or empty | `query-kvm.json`, `inspect.json` | Test: T1 (`tcg`), U8 | R12 |
| REQ-KVM-05 | Group-restricted /dev/kvm | For a `/dev/kvm` that is usable but not world read/write, the library shall add the annotation `run.oci.keep_original_groups=1` and shall never set `GroupAdd keep-groups` | Compat scenarios (PLAN-STEPS.md step 1.25): **A** (`0666`) → `accelerator()=KVM`, no annotation; **B** (`0660 root:kvm`, user in `kvm`, no `containers.conf`) → `Annotations` contains the key; Podman ≥ 5.7: KVM; Podman < 5.7: `PREFER` → `TCG` + the §9.6 group-restricted WARN, `REQUIRE` → `KvmUnavailableException` with that text; **C** (B + `containers.conf` annotation) → KVM on both. In every scenario `HostConfig.GroupAdd` is null or empty. U8 covers the decision table | `inspect.json`, `warnings.txt`, `scenario.txt` (scenario, Podman version, `ls -l /dev/kvm`, `id`) | Test: U8, T35 | R12, PLAN §1 rule h |
| REQ-KVM-06 | Fallback is explained | When KVM was intended but the container reported `VMTC_ACCEL tcg` or exited 3, the library shall log (PREFER) or throw (REQUIRE) the §9.6 group-restricted remediation text, followed by the container's own `VMTC_ERROR` line when there is one | U8 asserts the exact strings; T35 scenario B on Podman < 5.7 observes them live | `warnings.txt` / `exception.txt`, `container.log` | Test: U8, T35 | R12 |
| REQ-KVM-07 | TCG timeout scaling | Under TCG the library shall multiply these **defaults** by `tcgTimeoutFactor` (default 6.0): guest readiness 180 s, exec 60 s, hot-plug unplug 30 s. Explicit values given by the caller shall never be scaled. The 60 s container-start wait, recorder stop (10 s), QMP `quit` at stop (5 s) and the serial-log poll are host-side and never scaled | U9: with `Accelerator.TCG` and factor 6 the three effective defaults are 1080 s, 360 s, 180 s and the container-start wait stays 60 s; with factor 2.5 they scale accordingly; `withGuestReadyTimeout(10 s)` and `exec(2 s, …)` stay 10 s and 2 s | `timeouts.txt` (the computed table) | Test: U9 | PLAN §8.2 |

### 4.3 Epic: VM Lifecycle, Exec & Files

#### Feature LIFE: Lifecycle

| ID | Title | Requirement | Acceptance criteria | Evidence | Method / verified by | Source |
|----|-------|-------------|---------------------|----------|----------------------|--------|
| REQ-LIFE-01 | One VM per container | Each `QemuVmContainer` shall run exactly one QEMU process in one container | `inspectContainerCmd(getContainerId())` carries the session label; `execInContainer("pgrep","-c","-x","qemu-kvm")` prints `1` | `qemu-processes.txt` (`ps -eo pid,comm,args` in the container) | Test: T2 | PLAN §4 |
| REQ-LIFE-02 | Ready means the configured access answers | With SSH or agent access, `start()` shall return only when that access answers (SSH: banner + authentication + `exec("true")`; agent: `guest-ping`), within 30 s under KVM and 180 s under TCG for the reference guest on a warm host (runner image present, disk cached) | T2 *(transports: sshRoot, agent)* runs after the suite's warm-up start; `start()` to return ≤ 30 s (KVM) / ≤ 180 s (TCG) for each transport; immediately after return `exec("/usr/bin/true")` returns exit 0 on the first attempt | `timings.json` (per transport) | Test: T2 | R3, D15 |
| REQ-LIFE-03 | Base disk never written | The base disk shall never be written; each start shall use a fresh overlay | SHA-256 of the cache file is unchanged after the guest wrote 1 MiB to its root disk and ran `sync`; a second container from the same image does not see the file | `base-digest-before.txt`, `base-digest-after.txt`, `second-vm-ls.txt` | Test: T18 | PLAN §4 |
| REQ-LIFE-04 | Start failure is explained | If the container cannot start the VM, `start()` shall throw `VmtcException` (unwrapped) whose message contains the entrypoint's `VMTC_ERROR` text and the last 50 container-log lines | The runner image with `VmImage.baked()` but no `/vm/base/disk.img` → `VmtcException` with `no base disk`; U16: the message builder given 60 lines includes exactly lines 11–60, given 5 lines all 5 | `exception.txt`, `container.log` | Test: T29, U16 | PLAN §9.2 |
| REQ-LIFE-05 | Readiness timeout is explained | If the configured readiness is not reached within the guest-ready timeout, `start()` shall throw `GuestNotReadyException` (unwrapped) naming the readiness that failed and containing the last 50 serial-log lines (the §9.2 step 10 message builders); a readiness that saw the SSH banner but never authenticated shall throw `SshAuthException` instead (REQ-ACC-07) | (a) 16 MiB raw zero disk, SSH access, `withGuestReadyTimeout(10 s)`: `GuestNotReadyException` thrown ≤ 10 s + 10 s after `VMTC_READY` appeared in the container log and its message contains `ssh`; (b) the same disk with agent access → `GuestNotReadyException` containing `guest agent`; (c) reference guest, agent access, `withGuestReadyTimeout(1 s)`: the message contains a serial line matching `Linux version` or `login:` (serial output exists before the agent is up); U16 checks the message builders | `exception.txt`, `serial.log` | Test: T30, U16 | PLAN §9.2, D15 |
| REQ-LIFE-06 | stop() finalizes recordings | `stop()` shall finalize every open screen and audio recording before the container stops | A screen recording and an audio recording left open at `stop()` produce a valid WebM and a valid WAV at their host paths | `screen0.webm`, `audio.wav` + analysis | Test: T31 | PLAN §9.2 |
| REQ-LIFE-07 | Serial console access | Serial console output shall be available live (`withSerialLogConsumer`) and on demand (`saveSerialLog`) | `exec("/bin/sh","-c","echo VMTC_MARK_<uuid> > /dev/ttyS0")`: the consumer receives that line within 5 s *(not scaled)* of `exec` returning; the consumer also received at least one line emitted before `start()` returned (matching `Linux version`); `saveSerialLog(path)` returns `path` and the file contains the mark | `consumer-lines.txt`, `serial.log` | Test: T37 | PLAN §9.2 |
| REQ-LIFE-08 | Control ports on loopback | QMP, QGA and forwarded guest ports shall be published on `127.0.0.1` only, and no other container port shall be published | Inspect: `PortBindings` for `4444/tcp`, `4445/tcp` and every `10000+k/tcp` forward have `HostIp` `127.0.0.1`; `PublishAllPorts` is false; the set of published ports equals {4444, 4445, 5900..5900+n−1, 10000..10000+f−1} (T6 container A, agent access: f = 0; T6 container B, SSH access: f = 1, the implicit SSH forward; T42: f = 2) | `inspect.json` | Test: T6, T42 | PLAN §4, §8.5 |
| REQ-LIFE-09 | UEFI boot | `withFirmware(Firmware.UEFI)` shall boot a UEFI-capable guest under UEFI | The configured access (SSH as root) is ready; `exec("/usr/bin/test","-d","/sys/firmware/efi")` exits 0 | `efi.txt`, `screen0.png` | Test: T22 | PLAN §8.4 |
| REQ-LIFE-10 | Container labels | Every container started by `QemuVmContainer` shall carry labels `${LABEL_PREFIX}.managed=true`, `.version=<VmtcConstants.VERSION>`, `.session=<UUID fixed per JVM>` and `.created=<epoch seconds>` | Inspect shows all four; `.created` is within 60 s of the test's clock; two containers started in one JVM share `.session` | `inspect.json` (both containers) | Test: T2 | PLAN §9.2 |
| REQ-LIFE-11 | CPU and memory sizing | The VM shall boot with the configured vCPUs and memory and expose the configured hot-plug ceilings | `withCpus(3).withMaxCpus(6).withMemoryMb(1536).withMemoryHotplug(2,"6G")`: guest `nproc` = 3; `MemTotal` between 1300 and 1536 MiB; QMP `query-hotpluggable-cpus` has 6 entries; `query-memory-size-summary` → `base-memory` = 1536 MiB. `withMemoryMb(mb)` with `maxmem` smaller than `mb` → `IllegalArgumentException` at `start()` | `nproc.txt`, `meminfo.txt`, `hotpluggable-cpus.json`, `memory-size-summary.json` | Test: T36 | PLAN §9.2 |
| REQ-LIFE-12 | Protocol accessors | `qmp()`, `guestAgent()` (under agent access) and `screen(i).vnc()` shall expose working endpoints; `guestAgent()` under any other access shall throw `IllegalStateException` | After `start()`: `qmp().execute("query-status")` → `status: running`; with agent access `guestAgent().ping()` succeeds, with SSH access `guestAgent()` throws `IllegalStateException`; `vnc().host()` equals `getHost()` when the bind address is `127.0.0.1` or `0.0.0.0`, else the configured address; `vnc().port()` equals `getMappedPort(5900+i)`; `vnc().uri()` is `vnc://<host>:<port>` | `query-status.json`, `vnc-endpoints.txt` | Test: T6 | PLAN §9.2, §9.7 |

#### Feature EXEC: Run commands

| ID | Title | Requirement | Acceptance criteria | Evidence | Method / verified by | Source |
|----|-------|-------------|---------------------|----------|----------------------|--------|
All EXEC and FILE rows hold for every transport (SSH as root, SSH as a sudo user, agent); the tests are *(transports)*-parametrised and the evidence is attached once per transport.

| REQ-EXEC-01 | Exit code, stdout, stderr | `exec(argv)` shall run a program in the guest and return exit code, stdout and stderr, through whichever guest access is configured | `/usr/bin/uname -m` → exit 0, stdout `x86_64\n`; `/bin/sh -c 'echo out; echo err >&2; exit 3'` → (3, `out\n`, `err\n`) | `exec-uname.txt`, `exec-streams.txt` | Test: T3 *(transports)* | R3, D15 |
| REQ-EXEC-02 | Env and stdin | `exec(ExecRequest)` shall pass environment variables and stdin bytes | `ExecRequest` with env `FOO=bar`, stdin `hi`, argv `/bin/sh -c 'echo $FOO; cat'` → stdout `bar\nhi` | `exec-env-stdin.txt` | Test: T3 *(transports)* | R3 |
| REQ-EXEC-03 | Shell convenience | `execShell(script)` shall run `/bin/sh -c script` | `execShell("echo a \| tr a b")` → stdout `b\n`, exit 0 | `exec-shell.txt` | Test: T3 *(transports)* | R3 |
| REQ-EXEC-04 | Timeout kills | A command exceeding its timeout shall be killed and raise `GuestExecTimeoutException`, under every transport | `exec(Duration.ofSeconds(2), "/usr/bin/sleep", "61")` throws within 2 s + 5 s *(not scaled)*; afterwards `exec("/usr/bin/pgrep","-x","sleep")` exits 1 (`pgrep -f` would match the SSH wrapper itself, PLAN.md §3.10) | `exception.txt`, `pgrep.txt` | Test: T32 *(transports)* | R3, PLAN §1 rule q |
| REQ-EXEC-05 | Truncation flags | Output truncated at the 16 MiB per-stream cap shall be reported through `stdoutTruncated` / `stderrTruncated` by both transports | Fake QGA returning `out-truncated: true` → `stdoutTruncated()` true; `err-truncated` likewise (U2); `SshTransport` buffer fed 16 MiB + 1 byte → `stdoutTruncated()` true and exactly 16 MiB kept (U19) | — | Test: U2, U19 | R3 |
| REQ-EXEC-06 | Signals and bad UTF-8 | A command killed by a signal shall report `signal()` and `exitCode() == -1`; non-UTF-8 output shall decode with U+FFFD replacement | `exec("/bin/sh","-c","kill -9 $$")` → `signal() == 9`, `exitCode() == -1` (T3, each transport); U2: stdout bytes `0xFF 0x41` decode to `�A`; U19: SSH `exit-signal KILL` → 9 / −1 | `exec-signal.txt` | Test: T3, U2, U19 | R3 |

#### Feature FILE: File copy

| ID | Title | Requirement | Acceptance criteria | Evidence | Method / verified by | Source |
|----|-------|-------------|---------------------|----------|----------------------|--------|
| REQ-FILE-01 | Byte-exact round trip | `copyToVm` and `copyFromVm` shall copy files byte-exactly in both directions, through whichever guest access is configured (SFTP or agent file RPCs) | Random 5 MB and 50 MB files: SHA-256 equal after host → guest → host; guest-side `sha256sum` equals the host digest; for each transport | `copy-digests.txt` (sizes, host and guest digests, durations and MB/s, per transport) | Test: T4 *(transports)* | R4, D15 |
| REQ-FILE-02 | Parents are created | `copyToVm` shall create missing parent directories | `exec("/usr/bin/test","!","-e","/tmp/vmtc")` exits 0 first; copy to `/tmp/vmtc/a/b/c.bin` succeeds; guest `sha256sum` matches | `copy-parents.txt` | Test: T4 *(transports)* | R4 |
| REQ-FILE-03 | Byte arrays | `copyToVm(byte[], …)` and `readFromVm` shall round-trip byte arrays, including empty ones | 0-byte and 3-byte arrays round-trip equal | `copy-bytes.txt` | Test: T4 *(transports)* | R4 |

#### Feature ACC: Guest access and readiness

| ID | Title | Requirement | Acceptance criteria | Evidence | Method / verified by | Source |
|----|-------|-------------|---------------------|----------|----------------------|--------|
| REQ-ACC-01 | SSH access (primary) | `withGuestAccess(GuestAccess.ssh(credentials[, options]))` shall drive `exec` and file copy over SSH through a slirp port forward of the SSH port, with `Readiness.ssh()` as the default readiness | T38: the black-box guest starts with SSH root access, `start()` returns ≤ 30 s (KVM) / ≤ 180 s (TCG) after the warm-up, `exec("/usr/bin/id","-un")` → `root`, a 50 MB copy round-trips; U18: `ssh(...)` yields `SshHandshake` readiness and an implicit forward of the SSH port at k = 0 | `timings.json`, `exec-id.txt`, `copy-digests.txt`, `vm-config.json` | Test: T38, U18 | D15, D16 |
| REQ-ACC-02 | No agent required | A guest without `qemu-guest-agent` and without cloud-init shall be fully drivable over SSH | T38 on the black-box guest: `rpm -q qemu-guest-agent` exits 1, `systemctl is-active cloud-init` → `inactive`, `/etc/cloud/cloud-init.disabled` exists, and exec, copy, screenshot and keyboard all work | `agent-absent.txt`, `screen0.png` | Test: T38 | D15, G8 |
| REQ-ACC-03 | none() is the default and fails fast | Without `withGuestAccess`, the access shall be `GuestAccess.none()`: `start()` returns at `VMTC_READY`, `timings()` has no guest-ready entry, and `exec`, `execShell`, `copyToVm`, `copyFromVm`, `readFromVm` and `forceModeset()` throw `GuestAccessUnavailableException` without any network call | T30 (d) and T39 (a): `start()` returns within 5 s *(not scaled)* of `VMTC_READY` on a disk that never boots an OS (zeros) and on the black-box guest; each listed method throws within 100 ms; `screenshot()` still works; U18 asserts the defaults | `exception.txt`, `timings.json`, `screen0.png` | Test: T30, T39, U18 | PLAN §1 rule m, D15 |
| REQ-ACC-04 | Readiness is pluggable | `withReadiness` shall accept `bootOnly()`, `guestPort(port[, probe])`, `ssh()` and `guestAgent()`; `ssh()`/`guestAgent()` shall require the matching access (`IllegalArgumentException` at `start()`); `guestPort` shall count a port ready only after application data is received (never after a bare connect) | T39 (b): `withGuestPortForward(22)` + `guestPort(22)` with `none()` access returns only after an `SSH-` banner is readable on `forwardedPort(22)`, later than (a) by at least 5 s under KVM; T39 (c): SSH access + `bootOnly()` returns at `VMTC_READY` and the first `exec` connects lazily; T30 (a)/(b): `ssh()`/`guestAgent()` readiness time out on a disk that never boots; U18: the two invalid access combinations and `guestPort` of an unforwarded port throw | `timings.json` (a, b, c), `banner.txt` | Test: T30, T39, U18 | PLAN §1 rule n, §8.5 |
| REQ-ACC-05 | SSH readiness waits for authentication | `Readiness.ssh()` shall return only after the banner was received, the key exchange and authentication succeeded and `exec("true")` returned 0 | T38: `timings.json` has `sshBanner` ≤ `guestReady`; `exec` succeeds on the first attempt after `start()` | `timings.json` | Test: T38 | PLAN §9.2 step 10 |
| REQ-ACC-06 | sudo support | With `SshOptions.sudo(true)` every `exec` (including `forceModeset`'s script) shall run under `sudo -n`; with `sudo(false)` commands run as the SSH user | T3 *(sshVmtcSudo)*: `id -u` → `0`; T40: `id -un` → `root` with sudo and `vmtc` without; `screen(1).forceModeset()` succeeds with sudo and fails with a `VmtcException` containing `Permission denied` without | `exec-id.txt`, `fb-activate.txt`, `exception.txt` | Test: T3, T40 | D16 |
| REQ-ACC-07 | Authentication failure is clear and redacted | Wrong or rejected SSH credentials shall raise `SshAuthException` (unwrapped) within the guest-ready timeout; its message shall name the user and never contain the password, passphrase or key material; `SshCredentials.toString()` and `describe()` shall be redacted the same way | T41 (a): wrong root password → exactly `SshAuthException` whose message contains `root` and not `nope`, thrown after the banner was seen (`timings.json` has `sshBanner`); U18: `toString()` of password and key credentials prints the user only and `describe()` carries no secret | `exception.txt`, `timings.json`, `vm-config.json` | Test: T41, U18 | D16 |
| REQ-ACC-08 | Key types | Private-key authentication shall work with RSA, ECDSA and Ed25519 keys in OpenSSH format, with no Bouncy Castle registered as a JCE provider and no `org.bouncycastle` artifact in `:lib`'s dependencies (Testcontainers' own relocated copy is irrelevant) | T41 (b): one container per key type starts with key auth as root; the relocated `SecurityUtils.isBouncyCastleRegistered()` is false and `isEDDSACurveSupported()` true in the test JVM (attached) | `key-types.txt`, `security-facts.txt` | Test: T41 | D16 |
| REQ-ACC-09 | SSH command construction | The SSH transport shall quote every argument, pass `env` through `env(1)`, prefix `sudo -n` when configured and wrap the command in `timeout -k 1 <seconds>` (never `--foreground` or `-s KILL`); `execShell` shall run `/bin/sh -c <script>`; it shall report a timeout when `timeout` exits 124 or when an `exit-signal` arrives after the deadline | U19 table: `['a','b c']` → `timeout -k 1 60 'a' 'b c'`; env `K=V` → `env 'K=V'` before argv; `sudo(true)` → `sudo -n ` prefix; single quotes in arguments become `'\''`; `exit-status 124` → `GuestExecTimeoutException`; `exit-signal KILL` after the deadline → `GuestExecTimeoutException`, before it → `signal 9`/`exitCode −1`; `exit-status 137` → `exitCode 137` | `command-table.txt` | Test: U19 | PLAN §9.4.2, §3.10 |
| REQ-ACC-10 | Agent access is optional and explicit | `GuestAccess.guestAgent()` shall provide the agent behaviour of PLAN.md §9.2 steps 10–12 (readiness by `guest-ping`, RPC-filter and SELinux checks), and those checks shall run **only** under agent access; `describe()` shall record `agentChecks` = `run` or `skipped` | T19 and T25 pass under agent access with `describe()` → `agentChecks: run`; T38 under SSH shows `agentChecks: skipped` and `access: ssh`; T39 (a): `guestAgent()` under `none()` throws `IllegalStateException` | `guest-info.json` (T19), `vm-config.json` (T25, T38) | Test: T19, T25, T38, T39 | D15, G2–G4 |

#### Feature NET: Guest port forwards

| ID | Title | Requirement | Acceptance criteria | Evidence | Method / verified by | Source |
|----|-------|-------------|---------------------|----------|----------------------|--------|
| REQ-NET-01 | Forwarded guest ports | `withGuestPortForward(guestPort)` shall forward a guest TCP port to a host port on `127.0.0.1`, returned by `forwardedPort(guestPort)` | T42: an HTTP server started in the guest on 8080 answers `GET /` with 200 through `forwardedPort(8080)`; T38: `forwardedPort(22)` returns an `SSH-` banner | `http-get.txt`, `banner.txt`, `inspect.json` | Test: T38, T42 | PLAN §8.5 |
| REQ-NET-02 | Forward numbering and limits | Forwards shall occupy container ports 10000+k in configuration order with the implicit SSH forward first regardless of the order of builder calls; duplicates shall be ignored; ports outside 1..65535 shall raise `IllegalArgumentException`; `forwardedPort` of an unknown port shall raise `IllegalArgumentException` | U18: `ssh` + `withGuestPortForward(8080)` + `withGuestPortForward(22)` → `VM_PORT_FORWARDS=22:10000,8080:10001` and exposed ports {…, 10000, 10001}; `withGuestPortForward(0)` / `(70000)` throw; T39/T42: `forwardedPort(9999)` throws | `forwards.txt`, `exception.txt` | Test: U18, T39, T42 | PLAN §8.5 |
| REQ-NET-03 | Forwards are loopback-only and recorded | Forwarded ports shall be published on `127.0.0.1` and listed in `describe()` as guest port → container port → host port | T42 inspect: `10000/tcp`, `10001/tcp` bound to `127.0.0.1`; `vm-config.json` lists both triples | `inspect.json`, `vm-config.json` | Test: T42, U18 | PLAN §8.5 |

### 4.4 Epic: Disk Sources

#### Feature DISK: VM disk sources and cache

| ID | Title | Requirement | Acceptance criteria | Evidence | Method / verified by | Source |
|----|-------|-------------|---------------------|----------|----------------------|--------|
| REQ-DISK-01 | Host path, qcow2 or raw | `VmImage.fromPath` shall accept a qcow2 or raw file as the base disk | qcow2: the reference guest boots (T18); raw: a raw file is detected as `raw` and QEMU starts (`VMTC_READY` in the container log, T30) | `vm-config.json`, `container.log` | Test: T18, T30 | R5 |
| REQ-DISK-02 | HTTP source boots | `VmImage.fromUrl` shall boot a disk downloaded over HTTP(S) | In-JVM `HttpServer` serving the reference qcow2 → boots (T18); the stock Rocky base image served the same way → boots with a seed (T25) | `download.txt` (URL, bytes, seconds, digest) | Test: T18, T25 | R13 |
| REQ-DISK-03 | OCI source boots | `VmImage.fromOci` / `fromContainerDisk` shall boot a disk published as a containerDisk image (PLAN.md §10.4), by tag and by digest | Reference guest by tag (T18) and `@sha256:` (T28) → boots | `vm-config.json`, `pull.txt` (image id, digest) | Test: T18, T28 | R13 |
| REQ-DISK-04 | Exactly one disk file | A containerDisk with zero or more than one regular file under `/disk/` shall be rejected with `IllegalArgumentException("containerDisk must contain exactly one file under /disk/")` | Fixture images with 0 files (`FROM scratch` + `WORKDIR /disk`) and with 2 files → that exception (unwrapped) at `start()` | `exception.txt` | Test: T18 | R13 |
| REQ-DISK-05 | Baked disk | `VmImage.baked()` shall boot a disk baked into a derived runner image at `/vm/base/disk.img`, with no bind mount | Derived image `FROM <runner image>` + `COPY disk.qcow2 /vm/base/disk.img` → boots; inspect shows no mount with destination `/vm/base/disk.img` | `inspect.json` | Test: T18 | PLAN §9.3.4 |
| REQ-DISK-06 | Cache reuse (path, URL) | `fromPath` and `fromUrl` sources shall be materialised once and reused | Second `start()` with the same source: the cache file's inode and mtime (except the touch) are unchanged; `fromPath` performs no second copy (U3 counts); `fromUrl` with a SHA-256 sends **no** HTTP request (server request log empty) | `cache-listing.txt` (before/after: name, inode, size), `http-requests.txt` | Test: T18, U3 | PLAN §9.3.1 |
| REQ-DISK-07 | Concurrent materialisation | Concurrent writers of one cache entry shall produce one complete file | (a) two threads in one JVM; (b) two JVMs (`ProcessBuilder` launching a helper `main`) materialising the same key: exactly one `<key>.img`, identical content, both callers get it, no `*.tmp-*` left | `cache-listing.txt` | Test: U3 | PLAN §9.3.1 |
| REQ-DISK-08 | Copy, never link | `fromPath` shall copy the user's file into the cache, never hard-link it | Cache file inode ≠ source inode (`Files.getAttribute(p, "unix:ino")`) | — | Test: U3 | PLAN §9.3.1 |
| REQ-DISK-09 | Prune | `VmImageCache.prune(olderThan)` shall delete only entries not used within `olderThan`: the `<key>.img`, its `<key>.meta.json` and `<key>.lock`, and the `index.json` rows pointing at it | Entries touched by use survive; untouched older entries and their side files are deleted; `index.json` no longer references them | `cache-listing.txt` | Test: U3 | PLAN §9.3.1 |
| REQ-DISK-10 | SELinux hosts | On SELinux-enforcing hosts the cache bind shall be relabelled (`:z`) and the VM shall boot | U3: with an injected `selinuxEnforcing=true` the bind uses `SelinuxContext.SHARED`, with `false` none. Boot: T3 and T4 pass on a Fedora/RHEL host in enforcing mode | `bind.txt`; §5 record | Test: U3; Manual (§5) | PLAN §15 R3 |
| REQ-DISK-11 | referenceGuest() / blackBoxGuest() resolution | `VmImage.referenceGuest()` shall resolve to the `guestImage` override if set, else `${IMAGE_GUEST}:${VERSION}`; `VmImage.blackBoxGuest()` likewise with `blackBoxGuestImage` and `${IMAGE_GUEST_BLACKBOX}:${VERSION}` | U6 asserts all four resolutions; T23 boots the reference guest, T38 (from M3) the black-box guest | `vm-config.json` | Test: U6, T23, T38 | D3, D19 |
| REQ-DISK-12 | Cache reuse (OCI) | `fromOci` shall reuse the cache entry while the image ID is unchanged and create a new entry when a tag moves | Same tag twice → same inode, no new `<key>.img`; after the tag moves → one more `<key>.img` | `cache-listing.txt`, `pull.txt` | Test: T28 | PLAN §9.3.6 |
| REQ-DISK-13 | No backing files | `fromPath` shall reject a qcow2 whose header has a non-zero `backing_file_offset` (read by `Qcow2Header`, no `qemu-img` on the host) with `IllegalArgumentException` naming the backing file | Fixture overlay made with `qemu-img create -b` in a runner container → that exception from `start()` (unwrapped); the fixture's `qemu-img info --output=json` is attached to show the backing file the library detected | `exception.txt`, `qemu-img-info.json` (fixture) | Test: T18 | PLAN §9.3.1 |
| REQ-DISK-14 | Sparse copies | `fromPath` shall preserve sparseness when copying into the cache: zero-filled regions of the source become holes in the cache file | U3: a 64 MiB raw file with 1 MiB of random data at offset 0 and zeros elsewhere → cache file content identical, apparent size 64 MiB, allocated blocks (`stat -c '%b %B'`, blocks × block size) below 4 MiB; a source whose last 8 MiB are zeros keeps the 64 MiB length | `cache-listing.txt` (apparent and allocated sizes) | Test: U3 | PLAN §9.3.1 |

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
| REQ-OCI-07 | Local fallback | If the pull fails but the image exists locally, `start()` shall use the local image and emit exactly one WARN containing `using local image <ref>` per such `start()`; without a local image the pull error is reported (REQ-OCI-04) | T18 (c) with a never-pushed local tag boots and that method's `warnings.txt` has the line exactly once; T27 (a) with the image removed throws | `warnings.txt`, `pull.txt` | Test: T18, T27 | PLAN §9.3.3 |

### 4.5 Epic: Display, VNC & Keyboard

#### Feature DISP: Screens and screenshots

| ID | Title | Requirement | Acceptance criteria | Evidence | Method / verified by | Source |
|----|-------|-------------|---------------------|----------|----------------------|--------|
| REQ-DISP-01 | 1..4 screens | `withScreens(n)` shall accept 1..4 (default 1) and reject other values with `IllegalArgumentException` | Unit validation for 0, 1, 4, 5 | — | Test: U9 | D5 |
| REQ-DISP-02 | Per-screen screenshots | `screen(i).screenshot()` shall capture screen *i* | n=2: head 0 is 1280×800 with ≥ 0.5 % non-black pixels (console text); head 1 before modeset is 640×480; `screenshot(Path)` writes a PNG that decodes to the same dimensions | `screen0.png`, `screen1-before.png` | Test: T5 | R6 |
| REQ-DISP-03 | forceModeset | `forceModeset()` shall make a secondary screen scan out on a console-only guest and return only once the new mode is visible; it shall work over SSH as root or with `sudo(true)` and over the agent, and shall throw `GuestAccessUnavailableException` under `none()` | Head 1 is 1280×800 in a `screenshot()` taken **immediately** after `forceModeset()` returns (no sleep) — T5 (SSH root), T40 (SSH + sudo); `forceModeset()` on head 0 is a no-op that does not throw under SSH/agent access; T39 (a): under `none()` even `screen(0).forceModeset()` throws (the access check precedes the head-0 short-circuit) | `screen1-after.png`, `fb-activate.txt` (the script's output) | Test: T5, T39, T40 | R6, PLAN §15 L2 |

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
| REQ-REC-01 | One WebM per screen | `screen(i).startRecording(path)` shall record that screen's VNC output to one VP8 WebM file per recording, from a recorder container on the same container network (D17) | n=2, both screens recorded while the guest prints to `/dev/tty1`; wall time = from `startRecording()` return to `close()` call; each file is a valid WebM, `width()` = 1280 (after `forceModeset` on head 1), \|`duration()` − wall\| < 1 s | `screen0.webm`, `screen1.webm` (play inline), `recording-stats.txt` | Test: T8 | R8 |
| REQ-REC-02 | Fast stop | `ScreenRecording.close()` shall return within 2 s *(not scaled)*, including the copy from the recorder container to the host | Measured from `close()` call to return for each recording in T8 | `recording-stats.txt` | Test: T8 | R8 |
| REQ-REC-03 | Static screens record | A static (unchanging) screen shall still produce a full-length recording | 10 s unchanged screen → 9 s ≤ `duration()` ≤ 11 s | `screen0-static.webm` | Test: T10 | R8, PLAN §1 rule b |
| REQ-REC-04 | — | **Retired** (D17): recording no longer depends on a runner variant; the recorder is a separate container that is always available | — | — | — | D6, D17 |
| REQ-REC-05 | — | **Retired** (D8): VNC passwords are out of scope, so there is no secret to keep off command lines | — | — | — | D8 |
| REQ-REC-06 | Recorder failures are reported | A recorder that does not start, that exits before `close()`, or whose file is invalid, shall raise `RecordingException` including the recorder's output | U15: `vmtc-rec start` output without `VMTC_REC_STARTED` → `RecordingException` with that output; `VMTC_REC_INVALID` + log lines on stop → `RecordingException` containing the lines; `VMTC_REC_DIED` + log lines → `RecordingException`; `VMTC_REC_KILLED` then `VMTC_REC_OK` → success plus one WARN; `VMTC_REC_OK duration=12.346 width=1280 height=800` parses | `parse-table.txt` | Test: U15 | PLAN §9.8 |
| REQ-REC-07 | Options and concurrency | `RecordingOptions` shall control frame rate and bitrate, and several recordings of one screen shall run at once | `RecordingOptions.fps(5)` → the file's video track reports 5 fps (`gst-discoverer-1.0` in the recorder container: `Frame rate: 5/1`); two simultaneous recordings of screen 0 both produce valid WebMs | `screen0-5fps.webm`, `discoverer.txt`, `screen0-a.webm`, `screen0-b.webm` | Test: T8 | PLAN §9.8 |
| REQ-REC-08 | One recorder container per VM | The first recording of a VM shall start exactly one recorder container from the recorder image on the VM's network, labelled `${LABEL_PREFIX}.role=recorder` and `.vm=<vm container id>`, with no published ports; later recordings of that VM shall reuse it; a second VM shall get its own | T43: after `startRecording`, `listContainersCmd` with the label filter returns one container whose `Image` is the recorder image and whose `NetworkSettings` share the VM's network; a second recording adds none; a second VM adds one; `vmtc-rec status <name>` in it prints `running` during and `stopped` after `close()` | `recorder-inspect.json`, `containers.json`, `rec-status.txt` | Test: T43 | D17, PLAN §9.8 |
| REQ-REC-09 | Recorder stops with the VM | `stop()` shall stop and remove the recorder container after finalising open recordings | T31 and T43: after `stop()`, no container with `.vm=<id>` exists | `containers-after-stop.json` | Test: T31, T43 | D17 |

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
| REQ-GUEST-04 | Reference guest without seed | The reference guest `${IMAGE_GUEST}:${VERSION}` shall boot with no seed and meet Tiers 1a and 1b plus G5–G6 | No seed; ready within REQ-LIFE-02's limit under `sshRoot`, `sshVmtcSudo` and `agent`; `exec` works under each; under the agent `id -Z` shows `virt_qemu_ga_t` yet `ls /lib/modules/$(uname -r)` succeeds; `amixer -c 0 get Master` shows `[on]` | `amixer.txt`, `id-Z.txt`, `timings.json` (per transport) | Test: T23 *(transports)* | D3, D19 |
| REQ-GUEST-05 | containerDisk format | The reference guest shall be published in the containerDisk format of PLAN.md §10.4 | Check in `buildGuestDisk`: `podman image inspect` shows one layer and empty `Config.Cmd`/`Entrypoint`; the tar from `podman cp` of `/disk/` has one regular file, uid/gid 107. Test: `fromContainerDisk` boots it (T18) | `buildGuestDisk` output | Check: `buildGuestDisk`; Test: T18 | R13 |
| REQ-GUEST-06 | Seed builder | `CloudInitSeed.builder()` shall start empty; `stockRocky()` shall add the §10.2 built-ins; `packages` shall merge into one `dnf` line placed right after `update-ca-trust`; `runcmd` lines shall follow in call order; `writeFile`/`trustCaFile` shall emit `write_files` entries (and `trustCaFile` adds `update-ca-trust`); `rootPassword` shall emit the `chpasswd` block; `build()` shall always append `echo VMTC_SEED_DONE > /dev/ttyS0` last | U10 asserts each rule on generated YAML; T25 adds `writeFile("/etc/vmtc-test","x\n")` and reads the file back after start | `user-data.yaml`, `exec-readfile.txt` | Test: U10, T25 | PLAN §10.2 |
| REQ-GUEST-07 | Seeded start waits for the seed | With a seed, `start()` shall return only after the guest printed `VMTC_SEED_DONE`, whatever the readiness | `saveSerialLog` immediately after `start()` contains `VMTC_SEED_DONE`; `exec` succeeds on the first attempt; `amixer -c 0 get Master` shows `[on]` | `serial.log`, `timings.json` | Test: T25 | PLAN §9.2 |
| REQ-GUEST-08 | Black-box guest | The black-box guest `${IMAGE_GUEST_BLACKBOX}:${VERSION}` shall be built from the same pipeline as the reference guest with `qemu-guest-agent` removed and cloud-init disabled, and shall boot with no seed as a Tier 0/1a guest | `buildBlackBoxGuestDisk`: customize serial log contains `AGENT_REMOVED` and `VMTC_CUSTOMIZE_DONE`; format checks as REQ-GUEST-05. T38: boots, SSH ready, `rpm -q qemu-guest-agent` exits 1, `systemctl is-active cloud-init` prints `inactive` (exit status 3) and `/etc/cloud/cloud-init.disabled` exists | `buildBlackBoxGuestDisk` output; `agent-absent.txt`, `timings.json` | Check: `buildBlackBoxGuestDisk`; Test: T38 | D19 |
| REQ-GUEST-09 | Shipped guests' SSH identities | Both shipped guests shall accept `root` and `vmtc` by password `vmtc` and by the build's test key, `vmtc` with passwordless sudo, with `PermitRootLogin yes` and `PasswordAuthentication yes` | T23 *(transports)* on the reference guest and T38 (root password, black-box), T40 (`vmtc` key + `sudo -n`), T41 (b) (root key, three types) succeed; T38 attaches `sshd -T` from the guest showing `permitrootlogin yes` and `passwordauthentication yes` | `sshd-T.txt` (T38), `exec-id.txt` | Test: T23, T38, T40, T41 | D19 |

#### Feature CFG: Configuration

| ID | Title | Requirement | Acceptance criteria | Evidence | Method / verified by | Source |
|----|-------|-------------|---------------------|----------|----------------------|--------|
| REQ-CFG-01 | Overrides replace defaults only | Each runtime override key (PLAN.md §9.1) shall be settable as system property `${SYSPROP_PREFIX}.<key>` and environment variable `${ENV_PREFIX}<KEY>`; precedence **builder call > system property > environment variable > default** (D9). An unparsable override value shall raise `IllegalArgumentException` naming the key at `start()` | Table-driven over every key and every precedence level (environment injected through a `Map` seam); `kvm=maybe`, `tcgTimeoutFactor=0`, `cleanup=yes` → exception naming the key | `precedence-table.txt` | Test: U6 | PLAN §9.1 |
| REQ-CFG-02 | Builder ranges | Builder arguments outside their documented ranges shall raise `IllegalArgumentException` at the builder call | hot-plug ports 0..8; CPUs boot ≥ 1 and max ≥ boot; memory slots ≥ 0; `withQemuArgs` arg containing whitespace; recording fps 1..30; `withTcgTimeoutFactor` > 0; `withGuestPortForward` 1..65535; `SshOptions.port` 1..65535 (screens: REQ-DISP-01) | — | Test: U9, U18 | PLAN §9.2 |
| REQ-CFG-03 | Extra QEMU arguments | `withQemuArgs(String...)` shall append the arguments to the QEMU command line | `withQemuArgs("-name","vmtc-extra")` → QMP `query-name` returns `vmtc-extra` | `query-name.json` | Test: T1 | PLAN §9.2 |

#### Feature ERR: Errors

| ID | Title | Requirement | Acceptance criteria | Evidence | Method / verified by | Source |
|----|-------|-------------|---------------------|----------|----------------------|--------|
| REQ-ERR-01 | One unchecked hierarchy | Every exception type declared in the library's public packages shall be unchecked and extend `VmtcException`; the library shall not throw bare `RuntimeException`/`UncheckedIOException` | U14: reflection over the public exception types; Check: `lib/src/main` has no `throw new RuntimeException(` or `throw new UncheckedIOException(` | `exception-types.txt` | Test: U14; Check: `checkParameterHygiene` grep | PLAN §9.11 |
| REQ-ERR-02 | QMP errors carry class and desc | `QmpException` shall carry the QMP error `class` and `desc` | Fake QMP error → both fields set | — | Test: U1 | PLAN §9.11 |
| REQ-ERR-03 | start() throws the real exception | `QemuVmContainer.start()` shall rethrow the library's own `VmtcException` subclass directly, never wrapped in Testcontainers' `ContainerLaunchException` | T20, T29, T30, T41, T19 assert `isExactlyInstanceOf` (`KvmUnavailableException`, `VmtcException`, `GuestNotReadyException`, `SshAuthException`, `GuestCommandDisabledException`); T18 (g) and T36 get an unwrapped `IllegalArgumentException` from `start()`; U16: a `configure()` throwing `KvmUnavailableException` surfaces as that type from `start()` | `exception.txt` | Test: T19, T20, T29, T30, T41, U16 | PLAN §9.2 |

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
| REQ-BLD-03 | Dependency surface | `:lib`'s `api` dependency shall be Testcontainers only, its `implementation` dependencies exactly the PLAN.md §9.1 list, and its `shaded` dependencies exactly `sshd-core`, `sshd-sftp` and `eddsa`; no configuration shall contain Bouncy Castle | `checkDependencies` compares the three direct dependency sets with the list and fails on any difference or on any `org.bouncycastle` artifact | `checkDependencies` output | Check: `checkDependencies` | PLAN §9.1, D16 |
| REQ-BLD-04 | Automatic module name | The published jar shall carry the manifest attribute `Automatic-Module-Name` = `${BASE_PACKAGE}` and shall contain no `module-info.class` (D20) | U20 reads the shaded jar's manifest and entry list; U14 finds no `module-info.class` beside `QemuVmContainer.class` | `jar-listing.txt`, `module-name.txt` | Test: U14, U20 | PLAN §9.1, D20 |
| REQ-BLD-05 | Weekly rebuild | CI shall rebuild the runner images against fresh repositories weekly | `ci.yml` contains `schedule:` with `cron: '0 6 * * 1'`; the scheduled run builds images with `--no-cache` | — | Check: `checkParameterHygiene` grep of `ci.yml` | R1, PLAN §15 R1 |
| REQ-BLD-06 | Traceability enforced | Requirement traceability (§3.4) shall be enforced in CI | `checkRequirementTraceability` runs in the `unit` job and fails the build on violations; from M6 with `traceabilityRequireFullCoverage=true` | `traceability.md` (uploaded) | Check: CI `unit` job | this document |
| REQ-BLD-07 | Allure report with evidence | Every CI job that runs tests shall produce an Allure report organised by epic/feature/story with the evidence of §3.3 attached | After the test step, `./gradlew allureReport` succeeds; `build/allure-report/index.html` exists; `**/build/allure-results/*-attachment.*` includes at least one `.png`, one `.webm` and one `.wav` (integration jobs only; `unit` has text/JSON attachments); both artifacts are uploaded `if: always()` | the report itself | Check: CI step | this document |
| REQ-BLD-08 | SSH library is shaded | The published `${ART_LIB}` jar shall contain the SSH library relocated under `${BASE_PACKAGE}.shaded`, with merged service files, and **every** `.class` entry under `${BASE_PACKAGE}`'s path (no unrelocated `org.apache.sshd`, `net.i2p`, `org.slf4j` or Bouncy Castle classes); the published POM shall declare the `api`/`implementation` dependencies and none of the shaded ones; Ed25519 shall work from the shaded jar | U20 (task `:lib:shadedSmokeTest`, classpath = the shaded jar + Testcontainers + slf4j only): the jar entries and the relocated `SecurityUtils` facts of PLAN.md §13.2 U20; `unit` CI job: the POM in `~/.m2` after `publishToMavenLocal` contains no `org.apache.sshd` or `net.i2p.crypto` artifact | `jar-listing.txt`, `security-facts.txt`, CI step output | Test: U20; Check: CI `unit` job | D16 |
| REQ-BLD-09 | Protocol-neutral public API | No public method of the API types listed in PLAN.md §9.14 shall take or return a type from `${BASE_PACKAGE}.qmp`, `.qga`, `.internal…` or `.shaded…`, except `QemuVmContainer.qmp()` and `QemuVmContainer.guestAgent()` | U14 reflects over those types and lists every offending method (expected: none) | `api-surface.txt` | Test: U14 | D18, PLAN §1 rule p |

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
- The guest contract is tiered: black-box images need nothing (Tier 0); SSH (Tier 1a) or the agent (Tier 1b) is configured per test and verified by the ACC and NET features.
