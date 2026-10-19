# QEMU VM Testcontainer: Implementation Plan

> **Audience:** the agent (or engineer) who will implement this module in a **different repository**. Everything needed is in this document. Appendix A inlines every file you must copy verbatim.
> **Status:** final plan, reviewed. Behaviour marked **[V]** was verified by an experiment or against a primary source (jar, package list, documentation page); the evidence is in §17. **[D]** = from documentation or a jar's signatures. **[U]** = unverified; an acceptance test covers it.
> **Scope:** x86-64 hosts and Linux guests only.
> **Document set:** this file says **what** we build and why. [REQUIREMENTS.md](REQUIREMENTS.md) holds the testable requirements (`REQ-*` IDs) that tests point at. [PLAN-STEPS.md](PLAN-STEPS.md) is the step-by-step checklist the implementer follows. All three use the §0 parameters.

---

## 0. Project parameters (the ONLY place to retarget names)

The rest of this plan uses these names, for example `${GROUP_ID}`. To adopt the plan, change the **Example value** column here and nothing else.

In the implementation, each value is stored in **exactly one** file, given in the *Lives in* column. Everything else derives from that file:

- **Java:** a constants class generated from `gradle.properties` (§11.3).
- **Containerfiles:** `--build-arg` (§11.2).
- **Scripts:** arguments.

| Parameter | Example value | Meaning | Lives in (implementation) |
|-----------|---------------|---------|---------------------------|
| `${GROUP_ID}` | `io.github.nhwalker` | Maven group ID of every published artifact | `gradle.properties` → `group` |
| `${VERSION}` | `0.1.0-SNAPSHOT` | Project version (artifacts **and** image tags) | `gradle.properties` → `version` |
| `${ROOT_PROJECT}` | `vm-testcontainer` | Gradle root project name | `settings.gradle` |
| `${ART_LIB}` | `vm-testcontainer` | Artifact ID of the Java library | `gradle.properties` → `artifactLib` |
| `${ART_JUNIT}` | `vm-testcontainer-junit5` | Artifact ID of the optional JUnit 5 extension | `gradle.properties` → `artifactJunit` |
| `${BASE_PACKAGE}` | `io.github.nhwalker.vmtestcontainer` | Root Java package | source tree + `gradle.properties` → `basePackage` (for code generation) |
| `${IMAGE_REGISTRY}` | `ghcr.io/nhwalker` | Registry/namespace for runner and guest images | `gradle.properties` → `imageRegistry` |
| `${IMAGE_RUNNER_NAME}` | `vmtc-runner` | Runner image repository name (`${IMAGE_RUNNER}` = `${IMAGE_REGISTRY}/${IMAGE_RUNNER_NAME}`) | `gradle.properties` → `imageRunnerName` |
| `${IMAGE_RECORDER_NAME}` | `vmtc-recorder` | Recorder (GStreamer sidecar) image repository name (`${IMAGE_RECORDER}` = `${IMAGE_REGISTRY}/${IMAGE_RECORDER_NAME}`; D17) | `gradle.properties` → `imageRecorderName` |
| `${IMAGE_GUEST_NAME}` | `vmtc-guest-rocky9` | Reference guest containerDisk name (`${IMAGE_GUEST}` = `${IMAGE_REGISTRY}/${IMAGE_GUEST_NAME}`) | `gradle.properties` → `imageGuestName` |
| `${IMAGE_GUEST_BLACKBOX_NAME}` | `vmtc-guest-rocky9-blackbox` | Black-box guest containerDisk name (`${IMAGE_GUEST_BLACKBOX}` = `${IMAGE_REGISTRY}/${IMAGE_GUEST_BLACKBOX_NAME}`; no agent, no cloud-init; D19) | `gradle.properties` → `imageGuestBlackboxName` |
| `${IMAGE_TAG_SUFFIX}` | `ubi9.8` | Base-OS marker appended to runner tags | `gradle.properties` → `imageTagSuffix` |
| `${LABEL_PREFIX}` | `io.github.nhwalker.vmtc` | Prefix for OCI labels on images and containers | `gradle.properties` → `labelPrefix` |
| `${SYSPROP_PREFIX}` | `vmtc` | Prefix for JVM system properties (`vmtc.kvm`, …) | `gradle.properties` → `syspropPrefix` |
| `${ENV_PREFIX}` | `VMTC_` | Prefix for host environment variables (`VMTC_KVM`, …) | `gradle.properties` → `envPrefix` |
| `${CACHE_DIR_NAME}` | `vm-testcontainer` | Directory under `$XDG_CACHE_HOME` (default `~/.cache`) | `gradle.properties` → `cacheDirName` |
| `${JAVA_RELEASE}` | `21` | Java language level | `gradle.properties` → `javaRelease` |
| `${GRADLE_VERSION}` | `9.8.1` | Gradle wrapper; current release on 2026-10-18 **[V]** | `gradle/wrapper/gradle-wrapper.properties` |
| `${TESTCONTAINERS_VERSION}` | `2.0.5` | `org.testcontainers:testcontainers` **[V]** | `gradle/libs.versions.toml` |
| `${JUNIT_VERSION}` | `5.14.4` | JUnit BOM; latest 5.x on 2026-10-18 **[V]**. Stay on 5.x: `allure-jupiter` 3.x pulls JUnit 6 (D10) | `gradle/libs.versions.toml` |
| `${ALLURE_JAVA_VERSION}` | `2.35.5` | `io.qameta.allure:allure-bom`, `allure-jupiter`, `allure-java-commons` (2.x line; its JUnit runtime deps are 5.10.3 and are overridden by the BOM above) **[V]** | `gradle/libs.versions.toml` |
| `${ALLURE_REPORT_VERSION}` | `2.46.1` | `io.qameta.allure:allure-commandline` zip (the report generator; `bin/allure` inside `allure-2.46.1/`) **[V]** | `gradle/libs.versions.toml` |
| `${REQ_DOC_URL}` | `https://github.com/nhwalker/vm-testcontainer/blob/main/docs/vm-testcontainer/REQUIREMENTS.md` | Link target for requirement links in the Allure report | `gradle.properties` → `reqDocUrl` |
| `${JACKSON_VERSION}` | `2.22.4` | jackson-databind; latest 2.x release on Maven Central on 2026-10-18 **[V]** | `gradle/libs.versions.toml` |
| `${COMMONS_COMPRESS_VERSION}` | `1.28.0` | commons-compress: tar extraction, xz/zstd stream decoding **[V]** | `gradle/libs.versions.toml` |
| `${XZ_VERSION}` | `1.12` | `org.tukaani:xz`, required by commons-compress for `.xz` **[V]** | `gradle/libs.versions.toml` |
| `${ZSTD_JNI_VERSION}` | `1.5.7-24` | `com.github.luben:zstd-jni`, required by commons-compress for `.zst` (native; bundles linux-x86_64) **[V]** | `gradle/libs.versions.toml` |
| `${SLF4J_VERSION}` | `2.0.17` | slf4j-api / slf4j-simple (tests); the api version is **forced** on test runtime classpaths (§11.1) **[V]** | `gradle/libs.versions.toml` |
| `${SSHD_VERSION}` | `2.20.0` | Apache MINA SSHD `sshd-core` + `sshd-sftp` (latest 2.x on 2026-10-19; 3.0 is at milestone M6) **[V]**. Shaded into `:lib` (D16) | `gradle/libs.versions.toml` |
| `${EDDSA_VERSION}` | `0.3.0` | `net.i2p.crypto:eddsa`, the Ed25519 provider MINA SSHD uses on JDK 21 (unsigned, relocatable) **[V]**. Shaded into `:lib` | `gradle/libs.versions.toml` |
| `${SHADOW_PLUGIN_VERSION}` | `9.6.1` | Gradle plugin `com.gradleup.shadow` (relocation of the SSH library); latest on 2026-10-19 **[V]**; needs Gradle 9 (fails to configure under 8.14 **[V]**, spike E) | `gradle/libs.versions.toml` (plugin alias) |
| `${ASSERTJ_VERSION}` | `3.27.3` | AssertJ (tests; verify it is current when adopting) | `gradle/libs.versions.toml` |
| `${UBI_IMAGE}` | `registry.access.redhat.com/ubi9/ubi:9.8` | Runner base image **[V]** | `gradle.properties` → `ubiImage` (passed as `--build-arg UBI_IMAGE`) |
| `${ROCKY_RELEASE}` | `9.8` | Rocky fallback repo release **[V]** | `images/runner/repos/rocky.repo` (literal in `baseurl`; this file is its only home) |
| `${GUEST_BASE_URL}` | `https://dl.rockylinux.org/pub/rocky/9/images/x86_64/Rocky-9-GenericCloud-Base-9.8-20260525.0.x86_64.qcow2` | Reference guest source image **[V]** | `images/guest/guest.properties` → `baseUrl` |
| `${GUEST_BASE_SHA256}` | `92c206cc6f790c61583247eefe87890f8828420662c17cacf247cec78ab4eec8` | SHA-256 of the image above **[V]** | `images/guest/guest.properties` → `baseSha256` |

**Fixed protocol strings.** These are **not** parameters; they are identical in every adoption:

- Container-log markers printed by `vmtc-entrypoint`: `VMTC_ACCEL kvm|tcg`, `VMTC_QEMU_STARTING <epoch>` (log-only: Java neither parses it nor records it in `timings()`), `VMTC_READY`, `VMTC_ERROR <text>`.
- Recorder markers printed by `vmtc-rec` (in the recorder container): `VMTC_REC_STARTED <pid>`, `VMTC_REC_OK duration=<s> width=<w> height=<h>`, `VMTC_REC_KILLED`, `VMTC_REC_DIED`, `VMTC_REC_INVALID`.
- Serial-log markers printed by the guest: `VMTC_SEED_DONE` (every `CloudInitSeed`), `VMTC_CUSTOMIZE_DONE` (guest image builds only).
- In-image script names: `vmtc-entrypoint` (runner), `vmtc-rec` (recorder).
- Container paths: `/vm/...` (runner), `/rec/...` (recorder).
- Container ports: 4444 (QMP), 4445 (QGA), 5900+i (VNC per screen), 10000+k (the k-th guest port forward, k from 0; the implicit SSH forward is k = 0 when SSH access is configured, §8.5).
- Container env vars: `VM_*`, `VNC_*` (incl. `VM_PORT_FORWARDS`).
- Image labels: `${LABEL_PREFIX}.role` = `runner` | `recorder`.
- Container labels (§9.2 step 5): `${LABEL_PREFIX}.managed` = `true`, `${LABEL_PREFIX}.version`, `${LABEL_PREFIX}.session`, `${LABEL_PREFIX}.created`; the recorder container additionally `${LABEL_PREFIX}.role` = `recorder` and `${LABEL_PREFIX}.vm` = `<vm container id>` (§9.8); the per-JVM network carries `managed`, `session` and `created` (§9.2 step 4a). Optional guest-image labels `${LABEL_PREFIX}.guest.os` / `.guest.tiers` (§10.4).
- Jar manifest attribute `Automatic-Module-Name` = `${BASE_PACKAGE}` (D20).

**Parameter-hygiene check (§12.3).** CI greps (fixed-string, whole-word) for the values of these parameters:

- `${GROUP_ID}`
- `${BASE_PACKAGE}`
- `${IMAGE_REGISTRY}`
- `${IMAGE_RUNNER_NAME}`
- `${IMAGE_RECORDER_NAME}`
- `${IMAGE_GUEST_NAME}`
- `${IMAGE_GUEST_BLACKBOX_NAME}`
- `${LABEL_PREFIX}`

Rules (implemented by the `checkParameterHygiene` task, §11.5):

- `git grep --untracked -n -w -F -- <value>` for each value.
- Allowed locations for every value: `gradle.properties`, anything under a `build/` directory, `docs/vm-testcontainer/*.md` (this plan and its companions hold the example values by design) and `README.md` (user documentation shows real coordinates).
- `${GROUP_ID}` and `${BASE_PACKAGE}` are additionally allowed in `*.java` files: every `package`/`import`/`module` line contains them. The Java source tree is the second *Lives in* of `${BASE_PACKAGE}`.
- Any other hit fails the task, printing `file:line`.

Version numbers and short prefixes (`vmtc`, `VMTC_`) are excluded from the grep because they legitimately appear in fixed strings.

---

## 1. BLUF (Bottom Line Up Front)

**What it is:** a Testcontainers module, `${ART_LIB}`, whose `QemuVmContainer` runs **one QEMU VM per Podman container** on a UBI 9.8 runner image. **The primary use case is black-box testing of prebuilt disk images** (appliance images, bootc-built images): nothing is required of the guest beyond booting on virtio hardware. Guest access for exec and file copy is an **optional, pluggable layer** (D15): SSH over a forwarded port (primary), the QEMU guest agent (QGA, for images that ship it), or none.

- **With `/dev/kvm`:** it uses KVM. On CI the guest agent was ready about 11–12 s after container start; SSH answered about 16–21 s after start.
- **Without it:** it falls back to TCG (software emulation). The guest agent was ready in about 47–67 s and SSH in 65–93 s, so TCG is slower but functional.

**Verified end to end on GitHub-hosted runners**, under KVM (Podman 4.9.3 and 5.7.0) and under TCG, with a seeded stock Rocky 9.8 guest, with the pre-customized reference guest booted with no seed, and with an **agent-less, cloud-init-less black-box guest driven only over SSH** (run 31):

| Capability | How | Needs from the guest |
|------------|-----|----------------------|
| Boot, screenshots, keyboard, VNC, recording, audio capture, hot-plug (host side), serial log, port forwards | QMP, slirp `hostfwd`, VNC | nothing (Tier 0, §10.1) |
| Run commands, copy files | **SSH** (Apache MINA SSHD, shaded) through a slirp port forward, **or** QGA | sshd + credentials (Tier 1a), or `qemu-guest-agent` with G3/G4 (Tier 1b) |
| One VNC server per screen | exposed to library users; unauthenticated, bound to loopback by default (D8) | nothing |
| Screen recording | one WebM (VP8) per screen, recorded from that screen's VNC by GStreamer `rfbsrc` running in a **separate recorder container** on the same container network (D17) | nothing |
| Audio | separate WAV via QEMU `wavcapture` (runner container) | nothing to capture; a guest driver + unmuted mixer to produce sound |
| Hot-plug | USB storage/HID, PCIe disk, PCIe NIC, CD media change, memory; vCPU under KVM only | nothing to plug; exec to observe it |

**Packaging:**
- UBI has **no QEMU at all**. QEMU comes from a pinned **Rocky 9.8** fallback repo; `gstreamer1-plugins-good` (recorder image) likewise.
- `tini` comes from **EPEL**.
- Everything else comes from UBI.
- Two images we build: the **runner** (QEMU) and the **recorder** (GStreamer); plus two guest containerDisks (reference and black-box).

**VM images:** every source becomes **a read-only base disk file in a host cache**. It is bind-mounted into the container, and a throwaway qcow2 overlay is written per start.

| Source | API |
|--------|-----|
| Host path | `VmImage.fromPath` |
| `http(s)://` URL: optional SHA-256, optional credentials, `.gz`/`.xz`/`.zst` decompressed automatically | `VmImage.fromUrl` |
| `oci://` containerDisk image: **pulled by the engine** (engine or explicit credentials), `/disk/` extracted through the Docker API | `VmImage.fromOci` / `fromContainerDisk` **[V]** |
| Disk baked into a derived runner image | `VmImage.baked()` |

Two Rocky 9 guests are shipped as containerDisks (§10.3): the **reference guest** (agent, cloud-init, SSH users) and the **black-box guest** (SSH users only; no agent, cloud-init disabled).

**Rules the implementer must not get wrong:**

| # | Rule | Why |
|---|------|-----|
| a | Run `tini` as PID 1 | QEMU does not reap orphans, so recorders become zombies otherwise **[V]** |
| b | Use `rfbsrc incremental=false` | Static screens otherwise produce no frames **[V]** |
| c | Judge a recording by validating its file | `gst-launch` exits 1 after a normal SIGINT stop **[V]** |
| d | Use VNC `share=force-shared` | An exclusive client otherwise disconnects recorders **[V]** |
| e | Override `start()` and rethrow the library's own exception found in the `ContainerLaunchException` cause chain | Testcontainers wraps every start-up failure (`GenericContainer.doStart`/`tryStart`, checked by `javap` on 2.0.5) **[V]**; without this no test can assert `KvmUnavailableException` etc. |
| f | Guests need the QGA RPC filter relaxed **and** `semanage permissive -a virt_qemu_ga_t` | SELinux otherwise blocks guest-exec **[V]** |
| g | Secondary screens are dark until the guest modesets them | Expected; plan for it **[V]** |
| h | Group-restricted `/dev/kvm` (`0660`) needs annotation `run.oci.keep_original_groups=1`. Never use `GroupAdd keep-groups` | keep-groups breaks container start **[V]** |
| i | QMP TCP accepts **one** client at a time, so the entrypoint uses a second, internal QMP socket | **[V]** |
| j | A builder call beats a system-property/env override; overrides only replace defaults (D9) | Otherwise `-D<prefix>.kvm=DISABLE` on the TCG CI job silently neuters `withKvm(REQUIRE)` tests |
| k | Pair the cache's `FileChannel.lock()` with a per-key in-JVM lock | A second thread locking the same file in one JVM gets `OverlappingFileLockException`, it does not wait [D] |
| l | Set `PublishAllPorts=false` and bind exactly the needed ports | Testcontainers publishes every `EXPOSE`d port on `0.0.0.0` otherwise [D] |
| m | The default guest access is `GuestAccess.none()`: `start()` returns at `VMTC_READY`, and `exec`/`copy*`/`forceModeset` throw `GuestAccessUnavailableException` at once | Black-box images are the primary use case (D15); waiting for an agent that will never answer was the old failure mode |
| n | A readiness check through a slirp port forward must wait for **data** (banner or reply), never for a bare TCP connect | slirp completes the host-side handshake itself: connect succeeded 3–8 s after start while sshd answered only at 15–93 s **[V]** (run 31) |
| o | SFTP transfers use MINA SSHD's **synchronous handle API** (`open`/`write(handle, offset, …)`/`read`), never `SftpClient.write(String, int)` | the asynchronous output stream fails with `EOFException` on the first write against OpenSSH 9.9 **[V]** |
| p | Public API types carry no QMP/QGA/SSH types (D18): the only protocol escape hatches are `qmp()` and `guestAgent()`; everything else goes through the internal backend and transport interfaces | keeps a future backend or transport swap API-compatible; U14 enforces it |
| q | Under SSH access, `exec` wraps the command in `timeout -k 1 <seconds>` (never `--foreground`, never `-s KILL`) and maps **exit status 124**, or an **`exit-signal` that arrives after the deadline**, to `GuestExecTimeoutException` (§9.4.2) | `timeout` is the SSH replacement for QGA's `kill -9`. Non-foreground `timeout` signals its own process group, so with `-s KILL` it kills itself and sshd reports `exit-signal KILL` with **no** exit status; `--foreground` returns 124 but leaves grandchildren alive **[V]** (spike D, §3.10) |

---

## Table of contents

0. Project parameters
1. BLUF
2. Requirements and decisions
3. Verified facts
4. Architecture
5. Repository and module layout (Gradle)
6. Runner container image
7. In-container scripts
8. QEMU configuration
9. Java library design
10. Guest contract and reference guest image
11. Build (Gradle)
12. CI
13. Test plan
14. Implementation milestones
15. Limits, risks, open items
16. Assumptions to confirm
17. Evidence index
18. In Summary
- Appendix A: files to copy verbatim

---

## 2. Requirements and decisions

These are the original asks and decisions. The **testable** requirements derived from them, with acceptance criteria and verifying tests, are in [REQUIREMENTS.md](REQUIREMENTS.md); the last column points there.

| # | Requirement / decision | Where | REQUIREMENTS.md |
|---|------------------------|-------|-----------------|
| R1 | Container engine **Podman**; rootless is first-class, rootful works | §9.6, §12 | REQ-ENG-*, REQ-BLD-05 |
| R2 | Images based on **UBI 9.8** | §6 | REQ-IMG-01 |
| R3 | Run commands in the VM (through the configured guest access: SSH or QGA) | §9.4 | REQ-EXEC-*, REQ-ACC-*, REQ-LIFE-02 |
| R4 | Copy files to and from the VM (SSH/SFTP or QGA) | §9.5 | REQ-FILE-* |
| R5 | Run QEMU images (qcow2, raw) | §8, §9.3 | REQ-DISK-01, REQ-IMG-04 |
| R6 | Screenshots, per screen | §9.7 | REQ-DISP-*, REQ-KEY-* |
| R7 | Audio recording, **separate WAV** files | §9.9 | REQ-AUD-* |
| R8 | Screen recording: **GStreamer records each screen's VNC; one video per screen** (from a recorder sidecar container, D17) | §6.5, §7.2, §9.8 | REQ-REC-* |
| R9 | **VNC for display output, exposed to library users** | §8.3, §9.7 | REQ-VNC-01, 02, 05 |
| R10 | Hot-plug simulation | §9.10 | REQ-HOT-* |
| R11 | Repos: UBI first, then EPEL, with Rocky as fallback | §6.2 | REQ-IMG-02 |
| R12 | Use `/dev/kvm` when available; work without it | §8.2, §9.6 | REQ-KVM-* |
| R13 | VM images from `http(s)://` and `oci://` URLs, downloaded on demand, with credentials when needed | §9.3.5, §9.3.6, §10.4 | REQ-DISK-02..04, REQ-HTTP-*, REQ-OCI-* |
| D1 | x86-64 only | — | REQ-PLAT-01 |
| D2 | **Linux guests only** | §10 | REQ-PLAT-02 |
| D3 | Reference test guest: **Rocky Linux 9** | §10.3 | REQ-GUEST-04, REQ-DISK-11 |
| D4 | Build tool: **Gradle** | §5, §11 | REQ-BLD-02, REQ-PLAT-03 |
| D5 | Screens: **1..N configurable**, default 1, maximum 4 | §8.3 | REQ-DISP-01 |
| D6 | *Superseded by D17.* ~~Runner variants slim and media~~ → one runner image plus a separate recorder image | §6.1, §6.5 | REQ-IMG-03; REQ-REC-04 retired |
| D7 | SELinux guests: `semanage permissive -a virt_qemu_ga_t` | §10.1 | REQ-GUEST-02 |
| D8 | **No VNC authentication.** Test VMs are short-lived on an isolated network; VNC binds to loopback by default. `withVncPassword` and the per-display `set_password` step are dropped | §8.3 | REQ-VNC-03/04, REQ-REC-05 retired |
| D9 | **Precedence:** builder call > system property > environment variable > default. Overrides change defaults only | §9.1 | REQ-CFG-01 |
| D10 | **Test stack:** JUnit 5.14.x + Allure Java 2.35.5 (not Allure Java 3 / JUnit 6) | §0, §11.4 | REQ-BLD-07 |
| D11 | **Reporting:** Allure report per CI job as a downloadable artifact (single-file HTML); no hosted report | §11.4, §12 | REQ-BLD-07 |
| D12 | **Shipped guests are console-only.** No GUI (display server) guest is built, tested or planned; screenshot, keyboard and recording tests run against the text console plus `forceModeset()`. Consumers who need a desktop bring their own guest | §10.3 | REQ-PLAT-02, REQ-GUEST-04 |
| D13 | **Only Rocky/RHEL-family guests are verified.** The tiered guest contract (§10.1) is written so any Linux guest with virtio drivers can meet Tier 0 and any guest with sshd can meet Tier 1a, but no seed preset, spike or test exists for Debian/Ubuntu or other families, and none is planned | §10.1, §10.2 | REQ-PLAT-02 |
| D14 | **No KubeVirt / crun-vm / virt-launcher.** Podman and Docker are the only engines; Kubernetes is not a target. KubeVirt's `virt-launcher` image was evaluated and rejected as the runtime image: it is CentOS Stream based and distroless (the recorder could not live in it), its standalone mode (`STANDALONE_VMI`) is undocumented, and libvirt/VMI cannot express per-head VNC servers or `wavcapture`. The containerDisk layout (§10.4) stays shared with KubeVirt | §4, §10.4 | — |
| D15 | **Guest access is pluggable and optional.** `GuestAccess.ssh(...)` (primary), `GuestAccess.guestAgent()` (optional tier) or `GuestAccess.none()` (**default**). Readiness, exec, copy and `forceModeset` are transport-aware; with `none()` they fail fast | §9.2, §9.4, §10.1 | REQ-ACC-*, REQ-LIFE-02, REQ-LIFE-05 |
| D16 | **SSH library:** Apache MINA SSHD `${SSHD_VERSION}` + `net.i2p.crypto:eddsa` `${EDDSA_VERSION}`, **no Bouncy Castle**, **shaded and relocated** into `:lib` under `${BASE_PACKAGE}.shaded`. Password and private-key auth; `sudo -n` support; host keys accepted by default (same stance as D8), pinning optional | §9.1, §9.4, §11.1 | REQ-ACC-*, REQ-BLD-08 |
| D17 | **Recorder sidecar.** GStreamer runs in a separate recorder image (`${IMAGE_RECORDER}`) on the same container network as the VM; the runner image is single-variant. Audio capture stays in the runner (QEMU `wavcapture`) | §6.5, §7.2, §9.8 | REQ-REC-*, REQ-IMG-03, REQ-IMG-06 |
| D18 | **Backend seam.** Public API types reference no QMP/QGA/SSH types (rule p); VM operations go through internal `VmBackend` and `GuestTransport` interfaces with one implementation each (QEMU/Podman; QGA, SSH, none) | §9.1, §9.14 | REQ-BLD-09 |
| D19 | **Two CI guests, two identities.** The reference guest (agent + cloud-init + SSH) and the black-box guest (SSH only) are built from one pipeline; both carry `root` and `vmtc` (NOPASSWD sudo) with password `vmtc` and the build's test key (ed25519). Tests cover both identities and, where it applies, both transports | §10.3, §13 | REQ-GUEST-08, REQ-GUEST-09 |
| D20 | **No JPMS descriptor.** `:lib` ships no `module-info.java`; the jar's manifest declares `Automatic-Module-Name` = `${BASE_PACKAGE}`. Reason: Testcontainers 2.0.5 is not an automatic module (no `Automatic-Module-Name`), so a `requires` on it does not compile, and a descriptor would have to require the SSH library that the shadow jar relocates away (§3.10) | §9.1, §11.1 | REQ-BLD-04 |

---

## 3. Verified facts

Legend:
- **[V]** = verified by experiment (evidence in §17).
- **[D]** = from upstream documentation, source, or a library jar's signatures.
- **[U]** = not verified; the plan contains an acceptance test for it.

### 3.1 Packaging

| Fact | Status |
|------|--------|
| `ubi9/ubi:9.8` exists. RHEL 9.8 GA was 2026-05-19 | [V] |
| UBI 9 baseos, appstream and codeready-builder repos contain **no** `qemu-*` package | [V] |
| Rocky 9.8 AppStream provides `qemu-kvm-core`, `qemu-img` and `qemu-kvm-common` 10.1.0-17.el9_8.5, plus `edk2-ovmf` and `seabios-bin`. Installed onto UBI 9.8, they replaced **no** UBI package, installed no `rocky-*` release package, and `rpm -Va` was clean | [V] |
| UBI provides `gstreamer1`, `-plugins-base` and `-plugins-bad-free` (`rfbsrc`). **Rocky only:** `gstreamer1-plugins-good` (`vp8enc`, `webmmux`) and `-plugins-base-tools` (`gst-discoverer-1.0`). All 1.22.12. These now live in the recorder image (D17) | [V] |
| No H.264/AV1 encoder in the repo set (`x264enc`, `openh264enc`, `av1enc` absent) | [V] |
| EPEL 9 `tini-0.19.0-5.el9` installs `/usr/bin/tini` | [V] |
| `procps-ng` is in UBI baseos; there is **no** `nc` in the image | [V] |
| Rootless Podman image build on a GitHub runner took about 51 s; the former media variant (QEMU + GStreamer in one image) was 734 MB | [V] |

### 3.2 QEMU (RHEL build `/usr/libexec/qemu-kvm`, 10.1.0)

| Fact | Status |
|------|--------|
| No `qemu-system-x86_64` binary. Accelerators: `kvm`, `tcg`, `mshv` | [V] |
| The default machine type is deprecated, so **always** pass `-M q35` | [V] |
| Display devices: `VGA`, `secondary-vga`, `bochs-display`, `cirrus-vga`. **No `virtio-vga`** | [V] |
| Sound: `ich9-intel-hda` + `hda-duplex`. **No `usb-audio` and no `virtio-sound-pci`**. Audio backends: `none`, `wav` | [V] |
| `-netdev user` (slirp) and `passt` both available | [V] |
| Hot-plug devices present: `qemu-xhci`, `usb-storage`, `usb-kbd`, `usb-tablet`, `usb-mouse`, `pcie-root-port`, `virtio-blk-pci`, `virtio-net-pci`, `virtio-scsi-pci`, `scsi-cd`, `pc-dimm` | [V] |
| One VNC server per display: `-vnc <addr>:<n>,id=vnc<n>,display=video<n>`; `query-vnc-servers` confirms | [V] |
| `screendump {device:"video<n>", format:"png"}` captures that display | [V] |
| `share=force-shared` keeps recorders connected when another client connects | [V] |
| `password=on` + `set_password {protocol:"vnc", password, display:"vnc<n>"}` enforces auth; a wrong password is rejected | [V] |
| `wavcapture <path> <audiodev> [freq bits ch]` / `stopcapture <n>` via HMP works with `-audiodev none`. `info capture` prints `[<n>]: Capturing audio(44100,16,2) to <path>: <bytes> bytes` | [V] |
| Several `-qmp` monitors may coexist, e.g. TCP and Unix. A TCP QMP chardev serves **one client at a time**: a second client gets no greeting while the first is connected | [V] |
| `tini` as PID 1 reaps orphaned children (no zombies). With QEMU as PID 1, stopped recorders stayed zombies | [V] |
| Red Hat calls direct `qemu-kvm` use and TCG "unsupported". Acceptable for test infrastructure | [V] |
| Rocky 9 `edk2-ovmf-20241117-8.el9` ships `/usr/share/edk2/ovmf/OVMF_CODE.fd`, `OVMF_CODE.secboot.fd`, `OVMF_CODE.cc.fd`, `OVMF_VARS.fd`, `OVMF_VARS.secboot.fd`. The reference guest boots under `OVMF_CODE.fd` + a writable `OVMF_VARS.fd` copy with all feature checks passing (§8.4) | [V] (run 19) |

### 3.3 Measured on GitHub-hosted runners (4 vCPU, 16 GB)

| Measurement | KVM 24.04 (run 1) | KVM 26.04 (run 2) | TCG 24.04 (runs 1 / 2) |
|-------------|-------------------|-------------------|------------------------|
| Container start → QGA ready (seeded stock image) | 12.1 s | 10.9 s | 66.5 s / 46.7 s |
| Seed incl. `dnf install alsa-utils` done | about 102 s | 45.5 s | 480.8 s / 322.6 s |
| QGA write 50 MB | 2.5 s (20 MB/s) | 2.3 s (21 MB/s) | 36.4 s (1.4 MB/s) / 18.0 s (2.8 MB/s) |
| QGA read 50 MB | 1.1 s (46 MB/s) | 1.6 s (31 MB/s) | 10.5 s (4.8 MB/s) / 7.5 s (6.6 MB/s) |
| USB stick visible / unplugged | 1.7 s / 0.1 s | 1.7 s / 0.1 s | 3.2 s / 1.9 s visible; 0.1 s unplug |
| PCIe disk or NIC visible / unplugged | ≤ 0.4 s / 0.1 s | ≤ 0.3 s / 0.1 s | ≤ 0.5 s / ≤ 0.4 s |
| CD insert / eject | 0.4 s / 0.4 s | 0.3 s / 1.7 s | 0.4 s / 0.4 s |
| vCPU hot-plug | 0.4 s, auto-online | 0.4 s | **guest never sees the vCPU** (XFAIL in both TCG runs and in a sandbox TCG run) |
| Memory +512 MiB | 0.4 s, auto-online | 0.3 s | 1.7 s / 0.6 s |
| Recorder stop latency | about 106 ms | about 106 ms | about 106 ms |

### 3.4 Guest (Rocky 9.8 GenericCloud)

| Fact | Status |
|------|--------|
| Ships `qemu-guest-agent` (enabled), `cloud-init`, `kernel-modules` (HDA driver) and `python3` | [V] |
| `/etc/sysconfig/qemu-ga` sets `FILTER_RPC_ARGS="--allow-rpcs=..."`, which omits `guest-exec*` and `guest-file-*`. A blocked call returns `{"class":"CommandNotFound","desc":"Command guest-exec has been disabled: the command is not allowed"}` | [V] |
| `guest-info` lists the blocked commands with `"enabled": false` (`guest-exec`, `guest-exec-status`, `guest-file-open/close/read/write/seek/flush`) | [V] (sandbox spike #7) |
| guest-exec children run as SELinux domain `virt_qemu_ga_t`: *Permission denied* on `/usr/share/alsa/alsa.conf` and `/lib/modules/<kver>`, and `journalctl` cannot run | [V] |
| `virt_qemu_ga_run_unconfined=on` does **not** help. `semanage permissive -a virt_qemu_ga_t` **does**; the label stays `virt_qemu_ga_t` and the guest stays Enforcing | [V] |
| guest-exec runs with `PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin` | [V] (sandbox spike #7) |
| The HDA mixer must be unmuted (`amixer -c 0 sset Master 90% unmute`). Some boots have no `PCM` control, so ignore its failure | [V] |
| Every bochs-drm head is `/dev/fbN` / `cardN`. A text console drives only head 0. Heads 1..N-1 report "Guest has not initialized the display (yet)" at 640×480 until a modeset (Appendix A.4, or a desktop session) | [V] |
| Hot-added memory and vCPUs are auto-onlined by the guest | [V] |

### 3.5 GStreamer VNC recording

| Fact | Status |
|------|--------|
| The §7.2 pipeline records several screens concurrently; duration matches wall clock within 0.3 s | [V] |
| `incremental=true` (default) **blocks with no frames** on a static screen. With `incremental=false`, QEMU answers on its adaptive refresh timer (≈0.1–0.7 s idle) and `videorate` fills the gaps | [V] |
| SIGINT → `-e` EOS → the file is finalized in about 110 ms. **Exit code is 1**, with a benign "Error on setup VNC connection" line | [V] |
| `rfbsrc password=…` works with QEMU VNC auth | [V] |

### 3.6 Testcontainers 2.0.5 ↔ rootless Podman (compat API)

| Fact | Status |
|------|--------|
| Works against Podman 4.9.3 and 5.7.0 via `DOCKER_HOST=unix://$XDG_RUNTIME_DIR/podman/<sock>` and `TESTCONTAINERS_RYUK_DISABLED=true` | [V] |
| `HostConfig.Devices=[/dev/kvm]` works when the host `/dev/kvm` is `0666` | [V] |
| `/dev/kvm 0660 root:kvm`, user in `kvm`: device only → EACCES. With `HostConfig` annotation `run.oci.keep_original_groups=1` → **works on 5.7.0**; EACCES on 4.9.3 (annotation ignored). With the same annotation in `containers.conf` → **works on both** | [V] |
| `HostConfig.GroupAdd=["keep-groups"]` → container **fails to start** on both versions | [V] |
| `HostConfig.Init=true` → PID 1 `podman-init` | [V] |
| A read-only file bind is enforced (EROFS) | [V] |
| containerDisk extraction (`createContainerCmd` + `copyArchiveFromContainerCmd`) is byte-exact on Docker 29.8, Podman 4.9.3 and 5.7.0 when copying a known file path. Copying the `/disk/` **directory** yields entries `disk/` (dir) + `disk/<file>` (file) on Docker 29.8 | [V] |
| Directory copy of `/disk/` on Podman 4.9.3 and 5.7.0 gives the same `disk/` (dir) + `disk/<file>` (file) entries, byte-exact | [V] (run 5) |
| `ExecConfig` (`execInContainer(ExecConfig)` with `envVars`) and `RemoteDockerImage(DockerImageName)` exist in testcontainers-2.0.5.jar | [D] (javap) |
| `GenericContainer.addFileSystemBind(String, String, BindMode, SelinuxContext)` exists in 2.0.5 (void); the `withFileSystemBind` overloads take no `SelinuxContext`. `HostConfig.withPublishAllPorts(Boolean)`, `withAnnotations(Map)`, `withDevices(...)`, `withPortBindings(...)` exist in docker-java-api 3.7.1 | [V] (javap) |
| `GenericContainer.doStart()` and `tryStart()` wrap any failure from `configure()`, the wait strategy and `containerIsStarted` in `ContainerLaunchException` (javap of 2.0.5) | [V] |
| `PullPolicy.alwaysPull()`, `PullPolicy.defaultPolicy()` and `RemoteDockerImage.withImagePullPolicy(ImagePullPolicy)` exist in 2.0.5; the default policy pulls only when the image is not cached locally | [V] (javap) / [D] |
| `pullImageCmd(ref).withAuthConfig(new AuthConfig().withRegistryAddress(registry).withUsername(u).withPassword(p))` pulls a private image from the htpasswd TLS registry with **no** stored credentials (no `config.json`, no `podman login`), rootless Podman 4.9.3. With a wrong password, and also for the no-credentials `RemoteDockerImage` path, docker-java throws `com.github.dockerjava.api.exception.UnauthorizedException` (`Status 401: {"message":"unauthorized: authentication required"}`) as the top-level exception | [V] (run 16) |
| A JVM started from the GitHub Actions shell does **not** have the `kvm` supplementary group even after `usermod -aG kvm`, so `Files.isReadable/isWritable(/dev/kvm)` are false for a `0660 root:kvm` device; the same JVM started through `sudo -iu $USER` has the group and sees the device as readable and writable (Podman 4.9.3 and 5.7.0 runners) | [V] (run 16) |

### 3.7 Test tooling (JUnit, Allure)

| Fact | Status |
|------|--------|
| JUnit 5.12: an extension declared with `@ExtendWith` **on a `@Repeatable` custom annotation** is registered for a method whether the annotation appears once or several times (the repeated form is compiled into the container annotation, which carries no `@ExtendWith`); the extension runs exactly once per test method even when the class also carries the annotation; `AnnotationSupport.findRepeatableAnnotations` returns every repetition | [V] (`spike-oras` `RepeatableExtendWithTest`, `RepeatableContainerOnlyTest`) |
| Allure Report 2.46.1 renders images, `video/webm`/`video/mp4`, text, HTML, CSV, JSON/XML/YAML and URI lists inline; **no audio type** is rendered (download link only). `allure generate --single-file` exists | [V] (docs page, `allure help generate`) |
| Allure Java 2.35.5: `Allure.addAttachment(String name, String type, InputStream, String ext)`, `Allure.epic/feature/story/label/link/description`, `@LabelAnnotation` exist; `allure-jupiter` 2.35.5 declares JUnit 5.10.3 runtime deps; `allure-jupiter` 3.0.0 declares JUnit 6.1.0 | [V] (javap, poms) |
| Testcontainers 2.0.5 + docker-java 3.7.1: `UnauthorizedException` on 401 pulls; `addFileSystemBind(..., SelinuxContext)`; `HostConfig.withPublishAllPorts(Boolean)` | [V] (run 16, javap) |
| A test JVM with Testcontainers 2.0.5 and `slf4j-simple` 2.0.17 printed `Failed to load class "org.slf4j.impl.StaticLoggerBinder"` and logged nothing: a transitive **slf4j-api 1.7** won the resolution and cannot see a 2.x provider. Force `org.slf4j:slf4j-api:${SLF4J_VERSION}` on test runtime classpaths (§11.1) | [V] (run 31, `spike-ssh`) |

### 3.8 Image downloads (R13)

| Fact | Status |
|------|--------|
| **HTTP(S)** (JDK `HttpClient`, in-process servers): Basic and Bearer auth work. Missing credentials → 401 → clear exception. A SHA-256 mismatch is rejected and leaves no file. `.gz`, `.xz` and `.zst` decompress to the original bytes, with the checksum verified on the bytes as downloaded. On a redirect to another origin, the target receives **no** `Authorization` header (manual redirect handling). Same-origin redirects keep auth | [V] (local + CI runs #10–#12) |
| The JDK's own `Redirect.NORMAL` also does not forward a user-set `Authorization` header to another origin | [V] |
| JDK `HttpClient`: `HttpRequest.timeout` bounds only the wait for response headers (`HttpTimeoutException`); a body stall is not detected. Closing the `ofInputStream()` body from another thread makes the blocked `read()` throw `IOException: closed` immediately | [V] (local spike, `HttpDownloadTest.readStallWatchdogUnblocksRead`) |
| **Engine pull of a private OCI image** through Testcontainers (`RemoteDockerImage`), against an htpasswd TLS registry, rootless Podman 4.9.3: <ul><li>no credentials → `401 unauthorized`</li><li>credentials in `~/.docker/config.json` → pulled (Testcontainers' `RegistryAuthLocator` sends them)</li><li>**only `podman login`** (`$XDG_RUNTIME_DIR/containers/auth.json`) → pulled (Podman falls back to its own credentials when the client sends none)</li></ul> | [V] (run #12) |
| **Image mounts** (`MountType.IMAGE`, docker-java 3.7.1): <ul><li>Docker 29.8: works only with API ≥ 1.48 (Testcontainers 2.0.5 defaults to 1.44; `-Dapi.version=1.48` fixes it). Read-only without asking; byte-exact</li><li>Podman 4.9.3 / 5.7.0 via the compat API: `ReadOnly=true` → `500 ro: invalid mount option`. Without `ReadOnly` Podman creates the container, but **Testcontainers fails parsing the inspect response** (`HostConfig.Binds` contains a raw overlay string, and docker-java's `Binds` parser throws)</li><li>`podman run --mount type=image` (CLI) + full VM boot: all 15 applicable checks pass (seed check skipped)</li></ul> **Therefore not used (§15 L6)** | [V] (runs #11–#12) |
| `land.oras:oras-java-sdk` 0.8.5 pulls ORAS artifacts correctly (TLS + custom CA, credentials, `REGISTRY_AUTH_FILE`, podman `auth.json`, layer selection by title, tag vs digest). **Rejected:** about 10 runtime dependencies (Jackson 3.2.3, native zstd-jni, Bouncy Castle, Micrometer, …), and not needed once disks are published as OCI images | [V] (run #10; evidence only) |

### 3.9 SSH as the guest-access transport (spikes A/B/C, run 31 + local)

| Fact | Status |
|------|--------|
| `-netdev user,…,hostfwd=tcp:0.0.0.0:<cport>-:<gport>` on the existing slirp netdev, with `<cport>` exposed and published by Testcontainers to loopback, carries SSH from the host JVM to the guest (rootless Podman 4.9.3) | [V] (run 31) |
| **slirp accepts the host-side TCP connect immediately** (3–8 s after container start, i.e. as soon as QEMU runs), long before the guest listens; the SSH banner arrived at 15–21 s (KVM) / 64–93 s (TCG). A readiness check must wait for data (rule n) | [V] (run 31) |
| MINA SSHD 2.20.0 with no Bouncy Castle against OpenSSH 9.9 (Rocky 9.8): password, RSA and ECDSA key auth work; **Ed25519 needs `net.i2p.crypto:eddsa:0.3.0`** (`SecurityUtils.isEDDSACurveSupported()` is false on JDK 21 without it); a wrong password surfaces as `SshException: No more authentication methods available` | [V] (local + run 31) |
| Relocating `org.apache.sshd` **and** `net.i2p.crypto` (maven-shade, with `ServicesResourceTransformer`) keeps everything working, Ed25519 included; the relocated jar ran as a separate JVM against the CI guests. Bouncy Castle would not relocate (signed JCE provider), which is why it is excluded | [V] (local + run 31; the Gradle Shadow 9.6.1 equivalent **[V]**, spike E, §3.10) |
| `SftpClient.write(String, int)` (asynchronous stream) fails with `EOFException … SSH_MSG_CHANNEL_DATA` on the first write against OpenSSH 9.9; the synchronous handle API (`open`, `write(handle, offset, buf, 0, n)`, `read`) works (rule o) | [V] (local, run 31) |
| SFTP 50 MB through the forward: KVM 26 MB/s put, 47–54 MB/s get (QGA: 20 / 46); TCG 3.9–4.7 MB/s put, 5.5–5.6 MB/s get (QGA: 1.4–2.8 / 4.8–6.6) | [V] (run 31) |
| Exec round trip over SSH, warm: 75–125 ms (KVM), 0.6–1.2 s (TCG); the first exec after connect 0.3–7 s | [V] (run 31) |
| `sudo -n id -u` as the `vmtc` user → `0`; environment passing via `env K=V sh -c …`; `fb-activate.py` via `sudo -n python3` modesets head 1 (640×480 → 1280×800 confirmed by `screendump`) on both guests, KVM and TCG | [V] (run 31) |
| sshd sessions are unconfined under SELinux Enforcing, so the G3/G4 agent workarounds are not needed for SSH access | [V] (run 31: `getenforce` Enforcing, every command worked) |
| The agent-less guest (`qemu-guest-agent` removed, `/etc/cloud/cloud-init.disabled`) boots, serves SSH and still has `/dev/virtio-ports/org.qemu.guest_agent.0` (the host device is unchanged), so that path proves nothing about an agent | [V] (run 31) |
| cloud-init `ssh_pwauth: true`, `disable_root: false`, a `users:` entry with `ssh_authorized_keys` and `sudo: ALL=(ALL) NOPASSWD:ALL`, plus an `sshd_config.d` drop-in `PermitRootLogin yes` / `PasswordAuthentication yes`, give root and a non-root user password and key login on GenericCloud | [V] (run 31) |
| Black-box guest build: customize boot 45 s (KVM) / 465 s (TCG), flatten 47 s, 816–818 MB qcow2 and containerDisk | [V] (run 31) |
| **`guestPort` probe form:** a second forward (guest 8080) to `python3 -m http.server 8080`; writing `HEAD / HTTP/1.0\r\n\r\n` after the connect read `HTTP/1.0 200 OK` within 4–5 ms; the same forward **without** a probe hit the 3 s read timeout (HTTP never speaks first). A forward to a guest port with **no listener**: connect in 0 ms, then **EOF in 0 ms** (slirp closes it; neither a refused connect nor silence). Both guests, KVM | [V] (run 34, `GuestSshProbeTest`) |
| The guest's `python3 -m http.server 8080` was reachable through the forward on both shipped guests: no guest firewall stands in the way (GenericCloud ships no active firewalld) | [V] (run 34) |
| **Recorder sidecar over a Testcontainers `Network` (R11):** `Network.newNetwork()` + `withNetworkAliases("vm-<uuid8>")` through the compat API on rootless Podman 4.9.3 (netavark + aardvark-dns): a second container resolved the alias, read the `RFB 003.008` banner from `vm-<id>:5900`, and `gst-launch rfbsrc host=vm-<id>` recorded 7.9 s at 1280×800; `vmtc-rec stop` → gst process gone after 137 ms, file validated and copied to the host after 249 ms (REQ-REC-02 budget 2 s). The recorder's log ends with `rfbsrc` teardown errors after the normal stop (rule c) | [V] (run 36, KVM; `SidecarRecorderProbeTest`) |

### 3.10 Exec timeout, JPMS and Gradle Shadow (spikes D and E, local, 2026-10-19)

Recorded in `vm-testcontainer/container/spike/RESULTS.md` ("Spikes D and E"); the three documents lagged that commit by one revision, which is why these rows exist.

| Fact | Status |
|------|--------|
| coreutils `timeout` (9.x) without `--foreground` calls `setpgid(0,0)` and, on expiry, signals its **whole process group, itself included**. `timeout -s KILL 2 sleep 61` over SSH: `timeout` dies by SIGKILL, sshd sends `exit-signal KILL` and **no** `exit-status` (MINA `getExitStatus()` = `null`, `getExitSignal()` = `"KILL"`), 2.06 s after start. `timeout 2 sleep 61` (default TERM): `exit-status 124`, and `sh -c` grandchildren die with the group. `timeout --foreground`: exit 124 but the `sleep` grandchild **survives** and the channel stays open until it ends. A hard channel close (`close(false)`) ended a `sleep 300` on OpenSSH 9.9 | [V] (spike D) |
| `sh -c 'kill -9 $$'` over SSH also yields `exit-signal KILL` and no status; `sh -c 'exit 137'` yields `exit-status 137`. So neither 137 nor `exit-signal KILL` alone identifies a timeout: rule q uses exit 124 **or** an exit-signal after the deadline | [V] (spike D) |
| `pgrep -f '<pattern>'` run through the SSH wrapper matches the wrapper's own command line (`timeout … pgrep -f 'sleep 61'` prints the wrapper PID and exits 0); use `pgrep -x <name>` | [V] (local) |
| `testcontainers-2.0.5.jar` has no `module-info.class` and no `Automatic-Module-Name` (its manifest holds only `Manifest-Version` and `Implementation-Version`), so `requires org.testcontainers;` does not compile ("module not found"). Decision D20: no `module-info.java`; the jar's manifest carries `Automatic-Module-Name` | [V] (spike E) |
| Gradle Shadow 9.6.1 configures only under Gradle 9 (8.14.3 fails with a missing `addVariantsFromConfiguration(Provider, Action)`); generate the wrapper first. `from components.shadow` publishes a POM **without dependencies**; publishing `components.java` after disabling `jar` and swapping `shadowJar` into `apiElements`/`runtimeElements` gives a POM with Testcontainers (compile) and the `implementation` set (runtime) and nothing from `shaded`, and makes `project(':lib')` consumers resolve the relocated jar (`vm-testcontainer/spike-ssh/shadow-reference.build.gradle`) | [V] (spike E) |
| Shadow excludes `module-info.class` by default (moot under D20). `configurations = [shaded]` also bundles the **unrelocated** transitive `org.slf4j:slf4j-api` of `sshd-common` (38 `org/slf4j/*.class` entries in the spike jar) unless excluded with `dependencies { exclude(dependency('org.slf4j:slf4j-api')) }` | [V] (spike E jar inspected) |
| `systemctl is-active <unit>` exits 3 and prints `inactive` for an inactive unit; tests must compare stdout, not the exit status | [D, systemd] |

---

## 4. Architecture

```
┌────────────────────────────── Host (x86-64 Linux, Podman) ──────────────────────────────────────┐
│ Test (JUnit 5) ──► QemuVmContainer (${BASE_PACKAGE})                                             │
│                     │ VmBackend (internal) ── QmpClient ── TCP 127.0.0.1:<mapped 4444> ──┐        │
│                     │ GuestTransport (internal): SshTransport ── TCP 127.0.0.1:<mapped 10000> ─┤ │
│                     │                            QgaTransport ── TCP 127.0.0.1:<mapped 4445> ──┤ │
│                     │                            NoTransport (default: exec/copy throw)         │ │
│ library users' VNC viewers ─────────────────────── TCP <bind>:<mapped 5900+i> ───────────┤        │
│ library users' own clients ─────────────────────── TCP 127.0.0.1:<mapped 10000+k> ───────┤        │
│ ~/.cache/${CACHE_DIR_NAME}/images/<key>.img ── ro bind ──┐                                │        │
│ /dev/kvm (optional) ─────────────────────── --device ──┐ │                                │        │
│                                                        ▼ ▼                                ▼        │
│ ┌──── container ${IMAGE_RUNNER}:${VERSION}-${IMAGE_TAG_SUFFIX}  (network alias vm-<id>) ─────────┐ │
│ │ PID 1 tini ─► vmtc-entrypoint ─► /usr/libexec/qemu-kvm -M q35 …  (child)                       │ │
│ │  /vm/base/disk.img (ro) /vm/run/overlay.qcow2 /vm/out/{serial.log,*.png,*.wav}                 │ │
│ │  QMP tcp :4444 (Java)   QMP unix /vm/run/qmp-internal.sock (entrypoint only)                   │ │
│ │  QGA chardev :4445 ◄─ virtio-serial ─► guest qemu-ga (if the guest has one)                    │ │
│ │  slirp hostfwd :10000+k ─► guest :<gport>  (k=0 → guest sshd :22 when SSH access is used)       │ │
│ │  VNC :5900+i per screen (share=force-shared)                                                    │ │
│ └───────────────────────────────────────────────────────────────────────────────────────────────┘ │
│        ▲ rfbsrc over the per-JVM container network (vm-<id>:5900+i)                               │
│ ┌──── container ${IMAGE_RECORDER}:${VERSION}-${IMAGE_TAG_SUFFIX}  (one per VM, started lazily) ──┐ │
│ │ PID 1 tini ─► sleep infinity;  vmtc-rec ─► gst-launch rfbsrc ► vp8enc ► webmmux ► /rec/*.webm │ │
│ └───────────────────────────────────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
```

Principles:

1. **One container = one VM.** A VM that records gets one recorder container beside it (D17).
2. **Java speaks QMP directly over TCP** (newline-delimited JSON) and reaches the guest through the configured **transport** (SSH over a forwarded port, or QGA over TCP).
   - `execInContainer` is used only for in-image helpers (`vmtc-rec` in the recorder, `qemu-img`, `tail`, `rm` in the runner).
   - Files are moved with `copyFileToContainer` / `copyFileFromContainer`.
3. **The base disk is never written.** Each start creates a qcow2 overlay.
4. **Published host ports bind to `127.0.0.1`.** Only VNC's bind address is configurable.
5. **The guest owes nothing by default** (Tier 0, §10.1). Agent or SSH requirements apply only when that access is configured.
6. **Public API is protocol-neutral** (rule p, D18): `VmBackend` and `GuestTransport` are internal interfaces with one implementation each.

---

## 5. Repository and module layout (Gradle)

```
${ROOT_PROJECT}/
├─ settings.gradle               rootProject.name='${ROOT_PROJECT}'; include 'lib','junit5','test-support','images','it'
├─ gradle.properties             group, version, artifactLib, artifactJunit, basePackage, imageRegistry,
│                                imageRunnerName, imageRecorderName, imageGuestName, imageGuestBlackboxName,
│                                imageTagSuffix, labelPrefix, syspropPrefix, envPrefix, cacheDirName, javaRelease,
│                                ubiImage, reqDocUrl, traceabilityRequireFullCoverage     (§0, §11.5)
├─ gradle/libs.versions.toml     testcontainers, junit-bom, junit-platform-launcher, jackson-databind, commons-compress,
│                                xz, zstd-jni, slf4j, sshd-core, sshd-sftp, eddsa, assertj, allure-bom,
│                                allure-commandline; plugin alias shadow (${SHADOW_PLUGIN_VERSION})
├─ gradle/wrapper/…              ${GRADLE_VERSION}
├─ build.gradle                  root tasks: checkParameterHygiene, checkRequirementTraceability, checkDependencies,
│                                allureReport (§11.4, §11.5)
├─ lib/        (${ART_LIB})      java-library + maven-publish + shadow (relocates the SSH library, §11.1) + generateConstants (§11.3)
│  ├─ src/main/java/<${BASE_PACKAGE} as path>/…          (§9.1)
│  ├─ src/main/resources/<${BASE_PACKAGE} as path>/display/vmtc-fb-activate.py   (Appendix A.4)
│  └─ src/test/java/…            unit tests U1–U20 (no container engine)
├─ junit5/     (${ART_JUNIT})    @VmTest extension (§9.13); src/test/java holds T34 (task `extensionIT`, needs the engine)
├─ test-support/                 NOT published. @Requirement, RequirementExtension, RequirementsCatalog,
│  │                             RequirementsCheck (main), Evidence (§11.4); resources get a copy of
│  └─ src/main/…                 docs/vm-testcontainer/REQUIREMENTS.md at build time
├─ images/
│  ├─ runner/    Containerfile (§6.3), repos/epel.repo, repos/rocky.repo (A.1, A.2), scripts/vmtc-entrypoint (§7.1)
│  ├─ recorder/  Containerfile (§6.5), repos/ (same two files), scripts/vmtc-rec (§7.2)
│  ├─ guest/     guest.properties, customize.sh (§10.3; builds the reference and the black-box guest)
│  └─ build.gradle   buildRunner, buildRecorder, verifyRunnerImages, downloadGuestBase, generateTestSshKey,
│                    buildGuestDisk, buildBlackBoxGuestDisk, buildGuestDisks, pushImages (§11.2)
├─ it/                           integration tests against real Podman (§13); not published
│  ├─ src/test/java/…            VmIT base class, VmEvidence, TestImages, TestAccess, T1–T43 except T34 (incl. KvmCompatIT, OciAuthIT, BlackBoxIT)
│  └─ fixtures/                  test-registry.sh (T27/T28), kvm-scenario.sh (T35)
├─ docs/vm-testcontainer/        PLAN.md, REQUIREMENTS.md, PLAN-STEPS.md (copied from this plan at adoption)
└─ README.md                     user documentation (PLAN-STEPS.md step 6.6)
```

---

## 6. Runner and recorder container images

### 6.1 Images and tags (D17)

| Image | Tag | Contents | Label `${LABEL_PREFIX}.role` |
|-------|-----|----------|------------------------------|
| runner | `${IMAGE_RUNNER}:${VERSION}-${IMAGE_TAG_SUFFIX}` | QEMU, qemu-img, firmware, tini, xorriso, python3, jq, socat | `runner` |
| recorder | `${IMAGE_RECORDER}:${VERSION}-${IMAGE_TAG_SUFFIX}` | GStreamer (`base`, `base-tools`, `good`, `bad-free`), tini, procps-ng, `vmtc-rec` | `recorder` |

- The runner has **no GStreamer**; screen recording always runs in a recorder container that the library starts beside the VM on first use (§9.8). There is no runner variant to choose.
- The role label is checked at build time (§6.4 check 6) and read by the library when a custom image is passed (`QemuVmContainer(DockerImageName)` requires `role=runner`; `withRecorderImage` requires `role=recorder`; a missing label gives one WARN and is accepted).

### 6.2 Repositories (R11)

| File (in `images/runner/repos/`) | Repo IDs | Priority | Notes |
|----------------------------------|----------|----------|-------|
| UBI's own `/etc/yum.repos.d/ubi.repo` | `ubi-9-*` | **10** (sed in §6.3) | — |
| `epel.repo` (Appendix A.1) | `epel9` | 20 | gpgcheck=1 |
| `rocky.repo` (Appendix A.2) | `rocky-fallback-baseos`, `rocky-fallback-appstream`, `rocky-fallback-crb` | 90 | gpgcheck=1. `excludepkgs=rocky-release* rocky-logos* rocky-gpg-keys* rocky-repos*`. The `baseurl` contains the literal `${ROCKY_RELEASE}` value; this file is that value's only home |

When Rocky moves `${ROCKY_RELEASE}` to `vault.rockylinux.org`, change only the host in `rocky.repo`. As of 2026-10-18, `pub/rocky/9.8` is live and `vault/rocky/9.8` returns 404 **[V]**.

### 6.3 Runner Containerfile (normative; `images/runner/Containerfile`) **[U]** until the first `verifyRunnerImages` run (the spike built an equivalent `Containerfile.spike` **[V]**)

```dockerfile
ARG UBI_IMAGE
FROM ${UBI_IMAGE}
ARG VERSION
ARG LABEL_PREFIX
ARG IMAGE_TITLE
LABEL org.opencontainers.image.title="${IMAGE_TITLE}" \
      org.opencontainers.image.version="${VERSION}" \
      ${LABEL_PREFIX}.role="runner"
COPY repos/ /etc/yum.repos.d/
RUN sed -i '/^\[/a priority=10' /etc/yum.repos.d/ubi.repo \
 && rpm --import https://dl.rockylinux.org/pub/rocky/RPM-GPG-KEY-Rocky-9 \
 && rpm --import https://dl.fedoraproject.org/pub/epel/RPM-GPG-KEY-EPEL-9 \
 && dnf -y --setopt=install_weak_deps=False install qemu-kvm-core qemu-img edk2-ovmf seabios-bin tini xorriso jq socat python3 \
 && dnf clean all \
 && rpm -q redhat-release && ! rpm -qa 'rocky-*' | grep -q . \
 && mkdir -p /vm/base /vm/run /vm/out /vm/seed
COPY scripts/ /usr/local/bin/
RUN chmod 0755 /usr/local/bin/vmtc-entrypoint
EXPOSE 4444 4445 5900
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/vmtc-entrypoint"]
```

Notes:

- All `ARG`s are passed by Gradle (§11.2): `UBI_IMAGE`=`${UBI_IMAGE}`, `LABEL_PREFIX`=`${LABEL_PREFIX}`, `IMAGE_TITLE`=`${IMAGE_RUNNER_NAME}`.
  - `rpm --import` and `dnf install` were verified with the same repos via `install-packages.sh` **[V]**.
  - The `LABEL` key containing an ARG is checked by §6.4 check 6.
- The `rpm -q redhat-release` line is a guard: the build fails if a Rocky release package sneaks in.
- `tini` without `-g`: SIGTERM reaches `vmtc-entrypoint`, which shuts QEMU down through the internal QMP socket (§7.1 step 8).
- The container runs as root inside. Under rootless Podman, that is the invoking host user.
- `EXPOSE` is informational only: the library publishes exactly the ports it needs with `PublishAllPorts=false` (§9.2 step 4); forwarded guest ports (10000+k) are exposed per container at start.

### 6.4 Image verification (`verifyRunnerImages`; covers the runner **and** the recorder image)

Run each check inside the image it names (`<engine> run --rm --entrypoint /bin/bash <image> -c '<check>'`) unless marked *host*; all must succeed. The REQUIREMENTS.md rows in brackets are what each check proves. "both" = runner and recorder.

1. *runner:* `/usr/libexec/qemu-kvm -accel help | grep -qx tcg` [REQ-IMG-04]
2. *runner:* `/usr/libexec/qemu-kvm -device help` lists every one of: `VGA`, `secondary-vga`, `ich9-intel-hda`, `hda-duplex`, `virtio-serial-pci`, `virtserialport`, `qemu-xhci`, `usb-storage`, `usb-kbd`, `usb-tablet`, `usb-mouse`, `pcie-root-port`, `virtio-blk-pci`, `virtio-net-pci`, `virtio-scsi-pci`, `scsi-cd`, `pc-dimm` [REQ-IMG-04]
3. *recorder:* `gst-inspect-1.0 <e>` succeeds for `rfbsrc`, `vp8enc`, `webmmux`, `videorate`, `videoconvert`; `command -v gst-discoverer-1.0`; `test -x /usr/local/bin/vmtc-rec`; `! command -v qemu-kvm && ! test -e /usr/libexec/qemu-kvm` (no QEMU in the recorder) [REQ-IMG-03, REQ-IMG-06]
4. *both:* `rpm -q redhat-release` succeeds, and `rpm -qa 'rocky-*'` prints nothing [REQ-IMG-01]
5. *host, both:* `<engine> image inspect --format '{{index .Config.Entrypoint 0}}' <image>` prints `/usr/bin/tini` [REQ-IMG-05]
6. *host, both:* `<engine> image inspect --format '{{index .Labels "<labelPrefix>.role"}}' <image>` prints `runner` for the runner and `recorder` for the recorder [REQ-IMG-03]
7. *both:* repository configuration [REQ-IMG-02]: `grep -h '^priority=' /etc/yum.repos.d/ubi.repo | sort -u` prints only `priority=10`; `/etc/yum.repos.d/epel.repo` contains `priority=20`; every section of `/etc/yum.repos.d/rocky.repo` has `priority=90` and the `excludepkgs` line of Appendix A.2; every `.repo` file the build added has `gpgcheck=1`.
8. *runner:* `! command -v gst-launch-1.0` (the runner carries no GStreamer) [REQ-IMG-03]
9. *both:* `. /etc/os-release && [ "$ID" = rhel ] && [ "$VERSION_ID" = <tag of ${UBI_IMAGE}, e.g. 9.8> ]` [REQ-IMG-01]
10. Resolution order [REQ-IMG-02]: `dnf -q repoquery --installed --qf '%{from_repo}' <pkg>` prints, in the runner, `rocky-fallback-appstream` for `qemu-kvm-core`, `epel9` for `tini`, and a repo id starting with `ubi-9-` for `jq`; in the recorder, `rocky-fallback-appstream` for `gstreamer1-plugins-good`, `epel9` for `tini`, and an `ubi-9-` id for `gstreamer1`. Only packages installed by the Containerfile's own `dnf install` are meaningful here: for packages that came with the base image layer (e.g. `bash`) dnf 4.14 prints an opaque transaction id instead of a repo **[V]** (checked on `rockylinux:9`, dnf 4.14.0).
11. *recorder:* `/usr/local/bin/vmtc-rec status nothing` prints `stopped` and exits 0 (the script runs in this image) [REQ-IMG-06]

### 6.5 Recorder Containerfile (normative; `images/recorder/Containerfile`) **[U]** as a file (the packages and `rfbsrc` pipeline were verified inside the former media runner **[V]**, and running them in a separate container on a Podman network was verified in spike run 36 **[V]**, §3.9; §13 T8/T43 re-prove it in the target repository)

```dockerfile
ARG UBI_IMAGE
FROM ${UBI_IMAGE}
ARG VERSION
ARG LABEL_PREFIX
ARG IMAGE_TITLE
LABEL org.opencontainers.image.title="${IMAGE_TITLE}" \
      org.opencontainers.image.version="${VERSION}" \
      ${LABEL_PREFIX}.role="recorder"
COPY repos/ /etc/yum.repos.d/
RUN sed -i '/^\[/a priority=10' /etc/yum.repos.d/ubi.repo \
 && rpm --import https://dl.rockylinux.org/pub/rocky/RPM-GPG-KEY-Rocky-9 \
 && rpm --import https://dl.fedoraproject.org/pub/epel/RPM-GPG-KEY-EPEL-9 \
 && dnf -y --setopt=install_weak_deps=False install tini procps-ng gstreamer1 gstreamer1-plugins-base gstreamer1-plugins-base-tools gstreamer1-plugins-good gstreamer1-plugins-bad-free \
 && dnf clean all \
 && rpm -q redhat-release && ! rpm -qa 'rocky-*' | grep -q . \
 && mkdir -p /rec
COPY scripts/ /usr/local/bin/
RUN chmod 0755 /usr/local/bin/vmtc-rec
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["sleep", "infinity"]
```

Notes:

- `images/recorder/repos/` holds the same two files as the runner (Appendix A.1, A.2); Gradle keeps them identical (`buildRecorder` copies them from `images/runner/repos/` before the build).
- `IMAGE_TITLE`=`${IMAGE_RECORDER_NAME}`.
- `tini` is PID 1 so stopped `gst-launch` processes are reaped (rule a applies here, not to the runner, which no longer runs recorders). `sleep infinity` keeps the container alive; `vmtc-rec` is driven through `execInContainer`.
- No ports are exposed: the recorder connects **out** to the VM container's VNC servers over the container network (§9.8).

---

## 7. In-container scripts

### 7.1 `vmtc-entrypoint` (bash, `set -euo pipefail`)

**Inputs:** environment variables. Java always sets every one of them; the script **also** applies the defaults below with `${VAR:-default}` so it can be run by hand (PLAN-STEPS.md step 1.9).

| Env var | Default | Meaning |
|---------|---------|---------|
| `VM_ACCEL` | `auto` | `kvm`, `tcg` or `auto` (auto = `kvm` if `/dev/kvm` is readable and writable, else `tcg`) |
| `VM_CPUS` | `2` | Boot vCPUs |
| `VM_MAXCPUS` | `max(4, VM_CPUS)` | Hot-plug ceiling |
| `VM_MEM_MB` | `2048` | Boot RAM |
| `VM_MEM_SLOTS` | `4` | DIMM slots |
| `VM_MAXMEM` | `8G` | Hot-plug memory ceiling (Java guarantees `VM_MAXMEM ≥ VM_MEM_MB`, §9.2) |
| `VM_SCREENS` | `1` | 1..4 heads |
| `VNC_BIND` | `0.0.0.0` | Bind inside the container |
| `VM_HOTPLUG_PORTS` | `4` | Number of `pcie-root-port`s `hp1..hpN` (0..8) |
| `VM_FIRMWARE` | `bios` | `bios` or `uefi` |
| `VM_OVERLAY_SIZE` | *(empty)* | Optional overlay virtual size, e.g. `20G` |
| `VM_PORT_FORWARDS` | *(empty)* | Comma-separated `<guestPort>:<containerPort>` pairs, e.g. `22:10000,8080:10001`; each becomes a slirp `hostfwd` on `net0` (§8.5). Java assigns container ports 10000+k in forward order |
| `VM_EXTRA_ARGS` | *(empty)* | Space-separated extra QEMU args (Java guarantees no arg contains whitespace) |

**Steps:**

0. `mkdir -p /vm/run /vm/out`
1. If `/vm/base/disk.img` is missing: print `VMTC_ERROR no base disk` and exit 2.
2. `FMT=$(qemu-img info --output=json /vm/base/disk.img | jq -r .format)`
3. `qemu-img create -q -f qcow2 -F "$FMT" -b /vm/base/disk.img /vm/run/overlay.qcow2 ${VM_OVERLAY_SIZE:-}`
4. If `/vm/seed/user-data` exists:
   `xorriso -as mkisofs -quiet -V cidata -J -r -o /vm/run/seed.iso /vm/seed/user-data /vm/seed/meta-data`
5. Resolve the accelerator:
   - `VM_ACCEL=kvm` and `/dev/kvm` is not readable and writable → print `VMTC_ERROR kvm requested but /dev/kvm unusable` and exit 3.
   - Print `VMTC_ACCEL kvm|tcg`.
6. If `VM_FIRMWARE=uefi`: `cp /usr/share/edk2/ovmf/OVMF_VARS.fd /vm/run/OVMF_VARS.fd`
6a. Port forwards, written over exactly these five lines with `done` at column 0 (PLAN-STEPS.md step 1.7 extracts them with `sed -n '/^HOSTFWD=/,/^done/p'`):

    ```bash
    HOSTFWD=""
    PF=${VM_PORT_FORWARDS:-}
    for pf in ${PF//,/ }; do
      HOSTFWD+=",hostfwd=tcp:0.0.0.0:${pf##*:}-:${pf%%:*}"
    done
    ```

    The `:-` default matters under `set -u` **[V]** (run 31). `$HOSTFWD` is appended to the `-netdev user,id=net0` option (§8.1).
7. Build the QEMU argument **array** exactly as in §8.1 (no `eval`; `VM_EXTRA_ARGS` is word-split into the array). Print `VMTC_QEMU_STARTING <epoch>`.
8. **Install the trap before QEMU starts:** `trap on_term TERM INT`, where `on_term` runs
   `printf '{"execute":"qmp_capabilities"}\n{"execute":"quit"}\n' | socat -t2 - UNIX-CONNECT:/vm/run/qmp-internal.sock` **[V]**, waits up to 10 s for QEMU to exit, then `kill -9 "$QPID"` if it is still running.
9. Start QEMU **in the background**: `"${ARGS[@]}" & QPID=$!`. Poll every 50 ms, for up to 30 s, until `(exec 3<>/dev/tcp/127.0.0.1/4445)` succeeds.
   - This uses the QGA port, **not** the QMP port, so it never occupies the single QMP TCP client slot.
   - If QEMU exits first: print `VMTC_ERROR qemu exited <code>` and exit 4.
10. Print `VMTC_READY`.
11. Wait for QEMU and exit with **its** status. A trapped signal interrupts `wait`, so loop:

    ```bash
    set +e
    while :; do
      wait "$QPID"; RC=$?
      kill -0 "$QPID" 2>/dev/null || break
    done
    exit "$RC"
    ```

**Graceful shutdown happens only through Java `stop()`** (§9.2): it stops open recordings, sends QMP `quit` over the Java connection, then lets Testcontainers stop the container. The trap only covers a manual `podman stop`.

### 7.2 `vmtc-rec` (bash; runs in the **recorder** container, D17)

```
vmtc-rec start  <name> <vnc-host> <vnc-port> <fps> <bitrate-bps>
vmtc-rec stop   <name> [timeout-seconds=10]
vmtc-rec status <name>
```

`<vnc-host>` is the VM container's network alias (`vm-<id>`, §9.8) and `<vnc-port>` is `5900+i`; both are resolved on the container network, not on the host.

**`start`:**

1. Run detached (`setsid … < /dev/null > /rec/<name>.log 2>&1 &`; `$!` is `gst-launch`'s PID only because job control is off in a non-interactive script, so never `set -m` here):

   ```
   gst-launch-1.0 -q -e rfbsrc host=<vnc-host> port=<vnc-port> view-only=true shared=true incremental=false \
     ! videorate ! video/x-raw,framerate=<fps>/1 ! videoconvert \
     ! vp8enc deadline=1 cpu-used=8 threads=2 target-bitrate=<bitrate> ! webmmux ! filesink location=/rec/<name>.webm
   ```

2. Write the PID to `/rec/<name>.pid` and print `VMTC_REC_STARTED <pid>`. If `gst-launch-1.0` is missing or exits within 1 s, print `VMTC_REC_INVALID` and the log instead (Java maps that to `RecordingException`, §9.8).

This is the verified `gst-record.sh` + `rec.sh` **[V]** with the target changed from `127.0.0.1:$((5900+i))` to the VM's network alias **[U]** (the spike also verified `rfbsrc password=…`; passwords were dropped by D8).

**`stop`:**

0. If the PID from `/rec/<name>.pid` is already gone (or a zombie) **before** the signal is sent, the recorder died on its own: print `VMTC_REC_DIED` followed by the last 20 lines of `/rec/<name>.log` and stop here (Java maps it to `RecordingException`, §9.8). A silently truncated file is never reported as success.
1. `kill -INT <pid>`.
2. Poll every 100 ms. The process counts as alive only if `/proc/<pid>` exists **and** its `State:` is not `Z`. Give up after the timeout.
3. If it is still alive: `kill -9` it and print `VMTC_REC_KILLED`.
4. Validate with `gst-discoverer-1.0 /rec/<name>.webm`. Its output contains lines `Duration: H:MM:SS.nnnnnnnnn`, `Width: <w>`, `Height: <h>` **[V]**.
   - On success, print `VMTC_REC_OK duration=<seconds> width=<w> height=<h>`, where `<seconds>` is the duration converted to **decimal seconds with 3 fractional digits** (e.g. `duration=12.346`).
   - Otherwise print `VMTC_REC_INVALID` followed by the last 20 log lines.
5. **Ignore gst-launch's exit code** (it is 1 even on success) **[V]**.

**`status`:** prints `running` or `stopped` (also `stopped` for an unknown name, so §6.4 check 11 can call it in a fresh image).

**Defaults** (set by Java): fps 10, bitrate 1 000 000 **[V]**.

---

## 8. QEMU configuration

### 8.1 Command line (normative; built by `vmtc-entrypoint`)

```
/usr/libexec/qemu-kvm -name vm -nodefaults -no-user-config -M q35
  {kvm: -accel kvm -cpu host | tcg: -accel tcg -cpu max}
  -smp ${VM_CPUS},maxcpus=${VM_MAXCPUS}  -m ${VM_MEM_MB},slots=${VM_MEM_SLOTS},maxmem=${VM_MAXMEM}
  -display none
  for i in 0..VM_SCREENS-1:
     -device {i==0: VGA | else: secondary-vga},id=video$i
     -vnc ${VNC_BIND}:$i,id=vnc$i,display=video$i,share=force-shared
  -qmp tcp:0.0.0.0:4444,server=on,wait=off
  -qmp unix:/vm/run/qmp-internal.sock,server=on,wait=off
  -chardev socket,id=qga0,host=0.0.0.0,port=4445,server=on,wait=off
  -device virtio-serial-pci -device virtserialport,chardev=qga0,name=org.qemu.guest_agent.0
  -serial file:/vm/out/serial.log
  -drive file=/vm/run/overlay.qcow2,format=qcow2,if=none,id=disk0 -device virtio-blk-pci,drive=disk0,bootindex=0
  [seed present: -drive file=/vm/run/seed.iso,format=raw,if=none,id=seed,readonly=on -device virtio-blk-pci,drive=seed]
  -netdev user,id=net0${HOSTFWD} -device virtio-net-pci,netdev=net0      (HOSTFWD = ",hostfwd=tcp:0.0.0.0:<cport>-:<gport>" per forward, §7.1 step 6a)
  -audiodev none,id=snd0 -device ich9-intel-hda -device hda-duplex,audiodev=snd0
  -device qemu-xhci,id=xhci
  -device virtio-scsi-pci,id=scsi0 -device scsi-cd,id=cd0,bus=scsi0.0
  for j in 1..VM_HOTPLUG_PORTS: -device pcie-root-port,id=hp$j,chassis=$j
  [uefi: §8.4]
  ${VM_EXTRA_ARGS}
```

**Verification.** The verified reference is `start-vm.sh` (CI, all runs) **[V]**. The production command line differs from it only in these points:

| Item | Reference script | Production |
|------|------------------|------------|
| QMP/QGA bind | `127.0.0.1` (accessed via `podman exec`) | `0.0.0.0`, so the ports can be published. Host-side publishing is loopback (§9.2) |
| Internal QMP Unix monitor | absent | added; verified separately [V] |
| `VNC_BIND` default | `127.0.0.1` | `0.0.0.0` |
| `slots` | fixed 4 | variable |
| Seed ISO path | `/vm/seed.iso` | `/vm/run/seed.iso` |
| Overlay | always 20G | `VM_OVERLAY_SIZE` |
| `-name` | `spikevm` | `vm` |
| UEFI | `VM_FIRMWARE=uefi` (run 19) | §8.4 [V] |
| Port forwards | `VM_PORT_FORWARDS` (run 31, `22:10022`) | §8.5, container ports 10000+k [V] |

### 8.2 Acceleration (R12)

| `KvmMode` | Host KVM usable (§9.6) | Container config |
|-----------|------------------------|------------------|
| `REQUIRE` | yes | `--device /dev/kvm` (+ annotation per §9.6), `VM_ACCEL=kvm` |
| `REQUIRE` | no | `start()` throws `KvmUnavailableException` (message per §9.6) |
| `PREFER` (default) | yes | `--device /dev/kvm` (+ annotation per §9.6), **`VM_ACCEL=auto`** (falls back to TCG if the device cannot be opened in the container) |
| `PREFER` | no | no device, `VM_ACCEL=tcg`, one WARN per JVM |
| `DISABLE` | any | no device, `VM_ACCEL=tcg` |

- **Actual accelerator:** `accelerator()` returns what `VMTC_ACCEL` reported.
- **PREFER fallback:** when the result is TCG under PREFER although KVM was intended, log a WARN with the §9.6 remediation text.
- **TCG timeouts:** every default timeout is multiplied by `tcgTimeoutFactor` (default **6**; measured QGA-ready ratio about 4–5.5).

### 8.3 Screens and VNC (D5, R9, D8)

- `withScreens(n)`, n = 1..4 (default 1). Head 0 is `VGA`; heads 1..n-1 are `secondary-vga` **[V]** (n=2; n=4 is exercised by T6).
- **Ports:** head *i* has a VNC server on container port `5900+i`, published per §9.2 step 4 on the configured bind address (default `127.0.0.1`).
- **Sharing:** `share=force-shared` is always on.
- **Authentication:** none (D8). The spike verified per-display `set_password` **[V]** should it ever be needed; it is not part of this plan.
- **Dark secondary heads:** until the guest modesets them, they show "Guest has not initialized the display (yet)" at 640×480 [V]. Use `VmScreen.forceModeset()` for console-only guests.

### 8.4 UEFI (`VM_FIRMWARE=uefi`)

```
-drive if=pflash,format=raw,unit=0,readonly=on,file=/usr/share/edk2/ovmf/OVMF_CODE.fd
-drive if=pflash,format=raw,unit=1,file=/vm/run/OVMF_VARS.fd
```

- Both files exist in Rocky 9's `edk2-ovmf-20241117-8.el9` **[V]** (§3.2). The non-secure-boot `OVMF_CODE.fd` needs no `smm=on`.
- **Verified (spike run 19, KVM):** the customised Rocky 9 guest boots under this firmware pair exactly as under BIOS: QGA ready 11.5 s after container start, `/sys/firmware/efi` present with `Boot0000..Boot0002` efivars and `fw_platform_size` 64, and every other feature check (exec, copy, screenshots, keyboard, 2-screen recording, audio, all hot-plug cases) passes **[V]**.
- Side effect worth knowing: under OVMF **both** heads reported 1280×800 at the first screenshot, i.e. the firmware's GOP initialises the secondary head, so `forceModeset()` is a no-op there; under SeaBIOS head 1 stays 640×480 until the guest modesets it **[V]** (same run).

### 8.5 Guest port forwards (`withGuestPortForward`, SSH access) [V]

- Every configured forward `k` (0-based, in configuration order) becomes `hostfwd=tcp:0.0.0.0:<10000+k>-:<guestPort>` on `net0` (§7.1 step 6a, §8.1) and container port `10000+k` is exposed and published to `127.0.0.1` like QMP (§9.2 step 4).
- `GuestAccess.ssh(...)` adds an implicit forward of the SSH port (default 22) at `k = 0` **regardless of call order** (`PortForwards` places it first at `configure()` time, so `withGuestPortForward(8080)` followed by `withGuestAccess(ssh)` still yields `22:10000,8080:10001`); an explicit `withGuestPortForward(22)` is then a duplicate and ignored.
- `Readiness.guestPort(p, …)` requires `p` to be forwarded (implicitly or by `withGuestPortForward(p)`); otherwise `start()` throws `IllegalArgumentException("guestPort readiness needs withGuestPortForward(<p>)")` (start-time validation, §9.2).
- `forwardedPort(guestPort)` returns the mapped **host** port for a forward; unknown ports → `IllegalArgumentException`.
- **slirp accepts a host-side connect at once** (it answers the handshake itself and only then opens the guest connection); a guest port with no listener shows up as an **immediate EOF** after the connect (0 ms, run 34), a filtered one as silence, never as a refused connect (rule n, §3.9). A readiness loop therefore treats EOF, a read timeout and an I/O error alike: close and retry.
- Verified in run 31: SSH from the host JVM through `127.0.0.1:<mapped 10022>` on rootless Podman 4.9.3, KVM and TCG, both guests. Verified in run 34: a second forward carrying HTTP (`python3 -m http.server 8080`) on both guests (§3.9).

---

## 9. Java library design

### 9.1 Packages, types and runtime overrides

| Package | Types |
|---------|-------|
| `${BASE_PACKAGE}` | `QemuVmContainer`, `VmImage` (sealed interface; nested records `PathImage(Path file)`, `UrlImage(URI url, UrlOptions options)`, `OciImage(DockerImageName image, OciOptions options)`, `BakedImage()`), `KvmMode`, `Accelerator`, `Firmware`, **`GuestAccess`** (sealed interface, D15; nested records `SshAccess(SshCredentials credentials, SshOptions options)`, `AgentAccess()`, `NoAccess()`; factories `ssh(SshCredentials)`, `ssh(SshCredentials, SshOptions)`, `guestAgent()`, `none()`), **`SshCredentials`** (record `(String user, Secret password, Path privateKey, String privateKeyPem, Secret passphrase)` with factories `password(user, Secret)`, `privateKey(user, Path)`, `privateKey(user, Path, Secret passphrase)`, `privateKeyPem(user, String pem)`; `toString()` prints the user only), **`SshOptions`** (record `(int port, boolean sudo, HostKeyPolicy hostKey)`; `defaults()` = `(22, false, acceptAny())`; `builder().port(int).sudo(boolean).hostKey(HostKeyPolicy).build()`), **`HostKeyPolicy`** (sealed: `AcceptAny()`, `Pinned(String openSshPublicKeyLine)`; factories `acceptAny()`, `pin(String)`), **`Readiness`** (sealed interface; nested records `GuestAgentPing()`, `SshHandshake()`, `BootOnly()`, `GuestPort(int guestPort, byte[] probe)` (`probe` is never `null`; `guestPort(int)` = `guestPort(p, new byte[0])`; the record copies the array in and compares it with `Arrays.equals` in a custom `equals`/`hashCode`); factories `guestAgent()`, `ssh()`, `bootOnly()`, `guestPort(int)`, `guestPort(int, byte[] probe)`), **`Secret`** (moved here from `image`; shared by HTTP, OCI and SSH credentials; `Secret.of(String)`, `Secret.of(char[])`, `Secret.fromEnv(String)`, `char[] value()`, `toString()` = `***`, §9.3.5), `ExecRequest` (record: `List<String> argv`, `Map<String,String> env`, `byte[] stdin`, `Duration timeout` (`null` = the container's exec timeout); `ExecRequest.of(String... argv)` gives empty env, empty stdin, `null` timeout; copy methods `withEnv(Map<String,String>)`, `withStdin(byte[])`, `withTimeout(Duration)`; `stdin` is copied in and compared with `Arrays.equals`), `GuestExecResult` (record, §9.4), `Warnings` (§9.11), `VmtcConstants` (generated), `VmtcCleanup`, `VmtcException` and its subclasses `KvmUnavailableException`, **`GuestNotReadyException`** (replaces the former `GuestAgentTimeoutException`), **`GuestAccessUnavailableException`**, **`SshAuthException`**, `GuestCommandDisabledException`, `GuestAgentConfinedException`, `GuestExecTimeoutException`, `RecordingException`, `HotplugTimeoutException`, `ImageChecksumException`, `ImageAuthException`, `ImageDownloadException` |
| `${BASE_PACKAGE}.qmp` | `QmpClient`, `QmpEvent`, `QmpException` (extends `VmtcException`). Escape hatch (rule p) |
| `${BASE_PACKAGE}.qga` | `GuestAgentClient`. Escape hatch (rule p) |
| `${BASE_PACKAGE}.image` | `VmImageCache`, `ContainerDiskExtractor`, `ImageDownloader`, `UrlOptions`, `OciOptions`, `HttpCredentials`, `RegistryCredentials`, `Compression` (`AUTO`, `NONE`, `GZIP`, `XZ`, `ZSTD`), `Qcow2Header`, `SparseCopy` |
| `${BASE_PACKAGE}.display` | `VmScreen`, `VncEndpoint`, `ScreenRecording`, `RecordingOptions`, `KeyboardInput` |
| `${BASE_PACKAGE}.audio` | `AudioRecording` |
| `${BASE_PACKAGE}.hotplug` | `Devices`, `DeviceSpec` (sealed interface; nested records `UsbStorage(Path image, boolean readOnly)`, `UsbHid(HidKind kind)` with enum `HidKind {KEYBOARD, TABLET, MOUSE}`, `VirtioDisk(Path image, boolean readOnly)`, `BlankDisk(String size, DiskBus bus)`, `Nic(String mac)` (`mac` nullable), `Cpu()`, `Memory(int sizeMiB)`), `DiskBus` (`USB`, `VIRTIO`), `HotplugHandle`, `CdromDrive` |
| `${BASE_PACKAGE}.cloudinit` | `CloudInitSeed` |
| `${BASE_PACKAGE}.internal` | `KvmDetector`, `KvmDecision`, `Overrides`, `StartupMessages`, `VmWaitStrategy`, `SerialLogTailer`, `PortBindings`, `PortForwards`, `Json`, `Backoff`, `Session` (the per-JVM session UUID), `VmtcNetwork` (the per-JVM container network, §9.8) |
| `${BASE_PACKAGE}.internal.backend` | `VmBackend` (interface: screenshot, send keys, hot-plug add/remove, audio capture start/stop, VNC endpoint, serial tail, power off, recorder control) and `QemuPodmanBackend` (QMP + `execInContainer`); D18 |
| `${BASE_PACKAGE}.internal.transport` | `GuestTransport` (interface: `awaitReady(Readiness, Instant deadline)`, `exec`, `writeFile`, `readFile`, `close`), `QgaTransport` (wraps `GuestAgentClient`), `SshTransport` (MINA SSHD, shaded), `NoTransport` (throws `GuestAccessUnavailableException`), `SshCommandBuilder` (§9.4) |
| `${BASE_PACKAGE}.shaded.…` | relocated `org.apache.sshd.*` and `net.i2p.crypto.*` classes (build output of the shadow plugin, §11.1; never referenced outside `internal.transport`) |

**No `module-info.java` (D20).** The jar's manifest carries `Automatic-Module-Name: ${BASE_PACKAGE}` (§11.1), so module-path consumers get a stable automatic module. `internal`, `internal.*` and `shaded.*` are internal by convention: U14 proves that no public API signature mentions them (§9.14), and the Javadoc marks them. Reason: `testcontainers-2.0.5.jar` is not even an automatic module, so a `requires` on it does not compile, and a descriptor requiring the SSH library would be shipped inside the relocated jar (§3.10).

Every exception class declares `private static final long serialVersionUID = 1L;` (the build uses `-Xlint:all -Werror`, §11.1).

**Dependencies:**

| Scope | Dependencies |
|-------|--------------|
| `api` | `org.testcontainers:testcontainers:${TESTCONTAINERS_VERSION}` |
| `implementation` | `jackson-databind:${JACKSON_VERSION}`, `commons-compress:${COMMONS_COMPRESS_VERSION}`, `org.tukaani:xz:${XZ_VERSION}`, `com.github.luben:zstd-jni:${ZSTD_JNI_VERSION}` |
| `shaded` (custom configuration; `compileClasspath`, `runtimeClasspath` and `testRuntimeClasspath` extend it; **relocated into the jar**, absent from the published POM) | `org.apache.sshd:sshd-core:${SSHD_VERSION}`, `org.apache.sshd:sshd-sftp:${SSHD_VERSION}`, `net.i2p.crypto:eddsa:${EDDSA_VERSION}`. Their transitive `org.slf4j:slf4j-api` is **excluded from the shadow jar** (§11.1; slf4j comes from Testcontainers). **Never Bouncy Castle** (D16) |
| `testImplementation` | JUnit BOM `${JUNIT_VERSION}` + `junit-jupiter`, AssertJ, `slf4j-simple:${SLF4J_VERSION}`, `:test-support` |
| `testRuntimeOnly` | `org.junit.platform:junit-platform-launcher` (Gradle 9 no longer adds it [D]); `org.slf4j:slf4j-api` forced to `${SLF4J_VERSION}` (§3.7) |

`checkDependencies` (§11.5) fails the build when `:lib`'s `api`/`implementation`/`shaded` sets differ from the three rows above (REQ-BLD-03, REQ-OCI-06).

**Runtime overrides.** Each key exists as system property `-D${SYSPROP_PREFIX}.<key>` and as environment variable `${ENV_PREFIX}<KEY_IN_UPPER_SNAKE>`, for example `-Dvmtc.recorderImage` or `VMTC_RECORDER_IMAGE`.

**Precedence (D9):** **builder call > system property > environment variable > default.** An override changes the default a container starts from; a value the code sets explicitly always wins. An unparsable override value (for example `kvm=maybe`, `tcgTimeoutFactor=0`, `cleanup=yes`) throws `IllegalArgumentException("<key>: …")` at `start()`.

| Key | Values | Default | Effect |
|-----|--------|---------|--------|
| `kvm` | `REQUIRE`, `PREFER`, `DISABLE` (case-insensitive) | `PREFER` | Default for `withKvm` |
| `cacheDir` | absolute path | `${XDG_CACHE_HOME:-~/.cache}/${CACHE_DIR_NAME}` | Image cache root |
| `cleanup` | `true`, `false` | `true` | Orphan cleanup (§9.12) |
| `runnerImage` | image ref | `VmtcConstants.defaultRunnerImage()` | Runner image for the no-arg constructor; not applied to the `DockerImageName` constructor |
| `recorderImage` | image ref | `VmtcConstants.defaultRecorderImage()` | Default for `withRecorderImage` (§9.8) |
| `guestImage` | image ref | `VmtcConstants.referenceGuestImage()` | Used by `VmImage.referenceGuest()` |
| `blackBoxGuestImage` | image ref | `VmtcConstants.blackBoxGuestImage()` | Used by `VmImage.blackBoxGuest()` |
| `tcgTimeoutFactor` | double > 0 | `6.0` | Default for `withTcgTimeoutFactor` |

### 9.2 `QemuVmContainer` (public API, normative)

```java
public class QemuVmContainer extends GenericContainer<QemuVmContainer> {
  public QemuVmContainer();                                     // runner image from override 'runnerImage', else VmtcConstants.defaultRunnerImage()
  public QemuVmContainer(DockerImageName runnerImage);          // custom/derived runner; label ${LABEL_PREFIX}.role must be "runner" (missing → WARN)

  // configuration (each returns this; ranges are validated in the call → IllegalArgumentException)
  withVmImage(VmImage image)                    // REQUIRED. For derived runner images pass VmImage.baked()
  withGuestAccess(GuestAccess access)           // default GuestAccess.none() (rule m, D15); ssh(...) adds the implicit SSH forward (§8.5)
  withReadiness(Readiness readiness)            // default derived from the access: none→bootOnly, ssh→ssh, guestAgent→guestAgent (§9.2 step 10)
  withGuestPortForward(int guestPort)           // repeatable; 1..65535; duplicates ignored; → VM_PORT_FORWARDS (§8.5)
  withRecorderImage(DockerImageName image)      // default from override 'recorderImage'; label role must be "recorder" (§9.8)
  withKvm(KvmMode mode)                         // default from override 'kvm', else PREFER
  withCpus(int boot) / withMaxCpus(int max)     // defaults 2 / max(4, boot); boot ≥ 1, max ≥ boot
  withMemoryMb(int mb) / withMemoryHotplug(int slots, String maxmem)   // 2048 / 4, "8G"; slots ≥ 0
  withScreens(int n)                            // 1..4, default 1
  withVncBindAddress(String hostIp)             // default "127.0.0.1"
  withHotplugPorts(int n)                       // 0..8, default 4
  withFirmware(Firmware f)                      // BIOS (default) | UEFI
  withCloudInit(CloudInitSeed seed)             // optional (§10.2)
  withGuestReadyTimeout(Duration d)             // default 180 s (scaled under TCG); an explicit value is never scaled. Bounds the seed wait and the readiness check
  withExecTimeout(Duration d)                   // default 60 s (scaled under TCG); explicit never scaled
  withTcgTimeoutFactor(double f)                // default from override, else 6.0; > 0
  withSerialLogConsumer(Consumer<String> line)  // receives every serial-log line (step 13)
  withQemuArgs(String... extra)                 // → VM_EXTRA_ARGS; any arg with whitespace → IllegalArgumentException

  // state
  Accelerator accelerator();                    // KVM | TCG, from VMTC_ACCEL (the source of truth after start)
  GuestAccess guestAccess();                    // the configured access (never null)
  Readiness readiness();                        // the effective readiness
  int forwardedPort(int guestPort);             // mapped host port of a forward (§8.5); IllegalArgumentException if not forwarded
  QmpClient qmp();                              // escape hatch (rule p)
  GuestAgentClient guestAgent();                // escape hatch (rule p); IllegalStateException unless guestAccess() is AgentAccess
  KeyboardInput keyboard();
  Map<String,Object> describe();                // the vm-config evidence of REQUIREMENTS.md §3.3 (Jackson-serialisable); usable after a failed start() too
  Map<String,Long> timings();                   // milliseconds from start(): containerRunning, vmtcReady, sshBanner (SSH readiness only), guestReady (absent for bootOnly),
                                                // started; plus the applied limits guestReadyTimeoutMs, execTimeoutMs and tcgTimeoutFactorX1000 (factor × 1000, rounded).
                                                // After a failed start() it holds the keys reached so far

  // R3 / R4 — through the configured GuestAccess (§9.4, §9.5); GuestAccess.none() → GuestAccessUnavailableException at once
  GuestExecResult exec(String... argv);                 // absolute argv[0] recommended
  GuestExecResult exec(Duration timeout, String... argv);
  GuestExecResult exec(ExecRequest request);            // argv, env, stdin bytes, timeout
  GuestExecResult execShell(String script);             // /bin/sh -c script
  void copyToVm(Path local, String guestPath);          // mkdir -p parent first (exec)
  void copyToVm(byte[] content, String guestPath);
  void copyFromVm(String guestPath, Path local);
  byte[] readFromVm(String guestPath);

  // R6 / R9
  List<VmScreen> screens();  VmScreen screen(int index);

  // R7
  AudioRecording startAudioRecording(Path hostWav);     // one active at a time, else IllegalStateException

  // R10
  HotplugHandle hotplug(DeviceSpec spec);
  CdromDrive cdrom();

  // artifacts
  Path saveSerialLog(Path hostFile);                    // copies /vm/out/serial.log to hostFile and returns hostFile

  @Override public void start();                        // see "start() override" below
}
```

**`start()` override (rule e, REQ-ERR-03).** `start()` first runs the start-time validation below (so its `IllegalArgumentException`s never pass through Testcontainers), then calls `super.start()` inside `try`; on `ContainerLaunchException` it walks the `getCause()` chain and rethrows the **first** exception that is a `VmtcException`, an `IllegalArgumentException`, an `IllegalStateException` or an `UnsupportedOperationException` (preserving it as-is: the image-resolution checks of §9.3 and the image-role check run inside `configure()`, which Testcontainers wraps); if none is found it throws `new VmtcException(e.getMessage(), e)`. Testcontainers wraps every start-up failure **[V]**, so without this no caller could catch `KvmUnavailableException` and friends.

**Start-time validation** (in the override, before `super.start()`; all `IllegalArgumentException`): `withVmImage` was called; `VM_MAXMEM` ≥ `withMemoryMb` (parse `G`/`M` suffixes); override values parse (§9.1); `Readiness.guestAgent()` requires `GuestAccess.guestAgent()` and `Readiness.ssh()` requires `GuestAccess.ssh(...)` (the other combinations are allowed: for example `ssh` access with `bootOnly` readiness connects lazily on the first `exec`); `Readiness.guestPort(p, …)` requires `p` to be forwarded (§8.5).

**Image roles.** For `QemuVmContainer(DockerImageName)` the library reads the image label `${LABEL_PREFIX}.role` with `inspectImageCmd` after the image is pulled (in `configure()`): `runner` is accepted, a missing label gives one WARN and is accepted, any other value throws `IllegalArgumentException`. `withRecorderImage` is checked the same way for `recorder` when the recorder starts (§9.8).

**Start sequence** (`configure()`, `containerIsStarting`, `containerIsStarted`):

1. **Image:** resolve the `VmImage` to a cache file (§9.3), unless it is `BakedImage`.
2. **Bind** the cache file read-only at `/vm/base/disk.img` with `addFileSystemBind(hostPath, "/vm/base/disk.img", BindMode.READ_ONLY, selinux)` (the four-argument form is `addFileSystemBind`, not `withFileSystemBind`, in 2.0.5 **[V]** javap), where `selinux` is `SelinuxContext.SHARED` when `/sys/fs/selinux/enforce` exists and contains `1`, else `SelinuxContext.NONE`. Only cache files are ever bound (§9.3.2).
3. **KVM:** `KvmDecision.decide(mode, detector)` (§9.6); apply `HostConfig.withDevices(...)` and, if needed, `withAnnotations(...)` in `withCreateContainerCmdModifier`.
4. **Ports:** `withExposedPorts(4444, 4445, 5900, …, 5900+n-1, 10000, …, 10000+f-1)` where `f` is the number of forwards (§8.5; the implicit SSH forward counts). In the create-cmd modifier: `hostConfig.withPublishAllPorts(false)` (rule l) and replace `PortBindings` with `PortBinding(Ports.Binding.bindIp(addr), ExposedPort.tcp(p))` for each port, which keeps a random host port. `addr` is `127.0.0.1` for 4444, 4445 and every forward, and the configured VNC address for VNC ports.
4a. **Network:** `withNetwork(VmtcNetwork.get())` (one `Network.newNetwork()` per JVM, created lazily, labelled `${LABEL_PREFIX}.managed/session/created` like the containers, closed by a JVM shutdown hook) and `withNetworkAliases("vm-" + <uuid8>)`, so a recorder container can reach the VNC servers by name (§9.8). `VNC_BIND` stays `0.0.0.0` inside the container for the same reason **[V]** (run 36: `Network.newNetwork()` + alias through the compat API on rootless Podman 4.9.3 with netavark/aardvark-dns; T43 re-proves it).
5. **Labels:** `${LABEL_PREFIX}.managed=true`, `${LABEL_PREFIX}.version=<VmtcConstants.VERSION>`, `${LABEL_PREFIX}.session=<internal.Session.id(), a UUID created once per JVM>`, `${LABEL_PREFIX}.created=<epoch seconds>`.
6. **Env:** set §7.1 env, including `VM_PORT_FORWARDS` built from the forward list (`<guestPort>:<10000+k>` joined by `,`). For a seed, add `withCopyToContainer(Transferable.of(seed.userData()), "/vm/seed/user-data")` and the same for `meta-data`.
7. **Wait strategy:** `VmWaitStrategy` (custom `AbstractWaitStrategy`). Every 200 ms it reads the container logs:

   | Condition | Result |
   |-----------|--------|
   | `VMTC_READY` | ready |
   | container exited with code 3, or a `VMTC_ERROR` line containing `kvm requested` | throw `KvmUnavailableException(StartupMessages.kvmGroupRestricted() + "\n" + <the VMTC_ERROR line>)` (the §9.6 row-3 text first) |
   | any other `VMTC_ERROR <msg>` or container exit | throw `VmtcException(msg + "\n" + StartupMessages.tail(logLines, 50))` |
   | 60 s elapsed (**not** scaled) | throw `VmtcException("no VMTC_READY within 60 s\n" + tail 50) |

   `StartupMessages.tail(lines, n)` returns the last `n` lines (all of them when fewer) joined by `\n` (U16).
8. **QMP:** connect to the mapped port 4444 and send `qmp_capabilities`. Parse `VMTC_ACCEL` from the logs; `accelerator()` is now defined. Under PREFER, if KVM was intended but TCG resulted, emit the §9.6 row-3 WARN through `Warnings` (once per JVM). **Timeouts are derived now:** scaled defaults use `accelerator()`; explicit values stay as given.
9. *(removed by D8; numbering kept so references stay valid)*
10. **Guest readiness** (D15). One deadline, `withGuestReadyTimeout` (default 180 s, scaled), covers both parts below.
    - **Seeded start first waits for the seed:** if a seed is configured, start the `SerialLogTailer` (step 13) now and block until a line contains `VMTC_SEED_DONE`; on timeout throw `GuestNotReadyException`. Only then continue. Reason: the seed relaxes the RPC filter and restarts the agent (and may create SSH users), so the checks below would otherwise see the stock state and fail. The spike harness waited for seed completion before its exec checks in the same way **[V]**.
    - Then, by the effective `Readiness`:

    | Readiness | Loop (every attempt; sleep 1 s between attempts) | On deadline |
    |-----------|--------------------------------------------------|-------------|
    | `bootOnly()` | nothing: `start()` continues at once (`timings().guestReady` is absent) | — |
    | `guestAgent()` | (1) TCP connect to the mapped port 4445 with a 3 s socket timeout; (2) `guest-sync-delimited {id}`: skip a leading `0xFF` byte and read until `return == id`; (3) `guest-ping`. Any failure → close the socket, retry. This is the verified `vmctl.py wait-qga` behaviour **[V]** | `GuestNotReadyException("guest agent not ready within <d>" + "\n" + tail(serialLines, 50))` |
    | `ssh()` | (1) TCP connect to `forwardedPort(sshPort)` with a 3 s socket timeout; (2) read until the `SSH-` banner line arrives (3 s read timeout; **a connect without a banner is not readiness**, rule n); (3) `SshTransport.connect()`: key exchange + authentication + `exec("true")`. Any failure → close, retry **[V]** (run 31: banner at 15–21 s KVM, 64–93 s TCG; first auth succeeded) | `SshAuthException` if the banner was seen and **every** authentication attempt failed, else `GuestNotReadyException("ssh not ready within <d>" + "\n" + tail(serialLines, 50))` |
    | `guestPort(p, probe)` | (1) TCP connect to `forwardedPort(p)` (3 s); (2) if `probe` is non-empty, write it; (3) read ≥ 1 byte within 3 s (rule n). Any failure, **including an immediate EOF** (no listener yet, §8.5) → close, retry **[V]** (run 34: HTTP probe answered in 4–5 ms; the no-probe form timed out on HTTP as expected; a port without a listener gave EOF at once) | `GuestNotReadyException("guest port <p> not ready within <d>" + tail) |

    The `probe` for HTTP services is e.g. `"HEAD / HTTP/1.0\r\n\r\n"` (the Javadoc shows it); `guestPort(p)` without a probe waits for a banner (SSH, SMTP, …) and would wait for the whole timeout on a protocol where the client speaks first (run 34).
11. **Agent checks (only under `GuestAccess.guestAgent()`):** call `guest-info`. `describe()` records `agentChecks: "run"` here and `agentChecks: "skipped"` under every other access (REQ-ACC-10's evidence). If `guest-exec` or `guest-file-open` has `enabled:false`, throw `GuestCommandDisabledException` [V].
    - Independently, any QGA error with class `CommandNotFound` and a desc containing `has been disabled` is mapped to `GuestCommandDisabledException` [V].
    - The message points to §10.1 G3/G4.
12. **SELinux check (only under `GuestAccess.guestAgent()`):** `exec("/usr/bin/id","-Z")`. A non-zero exit or empty stdout (non-SELinux guest) → skip this step. If stdout contains `virt_qemu_ga_t`:
    - Run `execShell("ls /lib/modules/$(uname -r) >/dev/null")`.
    - Non-zero exit with stderr containing `Permission denied` means the domain is enforcing: throw `GuestAgentConfinedException` (remediation G4). This exact denial was observed **[V]**.
    - Under SSH access neither check runs: sshd sessions are unconfined and the RPC filter is irrelevant **[V]** (§3.9).
13. **Serial log:** if a consumer is set and the tailer is not already running (step 10), start `SerialLogTailer`.
    - It is a daemon thread that polls every 1 s: `execInContainer("tail","-c","+<offset+1>","/vm/out/serial.log")`.
    - It tracks the byte offset, strips `\r`, splits on `\n`, and keeps partial last lines.
    - Lines read before the consumer was attached (the seed wait) are replayed to the consumer when it attaches (REQ-LIFE-07 "lines emitted before `start()` returned").
14. Record `timings()`; cleanup (§9.12) ran once per JVM before step 1 of the **first** container.

**After a failed `start()`:** `getLogs()`, `describe()`, `timings()`, `saveSerialLog` and `stop()` still work on the instance (the container, if it was created, stays until `stop()`; `exec` and friends throw `IllegalStateException("not started")`). `VmIT` registers such containers too, attaches the standard evidence and stops them in `@AfterEach` (Ryuk is off). Exceptions thrown by steps 11–12 embed the relevant guest output in their message (`GuestCommandDisabledException`: the `guest-info` JSON; `GuestAgentConfinedException`: the `id -Z` output and the denied command's stderr), so tests can attach the evidence without a live guest.

**`stop()`:**
1. Stop every open `ScreenRecording` and `AudioRecording` (best effort, log failures through `Warnings`).
2. Close the guest transport (SSH session or QGA socket; best effort).
3. QMP `quit` with a 5 s timeout (not scaled); ignore errors.
4. Close the clients.
5. `super.stop()`.
6. Stop and remove the recorder container, if one was started (§9.8; best effort, failures through `Warnings`).

### 9.3 VM images

```java
public sealed interface VmImage permits VmImage.PathImage, VmImage.UrlImage, VmImage.OciImage, VmImage.BakedImage {
  record PathImage(Path file) implements VmImage {}
  record UrlImage(URI url, UrlOptions options) implements VmImage {}
  record OciImage(DockerImageName image, OciOptions options) implements VmImage {}
  record BakedImage() implements VmImage {}

  static VmImage fromPath(Path file);                          // qcow2 (without backing file) or raw
  static VmImage fromUrl(URI url);                             // http/https; = fromUrl(url, UrlOptions.defaults())
  static VmImage fromUrl(URI url, UrlOptions options);         // sha256, credentials, compression, timeout (§9.3.5)
  static VmImage fromOci(String ociUrl);                       // "oci://registry/repo:tag" or "oci://registry/repo@sha256:…" (§9.3.6)
  static VmImage fromOci(String ociUrl, OciOptions options);   // explicit registry credentials
  static VmImage fromContainerDisk(DockerImageName image);     // same as fromOci, typed reference
  static VmImage fromContainerDisk(DockerImageName image, OciOptions options);
  static VmImage referenceGuest();                             // fromContainerDisk(override 'guestImage' or VmtcConstants.referenceGuestImage())
  static VmImage blackBoxGuest();                              // fromContainerDisk(override 'blackBoxGuestImage' or VmtcConstants.blackBoxGuestImage())
  static VmImage baked();                                      // runner image already contains /vm/base/disk.img
}
```

`QemuVmContainer` dispatches with a `switch` over the sealed type. `describe()` reports the kind, a redacted locator (no user-info, query or secrets) and the cache key.

#### 9.3.1 Cache (`VmImageCache`)

- **Instance:** `new VmImageCache(Path root)`; `QemuVmContainer` builds one from the `cacheDir` override. **`Path resolve(VmImage image, DockerClient client)`** is the public entry point that materialises any non-baked source and returns the cache file (`QemuVmContainer.configure()` calls it; the `:it` warm-up calls it directly). **Root:** `<cacheDir>/images/`. Entries are `<key>.img`, side files `<key>.meta.json` (HTTP revalidation data) and `<key>.lock`.
- **Concurrency (rule k):** a writer first takes the per-key in-JVM lock (`ConcurrentHashMap<String, ReentrantLock>`), then `FileChannel.lock()` on `<key>.lock` (blocks against other JVMs), then re-checks whether `<key>.img` exists. It writes `<key>.tmp-<pid>-<nanotime>` and finishes with `Files.move(…, ATOMIC_MOVE)`. Readers only check for `<key>.img`.
- **`fromPath`:**
  - Reject with `IllegalArgumentException("qcow2 image has a backing file: <name>")` when `Qcow2Header.backingFile(file)` is non-empty. `Qcow2Header` reads the 104-byte header: magic `QFI\xfb` at offset 0; `backing_file_offset` = big-endian uint64 at offset 8; `backing_file_size` = big-endian uint32 at offset 16; a non-zero offset means a backing file, whose name is the UTF-8 bytes at that offset [D, qcow2 spec]. Files without the magic are treated as raw.
  - `key` = SHA-256 of the file content, memoised in `<cacheDir>/index.json` by `(absolutePath, size, mtimeMillis)`. Schema: `{"entries":[{"path":"…","size":123,"mtimeMillis":456,"key":"<hex>"}]}`. `index.json` is rewritten under `<cacheDir>/index.lock` (file lock + the same in-JVM discipline).
  - If `<key>.img` is missing, **copy** the file with `SparseCopy.copy(src, tmp)`: read in 1 MiB blocks; for an all-zero block `position()` the output channel forward instead of writing; after the last block set the file length to the source length with `RandomAccessFile.setLength` (`FileChannel.truncate` cannot extend) so trailing holes keep the apparent size. This preserves sparseness of raw images (a 20 GB raw file with 1 GB of data stays about 1 GB on disk) [D, POSIX sparse-file semantics]. **Never hard-link**: the `:z` relabel and later edits of the user's file must not touch the cache entry.
- **`fromUrl`:** see §9.3.5. Cache key = the SHA-256 if given; without a checksum, `key` = `sha256("url:" + normalizedUrl)` and `<key>.meta.json` stores `ETag` and `Last-Modified`.
- **`fromOci` / `fromContainerDisk`:** `key` = `sha256(imageId + "/" + fileName)` (§9.3.3, §9.3.6).
- **Touch:** every use sets the entry's last-modified time to now.
- **Eviction:** none automatic. `VmImageCache.prune(Duration olderThan)` deletes every entry whose last-modified time is older than `olderThan`, its side files, and its `index.json` rows.

#### 9.3.2 Why every source goes through the cache

- **Speed:** a bind plus overlay means O(1) start and per-start disposable state.
- **SELinux:** relabeling (`:z`) touches only library-owned files.
- **Simplicity:** one in-container path, `/vm/base/disk.img`.

#### 9.3.3 containerDisk extraction (`ContainerDiskExtractor`; uses the Testcontainers `DockerClient`, no CLI)

1. **Pull through the engine** (always, so tag moves are picked up; the engine skips unchanged layers):
   - **Without explicit credentials:** `String name = new RemoteDockerImage(image).withImagePullPolicy(PullPolicy.alwaysPull()).get()`. The explicit policy matters: Testcontainers' default policy pulls only when the image is missing locally [D], which would hide a moved tag. The engine authenticates with credentials from Testcontainers' `RegistryAuthLocator` (Docker `config.json` and credential helpers) or, on Podman, its own `auth.json` from `podman login` **[V]**.
   - **With `OciOptions.credentials(...)`:** `client.pullImageCmd(image.asCanonicalNameString()).withAuthConfig(new AuthConfig().withRegistryAddress(image.getRegistry()).withUsername(u).withPassword(p)).exec(new PullImageResultCallback()).awaitCompletion()`, then `name = image.asCanonicalNameString()`. This pull was exercised against the private registry with no stored credentials **[V]** (run 16); T27 (c) covers the boot that follows.
   - **Local fallback (REQ-OCI-07):** if the pull fails for any reason and `client.inspectImageCmd(name).exec()` succeeds, emit one WARN `using local image <ref>; pull failed: <reason>` through `Warnings` and continue with the local image. This covers locally built, never-pushed images (the reference guest before `pushImages`) and offline work. It does not mask T27 (a), because that test removes the image first.
   - **Pull errors** (no local image): if the failure is, or has in its cause chain, `com.github.dockerjava.api.exception.UnauthorizedException`, or its message contains `unauthorized`, `authentication required` or `denied` (case-insensitive), throw `ImageAuthException("pull of <ref> was rejected (credentials missing or rejected)", cause)`. Any other failure → `VmtcException("pull of <ref> failed", cause)`. Both the no-credentials `RemoteDockerImage` path and the explicit-`AuthConfig` path with a wrong password raise `UnauthorizedException` with `Status 401` **[V]** (runs 12, 16).
   - Then `imageId = client.inspectImageCmd(name).exec().getId()`.
2. `id = client.createContainerCmd(name).withCmd("/none").exec().getId()` creates without starting **[V]**.
3. `client.copyArchiveFromContainerCmd(id, "/disk/").exec()` → tar stream.
   - A `NotFoundException` (the image has no `/disk/` directory) → `IllegalArgumentException("containerDisk must contain exactly one file under /disk/")`.
   - Skip directory entries.
   - Require **exactly one** regular-file entry, else throw the same `IllegalArgumentException`.
   - Stream that entry into the cache tmp file; its base name is `fileName`.
   - Status: directory copy **[V]** on Docker 29.8, Podman 4.9.3 and 5.7.0 (run 5).
4. `client.removeContainerCmd(id).withForce(true).exec()` in `finally`.

#### 9.3.4 `baked()`

- The user builds `FROM <runner>` plus `COPY disk.qcow2 /vm/base/disk.img`.
- No cache, no bind. Intended for remote engines and podman-machine setups. If the file is missing, the entrypoint exits 2 and `start()` throws `VmtcException` containing `no base disk` (T29).

#### 9.3.5 HTTP(S) download (`ImageDownloader`) [V]

**Options:**

```java
UrlOptions.builder()
    .sha256(String hex)                       // optional; when absent: WARN once per URL "no integrity check"
    .credentials(HttpCredentials.basic(user, Secret pw) | .bearer(Secret token) | .header(name, Secret value))
    .compression(Compression.AUTO)            // AUTO (by the FINAL URL's path suffix .gz/.xz/.zst) | NONE | GZIP | XZ | ZSTD
    .timeout(Duration)                        // headers wait AND per-read idle limit; default 30 min
    .build();
UrlOptions.defaults()                         // = builder().build()
```

`Secret` wraps a `Supplier<char[]>`: `Secret.of(String)`, `Secret.of(char[])` (copied), `Secret.fromEnv(String)`, `char[] value()` (a fresh copy per call). `toString()` prints `***`. `Secret.fromEnv("NAME")` reads the environment variable lazily on first `value()`; a missing variable throws `IllegalStateException("environment variable NAME is not set")` then, not at construction.

**Algorithm:**

1. Use one JDK `HttpClient` with `followRedirects(Redirect.NEVER)`. It honours the JVM proxy properties.
2. Request the URL with `HttpRequest.timeout(options.timeout)` (bounds the wait for headers).
   - Send the credential header **only** while the request's `scheme://authority` equals the original URL's.
   - Follow 3xx `Location` manually, resolved relative to the current URL, up to 5 hops; a 6th redirect fails with `ImageDownloadException("too many redirects for <url>")`.
3. Handle the response:
   - `401` / `403` → `ImageAuthException("HTTP <code> for <url> (credentials missing or rejected)")`. Every URL in a message is printed without user-info or query string.
   - Any other status ≠ 200 (and ≠ 304 during revalidation) → `ImageDownloadException("HTTP <code> for <url>")`.
4. Stream through a `DigestInputStream` (SHA-256 over the **downloaded** bytes, i.e. what publishers checksum), then through the decompressor chosen by `compression` (for `AUTO`, by the **final** URL's path suffix), into `<key>.tmp-…`. A watchdog thread closes the body stream if no bytes arrive for `options.timeout`; the blocked `read()` then returns at once with `IOException("closed")` **[V]** (spike: a stalled server, watchdog after 2 s of no progress, read unblocked at 2.0 s). `HttpRequest.timeout` alone covers only the wait for headers (`HttpTimeoutException` after exactly the timeout) **[V]**.
5. If a SHA-256 was given and differs: delete the tmp file and throw `ImageChecksumException(expected, actual)`.
6. `ATOMIC_MOVE` to `<key>.img`.
7. **Retries:** up to 3 attempts in total, with backoff of 2 s and 8 s (injectable `Backoff`), for `IOException`s and 5xx. Never retry 401/403/404 or a checksum mismatch. After the last attempt, `IOException`s surface as `ImageDownloadException(message, cause)` — the library never throws checked exceptions (REQ-ERR-01).
8. **Revalidation** (no checksum given) on a cache hit: send `If-None-Match` / `If-Modified-Since` from `<key>.meta.json`, **one attempt, no retries**.
   - `304` → use the cache.
   - `200` → replace the entry (steps 4–6).
   - `IOException` or 5xx → use the cache and WARN once.

#### 9.3.6 `oci://` sources (R13) [V]

- **Parsing:** the string must start with `oci://`, else `IllegalArgumentException("expected oci://<registry>/<repo>[:tag|@sha256:…]")` (this rejects `oras://` and bare names). Strip `oci://` → `DockerImageName.parse(...)`. A missing tag means `latest`.
- **Pull:** through the engine, per §9.3.3 step 1. Explicit credentials come from `OciOptions.credentials(RegistryCredentials.basic(user, Secret pw))` (a static factory; `OciOptions` is a record `(RegistryCredentials credentials)` with `credentials` nullable and `OciOptions.defaults()` = no credentials); otherwise the engine's own credential stores are used.
- **Extraction:** `/disk/` is extracted per §9.3.3 steps 2–4 into the cache, keyed by image ID. A tag that moves yields a new image ID and therefore a new cache entry; an unchanged tag reuses the entry (REQ-DISK-12).
- **No JVM-side registry client:** neither the ORAS SDK nor our own (§15 L7).
- **Publishing format:** §10.4.

### 9.4 Guest access and exec (R3, D15)

`exec`, `execShell`, `copyToVm`, `copyFromVm`, `readFromVm` and `forceModeset` are implemented once in `QemuVmContainer` on top of the internal `GuestTransport` chosen by `withGuestAccess`:

| `GuestAccess` | Transport | `exec` runs as | Needs from the guest |
|---------------|-----------|----------------|----------------------|
| `none()` (**default**) | `NoTransport`: every call throws `GuestAccessUnavailableException("guest access is GuestAccess.none(); configure withGuestAccess(GuestAccess.ssh(...)) or GuestAccess.guestAgent()")` **immediately**, no network call | — | nothing (Tier 0) |
| `ssh(credentials, options)` | `SshTransport` | the SSH user; with `SshOptions.sudo(true)` every command is prefixed `sudo -n` | sshd on `options.port()`, the credentials, `timeout` and `env` from coreutils (Tier 1a) |
| `guestAgent()` | `QgaTransport` | root (the agent) | `qemu-guest-agent` with G3/G4 (Tier 1b) |

The result type is the same for both transports: `GuestExecResult(int exitCode, Integer signal, String stdout, String stderr, boolean stdoutTruncated, boolean stderrTruncated)`. Output is decoded as UTF-8 with U+FFFD replacement; each stream is capped at **16 MiB** (QGA's own limit; `SshTransport` enforces the same cap and sets the truncated flag).

#### 9.4.1 `QgaTransport` (`GuestAgentClient` protocol) [V]

- Newline-delimited JSON over TCP.
- Resync with `guest-sync-delimited` on every (re)connect [V].
- One request in flight, guarded by a `ReentrantLock`.
- On I/O error: reconnect once (resync per §9.2 step 10) and re-issue the interrupted request; a `guest-exec-status` poll simply continues with the same `pid`. A second failure propagates as `VmtcException`.

**Exec:**
1. `guest-exec {path: argv[0], arg: argv[1..], env: ["K=V", …] (omitted when empty), input-data: base64(stdin) (omitted when empty), capture-output: true}` → `pid`.
2. Poll `guest-exec-status {pid}`, starting at 50 ms and backing off ×1.5 to a 500 ms cap, until `exited` or the timeout.
3. On timeout: best-effort `guest-exec {path:"/bin/kill", arg:["-9", pid]}`, then throw `GuestExecTimeoutException`.
4. QGA omits `exitcode` when the process died from a signal: then `signal` is set and `exitCode` is **-1**. The truncated flags come from `out-truncated` / `err-truncated`.

**Notes:**
- PATH inside guest-exec is `/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin` **[V]**. The Javadoc recommends absolute paths.

#### 9.4.2 `SshTransport` (Apache MINA SSHD, shaded) [V] run 31 unless marked

- **Connection:** one `SshClient` (`SshClient.setUpDefaultClient()`, then `client.start()` **before** the first `connect`) + one `ClientSession` per container, opened by the readiness check (§9.2 step 10) or lazily on the first call when readiness is not `ssh()`. Host key verification per `HostKeyPolicy`: `acceptAny()` accepts (and records the key in `describe()`), `pin(line)` compares against the OpenSSH public-key line and throws `SshAuthException("host key mismatch")` on a difference. Authentication: `addPasswordIdentity` for password credentials; `KeyIdentityProvider.wrapKeyPairs(SecurityUtils.loadKeyPairIdentities(...))` for keys (OpenSSH, PKCS#8 and PEM formats as MINA supports them; RSA, ECDSA, Ed25519 verified). A failed authentication throws `SshAuthException` (cause: MINA's `SshException: No more authentication methods available`).
- **Exec channels are concurrent:** each `exec` opens its own exec channel on the shared session, limited by a `Semaphore(8)` (OpenSSH's `MaxSessions` defaults to 10 channels per connection); no other library-level lock. On an `IOException` from the session (not from the command), reconnect once and retry the call; a second failure propagates as `VmtcException`.
- **Command line** (`SshCommandBuilder`, U19): sshd runs one string as `$SHELL -c '<string>'` (the user's shell, not a login shell; bash still reads `~/.bashrc`, which is where L10's noise comes from), so the builder emits
  `[sudo -n ] timeout -k 1 <ceil(timeout seconds)> [env K=V …] <argv quoted>`
  where every `argv` element and every `K=V` is single-quoted (`'` → `'\''`). `execShell(script)` uses argv `/bin/sh -c <script>`. `env` is passed through `env(1)` because sshd silently ignores environment requests that `AcceptEnv` does not allow **[V]**.
- **Timeout detection (rule q, spike D [V], §3.10):** `timeout` without `--foreground` signals its whole process group, so `sh -c` grandchildren die too; `-k 1` escalates to KILL one second later. The transport throws `GuestExecTimeoutException` when the channel reports **`exit-status 124`**, or an **`exit-signal` (any name) and the wall clock since the channel was opened is ≥ the timeout**. Never use `--foreground` (grandchildren survive) and never `-s KILL` (`timeout` kills itself, sshd reports `exit-signal KILL` and no status; `sh -c 'exit 137'` would be indistinguishable anyway). After mapping, the channel is closed as a second line of defence (a hard close ended `sleep 300` on OpenSSH 9.9).
- **Streams:** stdin bytes go to the channel's input, which is closed (EOF) after the last byte **and also when `stdin` is empty** (otherwise `cat`-like commands block until the timeout); stdout/stderr are collected into bounded buffers (16 MiB cap, truncated flag). `exit-status` → `exitCode`; an `exit-signal` that is not a timeout (above) → `signal` (number from the name, e.g. `KILL` → 9, `TERM` → 15) and `exitCode = -1`.
- **Measured:** warm exec 75–125 ms on KVM, 0.6–1.2 s on TCG.
- **Limits:** no PTY (same as QGA); the SSH user's PATH and shell init apply; with a non-root user and `sudo(false)`, privileged paths fail with the guest's own error in `stderr`. A `pgrep -f <pattern>` sent through this transport matches the wrapper's own command line (§3.10): tests use `pgrep -x`.

### 9.5 File copy (R4)

| Transport | Write | Read |
|-----------|-------|------|
| `QgaTransport` [V] | `guest-file-open {path, mode:"w"}` → for each 1 MiB chunk `guest-file-write {handle, buf-b64}` → `guest-file-close` | `guest-file-open {mode:"r"}` → loop `guest-file-read {handle, count:1048576}` until `eof` (QGA per-call maximum is 48 MiB [D]) → `guest-file-close` |
| `SshTransport` [V] | `SftpClientFactory.instance().createSftpClient(session)`; `open(path, Write, Create, Truncate)` → `write(handle, offset, buf, 0, n)` in 256 KiB blocks → close handle and client (**synchronous handle API only**, rule o) | `open(path, Read)` → `read(handle, offset, buf)` until it returns ≤ 0 (MINA returns −1 at EOF; OpenSSH clamps a read to 255 KiB, so short reads are normal) → close. Mode bits are not preserved (SFTP creates with the user's umask); `copyToVm` then `chmod`s to `0644` only when the source had no execute bit, else `0755` |
| `NoTransport` | `GuestAccessUnavailableException` | same |

- Always close in `finally`.
- `copyToVm` first runs `exec("/usr/bin/mkdir","-p",parent)` through the same transport (so `sudo(true)` applies to the directory, not to the SFTP write: SFTP always runs as the SSH user).
- **Throughput [V]:** QGA: KVM 20–21 MB/s write, 31–46 MB/s read; TCG 1.4–2.8 MB/s write, 4.8–6.6 MB/s read. SSH/SFTP: KVM 26 MB/s write, 47–54 MB/s read; TCG 3.9–4.7 MB/s write, 5.5–5.6 MB/s read (run 31).
- The Javadoc recommends a hot-plugged disk image (§9.10) for data ≥ 100 MB.

### 9.6 KVM detection and Podman group handling (R12)

**`KvmDetector`** (a class with a public no-arg constructor; tests write `new KvmDetector().usable()`; `KvmDecision.decide(mode, detector)` takes an instance so U8 can inject fakes):

| Method | Returns |
|--------|---------|
| `present()` | `/dev/kvm` exists |
| `usable()` | `present()` && `Files.isReadable` && `Files.isWritable` for the JVM user. Rootless Podman bind-mounts the device with host permissions [D] |
| `worldAccessible()` | POSIX permissions include `OTHERS_READ` and `OTHERS_WRITE` |

**Compat-API behaviour [V]:**

| Host `/dev/kvm` | Podman | Device only | + `GroupAdd keep-groups` | + annotation `run.oci.keep_original_groups=1` (`HostConfig`) | same annotation in `containers.conf` |
|-----------------|--------|-------------|--------------------------|--------------------------------------------------------------|--------------------------------------|
| `0666` | 4.9.3, 5.7.0 | works | start fails | works | — |
| `0660 root:kvm`, user in `kvm` | 4.9.3 | EACCES | start fails | EACCES (ignored) | **works** |
| `0660 root:kvm`, user in `kvm` | 5.7.0 | EACCES | start fails | **works** | **works** |

These were measured with the Podman service running in a login session, so `kvm` was a *supplementary* group.

**Library behaviour:**

1. `usable() && worldAccessible()` → `--device /dev/kvm` only.
2. `usable() && !worldAccessible()` → `--device /dev/kvm` **and** `HostConfig.withAnnotations(Map.of("run.oci.keep_original_groups","1"))`. This needs crun, the Podman default. **Never** set `GroupAdd keep-groups`.
3. `!usable()` → §8.2 "no" rows.
4. **Messages for `KvmUnavailableException` and the PREFER WARN:**

   | Situation | Message |
   |-----------|---------|
   | `!present()` | "/dev/kvm is not present (not a KVM host, or nested virtualization disabled)." |
   | `present() && !usable()` | "/dev/kvm exists but the current user cannot open it read/write; add the user to the 'kvm' group or make /dev/kvm 0666." |
   | Device passed but the container reported `VMTC_ACCEL tcg` or exited 3 | "/dev/kvm is group-restricted and the container could not open it. Fix one of: (a) make /dev/kvm 0666 (systemd default; udev rule `KERNEL=="kvm", MODE="0666"`), or (b) add to `~/.config/containers/containers.conf`: `[containers]` / `annotations = ["run.oci.keep_original_groups=1"]` and restart `podman.socket` (required on Podman < 5.7, which ignores the per-container annotation)." |

### 9.7 Screens, VNC, screenshots, keyboard (R6, R9)

```java
public interface VmScreen {
  int index();
  VncEndpoint vnc();
  BufferedImage screenshot();
  void screenshot(Path hostPng);
  ScreenRecording startRecording(Path hostWebm);                          // RecordingOptions.defaults()
  ScreenRecording startRecording(Path hostWebm, RecordingOptions opts);
  void forceModeset();
}
public record VncEndpoint(String host, int port) { public String uri() { return "vnc://" + host + ":" + port; } }
public record RecordingOptions(int fps, int bitrateBps) {                 // fps 1..30, bitrate > 0
  public static RecordingOptions defaults() { return new RecordingOptions(10, 1_000_000); }
  public static RecordingOptions fps(int fps) { return new RecordingOptions(fps, 1_000_000); }
}
public interface KeyboardInput { void type(String text); void press(String... qcodes); }
```

- **`vnc()`:**
  - `host` = `getHost()` when the VNC bind address is `127.0.0.1` or `0.0.0.0`; otherwise the configured address.
  - `port` = `getMappedPort(5900+i)`.
- **`screenshot()`:**
  1. QMP `screendump {filename:"/vm/out/shot-<uuid8>.png", device:"video<i>", format:"png"}` [V].
  2. `copyFileFromContainer` → `ImageIO.read`.
  3. Delete the container file with `execInContainer("rm","-f",…)`.
- **`forceModeset()`** (needs guest access: under `GuestAccess.none()` it throws `GuestAccessUnavailableException`; under SSH it needs root or `sudo(true)`, otherwise step 4 fails with the guest's `Permission denied` in a `VmtcException`):
  0. Under `GuestAccess.none()` throw `GuestAccessUnavailableException` **before** anything else, for every index (so T39 observes it on `screen(0)`).
  1. For index 0 it is otherwise a no-op (returns without any guest call).
  2. Load classpath resource `<${BASE_PACKAGE} as path>/display/vmtc-fb-activate.py` (Appendix A.4).
  3. `copyToVm(bytes, "/tmp/vmtc-fb-activate.py")`.
  4. `exec("/usr/bin/python3","/tmp/vmtc-fb-activate.py","/dev/fb"+index)` (prefixed `sudo -n` under SSH with `sudo(true)`; verified over SSH + sudo on both CI guests, KVM and TCG **[V]** run 31). A non-zero exit throws `VmtcException` whose message contains the script's **stdout and stderr** (Python prints `Permission denied` on stderr).
  5. **Wait for the modeset to land:** poll `screenshot()` every 200 ms until the image is no longer 640×480 (or 10 s, **not scaled**, elapse → `VmtcException("screen <i> did not change mode")`). The VNC server resizes its framebuffer only when QEMU processes the new mode; a recorder attached before that sees a desktop-size change. One CI run (spike run 18) produced a 0.8 s recording of head 1 that had been modeset immediately before; the recorder's log was not captured, so the cause is not proven. The window is real: with the wait in place, the spike measured **0.2 s** between `fb-activate.py` returning and `screendump` showing the new mode (run 24, both BIOS boots) **[V]**, so a recorder attached inside that window sees a framebuffer resize (§15 R10).

  Guest `python3` is present in Rocky GenericCloud [V].
- **`type(text)`:**
  - **Validate first:** map every character per Appendix A.3; any unmapped character → `IllegalArgumentException("unsupported character U+XXXX")` **before** any key is sent.
  - Then one QMP `send-key {keys:[{type:"qcode",data:…}…]}` per character, with **30 ms** between characters [V].
  - Mapping per Appendix A.3: letters (shift for upper case), digits, US punctuation; `\n` → `ret`, space → `spc`.
- **`press(qcodes…)`:** a single `send-key` with all qcodes held together, for example `press("ctrl","alt","f2")`.
- **Making a screen change (tests):** output must go to the VGA console, e.g. `execShell("for i in $(seq 12); do echo tick$i > /dev/tty1; sleep 1; done >/dev/null 2>&1 &")`; writes to `/dev/ttyS0` do not change the screen.

### 9.8 Screen recording (R8, D17: recorder sidecar) **[V]** (pipeline: runs 1–24; sidecar over a Testcontainers `Network`: run 36)

- **Recorder container:** on the first `startRecording` of a VM, the library starts one `GenericContainer` from the recorder image (`withRecorderImage`, else override `recorderImage`, else `VmtcConstants.defaultRecorderImage()`): `withNetwork(VmtcNetwork.get())`, labels as §9.2 step 5 plus `${LABEL_PREFIX}.role=recorder` and `${LABEL_PREFIX}.vm=<vm container id>`, no ports, default command (`sleep infinity`), `Wait.forLogMessage` not needed (`startupCheckStrategy` = running). The image label `${LABEL_PREFIX}.role` must be `recorder` (missing → WARN; other → `IllegalArgumentException`). A pull or start failure → `RecordingException("recorder container failed to start", cause)`. One recorder per VM, reused by every recording of that VM, stopped in `stop()` step 6.
- **Naming:** `name` = `screen<i>-<uuid8>`.
- **Start:**
  - `recorder.execInContainer("/usr/local/bin/vmtc-rec","start",name,"vm-<id>",String.valueOf(5900+i),fps,bitrate)` (the VM's network alias from §9.2 step 4a).
  - Expect `VMTC_REC_STARTED <pid>` in stdout; anything else → `RecordingException("recorder did not start: " + stdout + stderr)`.
- **`ScreenRecording`** (`AutoCloseable`):
  - `close()` runs `vmtc-rec stop <name> 10` in the recorder container.
    - `VMTC_REC_OK duration=<s> width=<w> height=<h>` → `recorder.copyFileFromContainer("/rec/<name>.webm", hostWebm)`; parse the three values (`duration` is decimal seconds).
    - `VMTC_REC_INVALID` or `VMTC_REC_DIED` → `RecordingException` with the whole stop output (it includes the recorder's log tail).
    - `VMTC_REC_KILLED` followed by `VMTC_REC_OK` → success plus a WARN through `Warnings`.
    - `close()` is idempotent; a second call returns immediately.
  - Accessors: `Path file()`, `Duration duration()`, `int width()`, `int height()`, valid after `close()`.
- **Concurrency:** any number of recordings, including two on one screen (separate VNC clients under force-shared [V]).
- The VM container keeps a registry of open recordings for `stop()` (§9.2) and the JUnit extension (§9.13).
- **Why a sidecar:** the runner stays small and swappable; GStreamer updates do not rebuild the QEMU image; a future backend can keep the same recorder (D17). Cost: one more container per recording VM and a user-defined network. Both the network path (`rfbsrc` → `vm-<id>:5900+i` on a rootless Podman network) and the 2 s stop budget including the copy (REQ-REC-02) were verified in spike run 36 under KVM (alias resolved by aardvark-dns, `RFB 003.008` read from the recorder container, 7.9 s recording, stop + validate + copy in 249 ms; §3.9, §15 R11). Expect `rfbsrc` teardown errors (`Error on setup VNC connection`, `streaming stopped, reason error (-5)`) in the recorder's log after a normal stop: that is rule c, not a failure; `vmtc-rec` judges the file.

### 9.9 Audio recording (R7, separate WAV)

```java
public interface AudioRecording extends AutoCloseable { Path file(); @Override void close(); }
```

1. `startAudioRecording(path)`: HMP via QMP `human-monitor-command`: `wavcapture /vm/out/audio-<uuid8>.wav snd0`. The spike used QEMU's defaults (44100 Hz, 16 bit, 2 channels) **[V]**; do not pass explicit parameters.
2. HMP `info capture`: find the line ending in that path and parse `[<n>]` [V] (format `[<n>]: Capturing audio(44100,16,2) to <path>: <bytes> bytes`). If parsing fails and exactly one capture line exists, use its index.
3. `close()`: `stopcapture <n>`, copy the file out to `path`, and validate it (RIFF/WAVE header, `data` size > 0) else `RecordingException`. Idempotent.
4. Only one capture at a time: a second `startAudioRecording` while one is open → `IllegalStateException`; after `close()` a new one is allowed.

The guest mixer must be unmuted (G6); the Javadoc says an all-zero capture means it is not.

### 9.10 Hot-plug (R10)

```java
public final class Devices {
  public static DeviceSpec usbStorage(Path hostImage, boolean readOnly);   // raw or qcow2
  public static DeviceSpec usbKeyboard(); public static DeviceSpec usbTablet(); public static DeviceSpec usbMouse();
  public static DeviceSpec virtioDisk(Path hostImage, boolean readOnly);
  public static DeviceSpec newBlankDisk(String size, DiskBus bus);         // qemu-img create -f qcow2 inside the container
  public static DeviceSpec nic(String macOrNull);
  public static DeviceSpec cpu();                                          // KVM only; TCG → UnsupportedOperationException at hotplug()
  public static DeviceSpec memory(int sizeMiB);
}
public interface HotplugHandle extends AutoCloseable { String id(); void unplug(); void unplug(Duration timeout); @Override void close(); }
public interface CdromDrive { void insert(Path hostIso); void eject(); }
```

**Naming:**
- Device ids: `dev<counter>` (counter per container, from 1).
- Files: `/vm/run/<id>.img` (disks) and `/vm/run/<id>.iso` (CD).
- Block nodes: `n-<id>`. Netdevs: `nd-<id>`. Memory backends: `mem-<id>`.

**Image format:** `execInContainer("qemu-img","info","--output=json",file)` → `.format`.

**Root ports:** `hp1..hpN`. Java tracks which are free and takes the lowest free one. If none is free, throw `IllegalStateException`.

All sequences are **[V]** (CI `hotplug_*`, `cdrom_media_change`):

| Spec | Plug | `unplug()` |
|------|------|------------|
| usbStorage / newBlankDisk(USB) | copy to `/vm/run/<id>.img` → `blockdev-add {driver:<fmt>, node-name:"n-<id>", read-only:<ro>, file:{driver:"file", filename}}` → `device_add {driver:"usb-storage", id, bus:"xhci.0", drive:"n-<id>"}` | `device_del {id}` → poll every 250 ms until `id` ∉ `qom-list /machine/peripheral` → `blockdev-del {node-name}` |
| usbKeyboard / usbTablet / usbMouse | `device_add {driver:"usb-kbd"\|"usb-tablet"\|"usb-mouse", id, bus:"xhci.0"}` | `device_del` + poll |
| virtioDisk / newBlankDisk(VIRTIO) | as USB, with `device_add {driver:"virtio-blk-pci", id, bus:"hp<k>", drive}` | as USB; frees `hp<k>` |
| nic | `netdev_add {type:"user", id:"nd-<id>"}` → `device_add {driver:"virtio-net-pci", id, bus:"hp<k>", netdev, mac?}` | `device_del` + poll → `netdev_del`; frees `hp<k>` |
| cpu | `query-hotpluggable-cpus` → first entry without `qom-path` → `device_add {driver:<type>, id, …props}` | `UnsupportedOperationException`; `close()` is a no-op |
| memory | `object-add {qom-type:"memory-backend-ram", id:"mem-<id>", size}` → `device_add {driver:"pc-dimm", id, memdev}` | `UnsupportedOperationException`; `close()` is a no-op |
| `cdrom().insert` | copy to `/vm/run/<id>.iso` → `blockdev-change-medium {id:"cd0", filename, format:"raw", read-only-mode:"read-only"}` | `cdrom().eject()` → `eject {id:"cd0", force:true}` |

- **`unplug()`** = `unplug(default 30 s, scaled)`. **`close()`:** calls `unplug()` for USB, virtio-disk and NIC handles; for cpu and memory handles `close()` is a no-op and `unplug()` throws `UnsupportedOperationException`. `close()` is idempotent.
- **Read-only disks:** `readOnly=true` → `blockdev-add … read-only: true`; the guest cannot write (`dd` fails) and the host image is untouched (REQ-HOT-10).
- **Default unplug timeout:** 30 s (×TCG factor). On timeout, throw `HotplugTimeoutException("guest did not release <id>")`.
- **Measured [V]:** guest visibility ≤ 3.2 s (USB, TCG) and ≤ 0.5 s (PCIe); unplug ≤ 0.4 s.

### 9.11 Errors and warnings

- Every exception the library throws on purpose is unchecked and extends `VmtcException`; JDK `IllegalArgumentException`/`IllegalStateException`/`UnsupportedOperationException` are used for caller mistakes as documented. Checked exceptions from the JDK or docker-java are wrapped in the matching `VmtcException` subclass with the original as cause. Never `throw new RuntimeException(...)` or `UncheckedIOException` (REQ-ERR-01).
- `QmpException` carries the QMP `class` and `desc`.
- `start()` unwraps `ContainerLaunchException` (§9.2, rule e).
- **`Warnings`** (`${BASE_PACKAGE}.Warnings`, exported): every WARN the library emits goes through `Warnings.warn(Logger log, String message)`, which logs at WARN **and** notifies registered listeners. `AutoCloseable Warnings.listen(Consumer<String>)` registers a listener (tests capture warnings; `VmIT` attaches them as `warnings.txt`). "Once per JVM" warnings are guarded by a `ConcurrentHashMap.newKeySet()` of message keys inside `Warnings.warnOnce(Logger, String key, String message)`.
- **Test seams for once-per-JVM state:** package-private `static void resetForTests()` on `Warnings` (clears the once-keys), on `VmtcCleanup` (clears the ran-once flag and restores the default hook) and on `internal.Session` (new UUID). U8, U16, U17 and U18 call the relevant ones in `@BeforeEach`; production code never calls them.

### 9.12 Cleanup without Ryuk

- **Ryuk:** on rootless Podman, Ryuk must be disabled (`TESTCONTAINERS_RYUK_DISABLED=true`) [V].
- **`int VmtcCleanup.removeOrphans()`:**
  - Returns 0 immediately when the `cleanup` override is `false`.
  - Lists containers in **any** state (`listContainersCmd().withShowAll(true)`) labelled `${LABEL_PREFIX}.managed=true` whose `${LABEL_PREFIX}.session` differs from the current one **and** whose `${LABEL_PREFIX}.created` is more than 1 h old.
  - Force-removes them and returns the count.
- `QemuVmContainer.start()` calls it exactly once per JVM, before the first container is created (REQ-CLN-02). The call goes through a package-private `static Supplier<Integer> cleanupHook` seam (with `resetForTests()`, §9.11) so U17 can substitute it.

### 9.13 `${ART_JUNIT}` (optional)

- **`@VmTest`** (on a class) registers `VmArtifactsExtension`.
- The extension implements **`AfterTestExecutionCallback`** and checks `context.getExecutionException().isPresent()`. This callback runs **before** `@AfterEach` methods and before Testcontainers' own extension stops containers; `TestWatcher.testFailed` runs after them, when the container is already gone [D, JUnit 5 extension lifecycle], so it is **not** used for artifact capture.
- On failure, for every **running** `QemuVmContainer` found in the test instance's fields and the class's static fields (reflection, including superclasses):
  1. Close each open `ScreenRecording` and `AudioRecording`, which finalizes them at their own host paths.
  2. `saveSerialLog(dir.resolve("serial.log"))`.
  3. One screenshot per screen → `dir.resolve("screen<i>.png")`.

  `dir` = `build/vm-artifacts/<class simple name>/<method name>/` (created on demand).
- To support that, `QemuVmContainer` keeps a registry of its open recordings (§9.8, §9.9).

### 9.14 Backend and transport seams (D18, rule p)

- **`internal.backend.VmBackend`** is the only place that speaks QMP: `screenshot(int screen) → byte[] png`, `sendKeys(List<String> qcodes)`, `blockdevAdd/deviceAdd/deviceDel/...` as the §9.10 table needs, `audioCaptureStart/Stop`, `vncEndpoint(int)`, `powerOff()`, `startRecorder/recorderExec/copyFromRecorder` (§9.8), `serialTail(long offset)`. `QemuPodmanBackend` implements it with `QmpClient` and `execInContainer`/`copyFileFromContainer`. `QemuVmContainer`, `VmScreen`, `KeyboardInput`, `AudioRecording`, `ScreenRecording`, `HotplugHandle` and `CdromDrive` call the backend; they never call `QmpClient` directly.
- **`internal.transport.GuestTransport`** is the only place that speaks to the guest: `awaitReady(Readiness, Instant deadline)`, `exec(ExecRequest)`, `writeFile(String path, InputStream)`, `readFile(String path, OutputStream)`, `close()`. Implementations: `QgaTransport`, `SshTransport`, `NoTransport` (§9.4). **Who owns readiness:** `QemuVmContainer` runs `bootOnly()` (nothing) and `guestPort(...)` (host-side socket probe, §9.2 step 10) itself and calls `awaitReady` only for `ssh()` and `guestAgent()`; `NoTransport.awaitReady` is therefore never reached and simply throws like its other methods.
- **Public API rule (U14, REQ-BLD-09):** no public method of `QemuVmContainer`, `VmScreen`, `KeyboardInput`, `AudioRecording`, `ScreenRecording`, `HotplugHandle`, `CdromDrive`, `VmImage`, `GuestAccess`, `Readiness`, `SshCredentials`, `SshOptions`, `CloudInitSeed` or `Devices` has a parameter or return type from `${BASE_PACKAGE}.qmp`, `${BASE_PACKAGE}.qga`, `${BASE_PACKAGE}.internal…` or `${BASE_PACKAGE}.shaded…`, **except** `QemuVmContainer.qmp()` and `QemuVmContainer.guestAgent()` (documented escape hatches). `KeyboardInput.press(String... qcodes)` takes QMP key names as plain strings; that is accepted (the names are the de-facto standard) and documented.
- The seams are package-private/internal (not exported); they exist so a second backend (another engine) or transport could be added without an API break. **No second implementation is planned** (D14).

---

## 10. Guest contract and reference guest image

### 10.1 Guest contract (normative; tiered, D15)

The guest owes the library only what the features the test uses require. **Tier 0 is the primary use case** (black-box images, D15); the other tiers are opt-in through `withGuestAccess` and `withCloudInit`.

| Tier | Guest must provide | Enables | Reference how (Rocky/RHEL family) | Detected by |
|------|--------------------|---------|-----------------------------------|-------------|
| **0 — black-box** | G1: x86-64 Linux with virtio-blk/net drivers (any image that boots on the §8.1 hardware) | boot, screenshots, keyboard, VNC, screen recording, audio capture (host side), hot-plug (host side), serial log (if the guest prints to ttyS0: G5), port forwards, `Readiness.bootOnly()` / `guestPort(...)` | standard kernels have the drivers | `VMTC_READY`; nothing else |
| **1a — SSH** | G8: sshd listening on `SshOptions.port()`; credentials the test knows (password or key); for `sudo(true)` a NOPASSWD sudo rule; coreutils `timeout` and `env` | `exec`, `copy*`, `forceModeset` (root or sudo), `Readiness.ssh()` | GenericCloud ships sshd + coreutils; users/keys/`PermitRootLogin` via the §10.3 seed | §9.2 step 10 (`ssh()`) |
| **1b — agent** | G2: `qemu-guest-agent` on port `org.qemu.guest_agent.0`; G3: exec and file RPCs allowed (`sed -i 's/^FILTER_RPC_ARGS=.*/FILTER_RPC_ARGS=""/' /etc/sysconfig/qemu-ga` + restart [V]); G4: agent SELinux domain permissive (`semanage permissive -a virt_qemu_ga_t` [V]) | `exec`, `copy*`, `forceModeset`, `Readiness.guestAgent()`; no network or credentials needed | preinstalled in Rocky GenericCloud [V]; G3/G4 via seed | §9.2 steps 10–12 |
| **2 — cloud-init** | cloud-init with the NoCloud datasource (G5 serial console recommended so `VMTC_SEED_DONE` is seen) | `withCloudInit` (§10.2), the seeded-start wait | GenericCloud default | §9.2 step 10 (seed wait) |

**Feature prerequisites** (independent of tier; needed only by the test that uses the feature):

| # | Needed for | Reference how |
|---|------------|---------------|
| G5 | Serial log content, `VMTC_SEED_DONE`, `withSerialLogConsumer` | `console=ttyS0` on the kernel command line (GenericCloud default [V]) |
| G6 | Audio **playback** in the guest (T11): HDA driver (`kernel-modules`), `alsa-utils`, mixer unmuted | `dnf -y install alsa-utils`; `amixer -c 0 sset Master 90% unmute`; `amixer -c 0 sset PCM 90% unmute \|\| true`; `alsactl store` [V] |
| G7 | Multi-screen on console-only guests: `VmScreen.forceModeset()` | guest `python3`; root or sudo under SSH; a no-op under UEFI (§8.4) |

The previous flat list G1–G7 maps onto this table unchanged (G1 → Tier 0, G2–G4 → Tier 1b, G5–G7 → prerequisites); G8 is new. REQUIREMENTS.md rows cite the G numbers.

### 10.2 Cloud-init seed (`CloudInitSeed`)

**`CloudInitSeed.forStockRocky()`** brings a stock Rocky 9 GenericCloud image up to G2–G6. Its `userData()` is exactly this text (the spike's `make-seed.sh` emitted the same commands in the same order **[V]**; the spike's own `SPIKE_*` markers and root password are not part of it):

```yaml
#cloud-config
runcmd:
  - update-ca-trust
  - sh -c "dnf -y install alsa-utils > /var/log/seed-dnf.log 2>&1; echo DNF_RC=\$? > /dev/ttyS0; tail -5 /var/log/seed-dnf.log > /dev/ttyS0"
  - sed -i 's/^FILTER_RPC_ARGS=.*/FILTER_RPC_ARGS=""/' /etc/sysconfig/qemu-ga
  - semanage permissive -a virt_qemu_ga_t
  - systemctl enable qemu-guest-agent
  - systemctl restart qemu-guest-agent
  - amixer -c 0 sset Master 90% unmute || true
  - amixer -c 0 sset PCM 90% unmute || true
  - echo VMTC_SEED_DONE > /dev/ttyS0
```

**About `\$?`:** the backslash is **correct and verified** (the file the spike booted contained it **[V]**). The runcmd item is a YAML *plain* scalar, so `\` is literal; cloud-init runs the item through `/bin/sh -c`, whose double quotes turn `\$` into `$`, so the inner `sh -c` sees `$?`. Do not "fix" it.

**`metaData()`** is `instance-id: <uuid>\nlocal-hostname: vm\n`.

**Builder API.** `CloudInitSeed.builder()` starts **empty**. `build()` **always** appends `echo VMTC_SEED_DONE > /dev/ttyS0` as the last runcmd line, whatever else was added, because `start()` waits for it (§9.2 step 10). `forStockRocky()` is `builder().stockRocky().build()`.

| Method | Effect |
|--------|--------|
| `stockRocky()` | Adds the built-in lines above, in that order (without the final echo, which `build()` adds) |
| `packages(String…)` | Merged into **one** `sh -c "dnf -y install <pkgs> …"` line of exactly the form above, placed immediately after `update-ca-trust` (which is added if absent). With `stockRocky()`, `alsa-utils` is part of the same line. Needs guest network. `DNF_RC≠0` is logged as WARN, not failure: in one run dnf reported failure yet the package was installed [V] |
| `runcmd(String…)` | Appended after the built-ins and the dnf line, in call order |
| `writeFile(path, content)` | → a `write_files` entry (`path`, `content` as a literal block, `permissions: '0644'`) |
| `rootPassword(pw)` | → `chpasswd: {expire: false, users: [{name: root, password: pw, type: text}]}` |
| `trustCaFile(Path pem)` | → `write_files` entry at `/etc/pki/ca-trust/source/anchors/vmtc-<n>.crt` (n = 1, 2, … per call) and ensures `update-ca-trust` is in runcmd before any dnf line |

Section order in the YAML: `#cloud-config`, `chpasswd` (if any), `write_files` (if any), `runcmd`.

**Seed timings [V]:** KVM 45–102 s; TCG 323–481 s on CI and 569–654 s in the sandbox. Tests should therefore use the pre-customized reference guest (§10.3).

### 10.3 Shipped guest images: reference `${IMAGE_GUEST}` and black-box `${IMAGE_GUEST_BLACKBOX}` (D19)

**`images/guest/customize.sh <engine> <runner-image> <base.qcow2> <workdir> <out-tag> <kvm|tcg>`**, with environment:

| Env | Values | Meaning |
|-----|--------|---------|
| `GUEST_VARIANT` | `reference` (default) \| `blackbox` | `blackbox` removes `qemu-guest-agent` and disables cloud-init after customization; output file `rocky9-vmtc.qcow2` vs `rocky9-vmtc-blackbox.qcow2` |
| `SSH_PUBKEY_FILES` | space-separated `.pub` paths | installed for `root` and `vmtc` (Gradle passes the build's test key, §11.2 `generateTestSshKey`) |

This is the verified spike `build-guest.sh` **[V]** (run 5: reference pipeline, 40 s boot + 48 s flatten, 836 MB; run 31: both variants with SSH users and keys, black-box 45 s boot KVM / 465 s TCG, 816–818 MB). It is run by `buildGuestDisk` / `buildBlackBoxGuestDisk`, which `dependsOn buildRunner, downloadGuestBase, generateTestSshKey`. The base file is downloaded and checked by Gradle first, against `guest.properties`.

The spike ran the boot through `start-vm.sh`, with the seed ISO built at `/vm/seed.iso`. In production, `vmtc-entrypoint` builds it from `/vm/seed` (§7.1 step 4).

1. Write `<workdir>/seed/user-data` (both variants share the first part; `<KEY>` = each public-key line from `SSH_PUBKEY_FILES`):

   ```yaml
   #cloud-config
   ssh_pwauth: true
   disable_root: false
   chpasswd:
     expire: false
     users:
       - {name: root, password: vmtc, type: text}
       - {name: vmtc, password: vmtc, type: text}
   users:
     - default
     - name: vmtc
       shell: /bin/bash
       lock_passwd: false
       sudo: ALL=(ALL) NOPASSWD:ALL
       ssh_authorized_keys:
         - <KEY>
   write_files:
     - path: /etc/cloud/cloud.cfg.d/99-vmtc-datasource.cfg
       content: |
         datasource_list: [ NoCloud, None ]
     - path: /etc/ssh/sshd_config.d/10-vmtc.conf
       content: |
         PermitRootLogin yes
         PasswordAuthentication yes
   runcmd:
     - mkdir -p -m 700 /root/.ssh
     - echo "<KEY>" >> /root/.ssh/authorized_keys
     - chmod 600 /root/.ssh/authorized_keys
     - sh -c "dnf -y install alsa-utils > /var/log/seed-dnf.log 2>&1; echo DNF_RC=\$? > /dev/ttyS0"
     - sed -i 's/^FILTER_RPC_ARGS=.*/FILTER_RPC_ARGS=""/' /etc/sysconfig/qemu-ga
     - semanage permissive -a virt_qemu_ga_t
     - systemctl enable qemu-guest-agent
     - amixer -c 0 sset Master 90% unmute || true
     - amixer -c 0 sset PCM 90% unmute || true
     - alsactl store
   ```

   **`blackbox` only**, appended next:

   ```yaml
     - systemctl disable --now qemu-guest-agent
     - sh -c "dnf -y remove qemu-guest-agent > /var/log/seed-dnf-remove.log 2>&1; echo DNF_REMOVE_RC=\$? > /dev/ttyS0"
     - rpm -q qemu-guest-agent > /dev/ttyS0 2>&1 || echo AGENT_REMOVED > /dev/ttyS0
   ```

   **Both**, last:

   ```yaml
     - cloud-init clean --logs
     - truncate -s0 /etc/machine-id
     - touch /etc/cloud/cloud-init.disabled        # blackbox only
     - echo VMTC_CUSTOMIZE_DONE > /dev/ttyS0
     - poweroff
   ```

   Write `meta-data` with `instance-id: vmtc-customize`.
   - `datasource_list: [NoCloud, None]` stops cloud-init from probing network metadata services on later seedless boots, while still honouring a user's NoCloud seed (reference guest).
   - `alsactl store` persists the unmuted mixer.
   - The passwords (`vmtc`) and the sudo rule are **test-only**; the images are test fixtures, never production artefacts, and the README says so.
2. **Customization boot.** The guest powers itself off, QEMU exits, and so does the container:

   ```
   timeout 1800 <engine> run --rm [--device /dev/kvm] -e VM_ACCEL=auto \
     -v <base>:/vm/base/disk.img:ro[,z] -v <workdir>/run:/vm/run[:z] -v <workdir>/out:/vm/out[:z] -v <workdir>/seed:/vm/seed:ro[,z] <runner-image>
   ```

   Then require `VMTC_CUSTOMIZE_DONE` in `<workdir>/out/serial.log` (the `/vm/out` mount keeps the log after the container exits; the spike copied it out the same way **[V]**). Print its `DNF_RC=` line (and `DNF_REMOVE_RC=` / `AGENT_REMOVED` for `blackbox`). `VM_ACCEL=auto` (not `kvm`) because on a `0660 root:kvm` host the CLI container may not be able to open the device even when Gradle's user can; the script reads the `VMTC_ACCEL` line from the container log and prints it, and the `<kvm|tcg>` argument only decides whether `--device /dev/kvm` is passed. The production entrypoint builds the seed ISO from `/vm/seed`.
3. **Flatten.** The overlay's backing path is `/vm/base/disk.img`, so the same mounts are used:

   ```
   <engine> run --rm --entrypoint qemu-img -v <base>:/vm/base/disk.img:ro -v <workdir>/run:/vm/run <runner-image> \
     convert -O qcow2 -c /vm/run/overlay.qcow2 /vm/run/<out-file>
   ```
4. **containerDisk.** Create `<workdir>/run/Containerfile` with:

   ```dockerfile
   FROM scratch
   ADD --chown=107:107 <out-file> /disk/<out-file>
   ```

   Then `<engine> build -t <out-tag> <workdir>/run`. The tags are `${IMAGE_GUEST}:${VERSION}` and `${IMAGE_GUEST_BLACKBOX}:${VERSION}`.

**Acceptance, reference guest (M3, test T23):** boots **with no seed**:
- ready ≤ 30 s (KVM) under both `GuestAccess.guestAgent()` and `GuestAccess.ssh(root)`;
- `exec` works through both transports (G3/G4 persisted; sshd + users persisted);
- `amixer -c 0 get Master` shows `[on]`;
- T11 audio passes.

Result **[V]** (CI run 5, KVM, no seed, agent; run 31, KVM and TCG, SSH):
- agent ready 11.2 s after container start; SSH auth complete 21 s (KVM) / 93 s (TCG);
- exec works and the agent context is `virt_qemu_ga_t` (permissive persisted); root and `vmtc` log in by password and by ed25519/RSA/ECDSA key; `sudo -n` works;
- `amixer` Master `[on]` at boot (persisted by `alsactl store`);
- the 1 kHz audio test passes;
- all 16 feature checks pass, including console login after `chpasswd` via QGA.

**Acceptance, black-box guest (M2, test T38):** boots with no seed and no agent:
- `GuestAccess.ssh(root)` ready ≤ 30 s (KVM); `rpm -q qemu-guest-agent` exits 1; `systemctl is-active cloud-init` prints `inactive`; `/etc/cloud/cloud-init.disabled` exists;
- exec, SFTP copy, screenshots, keyboard, `forceModeset` via `sudo` all work.

Result **[V]** (run 31, KVM and TCG): SSH auth complete 15.7 s (KVM) / 65 s (TCG); every check above passed.

### 10.4 Publishing VM disks as OCI images (R13)

We control the publishers, so every OCI-hosted disk uses the KubeVirt containerDisk layout:

```dockerfile
FROM scratch
ADD --chown=107:107 <name>.qcow2 /disk/<name>.qcow2      # exactly ONE regular file under /disk/
```

- **qcow2:** produce it with `qemu-img convert -O qcow2 -c` (internally compressed). Do not `.xz` it: registry layers are already compressed.
- **Push:** `podman push` or `docker push` to any OCI registry.
- **References:** consume by tag, or by digest for reproducible tests (`oci://registry/repo@sha256:…`).
- **Labels (optional, informational):** `${LABEL_PREFIX}.guest.os`, `${LABEL_PREFIX}.guest.tiers=0,1a,1b,2` (reference) / `0,1a` (black-box).
- **Both shipped guests (§10.3) are published exactly this way.** Any public KubeVirt containerDisk (for example the `quay.io/containerdisks/*` images) is a valid input too, since the layout is KubeVirt's **[D]** (not exercised).

---

## 11. Build (Gradle)

### 11.1 Conventions

- Java toolchain `${JAVA_RELEASE}`. javac `-Xlint:all,-processing,-this-escape -Werror` for every module. **This lint set is final: never add an exclusion; fix the code instead** (utility classes such as `Devices`, `Warnings`, `VmtcCleanup`, `VmtcConstants`, `StartupMessages` declare a private constructor; long→int narrowing uses `Math.toIntExact`; every exception has a `serialVersionUID`, so `serial` stays on). The module-related lints do not apply because there is no `module-info.java` (D20).
- Formatting: adopt the target repo's formatter, if it has one.
- Every `Test` task: `useJUnitPlatform()`, `testRuntimeOnly 'org.junit.platform:junit-platform-launcher'`, Allure results per §11.4, and `configurations.testRuntimeClasspath { resolutionStrategy.force "org.slf4j:slf4j-api:${SLF4J_VERSION}" }` (§3.7: a transitive 1.7 api otherwise silences `slf4j-simple` 2.x).
- **`:lib` shading (D16; verified with Gradle 9.8.1 + Shadow 9.6.1 in spike E, §3.10):** plugin `com.gradleup.shadow` `${SHADOW_PLUGIN_VERSION}` (needs Gradle 9: generate the wrapper before applying it). `lib/build.gradle` contains, with `<basePackage>` taken from `gradle.properties`:

  ```groovy
  configurations {
      shaded { canBeConsumed = false; canBeResolved = true }
      compileClasspath.extendsFrom(shaded)
      runtimeClasspath.extendsFrom(shaded)
      testRuntimeClasspath.extendsFrom(shaded)
  }
  tasks.named('shadowJar') {
      configurations = [project.configurations.shaded]
      relocate 'org.apache.sshd', "${basePackage}.shaded.org.apache.sshd"
      relocate 'net.i2p.crypto', "${basePackage}.shaded.net.i2p.crypto"
      dependencies { exclude(dependency('org.slf4j:slf4j-api')) }      // sshd-common's transitive slf4j must not be bundled
      mergeServiceFiles()
      exclude 'META-INF/*.SF', 'META-INF/*.DSA', 'META-INF/*.RSA'
      archiveClassifier = ''
      manifest { attributes 'Automatic-Module-Name': basePackage }
  }
  tasks.named('jar') { enabled = false }                            // the shadow jar IS the jar
  configurations.apiElements.outgoing.artifacts.clear()
  configurations.runtimeElements.outgoing.artifacts.clear()
  configurations.apiElements.outgoing.artifact(tasks.named('shadowJar'))
  configurations.runtimeElements.outgoing.artifact(tasks.named('shadowJar'))
  publishing { publications { maven(MavenPublication) { from components.java; artifactId = artifactLib } } }
  ```

  Consequences, all verified in spike E: the published POM lists Testcontainers (compile scope) and the `implementation` set (runtime scope) and nothing from `shaded`; `project(':lib')` consumers (`:junit5`, `:it`) resolve the **relocated** jar, so their tests see `${BASE_PACKAGE}.shaded.org.apache.sshd…` and never the original packages; `components.shadow` is **not** used (its POM has no dependencies). Shadow drops `module-info.class` by default, which is irrelevant under D20. U20 checks the jar (every `.class` entry under `<basePackage path>/`, the manifest attribute, the merged service files).
- `:lib:test` needs no engine. `:junit5:test` is engine-free as well (it may hold zero tests; `failOnNoDiscoveredTests = false`); T34 lives in `:junit5`'s separate `extensionIT` task, which needs the engine and runs in the `integration` CI job (§12.1, §13.1).
- `:it` (sources in `it/src/test/java`) holds the integration tests. Its `Test` tasks all set `failOnNoDiscoveredTests = false` (Gradle 9 defaults it to `true` [D]) and `dependsOn ':images:verifyRunnerImages', ':images:buildGuestDisks'`:
  - `integrationTest`: everything except `KvmCompatIT` and `OciAuthIT`. **Tag mapping:** `-P<syspropPrefix>.kvm=REQUIRE` → `excludeTags 'tcg'`; `DISABLE` → `excludeTags 'kvm'`; `PREFER` or absent → no exclusion. Untagged tests always run.
  - `compatTest`: `KvmCompatIT` only (T35).
  - `ociAuthTestA`..`ociAuthTestD`: `OciAuthIT` only, one JVM each with `systemProperty '<syspropPrefix>.it.ociScenario', 'a'..'d'`, chained with `mustRunAfter`; `ociAuthTest` aggregates them.
  - All of them forward the mode: `systemProperty "<syspropPrefix>.kvm", providers.gradleProperty("<syspropPrefix>.kvm").getOrElse("PREFER")` — the CI passes it as **`-P`**, not `-D` (`findProperty`/`gradleProperty` read project properties only).
  - They pass `systemProperty "<syspropPrefix>.it.guestQcow2", file("../images/build/guest/work/run/rocky9-vmtc.qcow2").absolutePath`, `"<syspropPrefix>.it.blackboxQcow2"` = `images/build/guest/work-blackbox/run/rocky9-vmtc-blackbox.qcow2`, `"<syspropPrefix>.it.baseQcow2"` = `images/build/guest/base.qcow2` (served by in-JVM HTTP servers in T19/T25, so CI never downloads the 1 GB stock image twice), and `"<syspropPrefix>.it.sshKey"` = `images/build/guest/ssh/id_ed25519`, `"<syspropPrefix>.it.sshKeyRsa"` = `…/id_rsa`, `"<syspropPrefix>.it.sshKeyEcdsa"` = `…/id_ecdsa` (the private halves of the three keys baked into both guests, §11.2).
  - `:it` and `:junit5` depend on `project(':lib')` (which resolves to the shadow jar, above) and `project(':test-support')`; where a test needs MINA's `SecurityUtils` (REQ-ACC-08's `security-facts.txt`) it imports the **relocated** class `${BASE_PACKAGE}.shaded.org.apache.sshd.common.util.security.SecurityUtils`.
- `TestImages.guest()` (in `:it`) returns `VmImage.fromPath(Path.of(System.getProperty("<syspropPrefix>.it.guestQcow2")))` until M3 and `VmImage.referenceGuest()` from M3 on; `TestImages.blackBox()` likewise with `it.blackboxQcow2` and `VmImage.blackBoxGuest()`.
- `TestAccess` (in `:it`) is an **enum** with a display name and a `GuestAccess access()`: `SSH_ROOT` (`sshRoot`) = `GuestAccess.ssh(SshCredentials.password("root", Secret.of("vmtc")))`; `SSH_ROOT_KEY` (`sshRootKey`) = `GuestAccess.ssh(SshCredentials.privateKey("root", <it.sshKey>))`; `SSH_VMTC_SUDO` (`sshVmtcSudo`) = `GuestAccess.ssh(SshCredentials.privateKey("vmtc", <it.sshKey>), SshOptions.builder().sudo(true).build())`; `AGENT` (`agent`) = `GuestAccess.guestAgent()`. `toString()` returns the display name (so Allure rows read `exec-uname [sshRoot]`), and static shorthands `sshRoot()`, `sshRootKey()`, `sshVmtcSudo()`, `agent()` return `access()` of the constant. **Default for `VmIT` containers: `sshRoot()`** (black-box is the primary use case, and the reference guest supports it); tests that need the agent say so.
- `VmIT` (base class in `:it`): `@BeforeAll` warm-up (resolves `TestImages.guest()` and `TestImages.blackBox()` once so the cache is populated before timing tests, REQ-LIFE-02); `register(container)` (call it **before** `start()`, so a container whose `start()` throws is still cleaned up and documented); `vm()` = a registered `QemuVmContainer` with `TestImages.guest()` and `TestAccess.sshRoot()` pre-applied; `@AfterEach` attaches the standard evidence (REQUIREMENTS.md §3.3; for a failed `start()` whatever `getLogs()`/`describe()`/`timings()` return), writes the failure artifacts (`serial.log`, one `screen<i>.png` per screen) to `it/build/vm-artifacts/<class simple name>/<method name>/` (the §9.13 layout, so the CI upload of `it/build/vm-artifacts/**` has content) and stops registered containers. It also keeps a static `lastSessionLabel` so T2 can compare `.session` across containers of one JVM.
- **Transport-parametrised tests:** `ExecIT`, `CopyIT` and `GuestContractIT.referenceGuestNoSeed` are `@ParameterizedTest`s over the `TestAccess` constants `SSH_ROOT`, `SSH_VMTC_SUDO`, `AGENT` (a JUnit `@MethodSource` named `transports` in `VmIT`); T2 uses the source `transportsSshAgent` (`SSH_ROOT`, `AGENT`). The Allure story shows one row per transport (`exec-uname [sshRoot]`, …).

### 11.2 `:images` tasks (all `Exec` with declared inputs/outputs; engine from `-PcontainerEngine`, default `podman`)

| Task | Command |
|------|---------|
| `buildRunner` | `<engine> build -f images/runner/Containerfile --build-arg UBI_IMAGE=<ubiImage> --build-arg VERSION=<version> --build-arg LABEL_PREFIX=<labelPrefix> --build-arg IMAGE_TITLE=<imageRunnerName> -t <imageRegistry>/<imageRunnerName>:<version>-<imageTagSuffix> images/runner`; with `-PnoCache=true` add `--no-cache --pull` (used by the weekly CI run, REQ-BLD-05) |
| `buildRecorder` | copy `images/runner/repos/*` to `images/recorder/repos/`, then the same build for `images/recorder/Containerfile` with `IMAGE_TITLE=<imageRecorderName>`, tag `<imageRegistry>/<imageRecorderName>:<version>-<imageTagSuffix>` |
| `verifyRunnerImages` | §6.4 checks 1–11 over both images; `dependsOn buildRunner, buildRecorder` |
| `downloadGuestBase` | download `baseUrl` to `images/build/guest/base.qcow2`, verify `baseSha256`; skipped when the file already verifies |
| `generateTestSshKey` | `mkdir -p images/build/guest/ssh` (ssh-keygen does not create parents), then `ssh-keygen -q -t ed25519 -N '' -C vmtc-test -f images/build/guest/ssh/id_ed25519`, `ssh-keygen -q -t rsa -b 3072 -N '' -C vmtc-test -f …/id_rsa` and `ssh-keygen -q -t ecdsa -b 256 -N '' -C vmtc-test -f …/id_ecdsa`, each unless both of its files exist (host `ssh-keygen`; PLAN-STEPS.md step 0.5). The keys are **test fixtures**, never published; `images/build/` is git-ignored. T41 (b) logs in with each |
| `buildGuestDisk` | `GUEST_VARIANT=reference SSH_PUBKEY_FILES="images/build/guest/ssh/id_ed25519.pub images/build/guest/ssh/id_rsa.pub images/build/guest/ssh/id_ecdsa.pub" images/guest/customize.sh <engine> <runner tag> images/build/guest/base.qcow2 images/build/guest/work <imageRegistry>/<imageGuestName>:<version> <kvm if /dev/kvm usable else tcg>`, then the REQ-GUEST-05 format checks (one layer, empty `Config.Cmd`/`Entrypoint`, one regular file uid/gid 107 under `/disk/`); `dependsOn buildRunner, downloadGuestBase, generateTestSshKey` |
| `buildBlackBoxGuestDisk` | same with `GUEST_VARIANT=blackbox`, work dir `images/build/guest/work-blackbox`, tag `<imageRegistry>/<imageGuestBlackboxName>:<version>`, plus a check that the customize serial log contains `AGENT_REMOVED` |
| `buildGuestDisks` | aggregate: `dependsOn buildGuestDisk, buildBlackBoxGuestDisk` |
| `pushImages` | `<engine> push` the four tags (runner, recorder, two guests) to `${IMAGE_REGISTRY}`; `onlyIf { project.findProperty('pushImages') == 'true' }` |

### 11.3 Generated constants (`:lib:generateConstants`)

`generateConstants` writes `VmtcConstants.java` into `build/generated/sources/vmtc/java/<basePackage path>/` and adds it to `main`:

```java
public final class VmtcConstants {
  public static final String VERSION = "<version>";
  public static final String LABEL_PREFIX = "<labelPrefix>";
  public static final String SYSPROP_PREFIX = "<syspropPrefix>";
  public static final String ENV_PREFIX = "<envPrefix>";
  public static final String CACHE_DIR_NAME = "<cacheDirName>";
  public static final String IMAGE_TAG_SUFFIX = "<imageTagSuffix>";
  public static DockerImageName defaultRunnerImage()   { return DockerImageName.parse("<imageRegistry>/<imageRunnerName>:" + VERSION + "-" + IMAGE_TAG_SUFFIX); }
  public static DockerImageName defaultRecorderImage() { return DockerImageName.parse("<imageRegistry>/<imageRecorderName>:" + VERSION + "-" + IMAGE_TAG_SUFFIX); }
  public static DockerImageName referenceGuestImage()  { return DockerImageName.parse("<imageRegistry>/<imageGuestName>:" + VERSION); }
  public static DockerImageName blackBoxGuestImage()   { return DockerImageName.parse("<imageRegistry>/<imageGuestBlackboxName>:" + VERSION); }
}
```

### 11.4 Allure reporting and `test-support` (D10, D11)

**`test-support` module** (not published; `java-library`):

- Dependencies: `platform("io.qameta.allure:allure-bom:${ALLURE_JAVA_VERSION}")`, `io.qameta.allure:allure-jupiter`, `io.qameta.allure:allure-java-commons`, `org.junit.jupiter:junit-jupiter-api`, `jackson-databind` (all `api` so test modules inherit them).
- `processResources` copies `../docs/vm-testcontainer/REQUIREMENTS.md` to `vmtc-requirements/REQUIREMENTS.md` (declared input, so edits to the document rebuild the jar).
- Types in `${BASE_PACKAGE}.testsupport`: `Requirement`, `Requirements`, `RequirementExtension`, `RequirementsCatalog` (parser, REQUIREMENTS.md §3.2), `RequirementsCheck` (`main`, §11.5), `Evidence` (REQUIREMENTS.md §3.3), `AllureEnvironment` (writes `environment.properties`).
- Resource `allure.properties` is **not** here; each test module has its own (below).

**Every test module** (`:lib`, `:junit5`, `:it`):

- `testImplementation project(':test-support')` (plus `allure-jupiter` transitively; `:junit5` adds `junit-platform-testkit` for T34).
- Every `Test` task gets `systemProperty "<syspropPrefix>.reqDocUrl", reqDocUrl` (configured once in the root `build.gradle` for `allprojects`); `RequirementExtension` reads it for the link target and falls back to the bare `#<anchor>` when the property is absent. Likewise `:lib:test` passes every `gradle.properties` value as `expected.<key>` (U5, U14 use `expected.javaRelease`).
- `src/test/resources/allure.properties`: `allure.results.directory=build/allure-results`. No link pattern is needed: `RequirementExtension` passes the complete URL to `Allure.link(name, type, url)`.
- No AspectJ agent: only the Allure runtime API is used (`Allure.addAttachment`, `Allure.step`, labels, links), never `@Step`/`@Attachment`.
- Attachments go through `Evidence` (test-support), which calls `Allure.addAttachment(name, type, InputStream, extension)`.

**Root task `allureReport`:**

1. A detached configuration `allureCli` with `io.qameta.allure:allure-commandline:${ALLURE_REPORT_VERSION}@zip`; a `Sync` task unzips it to `build/allure-cli/` (contains `allure-${ALLURE_REPORT_VERSION}/bin/allure`) **[V]** (zip layout checked).
2. `AllureEnvironment` output: `environment.properties` with `engine`, `engine.version` (from env `<envPrefix>IT_ENGINE_VERSION`, set by CI from `podman --version`, else `unknown`), `kvm.mode`, `os`, `java.version`, `git.commit` (env `GITHUB_SHA`), written into `it/build/allure-results` (or the first results dir that exists).
3. `Exec`: `build/allure-cli/allure-<v>/bin/allure generate --clean --single-file -o build/allure-report <results dirs that exist>`. `--single-file` produces one `index.html` that opens from disk **[V]** (`allure help generate` lists the option).
4. Never fails the build when a results directory is missing; prints which were included.

### 11.5 Root check tasks

| Task | What it does |
|------|--------------|
| `checkParameterHygiene` | The §0 grep rules. Plus these greps on `lib/src/main`: no `ProcessBuilder`, no `Runtime.getRuntime().exec`, no `throw new RuntimeException(`, no `throw new UncheckedIOException(` (REQ-ENG-03, REQ-ERR-01); no `org.apache.sshd` or `net.i2p.crypto` import outside `internal/transport/` (D16). Plus: `.github/workflows/ci.yml` contains `cron: '0 6 * * 1'` (REQ-BLD-05). Plus: no `@Disabled` in any test source set (REQUIREMENTS.md §3.1). This list is complete: REQ-ENG-03 does **not** ask for a grep of the strings `podman`/`docker` (they appear legitimately in remediation messages, §9.6) |
| `checkRequirementTraceability` | `JavaExec` of `RequirementsCheck` (test-support) with the REQUIREMENTS.md path, the test source roots and `traceabilityRequireFullCoverage`; writes `build/reports/requirements/traceability.md` (REQUIREMENTS.md §3.4) |
| `checkDependencies` | Resolves `:lib` `configurations.api.dependencies`, `implementation.dependencies` and `shaded.dependencies` (direct declarations) and compares `group:name` sets with the §9.1 table; any difference fails. Also fails if any **resolvable** configuration (`canBeResolved`) of `:lib` resolves an `org.bouncycastle:*` artifact (D16) (REQ-BLD-03, REQ-OCI-06) |

---

## 12. CI (GitHub Actions)

### 12.1 Workflow `ci.yml`

- **Triggers:** `push`, `pull_request`, and `schedule: cron '0 6 * * 1'` (weekly rebuild against fresh repos; the scheduled run passes `-PnoCache=true`; §15 R1).
- **Common steps:** checkout; `actions/setup-java` (Temurin `${JAVA_RELEASE}`, Gradle cache); `export <envPrefix>IT_ENGINE_VERSION="$(podman --version | awk '{print $3}')"` (the same value that `kvm-scenario.sh` exports as `<envPrefix>IT_PODMAN_VERSION` for T35; two names, one source).
- **Jobs:**

| Job | Runner(s) | Runs | Timeout |
|-----|-----------|------|---------|
| `unit` | ubuntu-24.04 | `./gradlew checkParameterHygiene checkDependencies checkRequirementTraceability :lib:test :lib:shadedSmokeTest :junit5:test publishToMavenLocal allureReport` (`:junit5:test` is engine-free; T34 runs as `:junit5:extensionIT` in `integration`); then the REQ-BLD-02 POM assertions (incl. REQ-BLD-08: the published POM has no `org.apache.sshd` or `net.i2p.crypto` dependency) | 20 min |
| `integration` | matrix `{ubuntu-24.04, kvm}`, `{ubuntu-24.04, tcg}`, `{ubuntu-26.04, kvm}` | steps below | 30 min KVM / 90 min TCG |
| `compat` | matrix ubuntu-24.04, ubuntu-26.04 | `./gradlew :images:verifyRunnerImages`; for S in A, B, C: `it/fixtures/kvm-scenario.sh S` then `:it:compatTest` (PLAN-STEPS.md step 1.25); `allureReport` | 30 min |
| `oci-auth` | ubuntu-24.04, KVM | KVM udev rule; `it/fixtures/test-registry.sh start`; `./gradlew :it:ociAuthTest -P<syspropPrefix>.kvm=REQUIRE`; `test-registry.sh stop` (always); `allureReport` | 45 min |

- **Steps for `integration`:**
  1. If kvm: udev rule `KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"`, then `udevadm control --reload-rules && udevadm trigger --name-match=kvm` **[V]**. The tcg job does **not** apply it (T20 relies on `/dev/kvm` being unusable there).
  2. `podman system service --time=0 unix://$XDG_RUNTIME_DIR/podman/podman.sock &` (the same command form as `run-scenario.sh` [V]).
  3. Export `DOCKER_HOST=unix://$XDG_RUNTIME_DIR/podman/podman.sock` and `TESTCONTAINERS_RYUK_DISABLED=true` [V].
  4. `actions/cache` for `images/build/guest/base.qcow2`, keyed by `baseSha256` (the spike cached the base image the same way [V]). The two customized guests are rebuilt each run, about 1.5 min each on KVM, about 8 min each on TCG [V] (run 31).
  5. `./gradlew :it:integrationTest :junit5:extensionIT -P<syspropPrefix>.kvm=<REQUIRE for kvm | DISABLE for tcg>` (`extensionIT` exists from M6 on)
  6. `./gradlew allureReport` with `if: always()`.
  7. Upload, all `if: always()`: `build/allure-report/` as `allure-report-integration-<os>-<accel>`; `**/build/allure-results/` as `allure-results-…`; `**/build/vm-artifacts/**` (written by `VmIT` in `:it` and by `@VmTest` in `:junit5`) and `**/build/reports/**`.
- Every other job uploads `build/allure-report/` and `**/build/allure-results/` the same way (`allure-report-unit`, `allure-report-compat-<os>`, `allure-report-oci-auth`), plus `build/reports/requirements/traceability.md` from `unit`.

### 12.2 Expected durations

| Item | Duration |
|------|----------|
| Runner image build | about 51 s [V]; recorder image similar [U] (the spike's combined media image built in 40–60 s [V]) |
| Guest disk builds | about 1.5 min each on KVM (40–45 s boot + 47 s flatten + packaging); about 8–9 min each on TCG [V] (runs 5, 31) |
| KVM integration suite | about 5 min [U, estimate] |
| TCG integration suite | about 20 min [U, estimate] |
| Allure report generation | well under 1 min [U, estimate] |

### 12.3 Parameter hygiene

The `checkParameterHygiene` task (§0 rules, §11.5) runs in the `unit` job.

---

## 13. Test plan

**Acceptance criteria and evidence are normative in [REQUIREMENTS.md](REQUIREMENTS.md).** This section says how each test is set up and which requirements it verifies (its `@Requirement` annotations). The evidence each test attaches is the *Evidence* cell of every requirement it verifies, plus the standard VM evidence (REQUIREMENTS.md §3.3).

**Rules:**
- **Guest and access:** every `:it` test uses `TestImages.guest()` with `TestAccess.sshRoot()` unless stated otherwise (§11.1). *(agent)* = `TestAccess.agent()`; *(transports)* = parametrised over `sshRoot`, `sshVmtcSudo`, `agent`; *(black-box)* = `TestImages.blackBox()`.
- **Jobs:** KVM jobs exclude `tcg`-tagged tests; TCG jobs exclude `kvm`-tagged tests; untagged tests run everywhere.
- **Recording:** tests marked *recording* start a recorder container (§9.8) with the locally built recorder image.
- **Fixtures:** `:it` test fixtures may call the engine CLI (`podman build`, `podman push`, `podman login`) and generate files with `xorriso`/`qemu-img` inside a runner container. The library itself never does (REQ-ENG-03).
- **System property hygiene:** tests that must control the KVM mode regardless of the CI job (`accelPreferDefault*`, T20, T35) clear `<syspropPrefix>.kvm` for their duration and restore it in `finally`. With D9 this only matters for the *default*; tests calling `withKvm(...)` explicitly are unaffected.
- **Evidence:** [V] = the behaviour was verified by a spike (§17); [U] = new, not yet run.
- **Expected start failures:** a test that expects `start()` to throw still calls `register(container)` first (§11.1), so `VmIT` stops the container and attaches `container.log`, `vm-config.json` and `timings.json`; the exception message carries the guest-side facts (§9.2, after step 14) and is attached as `exception.txt`.
- **One test method per lettered case** where a test lists cases (a), (b), …, named `<method><Letter>` (for example `ImageSourcesIT.fromPathIsolation` for T18 (a)); evidence names are then unique per method and "once" assertions hold per method.

### 13.1 Integration tests (`:it`, real Podman)

| ID | Class.method | Setup / procedure | Verifies | Spike evidence |
|----|--------------|-------------------|----------|----------------|
| T1 | `AccelIT.accelKvm` (`kvm`), `accelTcg` (`tcg`), `accelPreferDefault` (`kvm`), `accelPreferDefaultTcg` (`tcg`) | `REQUIRE` / `DISABLE` / no `withKvm` with the property cleared; QMP `query-kvm`; inspect `HostConfig.Devices`; `withQemuArgs("-name","vmtc-extra")` + `query-name` in `accelKvm`/`accelTcg` | REQ-KVM-01, REQ-KVM-03, REQ-KVM-04, REQ-CFG-03 | [V] |
| T2 | `LifecycleIT.bootToReady` *(transports: sshRoot, agent; source `transportsSshAgent`)* | After the `VmIT` warm-up: time `start()`; `exec("/usr/bin/true")`; `infoCmd`; inspect the labels and compare `.session` with `VmIT.lastSessionLabel` (the previous container of this JVM; when none ran yet, create a second container with docker-java from the inspected image **without starting it** — labels are set at create); `pgrep -c -x qemu-kvm`; `cat /etc/os-release; uname -a` | REQ-LIFE-01, REQ-LIFE-02, REQ-LIFE-10, REQ-ENG-01, REQ-PLAT-02 | [V] agent 10.9–12.1 s KVM / 46.7–66.5 s TCG; SSH 21 s KVM / 93 s TCG (run 31) |
| T3 | `ExecIT.execBasics` *(transports)* | The commands in REQ-EXEC-01..03 and REQ-EXEC-06 (`kill -9 $$`); under `sshVmtcSudo` additionally `id -u` → `0` | REQ-EXEC-01, REQ-EXEC-02, REQ-EXEC-03, REQ-EXEC-06, REQ-ACC-06 | [V] (01, env, sudo over SSH; 01 over QGA); others [U] |
| T4 | `CopyIT.copyRoundTrip` *(transports)* | Random 5 MB / 50 MB files, `test ! -e /tmp/vmtc` then a deep target path, 0- and 3-byte arrays; MB/s per transport in the evidence | REQ-FILE-01, REQ-FILE-02, REQ-FILE-03 | [V] (50 MB both transports) |
| T5 | `DisplayIT.screenshotEachScreen` | `withScreens(2)`; screenshot both (count non-black pixels on head 0); `screen(0).forceModeset()` (no-op); `screen(1).forceModeset()`; screenshot head 1 again; `screenshot(Path)` | REQ-DISP-02, REQ-DISP-03 | [V] |
| T6 | `DisplayIT.vncExposed` | Container A: `withScreens(4)` + `TestAccess.agent()`: `query-vnc-servers`; raw RFB handshake per `vnc()` (read `RFB 003.008`, send it back, read the security-type list); `query-status`, `guestAgent().ping()`, `vnc()` fields; inspect port bindings and `PublishAllPorts` (published set = {4444, 4445, 5900–5903}, f = 0). Container B: default `sshRoot` access + `withVncBindAddress("0.0.0.0")`: `guestAgent()` → `IllegalStateException`; published set includes `10000` (f = 1) bound to `127.0.0.1` while VNC binds to `0.0.0.0` | REQ-VNC-01, REQ-VNC-02, REQ-LIFE-08, REQ-LIFE-12 | [V] (handshake, 2 screens); 4 screens and bindings [U] |
| T7 | — | *Removed (D8: no VNC password). Number kept so older references stay valid.* | — | — |
| T8 | `RecordingIT.recordTwoScreens` (*recording*) | `withScreens(2)`, `screen(1).forceModeset()`, start a `tick` loop on `/dev/tty1` (§9.7), record both for 12 s, measure `close()`; then one 6 s recording with `RecordingOptions.fps(5)` + `gst-discoverer-1.0` in the **recorder** container; then two simultaneous recordings of screen 0 | REQ-REC-01, REQ-REC-02, REQ-REC-07 | [V] pipeline (two screens, in-container); sidecar path, options, concurrency [U] |
| T9 | `RecordingIT.recordingSurvivesExclusiveClient` (*recording*) | While recording, a raw RFB client completes the handshake with security type None and sends ClientInit `shared-flag=0`, holds 3 s, disconnects | REQ-VNC-05 | [V] (sandbox) |
| T10 | `RecordingIT.recordStaticScreen` (*recording*) | Record 10 s without guest activity | REQ-REC-03 | [V] (sandbox) |
| T11 | `AudioIT.audioTone` | `info capture` before; start WAV; guest `/usr/bin/speaker-test -t sine -f 1000 -c 2 -l 1`; second `startAudioRecording` → exception; close; `info capture` after; RMS and dominant frequency (DFT of ≤ 65536 samples) in Java; start and close a second recording | REQ-AUD-01, REQ-AUD-02, REQ-AUD-03 | [V] (01); 02, 03 [U] |
| T12 | `DisplayIT.keyboardType` | `execShell("echo root:vmtc \| chpasswd")`; `type("root\n")`, wait 3 s; `type("vmtc\n")`, wait 5 s; `type("echo TYPED_OK > /dev/ttyS0\n")`; a serial-log consumer must see `TYPED_OK` within 60 s; screenshot | REQ-KEY-01 | [V] (run 5) |
| T13 | `HotplugIT.usbStorage`, `usbHid`, `usbReadOnly` | Storage: 16 MiB raw image made with `qemu-img` in a runner container. HID: keyboard, tablet, mouse in turn. Read-only: `usbStorage(img, true)` + `dd` + host digest; `close()` unplugs | REQ-HOT-01, REQ-HOT-02, REQ-HOT-10 | storage [V]; HID, read-only [U] |
| T14 | `HotplugIT.virtioDisk`, `nic`, `portsExhausted` | Host image and `newBlankDisk("64M", VIRTIO)` (`lsblk -b`); `nic("52:54:00:12:34:56")`; `withHotplugPorts(1)` + plug, plug (fails), unplug, plug | REQ-HOT-03, REQ-HOT-04, REQ-HOT-05 | [V] (03, 04); 05 [U] |
| T15 | `HotplugIT.cdromInsertEject` | ISO with label `VMTCCD` and one file, made with `xorriso` in a runner container; `blkid` after eject | REQ-HOT-06 | [V] |
| T16 | `HotplugIT.cpuKvm` (`kvm`), `cpuTcg` (`tcg`) | `hotplug(Devices.cpu())`; `unplug()` → `UnsupportedOperationException`; `close()` no-op | REQ-HOT-07, REQ-HOT-10 | [V] (KVM; TCG failure observed) |
| T17 | `HotplugIT.memory` | `hotplug(Devices.memory(512))`; handle semantics as T16 | REQ-HOT-08, REQ-HOT-10 | [V] |
| T18 | `ImageSourcesIT` (one method per case: `fromPathIsolation`, `fromUrlWithSha`, `containerDiskLocalFallback`, `baked`, `containerDiskShape`, `cacheReuse`, `backingFileRejected`) | (a) `fromPath` of the guest qcow2; guest writes 1 MiB + `sync`; stop; cache file SHA-256 unchanged; a second container does not see the file. (b) `fromUrl` from an in-JVM `HttpServer` serving that qcow2, with SHA-256; request log. (c) `fromContainerDisk(<local never-pushed tag>)` → boots + `using local image` WARN once. (d) `baked()` on an image built by the fixture `FROM <runner>` + `COPY disk.qcow2 /vm/base/disk.img`; inspect mounts. (e) containerDisks with 0 files (`FROM scratch` + `WORKDIR /disk`) and 2 files → `IllegalArgumentException`. (f) repeat (a)–(c) in its own method: cache listing unchanged, no HTTP request (the `using local image` WARN appears once more, once per `start()`). (g) an overlay qcow2 with a backing file (made with `qemu-img create -b` in a runner container) → `IllegalArgumentException` at `start()` (unwrapped by the override) | REQ-DISK-01, REQ-DISK-02, REQ-DISK-03, REQ-DISK-04, REQ-DISK-05, REQ-DISK-06, REQ-DISK-13, REQ-LIFE-03, REQ-GUEST-05, REQ-OCI-07 | partly [V] |
| T19 | `GuestContractIT.guestNotPrepared` *(agent)* | Stock Rocky served from an in-JVM `HttpServer` (`<syspropPrefix>.it.baseQcow2`) with its SHA-256, no seed, `GuestAccess.guestAgent()` → `start()` throws exactly `GuestCommandDisabledException`; attach its message as `guest-info.json` (it embeds the `guest-info` reply, §9.2). Then a seed from `CloudInitSeed.builder().runcmd(<G3 sed line>, "systemctl restart qemu-guest-agent")` → exactly `GuestAgentConfinedException`; attach its message as `id-Z.txt` (embeds the `id -Z` output). `describe()` of both shows `agentChecks: run` | REQ-GUEST-01, REQ-GUEST-02, REQ-ERR-03, REQ-ACC-10 | [V] (behaviour) |
| T20 | `AccelIT.kvmRequireWithoutKvm` (`tcg`) | `assumeFalse(new KvmDetector().usable())`; property cleared; `withKvm(REQUIRE)` → `start()` throws exactly `KvmUnavailableException`; message equals the §9.6 text for `!present()` or `present() && !usable()` | REQ-KVM-02, REQ-ERR-03 | [U] |
| T21 | `CleanupIT.orphanCleanup` | With docker-java, create (do not start) three containers from the runner image, labelled: (1) other session, created now − 2 h; (2) other session, created now − 10 min; (3) current session, now − 2 h. `VmtcCleanup.removeOrphans()` → returns 1, only (1) is gone. Repeat with override `cleanup=false` → returns 0 | REQ-CLN-01 | [U] |
| T22 | `UefiIT.uefiBoot` | `withFirmware(Firmware.UEFI)`; `test -d /sys/firmware/efi`; screenshot | REQ-LIFE-09 | [V] (run 19: efivars present, all checks pass under OVMF) |
| T23 | `GuestContractIT.referenceGuestNoSeed` *(transports: sshRoot, sshVmtcSudo, agent)* | `VmImage.referenceGuest()`, no seed; `id -Z`, `ls /lib/modules/$(uname -r)`, `amixer -c 0 get Master`; under the agent `id -Z` must show `virt_qemu_ga_t` | REQ-GUEST-04, REQ-GUEST-09, REQ-DISK-11 | [V] (run 5 agent 11.2 s; run 31 SSH both users) |
| T24 | `RecordingIT.noZombieRecorders` (*recording*) | In the **recorder** container: `cat /proc/1/comm` = `tini`; start + stop a recording; within 2 s `execInContainer("sh","-c","grep -H '^State:' /proc/[0-9]*/status")` has no `Z (zombie)`. In the runner: `cat /proc/1/comm` = `tini` | REQ-IMG-05 | [V] (tini reaping, sandbox); recorder container [U] |
| T25 | `GuestContractIT.stockRockyWithSeed` *(agent)* | Stock Rocky from the in-JVM server (as T19) + `CloudInitSeed.builder().stockRocky().writeFile("/etc/vmtc-test","x\n").build()`, `GuestAccess.guestAgent()`; `saveSerialLog` right after `start()`; T3's first command; read `/etc/vmtc-test`; `amixer`; `describe()` shows `agentChecks: run` | REQ-GUEST-06, REQ-GUEST-07, REQ-DISK-02, REQ-ACC-10 | [V] (seed), file [U] |
| T26 | `HttpDownloadTest` (`:lib`, engine-free) | In-JVM `HttpServer`s with request logs: (a) no/wrong credentials → `ImageAuthException`, redaction; (b) Basic, Bearer, custom header; (c) `.gz` URL with compressed vs decompressed digest; (d) `.gz`/`.xz`/`.zst`, `AUTO` unknown suffix, `NONE`, `GZIP` without suffix, redirect to `.gz`; (e) cross-origin vs same-origin redirect per credential kind; (f) no checksum: one WARN (via `Warnings.listen`), 304 reuse, 200 replace, server down → cache + WARN; (g) 5 and 6 redirects; (h) 503,503,200 / 503×3 / reset×2 then 200 / 404 / 401 / wrong digest request counts with an injected zero backoff; (i) header stall and read stall, `Secret.fromEnv` laziness | REQ-HTTP-01, REQ-HTTP-02, REQ-HTTP-03, REQ-HTTP-04, REQ-HTTP-05, REQ-HTTP-06, REQ-HTTP-07, REQ-HTTP-08, REQ-HTTP-10 | [V] (a–e); (f)–(i) [U] |
| T27 | `OciAuthIT.pull` — run by `ociAuthTestA..D`, **one JVM per scenario** (`-D<syspropPrefix>.it.ociScenario=a\|b\|c\|d`) | Fixture registry (§12.1 `oci-auth`). Once, before scenario (a): `podman tag <local guest> <ref>`, `podman login`, `podman push --digestfile it/build/test-registry/v1.manifest-digest <ref>`, `sha256sum` of the pushed qcow2 → `it/build/test-registry/v1.qcow2.sha256`, `podman logout` (the files survive the per-scenario JVMs). Before each scenario: `podman rmi -f <ref>`, remove `~/.docker/config.json` and `$XDG_RUNTIME_DIR/containers/auth.json`. (a) nothing → `ImageAuthException`; (b) write `config.json`; (c) nothing stored + `OciOptions.credentials`; (d) `podman login` only | REQ-OCI-02, REQ-OCI-03, REQ-OCI-04, REQ-OCI-07 | [V] pull for a–d (run 16: S4 = explicit `AuthConfig`, S5 = wrong password → `UnauthorizedException`) |
| T28 | `OciAuthIT.tagMove` — in scenario (b) | Fixture: v1 = the reference guest qcow2; v2 = the same disk re-encoded with `qemu-img convert -c -O qcow2 -o cluster_size=128k` (bootable, different bytes). Push v1 as `:t` (`--digestfile it/build/test-registry/v1.manifest-digest`), start, `execInContainer("sha256sum","/vm/base/disk.img")` = the **file** digest in `v1.qcow2.sha256`, cache listing; start again (same inode); push v2 as `:t` (`v2.manifest-digest`, `v2.qcow2.sha256`), start → the v2 file digest and one more cache entry; start `@sha256:<content of v1.manifest-digest>` (the **manifest** digest, not the file's) → the v1 file digest | REQ-OCI-05, REQ-DISK-03, REQ-DISK-12 | [U] |
| T29 | `LifecycleIT.startFailureReported` | The runner image + `VmImage.baked()` (the runner has no `/vm/base/disk.img`) → exactly `VmtcException` containing `no base disk` | REQ-LIFE-04, REQ-ERR-03 | [U] |
| T30 | `LifecycleIT.guestNotReady` | (a) `fromPath(<16 MiB raw file of zeros>)`, `TestAccess.sshRoot()`, `withGuestReadyTimeout(10 s)`, `withLogConsumer` to time `VMTC_READY`; exactly `GuestNotReadyException`. (b) the same disk with `GuestAccess.guestAgent()` → exactly `GuestNotReadyException`. (c) reference guest, `GuestAccess.guestAgent()`, `withGuestReadyTimeout(1 s)` → message contains `Linux version` or `login:`. (d) the zeros disk with the **default** access: `start()` returns within 5 s of `VMTC_READY`; `timings()` has no `guestReady`; `exec("/usr/bin/true")` throws `GuestAccessUnavailableException` within 100 ms | REQ-LIFE-05, REQ-ACC-03, REQ-ACC-04, REQ-DISK-01, REQ-ERR-03 | [U] |
| T31 | `RecordingIT.stopFinalizesRecordings` (*recording*) | Start a screen recording and an audio recording; call `stop()` without closing them; both host files valid; the recorder container is gone afterwards | REQ-LIFE-06, REQ-REC-09 | [U] |
| T32 | `ExecIT.execTimeout` *(transports)* | `exec(Duration.ofSeconds(2), "/usr/bin/sleep", "61")` → `GuestExecTimeoutException` within 2 s + 5 s; then `exec("/usr/bin/pgrep","-x","sleep")` exits 1 (never `pgrep -f`: it matches the SSH wrapper's own command line, §3.10) | REQ-EXEC-04 | [V] mechanism (spike D: `timeout -k 1` → exit 124, children gone); the test itself [U] |
| T33 | — | *Removed (D17: recording no longer depends on a runner variant; REQ-REC-04 retired). Number kept.* | — | — |
| T34 | `VmTestExtensionIT.artifactsOnFailure` (`junit5/src/test/java`, task `:junit5:extensionIT`; tagged `testkit-only`) | A separate `Test` task `extensionIT` in `:junit5` (`includeTags 'testkit-only'`, engine environment and `dependsOn ':images:verifyRunnerImages', ':images:buildGuestDisks'` like `:it`); `:junit5:test` excludes the tag. JUnit Platform `EngineTestKit` runs a nested `@VmTest` class (also tagged `testkit-only`, so the normal run never discovers it) whose test starts a VM with `TestAccess.sshRoot()`, opens a screen recording (recorder sidecar), and fails; its `@AfterEach` asserts the artifact files already exist under `junit5/build/vm-artifacts/<class>/<method>/` | REQ-JUN-01 | [U] |
| T35 | `KvmCompatIT` (task `compatTest`) | Reads `<envPrefix>IT_KVM_SCENARIO` and `<envPrefix>IT_PODMAN_VERSION` (set by `it/fixtures/kvm-scenario.sh`); property cleared. Case 1 (`PREFER`): `fromPath(<16 MiB raw zeros>)`, default access (`none()`), capture logs; `start()` returns at `VMTC_READY`; assert `VMTC_ACCEL`, `HostConfig.Devices`, `Annotations`, `GroupAdd` and the WARN per REQ-KVM-05's scenario table. Case 2 (`REQUIRE`): where case 1 expects `tcg`, expect `KvmUnavailableException` with the group-restricted text; elsewhere `start()` returns with `VMTC_ACCEL kvm` | REQ-KVM-05, REQ-KVM-06 | [V] (matrix via the spike probe; run 16: the login-session JVM sees `/dev/kvm` readable+writable, the CI-shell JVM does not) |
| T36 | `LifecycleIT.resources` | `withCpus(3).withMaxCpus(6).withMemoryMb(1536).withMemoryHotplug(2,"6G")`: `nproc`, `/proc/meminfo`, `query-hotpluggable-cpus`, `query-memory-size-summary`; then `withMemoryMb(4096)` with default maxmem `8G` → ok, and `withMemoryMb(16384)` → `IllegalArgumentException` at `start()` | REQ-LIFE-11 | [U] |
| T37 | `LifecycleIT.serialLog` | `withSerialLogConsumer` collecting lines; after start: `echo VMTC_MARK_<uuid> > /dev/ttyS0`; time to the consumer; `saveSerialLog` | REQ-LIFE-07 | [U] |
| T38 | `BlackBoxIT.sshRoot` *(black-box)* | `TestImages.blackBox()`, `TestAccess.sshRoot()`, default readiness (`ssh()`): time `start()`; `rpm -q qemu-guest-agent` exits 1; `systemctl is-active cloud-init` prints `inactive` (exit status **3**, not 0); `test -e /etc/cloud/cloud-init.disabled`; `exec` basics; 50 MB copy round trip with MB/s; `sshd -T` output contains `permitrootlogin yes` and `passwordauthentication yes` (`sshd-T.txt`); `screenshot()` of head 0 is 1280×800 with console text; `keyboard().type(...)` login as in T12; `forwardedPort(22)` accepts a TCP connect and returns an `SSH-` banner; `describe()` shows `access: ssh`, `agentChecks: skipped` | REQ-ACC-01, REQ-ACC-02, REQ-ACC-05, REQ-ACC-10, REQ-GUEST-08, REQ-GUEST-09, REQ-NET-01, REQ-PLAT-02, REQ-DISK-11 | [V] (run 31: everything but the typed login) |
| T39 | `BlackBoxIT.noneAccessAndGuestPort` *(black-box; methods `noneAccess`, `guestPortReadiness`, `lazySsh`)* | (a) default access: `start()` returns within 5 s of `VMTC_READY`; `screenshot()` works; `exec`, `copyToVm` and `screen(0).forceModeset()` each throw `GuestAccessUnavailableException` (§9.7 step 0); `guestAgent()` throws `IllegalStateException`; `forwardedPort(9999)` → `IllegalArgumentException`. (b) `withGuestPortForward(22).withReadiness(Readiness.guestPort(22))` with default access (the forward is explicit because `none()` adds none): `start()` returns only after the SSH banner is readable on `forwardedPort(22)`; time it against (a). (c) `withGuestAccess(ssh)` + `withReadiness(Readiness.bootOnly())`: `start()` returns at `VMTC_READY`; the first `exec` connects lazily and succeeds | REQ-ACC-03, REQ-ACC-04, REQ-ACC-10, REQ-NET-02, REQ-PLAT-02, REQ-DISP-03 | [U] (banner timing and the probe form [V], runs 31/34) |
| T40 | `GuestAccessIT.sshNonRootSudo` (**M2**: needs `withScreens(2)` and `forceModeset`) | Reference guest, `TestAccess.sshVmtcSudo()` (key auth, `sudo(true)`): `id -un` → `root` (sudo prefix), `exec` without sudo via a second container with `sudo(false)` → `vmtc`; `screen(1).forceModeset()` with `withScreens(2)` works under sudo and fails with a `VmtcException` containing `Permission denied` without it; `describe()` shows `agentChecks: skipped` | REQ-ACC-06, REQ-DISP-03, REQ-GUEST-09 | [V] (run 31: sudo + forceModeset) |
| T41 | `GuestAccessIT.sshAuthFailureAndKeyTypes` (methods `wrongPassword`, `keyTypes`) | (a) `SshCredentials.password("root", Secret.of("nope"))`, `withGuestReadyTimeout(20 s)` → exactly `SshAuthException` whose message names the user but not the password; `timings()` shows `sshBanner`. (b) one container per key type, each a plain `start()` with `GuestAccess.ssh(SshCredentials.privateKey("root", <key>))` for `it.sshKey` (Ed25519; this is `TestAccess.sshRootKey()`), `it.sshKeyRsa` and `it.sshKeyEcdsa` (all three public keys are baked into both guests by `generateTestSshKey`/`buildGuestDisks`, §11.2); `exec("/usr/bin/id","-un")` → `root`; attach `key-types.txt` and `security-facts.txt` (relocated `SecurityUtils`: `isBouncyCastleRegistered() == false`, `isEDDSACurveSupported() == true`) | REQ-ACC-07, REQ-ACC-08, REQ-GUEST-09, REQ-ERR-03 | [V] (wrong password; RSA/ECDSA/Ed25519 all accepted, run 31) |
| T42 | `GuestAccessIT.portForwardHttp` | `withGuestPortForward(8080)`; after start `execShell("nohup python3 -m http.server 8080 >/dev/null 2>&1 &")`; from the host, `HttpClient` GET `http://127.0.0.1:<forwardedPort(8080)>/` → 200 within 10 s (poll); `forwardedPort(9999)` → `IllegalArgumentException`; inspect shows `10000/tcp` and `10001/tcp` bound to `127.0.0.1` | REQ-NET-01, REQ-NET-02, REQ-NET-03, REQ-LIFE-08 | [V] (run 34: `http.server` on 8080 reached through the second forward on both guests; no firewall in the way) |
| T43 | `RecordingIT.recorderSidecar` (*recording*) | Start a recording; list containers: exactly one with labels `role=recorder` and `vm=<id>`, on the same network as the VM, image = the recorder image; its `inspect` shows no published ports; `vmtc-rec status <name>` in it prints `running`; after `close()` → `stopped`; after `stop()` the recorder container no longer exists; a second VM in the same test gets its own recorder | REQ-REC-08, REQ-REC-09, REQ-IMG-06 | [U] |

### 13.2 Engine-free tests (`:lib:test`, `:junit5:test`)

| ID | Class | Covers | Verifies |
|----|-------|--------|----------|
| U1 | `QmpClientTest` | In-JVM fake QMP server: greeting + `qmp_capabilities`; event lines skipped while waiting for a return; error → `QmpException(class, desc)`; response timeout | REQ-ERR-02 |
| U2 | `GuestAgentClientTest` | Fake QGA: leading `0xFF` resync; `guest-sync-delimited` id matching; base64 in/out; `0xFF 0x41` → `�A`; exec polling back-off; `out-truncated` / `err-truncated`; omitted `exitcode` + `signal` → `exitCode == -1`; reconnect once and continue polling; `CommandNotFound` + "has been disabled" → `GuestCommandDisabledException` | REQ-EXEC-05, REQ-EXEC-06, REQ-GUEST-01 |
| U18 | `GuestAccessConfigTest` | Defaults: `guestAccess()` is `NoAccess`, `readiness()` is `BootOnly`; `ssh(...)` → `SshHandshake` and an implicit forward of `options.port()` at k = 0 **whatever the call order** (`withGuestPortForward(8080)` before `withGuestAccess(ssh)` still renders `22:10000,8080:10001`); `guestAgent()` → `GuestAgentPing`; `withGuestPortForward` range and duplicate handling; `PortForwards` renders `VM_PORT_FORWARDS` and the exposed-port set {4444, 4445, 5900…, 10000, 10001}; `SshCredentials.toString()` (password and key forms) and `describe()` contain neither password nor key material; `SshOptions.defaults()` = (22, false, acceptAny). **Start-time part (written in step 1.20, needs the `start()` override):** `Readiness.guestAgent()` without agent access, `Readiness.ssh()` without SSH access and `Readiness.guestPort(8080)` without a forward of 8080 → `IllegalArgumentException` from `start()` (via the U16 short-circuit technique) | REQ-ACC-01, REQ-ACC-03, REQ-ACC-04, REQ-ACC-07, REQ-NET-02, REQ-NET-03, REQ-CFG-02 |
| U19 | `SshCommandBuilderTest` | Table-driven over `SshCommandBuilder`: plain argv → `timeout -k 1 60 'a' 'b c'`; env → `env 'K=V' …` between `timeout` and argv; `sudo(true)` → leading `sudo -n`; `execShell` → `/bin/sh -c '<script>'`; quoting of `'`, spaces, `$`, newlines (every element single-quoted with `'\''`); timeout seconds rounded up; result mapping: `exit-status 124` → `GuestExecTimeoutException`; `exit-signal KILL` with elapsed ≥ timeout → `GuestExecTimeoutException`; `exit-signal KILL` with elapsed < timeout → `signal 9`, `exitCode -1`; `exit-status 137` → `exitCode 137` (not a timeout); 16 MiB cap sets the truncated flag | REQ-ACC-09, REQ-EXEC-05, REQ-EXEC-06 |
| U20 | `ShadedJarTest` (task `:lib:shadedSmokeTest`, runs after `shadowJar` with **only** the shaded jar + Testcontainers + slf4j on its classpath) | The jar contains `<basePackage path>/shaded/org/apache/sshd/` and `<basePackage path>/shaded/net/i2p/crypto/`; **every `.class` entry lies under `<basePackage path>/`** (so no top-level `org/apache/sshd/`, `net/i2p/`, `org/slf4j/` or `org/bouncycastle/`); no `module-info.class`; the manifest has `Automatic-Module-Name` = `<basePackage>`; exactly one merged `META-INF/services/<relocated>…` file per service; loading the relocated `SecurityUtils` reports `isEDDSACurveSupported() == true` and `isBouncyCastleRegistered() == false`; `SshCredentials`/`GuestAccess` classes load | REQ-BLD-08, REQ-BLD-04 |
| U3 | `VmImageCacheTest` | In-JVM lock + file lock; two threads and two JVMs (helper `main` via `ProcessBuilder`) on one key; `fromPath` copy (inode differs) and `index.json` memo (copy count); `prune` incl. side files and index rows; SELinux flag → `SelinuxContext`; `Qcow2Header` on a crafted header with and without backing file; `SparseCopy` of a 64 MiB file with 1 MiB of data: content equal, apparent size 64 MiB, allocated size (`stat -c '%b %B'` → blocks × block size; Java has no portable API for it) below 4 MiB; a file ending in a hole keeps its length | REQ-DISK-06, REQ-DISK-07, REQ-DISK-08, REQ-DISK-09, REQ-DISK-10, REQ-DISK-14 |
| U4 | `KeyboardInputTest` | Appendix A.3 table → qcodes; unsupported character → exception and zero `send-key`; `press` chord | REQ-KEY-01, REQ-KEY-02 |
| U5 | `VmtcConstantsTest` | Generated constants equal the `gradle.properties` values passed to the test JVM as system properties | REQ-BLD-01 |
| U6 | `OverridesTest` | Every §9.1 key (`kvm`, `cacheDir`, `cleanup`, `runnerImage`, `recorderImage`, `guestImage`, `blackBoxGuestImage`, `tcgTimeoutFactor`): builder > property > env > default (env injected through a `Map<String,String>` seam); invalid values → `IllegalArgumentException` naming the key; `referenceGuest()` and `blackBoxGuest()` resolution | REQ-CFG-01, REQ-DISK-11 |
| U7 | `InfoCaptureParserTest` | `[<n>]: Capturing audio(44100,16,2) to <path>: <bytes> bytes`; several lines; fallback when exactly one line; empty output → no capture | REQ-AUD-03 |
| U8 | `KvmDecisionTest` | §8.2 table × §9.6 detector states → device / annotation / `VM_ACCEL` / exception; never `GroupAdd`; exact message strings; once-per-JVM WARN observed through `Warnings.listen` (`Warnings.resetForTests()` in `@BeforeEach`, §9.11) | REQ-KVM-02, REQ-KVM-03, REQ-KVM-04, REQ-KVM-05, REQ-KVM-06 |
| U9 | `ConfigValidationTest` | Builder ranges (REQ-CFG-02 list, screens, fps, forward ports, `SshOptions.port`); the timeout table of REQ-KVM-07 for factors 6 and 2.5 (guest-ready, exec, unplug); explicit values unscaled | REQ-CFG-02, REQ-DISP-01, REQ-KVM-07 |
| U10 | `CloudInitSeedTest` | `forStockRocky().userData()` equals §10.2 byte for byte (expected text stored as a test resource copied from PLAN.md; the text ends with exactly one `\n`, as `userData()` does); every builder rule of REQ-GUEST-06; `VMTC_SEED_DONE` last even for `builder().build()` | REQ-GUEST-03, REQ-GUEST-06 |
| U11 | `OciReferenceTest` | Tag, digest, default `latest`, registry with port, `oras://` and missing scheme rejected | REQ-OCI-01 |
| U12 | `SecretAndRedactionTest` | `Secret.toString()`; `Secret.fromEnv` laziness; exception messages contain no secret, user-info or query | REQ-HTTP-02, REQ-HTTP-09, REQ-HTTP-10 |
| U13 | `HotplugUnplugTimeoutTest` | Fake QMP whose `qom-list` always contains the device → `HotplugTimeoutException` | REQ-HOT-09 |
| U14 | `ApiShapeTest` | All public exception types are unchecked and extend `VmtcException`; **no** `module-info.class` next to `QemuVmContainer.class` (located via `QemuVmContainer.class.getProtectionDomain().getCodeSource().getLocation()`, never via a bare `getResourceAsStream("/module-info.class")`, which would find another jar's); class-file major version of `QemuVmContainer.class` = 44 + `Integer.parseInt(System.getProperty("expected.javaRelease"))`; **protocol-neutral API** (§9.14): reflection over the public methods of the §9.14 type list finds no parameter or return type from `.qmp`, `.qga`, `.internal` or `.shaded` except `QemuVmContainer.qmp()` and `guestAgent()` | REQ-ERR-01, REQ-BLD-04, REQ-BLD-09, REQ-PLAT-03 |
| U15 | `RecorderCommandTest` | `vmtc-rec` argv built by Java; parsing of `VMTC_REC_STARTED`, `VMTC_REC_OK duration=12.346 width=1280 height=800`, `VMTC_REC_KILLED`+`VMTC_REC_OK` (WARN), `VMTC_REC_INVALID` + log → `RecordingException`, missing `VMTC_REC_STARTED` → `RecordingException` | REQ-REC-06 |
| U16 | `StartupMessagesTest` | `StartupMessages.tail` (60 → lines 11–60; 5 → all); the `VmWaitStrategy` table of §9.2 step 7 against fake log lines; `start()` unwrapping: a `QemuVmContainer` subclass whose `configure()` throws `KvmUnavailableException` → `start()` throws exactly that type (no engine needed: the throw happens before any client call); the `GuestNotReadyException` / `SshAuthException` message builders of §9.2 step 10 | REQ-LIFE-04, REQ-LIFE-05, REQ-ERR-03 |
| U17 | `CleanupTriggerTest` | Package-private cleanup seam counts invocations (`VmtcCleanup.resetForTests()` in `@BeforeEach`): two `start()`s of containers whose `configure()` short-circuits → one invocation; `cleanup=false` → zero | REQ-CLN-02 |

---

## 14. Implementation milestones

The step-by-step checklist for each milestone is [PLAN-STEPS.md](PLAN-STEPS.md). A milestone is done when its tests pass on **every** `integration` matrix entry (or are excluded there by their `kvm`/`tcg` tag), `unit` is green, `checkRequirementTraceability` passes, and the Allure report of each job shows the milestone's requirements with their evidence attached.

| M | Content | Tests that must pass |
|---|---------|----------------------|
| M1 | Gradle skeleton (§5, §11) incl. shading, `test-support` (`@Requirement`, extension, catalog, `Evidence`), check tasks and `allureReport`, runner and recorder images (§6, §7), both guest builds (§10.3), CI workflow (§12) incl. `compat`; `QemuVmContainer` start/stop (incl. the `start()` override), QMP, **guest access** (`GuestAccess`, `Readiness`, port forwards, `SshTransport`, `QgaTransport`, `NoTransport`), exec, copy, `fromPath`, `baked`, basic `fromUrl` (SHA-256, no credentials, no decompression), `CloudInitSeed`, `KvmMode`/§9.6, `Warnings` | U1–U3, U5, U6, U8–U10, U14, U16–U20; T1–T4, T20, T25, T29, T30, T32, T35, T36, T37, T41; §6.4; `compat` job |
| M2 | Screens, VNC endpoints, screenshots, keyboard, `forceModeset`, UEFI, **black-box guest tests**, HTTP port forward, sudo + `forceModeset` (T40) | U4; T5, T6, T12, T22, T38, T39, T40, T42 |
| M3 | `fromContainerDisk`, `fromOci` (engine pull + credentials + local fallback), full `fromUrl` (§9.3.5), `referenceGuest`, `blackBoxGuest`, test registry fixture and `oci-auth` CI job; switch `TestImages.guest()`/`blackBox()` to the containerDisk factories | U11, U12, T26; T18, T19, T23, T27, T28 |
| M4 | Screen recording (recorder sidecar, §9.8), audio | U7, U15; T8–T11, T24, T31, T43 |
| M5 | Hot-plug | U13; T13–T17 |
| M6 | JUnit extension, orphan cleanup, full traceability (`traceabilityRequireFullCoverage=true`), CI hardening, manual procedures (REQUIREMENTS.md §5), docs | T21, T34; every job green; every requirement traced |

---

## 15. Limits, risks, open items

| # | Item | Impact | Handling |
|---|------|--------|----------|
| L1 | vCPU hot-plug under TCG: device added, but the guest never sees it (every TCG run; root cause unknown) | no CPU hot-plug without KVM | `Devices.cpu()` → `UnsupportedOperationException` under TCG |
| L2 | Secondary screens dark until the guest modesets them | placeholder 640×480 frames | `forceModeset()` for console guests; document |
| L3 | *(merged into L8 — VNC is unauthenticated by decision D8)* | — | — |
| L4 | No H.264/AV1 encoders | WebM/VP8 only | accept |
| L5 | Red Hat: direct qemu-kvm and TCG unsupported | no vendor support | accept for test infrastructure |
| R1 | Rocky RPMs on UBI could conflict in future point releases | build break | pinned `${ROCKY_RELEASE}`; build-time guard; weekly scheduled CI |
| R3 | SELinux-enforcing hosts (Fedora/RHEL): `:z` relabel, `container_use_devices` for `/dev/kvm` | untestable on GitHub runners (AppArmor) | acceptance run on a Fedora/RHEL host before 1.0 [U] |
| R4 | `/dev/kvm` 0660 hosts on Podman < 5.7 | needs user config | §9.6 detection and message [V] |
| R5 | UEFI firmware paths | — | T22 [V] (run 19) |
| R6 | ubuntu-26.04 runner image is in public preview | CI label may change | 24.04 stays primary |
| L6 | **Image mounts not used** (they would avoid the extraction copy): Docker needs API ≥ 1.48 while Testcontainers defaults to 1.44; Podman's inspect output breaks docker-java's `Binds` parser | one extra disk copy per image digest | Extraction + bind (§9.3.6). Revisit when Podman/docker-java fix the inspect issue |
| L7 | ORAS artifacts (`oras push`) are **not supported**: disks are published as OCI images instead (§10.4). The ORAS Java SDK was prototyped and rejected (dependency weight) | — | Publish with §10.4 |
| R7 | Large HTTP downloads over slow links | long first-run time | cache; retries; per-request timeout option |
| R8 | Allure Java 2.x is the previous major (3.0.0 shipped 2026-09); its JUnit adapter declares JUnit 5.10.3 runtime deps that the 5.14 BOM overrides | a future 2.x drop would force the 3.x/JUnit 6 move | D10; versions live in §0 |
| R9 | `--single-file` Allure reports embed every attachment; a job with many WebM recordings can produce a report of tens of MB | slow to open | keep recordings short (≤ 12 s, 1 Mbps); the raw results are uploaded too |
| R10 | **Intermittent short recording** (spike run 18, image-mount boot): head 1 recorded 0.8 s of a 12 s window; the same check passed in the two other boots of that run and in all other runs; the recorder's own log was not captured | a truncated video would pass silently without the `VMTC_REC_DIED` rule | `vmtc-rec stop` reports a recorder that exited early (§7.2 step 0); `forceModeset()` waits for the new mode (§9.7; measured window 0.2 s, run 24); the spike harness now keeps `screen<i>.log`, so a recurrence is diagnosable; T8/T10 assert the duration |
| L8 | VNC is unauthenticated (D8) | anyone who can reach the published port can view and type into the VM | loopback bind by default; `withVncBindAddress` is an explicit opt-in |
| L9 | SSH host keys are accepted by default (D16) and the shipped guests carry well-known test passwords | a man-in-the-middle on the loopback forward is not detected; the guests must never be used outside tests | loopback publishing; `HostKeyPolicy.pin(...)` available; README labels the guests as fixtures |
| L10 | `exec` over SSH runs through the user's login shell and `timeout(1)`; no PTY | shell-init noise on stderr for exotic images; `timeout`/`env` must exist in the guest (Tier 1a, G8) | documented; `GuestExecResult.stderr` keeps the noise visible |
| R11 | *(closed for KVM 2026-10-19, spike run 36; TCG entry of the same run pending)* Recorder sidecar over a Testcontainers `Network` on rootless Podman 4.9.3: alias resolution, VNC reachability, 7.9 s recording, stop + validate + copy 249 ms (§3.9). Residual risk: a host whose Podman uses the CNI backend without the `dnsname` plugin resolves no aliases | recording tests T8–T10, T24, T31, T43 fail in M4 for network reasons on such a host | step 4.0 smoke-tests the sidecar by hand and records `podman info --format '{{.Host.NetworkBackend}}'`; fallback if the network path is unusable: publish the VNC ports on `127.0.0.1` and run the recorder with host networking (`--network host`), documented as a deviation |
| R12 | *(closed 2026-10-19, spike E)* Gradle Shadow 9.6.1 relocation of MINA SSHD + eddsa, publishing through `components.java` with the artifact swap, `Automatic-Module-Name`: all verified locally on Gradle 9.8.1; the slf4j-api leak was found and is excluded (§11.1) | — | the §11.1 build snippet is normative; U20 re-proves it in M1 |
| R13 | *(closed 2026-10-19, spike D)* `timeout -s KILL` would have killed `timeout` itself (exit-signal, no status) and `--foreground` leaves grandchildren alive; rule q now prescribes `timeout -k 1 <n>` with exit 124 / late exit-signal mapping (§9.4.2, §3.10) | — | T32 over SSH confirms in M1 |

---

## 16. Assumptions to confirm

1. §0 values are examples; replace them at adoption.
2. `${ASSERTJ_VERSION}` was not checked against release pages; the other versions in §0 were checked on 2026-10-18. Use the current versions when adopting.
3. Maximum screens = 4 and default hot-plug ports = 4 are design choices; only 2 screens and 4 ports were exercised (T6 exercises 4 screens).
4. `${ART_JUNIT}` is optional.
5. JUnit tags cannot be set dynamically through a meta-annotation, so `@Requirement` does not create JUnit tags; this is by design (REQUIREMENTS.md §3.1).
6. **The Maven publishing destination is undecided** (Maven Central / GitHub Packages / internal). The plan configures `maven-publish` for `publishToMavenLocal` only; PLAN-STEPS.md step 6.7 is a placeholder until the decision is made.
7. Consumers' black-box images are **prebuilt qcow2/raw disks with virtio drivers** (bootc-built images included). Installer ISOs, vmdk/vdi/vhdx, non-virtio buses, TPM and GUI guests are out of scope (D12, D13, D15).
8. `${SSHD_VERSION}`, `${EDDSA_VERSION}` and `${SHADOW_PLUGIN_VERSION}` were checked on 2026-10-19; MINA SSHD 3.0 (milestone) is out of scope until it ships a GA release. Shadow 9.6.1 requires Gradle 9 (§3.10); if the adopting repo must stay on Gradle 8, pin Shadow 8.3.x and re-verify U20.

**Open product decisions from the 2026-10-19 review** (each has a recommended default; the implementer follows the default unless this list says otherwise):

9. **SSH readiness fail-fast.** Today `SshAuthException` is thrown only when the deadline expires after a banner was seen (§9.2 step 10), so T41 (a) with `withGuestReadyTimeout(20 s)` would see `GuestNotReadyException` under TCG (banner at 64–93 s). Recommended: throw `SshAuthException` after **3 consecutive** post-banner authentication failures, without waiting for the deadline. **Pending user decision.**
10. **`copyToVm`/`copyFromVm` under `SshOptions.sudo(true)`.** `sudo -n mkdir -p` creates root-owned directories the SFTP user cannot write into (§9.5). Recommended: SFTP to a user-owned temp path, then `sudo -n sh -c 'mkdir -p "$1" && mv "$2" "$3" && chmod <mode> "$3"'`; for reads `sudo -n sh -c 'cp "$1" "$2" && chown <user> "$2"'` to a temp file, SFTP it, delete it. **Pending user decision.**
11. **T30 (c) timeout.** `withGuestReadyTimeout(1 s)` may leave no kernel line in the serial tail (SeaBIOS/GRUB only), so the assertion on `Linux version` or `login:` is marginal under KVM and false under TCG. Recommended: 5 s under KVM / 30 s under TCG (chosen by the test from the `kvm` property), asserting `Linux version`. **Pending user decision.**
12. **Leaked per-JVM networks.** With Ryuk off, a crashed JVM leaves its labelled network behind; `VmtcCleanup.removeOrphans()` removes only containers. Recommended: also remove networks labelled `${LABEL_PREFIX}.managed=true` from another session, older than 1 h and with no containers attached. **Pending user decision.**
13. **CI pull cost of `referenceGuest()`.** Every `start()` of a shipped-guest image first attempts a real pull from `${IMAGE_REGISTRY}` (denied in CI until images are pushed) before the local fallback (§9.3.3). Recommended: accept the ~1 s per start (it exercises REQ-OCI-07 on every run); alternative: CI passes `-D<prefix>.guestImage=<local tag>`. **Pending user decision.**


---

## 17. Evidence index

All on branch `claude/vm-testcontainer-plan-lqhfa6` of this repository (PR #9).

| Evidence | Location |
|----------|----------|
| Spike write-ups (packaging, audio, SELinux, GStreamer, multi-screen, CI matrices) | `vm-testcontainer/container/spike/RESULTS.md` |
| Reference scripts | `vm-testcontainer/container/spike/`: `start-vm.sh`, `make-seed.sh`, `build-guest.sh`, `gst-record.sh`, `rec.sh`, `vmctl.py`, `typekeys.py`, `fb-activate.py`, `install-packages.sh`, `repos/` |
| Feature checks | `vm-testcontainer/container/spike/ci_spikes.py` |
| Compat probe | `vm-testcontainer/spike-java/` (`PodmanKvmProbeTest`, `run-scenario.sh`; `PullAuthProbeTest`, `run-pull-auth.sh` for R13) |
| Download probes (R13) | `vm-testcontainer/spike-oras/` (`HttpDownloadTest` = candidate §9.3.5 algorithm; `OrasPullTest` = rejected ORAS SDK option; `run-oras-ci.sh`) |
| SSH transport spikes A/B/C/D/E (D15, D16, D20) | `vm-testcontainer/spike-ssh/` (`SshProbe` = candidate `SshTransport`; `SshMatrix` = auth matrix tool, also run from the shaded jar, `--signal-tests` = spike D; `GuestSshProbeTest` = Testcontainers + hostfwd probe incl. the `guestPort` probe forms; `SidecarRecorderProbeTest` = R11 probe; `shadow-reference.build.gradle` = the Gradle Shadow configuration §11.1 prescribes (spike E); `pom.xml` = the earlier maven-shade equivalent; `run-ssh-probe.sh`); `build-guest.sh` with `GUEST_VARIANT`/`SSH_PUBKEY_FILES`; `start-vm.sh` with `VM_PORT_FORWARDS` |
| Workflow | `.github/workflows/vm-testcontainer-spikes.yml` |
| Primary-source checks on 2026-10-18 (versions, jar signatures, package lists) | `javap` on testcontainers-2.0.5 (`doStart`/`tryStart` wrapping; `PullPolicy`), `allure-java-commons` 2.35.5 and 3.0.0 (annotations and `Allure` API), Maven Central metadata (Allure, JUnit, Gradle), `dnf repoquery -l edk2-ovmf` on `rockylinux:9`, `allure help generate` (2.46.1) |

| CI run | Showed |
|--------|--------|
| 1 (`39137816568`) | 24.04 KVM all pass; 24.04 TCG all pass except vCPU hot-plug; first compat probe |
| 2 (`39141980865`) | + 26.04 KVM all pass; TCG XFAIL vCPU; seed `DNF_RC` logging |
| 4 (`39142697864`) | login-session 0660 matrix on Podman 4.9.3 and 5.7.0; containerDisk (file path) on both |
| 5 (`39143866181`) | reference-guest pipeline + seedless boot, all checks pass (§10.3, T23); `/disk/` directory copy on Podman 4.9.3/5.7.0; chpasswd via QGA before console login |
| 10 (`39146302796`) | ORAS SDK prototype (rejected option) and HTTP downloader: all checks pass |
| 11 (`39146734647`) | Image mount: works through the Podman CLI (full VM boot); compat API rejects `ReadOnly` |
| 12 (`39147798578`) | Engine pull of a private image: 401 / Docker config / `podman login` fallback; image mount through Testcontainers on Podman blocked by the inspect `Binds` parse |
| 18 (`39169941120`) | **Flake observed:** `record_screens` on the image-mount boot recorded 0.8 s of 12 s on head 1 (passed in the two other boots of the run and in every other run); recorder log not captured → harness hardened (§15 R10) |
| 19 (`39170272915`) | UEFI boot of the customised guest (OVMF_CODE.fd + VARS copy): efivars present, all checks pass; under OVMF both heads start at 1280×800 |
| 24 (`39170999480`) | Hardened recorder harness green on all 7 jobs; the modeset of head 1 becomes visible to `screendump` 0.2 s after `fb-activate.py` returns (both BIOS boots); UEFI boot green again |
| 16 (`39167116353`) | Explicit `pullImageCmd().withAuthConfig()` pull with no stored credentials (S4) and the wrong-password exception (S5, `UnauthorizedException`); the probe JVM run through `sudo -iu` has the `kvm` group and sees `/dev/kvm` readable/writable in the 0660 scenarios while the CI-shell JVM does not (Podman 4.9.3 and 5.7.0) |
| 36 (`39256311022`) | **Recorder sidecar (R11), KVM:** VM on `Network.newNetwork()` with alias `vm-<uuid8>`, recorder container (same spike image, podman `--init`) resolved the alias (`10.89.0.2 vm-….dns.podman`), read `RFB 003.008` from `vm-<id>:5900`, recorded 7.9 s at 1280×800 via `rfbsrc` over the network; `stop` → process gone in 137 ms, validated + copied to the host in 249 ms (28 KB WebM). TCG entry pending at the time of writing |
| 34 (`39254459700`) | `guestPort` probe forms on both guests (HTTP probe 4–5 ms, no-probe timeout, no-listener EOF, §3.9); `python3 -m http.server` reached through a second forward (T42); three key types baked by the build and accepted for `vmtc` and `root`. The first sidecar probe (`SidecarRecorderProbeTest`) failed before testing anything (the spike image has no `tini`; fixed in f7457e8, see run 36) |
| 31 (`39193236335`) | **Spikes A and C**, KVM and TCG: slirp `hostfwd` published through Testcontainers; SSH readiness timings and the connect-before-banner behaviour; SFTP throughput; root and `vmtc` (3 key types, `sudo -n`, env prefix); `forceModeset` over SSH + sudo; the agent-less black-box guest build and boot; the shaded (maven-shade) jar against the live guests. All green. Details §3.9 and `RESULTS.md` "Run 31" |

**Sandbox-only evidence** (Docker 29.8, TCG):
- **Spike B:** MINA SSHD 2.20.0 vs OpenSSH 9.9 in a `rockylinux:9` container: auth matrix, Ed25519 needs `net.i2p.crypto:eddsa`, SFTP async stream failure vs synchronous handle API, relocated jar (`RESULTS.md` "Spike B");
- **Spike D:** coreutils `timeout` variants and `exit-signal` reporting over SSH, channel-close effect on children (`SshMatrix --signal-tests`, `RESULTS.md` "Spikes D and E"; §3.10);
- **Spike E:** Gradle 9.8.1 + Shadow 9.6.1 relocation, POM contents, `Automatic-Module-Name`, the `requires org.testcontainers` failure (`shadow-reference.build.gradle`, `RESULTS.md` "Spikes D and E"; §3.10);
- `tini` reaping (T24);
- dual QMP monitors and single TCP client;
- exclusive VNC client vs `force-shared`;
- `incremental=false`;
- `guest-info` `enabled:false`;
- guest-exec PATH;

---

## 18. In Summary

- **Design:** `QemuVmContainer` runs one QEMU VM per Podman container on a UBI 9.8 runner image; GStreamer recording runs in a sidecar recorder container on the same network (D17). QEMU and `gstreamer1-plugins-good` come from a pinned Rocky 9.8 fallback; `tini` comes from EPEL. Java drives QEMU through QMP over a loopback-published port and reaches the guest, **only if the test asks for it**, through SSH over a slirp port forward (shaded MINA SSHD, D16) or through QGA. Black-box images that offer nothing but a bootable disk are the primary use case (D15).
- **Verified on GitHub-hosted runners** with real KVM (Podman 4.9.3 and 5.7.0) and TCG:
  - exec and copy over SSH (root and sudo user, three key types) and over QGA; an agent-less, cloud-init-less guest driven entirely over SSH;
  - per-screen screenshots and VNC (exposed, unauthenticated, loopback by default);
  - per-screen GStreamer WebM recording;
  - WAV audio;
  - keyboard;
  - hot-plug of USB, PCIe disk/NIC, CD, memory, and vCPU (KVM only).
- **Images:** every source ends up as a cached, read-only base disk plus a per-start overlay. Sources: host paths, `http(s)://` (optional SHA-256, credentials, gz/xz/zst), and `oci://` containerDisk images. OCI images are pulled by the engine with engine or explicit credentials, then extracted. No ORAS SDK. Two pre-customized Rocky 9 guests (reference and black-box) are shipped as containerDisks; the pipeline is verified, and they are ready about 16–21 s after start under KVM over SSH (about 11 s over the agent).
- **Rules that matter** (§1 a–q): `tini`; `incremental=false`; validate recordings by file; `force-shared`; unwrap `ContainerLaunchException` in `start()`; guest RPC filter **and** SELinux permissive domain (agent access only); the keep-groups **annotation**, never `GroupAdd`; internal QMP socket; builder beats overrides; in-JVM lock beside the file lock; `PublishAllPorts=false`; default access `none()`; readiness waits for data, not connect; synchronous SFTP API; protocol-neutral public API; `timeout -k 1` around SSH execs with exit 124 / late exit-signal as the timeout signal.
- **Verification:** the requirement stories in REQUIREMENTS.md (see its BLUF for the count), each with evidence attached to a per-job Allure report; the build enforces that every test names the requirement it proves.
- **Open:**
  - an SELinux-host acceptance run (§15 R3);
  - the recorder sidecar on a Podman host with the CNI network backend (§15 R11 residual; the netavark path was verified in run 36). Gradle Shadow (R12) and the SSH exec timeout (R13) were closed by spikes E and D (§3.10).
- **Adoption:** edit **§0 only**.
- **Companion documents:** [REQUIREMENTS.md](REQUIREMENTS.md) (testable `REQ-*` stories by epic and feature; tests carry `@Requirement`) and [PLAN-STEPS.md](PLAN-STEPS.md) (the checklist to implement this plan).

---

## Appendix A: files to copy verbatim

### A.1 `images/runner/repos/epel.repo` [V]

```ini
[epel9]
name=EPEL 9
baseurl=https://dl.fedoraproject.org/pub/epel/9/Everything/x86_64/
gpgcheck=1
gpgkey=https://dl.fedoraproject.org/pub/epel/RPM-GPG-KEY-EPEL-9
priority=20
```

### A.2 `images/runner/repos/rocky.repo` [V] (IDs renamed; the release literal is `${ROCKY_RELEASE}`)

```ini
# Rocky Linux - FALLBACK only (UBI repos are priority 10, EPEL 20)
[rocky-fallback-baseos]
name=Rocky Linux 9.8 BaseOS (fallback)
baseurl=https://dl.rockylinux.org/pub/rocky/9.8/BaseOS/x86_64/os/
gpgcheck=1
gpgkey=https://dl.rockylinux.org/pub/rocky/RPM-GPG-KEY-Rocky-9
priority=90
excludepkgs=rocky-release* rocky-logos* rocky-gpg-keys* rocky-repos*

[rocky-fallback-appstream]
name=Rocky Linux 9.8 AppStream (fallback)
baseurl=https://dl.rockylinux.org/pub/rocky/9.8/AppStream/x86_64/os/
gpgcheck=1
gpgkey=https://dl.rockylinux.org/pub/rocky/RPM-GPG-KEY-Rocky-9
priority=90
excludepkgs=rocky-release* rocky-logos* rocky-gpg-keys* rocky-repos*

[rocky-fallback-crb]
name=Rocky Linux 9.8 CRB (fallback)
baseurl=https://dl.rockylinux.org/pub/rocky/9.8/CRB/x86_64/os/
gpgcheck=1
gpgkey=https://dl.rockylinux.org/pub/rocky/RPM-GPG-KEY-Rocky-9
priority=90
excludepkgs=rocky-release* rocky-logos* rocky-gpg-keys* rocky-repos*
```

### A.3 Keyboard mapping (QMP qcodes, US layout) [V]

| Character(s) | qcodes sent |
|--------------|-------------|
| `a`–`z` | the letter |
| `A`–`Z` | `shift` + lower-case letter |
| `0`–`9` | the digit |
| space | `spc` |
| `\n` | `ret` |
| `-` `=` `[` `]` `\` `;` `'` `,` `.` `/` `` ` `` | `minus` `equal` `bracket_left` `bracket_right` `backslash` `semicolon` `apostrophe` `comma` `dot` `slash` `grave_accent` |
| `!` `@` `#` `$` `%` `^` `&` `*` `(` `)` | `shift` + `1` `2` `3` `4` `5` `6` `7` `8` `9` `0` |
| `_` `+` `{` `}` `\|` `:` `"` `<` `>` `?` `~` | `shift` + `minus` `equal` `bracket_left` `bracket_right` `backslash` `semicolon` `apostrophe` `comma` `dot` `slash` `grave_accent` |

### A.4 `vmtc-fb-activate.py` (classpath resource; runs in the guest) [V]

```python
#!/usr/bin/env python3
"""Force a modeset on /dev/fbN (FBIOPUT_VSCREENINFO with FB_ACTIVATE_FORCE), then unblank it.
Lets a secondary bochs-drm head scan out without a desktop session. Usage: vmtc-fb-activate.py /dev/fbN"""
import fcntl, os, sys
FBIOGET_VSCREENINFO, FBIOPUT_VSCREENINFO, FBIOBLANK = 0x4600, 0x4601, 0x4611
fd = os.open(sys.argv[1], os.O_RDWR)
buf = bytearray(160)                                  # struct fb_var_screeninfo
fcntl.ioctl(fd, FBIOGET_VSCREENINFO, buf)
buf[84:88] = (0x80).to_bytes(4, "little")            # .activate = FB_ACTIVATE_FORCE
fcntl.ioctl(fd, FBIOPUT_VSCREENINFO, buf)
try:
    fcntl.ioctl(fd, FBIOBLANK, 0)                     # FB_BLANK_UNBLANK
except OSError as e:
    print("unblank:", e)
print("activated", sys.argv[1], int.from_bytes(buf[0:4], "little"), "x", int.from_bytes(buf[4:8], "little"))
```
