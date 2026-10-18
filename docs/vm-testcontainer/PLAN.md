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
| `${IMAGE_GUEST_NAME}` | `vmtc-guest-rocky9` | Reference guest containerDisk name (`${IMAGE_GUEST}` = `${IMAGE_REGISTRY}/${IMAGE_GUEST_NAME}`) | `gradle.properties` → `imageGuestName` |
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
| `${SLF4J_VERSION}` | `2.0.17` | slf4j-api / slf4j-simple (tests) **[V]** | `gradle/libs.versions.toml` |
| `${ASSERTJ_VERSION}` | `3.27.3` | AssertJ (tests; verify it is current when adopting) | `gradle/libs.versions.toml` |
| `${JACOCO_VERSION}` | — | *(not used; placeholder removed)* | — |
| `${UBI_IMAGE}` | `registry.access.redhat.com/ubi9/ubi:9.8` | Runner base image **[V]** | `gradle.properties` → `ubiImage` (passed as `--build-arg UBI_IMAGE`) |
| `${ROCKY_RELEASE}` | `9.8` | Rocky fallback repo release **[V]** | `images/runner/repos/rocky.repo` (literal in `baseurl`; this file is its only home) |
| `${GUEST_BASE_URL}` | `https://dl.rockylinux.org/pub/rocky/9/images/x86_64/Rocky-9-GenericCloud-Base-9.8-20260525.0.x86_64.qcow2` | Reference guest source image **[V]** | `images/guest/guest.properties` → `baseUrl` |
| `${GUEST_BASE_SHA256}` | `92c206cc6f790c61583247eefe87890f8828420662c17cacf247cec78ab4eec8` | SHA-256 of the image above **[V]** | `images/guest/guest.properties` → `baseSha256` |

**Fixed protocol strings.** These are **not** parameters; they are identical in every adoption:

- Container-log markers printed by `vmtc-entrypoint`: `VMTC_ACCEL kvm|tcg`, `VMTC_QEMU_STARTING <epoch>` (informational), `VMTC_READY`, `VMTC_ERROR <text>`.
- Recorder markers printed by `vmtc-rec`: `VMTC_REC_STARTED <pid>`, `VMTC_REC_OK duration=<s> width=<w> height=<h>`, `VMTC_REC_KILLED`, `VMTC_REC_INVALID`.
- Serial-log markers printed by the guest: `VMTC_SEED_DONE` (every `CloudInitSeed`), `VMTC_CUSTOMIZE_DONE` (reference-guest build only).
- In-image script names: `vmtc-entrypoint`, `vmtc-rec`.
- Container paths: `/vm/...`.
- Container ports: 4444, 4445, 5900+i.
- Container env vars: `VM_*`, `VNC_*`.

**Parameter-hygiene check (§12.3).** CI greps (fixed-string, whole-word) for the values of these parameters:

- `${GROUP_ID}`
- `${BASE_PACKAGE}`
- `${IMAGE_REGISTRY}`
- `${IMAGE_RUNNER_NAME}`
- `${IMAGE_GUEST_NAME}`
- `${LABEL_PREFIX}`

Rules (implemented by the `checkParameterHygiene` task, §11.5):

- `git grep --untracked -n -w -F -- <value>` for each value.
- Allowed locations for every value: `gradle.properties`, anything under a `build/` directory, `docs/vm-testcontainer/*.md` (this plan and its companions hold the example values by design) and `README.md` (user documentation shows real coordinates).
- `${GROUP_ID}` and `${BASE_PACKAGE}` are additionally allowed in `*.java` files: every `package`/`import`/`module` line contains them. The Java source tree is the second *Lives in* of `${BASE_PACKAGE}`.
- Any other hit fails the task, printing `file:line`.

Version numbers and short prefixes (`vmtc`, `VMTC_`) are excluded from the grep because they legitimately appear in fixed strings.

---

## 1. BLUF (Bottom Line Up Front)

**What it is:** a Testcontainers module, `${ART_LIB}`, whose `QemuVmContainer` runs **one QEMU VM per Podman container** on a UBI 9.8 runner image.

- **With `/dev/kvm`:** it uses KVM. On CI the guest agent was ready about 11–12 s after container start.
- **Without it:** it falls back to TCG (software emulation). The guest agent was ready in about 47–67 s, so TCG is slower but functional.

**Verified end to end on GitHub-hosted runners**, under KVM (Podman 4.9.3 and 5.7.0) and under TCG, with a seeded stock Rocky 9.8 guest. The pre-customized reference guest was also verified, booted with no seed under KVM:

| Capability | How |
|------------|-----|
| Run commands, copy files | QEMU guest agent (QGA) |
| Screenshots, keyboard, hot-plug | QEMU monitor protocol (QMP) |
| One VNC server per screen | exposed to library users; unauthenticated, bound to loopback by default (D8) |
| Screen recording | one WebM (VP8) per screen, recorded from that screen's VNC by GStreamer `rfbsrc` |
| Audio | separate WAV via QEMU `wavcapture` |
| Hot-plug | USB storage/HID, PCIe disk, PCIe NIC, CD media change, memory; vCPU under KVM only |

**Packaging:**
- UBI has **no QEMU at all**. QEMU and `gstreamer1-plugins-good` come from a pinned **Rocky 9.8** fallback repo.
- `tini` comes from **EPEL**.
- Everything else comes from UBI.

**VM images:** every source becomes **a read-only base disk file in a host cache**. It is bind-mounted into the container, and a throwaway qcow2 overlay is written per start.

| Source | API |
|--------|-----|
| Host path | `VmImage.fromPath` |
| `http(s)://` URL: optional SHA-256, optional credentials, `.gz`/`.xz`/`.zst` decompressed automatically | `VmImage.fromUrl` |
| `oci://` containerDisk image: **pulled by the engine** (engine or explicit credentials), `/disk/` extracted through the Docker API | `VmImage.fromOci` / `fromContainerDisk` **[V]** |
| Disk baked into a derived runner image | `VmImage.baked()` |

A pre-customized Rocky 9 reference guest is shipped as a containerDisk (§10.3).

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
| R3 | Run commands in the VM | §9.4 | REQ-EXEC-*, REQ-LIFE-02 |
| R4 | Copy files to and from the VM | §9.5 | REQ-FILE-* |
| R5 | Run QEMU images (qcow2, raw) | §8, §9.3 | REQ-DISK-01, REQ-IMG-04 |
| R6 | Screenshots, per screen | §9.7 | REQ-DISP-*, REQ-KEY-* |
| R7 | Audio recording, **separate WAV** files | §9.9 | REQ-AUD-* |
| R8 | Screen recording: **GStreamer records each screen's VNC; one video per screen** | §7.2, §9.8 | REQ-REC-* |
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
| D6 | Runner variants: **slim** (no GStreamer) and **media** (GStreamer) | §6.1 | REQ-IMG-03, REQ-REC-04 |
| D7 | SELinux guests: `semanage permissive -a virt_qemu_ga_t` | §10.1 | REQ-GUEST-02 |
| D8 | **No VNC authentication.** Test VMs are short-lived on an isolated network; VNC binds to loopback by default. `withVncPassword` and the per-display `set_password` step are dropped | §8.3 | REQ-VNC-03/04, REQ-REC-05 retired |
| D9 | **Precedence:** builder call > system property > environment variable > default. Overrides change defaults only | §9.1 | REQ-CFG-01 |
| D10 | **Test stack:** JUnit 5.14.x + Allure Java 2.35.5 (not Allure Java 3 / JUnit 6) | §0, §11.4 | REQ-BLD-07 |
| D11 | **Reporting:** Allure report per CI job as a downloadable artifact (single-file HTML); no hosted report | §11.4, §12 | REQ-BLD-07 |

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
| UBI provides `gstreamer1`, `-plugins-base` and `-plugins-bad-free` (`rfbsrc`). **Rocky only:** `gstreamer1-plugins-good` (`vp8enc`, `webmmux`) and `-plugins-base-tools` (`gst-discoverer-1.0`). All 1.22.12 | [V] |
| No H.264/AV1 encoder in the repo set (`x264enc`, `openh264enc`, `av1enc` absent) | [V] |
| EPEL 9 `tini-0.19.0-5.el9` installs `/usr/bin/tini` | [V] |
| `procps-ng` is in UBI baseos; there is **no** `nc` in the image | [V] |
| Rootless Podman image build on a GitHub runner took about 51 s; the media variant is 734 MB | [V] |

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

### 3.8 Image downloads (R13)

| Fact | Status |
|------|--------|
| **HTTP(S)** (JDK `HttpClient`, in-process servers): Basic and Bearer auth work. Missing credentials → 401 → clear exception. A SHA-256 mismatch is rejected and leaves no file. `.gz`, `.xz` and `.zst` decompress to the original bytes, with the checksum verified on the bytes as downloaded. On a redirect to another origin, the target receives **no** `Authorization` header (manual redirect handling). Same-origin redirects keep auth | [V] (local + CI runs #10–#12) |
| The JDK's own `Redirect.NORMAL` also does not forward a user-set `Authorization` header to another origin | [V] |
| JDK `HttpClient`: `HttpRequest.timeout` bounds only the wait for response headers (`HttpTimeoutException`); a body stall is not detected. Closing the `ofInputStream()` body from another thread makes the blocked `read()` throw `IOException: closed` immediately | [V] (local spike, `HttpDownloadTest.readStallWatchdogUnblocksRead`) |
| **Engine pull of a private OCI image** through Testcontainers (`RemoteDockerImage`), against an htpasswd TLS registry, rootless Podman 4.9.3: <ul><li>no credentials → `401 unauthorized`</li><li>credentials in `~/.docker/config.json` → pulled (Testcontainers' `RegistryAuthLocator` sends them)</li><li>**only `podman login`** (`$XDG_RUNTIME_DIR/containers/auth.json`) → pulled (Podman falls back to its own credentials when the client sends none)</li></ul> | [V] (run #12) |
| **Image mounts** (`MountType.IMAGE`, docker-java 3.7.1): <ul><li>Docker 29.8: works only with API ≥ 1.48 (Testcontainers 2.0.5 defaults to 1.44; `-Dapi.version=1.48` fixes it). Read-only without asking; byte-exact</li><li>Podman 4.9.3 / 5.7.0 via the compat API: `ReadOnly=true` → `500 ro: invalid mount option`. Without `ReadOnly` Podman creates the container, but **Testcontainers fails parsing the inspect response** (`HostConfig.Binds` contains a raw overlay string, and docker-java's `Binds` parser throws)</li><li>`podman run --mount type=image` (CLI) + full VM boot: all 15 applicable checks pass (seed check skipped)</li></ul> **Therefore not used (§15 L6)** | [V] (runs #11–#12) |
| `land.oras:oras-java-sdk` 0.8.5 pulls ORAS artifacts correctly (TLS + custom CA, credentials, `REGISTRY_AUTH_FILE`, podman `auth.json`, layer selection by title, tag vs digest). **Rejected:** about 10 runtime dependencies (Jackson 3.2.3, native zstd-jni, Bouncy Castle, Micrometer, …), and not needed once disks are published as OCI images | [V] (run #10; evidence only) |

---

## 4. Architecture

```
┌────────────────────────────── Host (x86-64 Linux, Podman) ─────────────────────────────────┐
│ Test (JUnit 5) ──► QemuVmContainer (${BASE_PACKAGE})                                        │
│                     │ QmpClient ─────────── TCP 127.0.0.1:<mapped 4444> ─┐                  │
│                     │ GuestAgentClient ──── TCP 127.0.0.1:<mapped 4445> ─┤                  │
│ library users' VNC viewers ───────────────── TCP <bind>:<mapped 5900+i> ─┤                  │
│                     │ execInContainer(vmtc-rec …), copyFile{To,From}Container                │
│ ~/.cache/${CACHE_DIR_NAME}/images/<key>.img ── ro bind ──┐               │                  │
│ /dev/kvm (optional) ─────────────────────── --device ──┐ │               │                  │
│                                                        ▼ ▼               ▼                  │
│ ┌──── container ${IMAGE_RUNNER}:${VERSION}-${IMAGE_TAG_SUFFIX}[-media] ─────────────────┐  │
│ │ PID 1 tini ─► vmtc-entrypoint ─► /usr/libexec/qemu-kvm -M q35 …  (child)               │  │
│ │  /vm/base/disk.img (ro) /vm/run/overlay.qcow2 /vm/out/{serial.log,*.png,*.wav,*.webm}  │  │
│ │  QMP tcp :4444 (Java)   QMP unix /vm/run/qmp-internal.sock (entrypoint only)           │  │
│ │  QGA chardev :4445 ◄─ virtio-serial ─► guest qemu-ga                                   │  │
│ │  VNC :5900+i per screen (share=force-shared[,password=on])                             │  │
│ │  vmtc-rec ─► gst-launch rfbsrc ► vp8enc ► webmmux        (media variant only)          │  │
│ └────────────────────────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

Principles:

1. **One container = one VM.**
2. **Java speaks QMP and QGA directly over TCP** (newline-delimited JSON).
   - `execInContainer` is used only for in-image helpers (`vmtc-rec`, `qemu-img`, `tail`).
   - Files are moved with `copyFileToContainer` / `copyFileFromContainer`.
3. **The base disk is never written.** Each start creates a qcow2 overlay.
4. **Published host ports bind to `127.0.0.1`.** Only VNC's bind address is configurable.

---

## 5. Repository and module layout (Gradle)

```
${ROOT_PROJECT}/
├─ settings.gradle               rootProject.name='${ROOT_PROJECT}'; include 'lib','junit5','test-support','images','it'
├─ gradle.properties             group, version, artifactLib, artifactJunit, basePackage, imageRegistry,
│                                imageRunnerName, imageGuestName, imageTagSuffix, labelPrefix, syspropPrefix,
│                                envPrefix, cacheDirName, javaRelease, ubiImage, reqDocUrl,
│                                traceabilityRequireFullCoverage                         (§0, §11.5)
├─ gradle/libs.versions.toml     testcontainers, junit-bom, junit-platform-launcher, jackson-databind, commons-compress,
│                                xz, zstd-jni, slf4j, assertj, allure-bom, allure-commandline
├─ gradle/wrapper/…              ${GRADLE_VERSION}
├─ build.gradle                  root tasks: checkParameterHygiene, checkRequirementTraceability, checkDependencies,
│                                allureReport (§11.4, §11.5)
├─ lib/        (${ART_LIB})      java-library + maven-publish + generateConstants (§11.3)
│  ├─ src/main/java/module-info.java
│  ├─ src/main/java/<${BASE_PACKAGE} as path>/…          (§9.1)
│  ├─ src/main/resources/<${BASE_PACKAGE} as path>/display/vmtc-fb-activate.py   (Appendix A.4)
│  └─ src/test/java/…            unit tests U1–U17 (no container engine)
├─ junit5/     (${ART_JUNIT})    @VmTest extension (§9.13); src/test/java holds T34
├─ test-support/                 NOT published. @Requirement, RequirementExtension, RequirementsCatalog,
│  │                             RequirementsCheck (main), Evidence (§11.4); resources get a copy of
│  └─ src/main/…                 docs/vm-testcontainer/REQUIREMENTS.md at build time
├─ images/
│  ├─ runner/  Containerfile (§6.3), repos/epel.repo, repos/rocky.repo (A.1, A.2),
│  │           scripts/vmtc-entrypoint (§7.1), scripts/vmtc-rec (§7.2)
│  ├─ guest/   guest.properties, customize.sh (§10.3)
│  └─ build.gradle   buildRunnerSlim, buildRunnerMedia, verifyRunnerImages, downloadGuestBase, buildGuestDisk,
│                    pushImages (§11.2)
├─ it/                           integration tests against real Podman (§13); not published
│  ├─ src/test/java/…            VmIT base class, VmEvidence, TestImages, T1–T37 (incl. KvmCompatIT, OciAuthIT)
│  └─ fixtures/                  test-registry.sh (T27/T28), kvm-scenario.sh (T35)
├─ docs/vm-testcontainer/        PLAN.md, REQUIREMENTS.md, PLAN-STEPS.md (copied from this plan at adoption)
└─ README.md                     user documentation (PLAN-STEPS.md step 6.6)
```

---

## 6. Runner container image

### 6.1 Variants and tags

| Variant | Tag | Contents |
|---------|-----|----------|
| slim | `${IMAGE_RUNNER}:${VERSION}-${IMAGE_TAG_SUFFIX}` | QEMU, qemu-img, firmware, tini, xorriso, python3, jq, socat |
| media | `${IMAGE_RUNNER}:${VERSION}-${IMAGE_TAG_SUFFIX}-media` | slim + GStreamer (`base`, `base-tools`, `good`, `bad-free`) |

- Screen recording needs **media**. On slim, `VmScreen.startRecording(...)` throws `UnsupportedOperationException("screen recording requires the media runner image")`.
- The variant is read from the image label `${LABEL_PREFIX}.variant` (§6.4 check 6).

### 6.2 Repositories (R11)

| File (in `images/runner/repos/`) | Repo IDs | Priority | Notes |
|----------------------------------|----------|----------|-------|
| UBI's own `/etc/yum.repos.d/ubi.repo` | `ubi-9-*` | **10** (sed in §6.3) | — |
| `epel.repo` (Appendix A.1) | `epel9` | 20 | gpgcheck=1 |
| `rocky.repo` (Appendix A.2) | `rocky-fallback-baseos`, `rocky-fallback-appstream`, `rocky-fallback-crb` | 90 | gpgcheck=1. `excludepkgs=rocky-release* rocky-logos* rocky-gpg-keys* rocky-repos*`. The `baseurl` contains the literal `${ROCKY_RELEASE}` value; this file is that value's only home |

When Rocky moves `${ROCKY_RELEASE}` to `vault.rockylinux.org`, change only the host in `rocky.repo`. As of 2026-10-18, `pub/rocky/9.8` is live and `vault/rocky/9.8` returns 404 **[V]**.

### 6.3 Containerfile (normative; `images/runner/Containerfile`) **[U]** until the first `verifyRunnerImages` run (the spike built an equivalent `Containerfile.spike` **[V]**)

```dockerfile
ARG UBI_IMAGE
FROM ${UBI_IMAGE}
ARG VARIANT=slim
ARG VERSION
ARG LABEL_PREFIX
ARG IMAGE_TITLE
LABEL org.opencontainers.image.title="${IMAGE_TITLE}" \
      org.opencontainers.image.version="${VERSION}" \
      ${LABEL_PREFIX}.variant="${VARIANT}"
COPY repos/ /etc/yum.repos.d/
RUN sed -i '/^\[/a priority=10' /etc/yum.repos.d/ubi.repo \
 && rpm --import https://dl.rockylinux.org/pub/rocky/RPM-GPG-KEY-Rocky-9 \
 && rpm --import https://dl.fedoraproject.org/pub/epel/RPM-GPG-KEY-EPEL-9 \
 && PKGS="qemu-kvm-core qemu-img edk2-ovmf seabios-bin tini xorriso jq socat python3" \
 && if [ "$VARIANT" = media ]; then PKGS="$PKGS gstreamer1 gstreamer1-plugins-base gstreamer1-plugins-base-tools gstreamer1-plugins-good gstreamer1-plugins-bad-free"; fi \
 && dnf -y --setopt=install_weak_deps=False install $PKGS \
 && dnf clean all \
 && rpm -q redhat-release && ! rpm -qa 'rocky-*' | grep -q . \
 && mkdir -p /vm/base /vm/run /vm/out /vm/seed
COPY scripts/ /usr/local/bin/
RUN chmod 0755 /usr/local/bin/vmtc-entrypoint /usr/local/bin/vmtc-rec
EXPOSE 4444 4445 5900
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/vmtc-entrypoint"]
```

Notes:

- All `ARG`s are passed by Gradle (§11.2): `UBI_IMAGE`=`${UBI_IMAGE}`, `LABEL_PREFIX`=`${LABEL_PREFIX}`, `IMAGE_TITLE`=`${IMAGE_RUNNER_NAME}`.
  - `rpm --import` and `dnf install` were verified with the same repos via `install-packages.sh` **[V]**.
  - The `LABEL` key containing an ARG is checked by §6.4 check 6.
- The `rpm -q redhat-release` line is a guard: the build fails if a Rocky release package sneaks in.
- `tini` without `-g`: SIGTERM reaches `vmtc-entrypoint`, which shuts QEMU down through the internal QMP socket (§7.1 step 10).
- The container runs as root inside. Under rootless Podman, that is the invoking host user.
- `EXPOSE` is informational only: the library publishes exactly the ports it needs with `PublishAllPorts=false` (§9.2 step 4).

### 6.4 Image verification (`verifyRunnerImages`)

Run each check inside every built image (`<engine> run --rm --entrypoint /bin/bash <image> -c '<check>'`) unless marked *host*; all must succeed. The REQUIREMENTS.md rows in brackets are what each check proves.

1. `/usr/libexec/qemu-kvm -accel help | grep -qx tcg` [REQ-IMG-04]
2. `/usr/libexec/qemu-kvm -device help` lists every one of: `VGA`, `secondary-vga`, `ich9-intel-hda`, `hda-duplex`, `virtio-serial-pci`, `virtserialport`, `qemu-xhci`, `usb-storage`, `usb-kbd`, `usb-tablet`, `usb-mouse`, `pcie-root-port`, `virtio-blk-pci`, `virtio-net-pci`, `virtio-scsi-pci`, `scsi-cd`, `pc-dimm` [REQ-IMG-04]
3. **media only:** `gst-inspect-1.0 <e>` succeeds for `rfbsrc`, `vp8enc`, `webmmux`, `videorate`, `videoconvert` [REQ-IMG-03]
4. `rpm -q redhat-release` succeeds, and `rpm -qa 'rocky-*'` prints nothing [REQ-IMG-01]
5. *host:* `<engine> image inspect --format '{{index .Config.Entrypoint 0}}' <image>` prints `/usr/bin/tini` [REQ-IMG-05]
6. *host:* `<engine> image inspect --format '{{index .Labels "<labelPrefix>.variant"}}' <image>` equals the variant [REQ-IMG-03]
7. Repository configuration [REQ-IMG-02]: `grep -h '^priority=' /etc/yum.repos.d/ubi.repo | sort -u` prints only `priority=10`; `/etc/yum.repos.d/epel.repo` contains `priority=20`; every section of `/etc/yum.repos.d/rocky.repo` has `priority=90` and the `excludepkgs` line of Appendix A.2; every `.repo` file the build added has `gpgcheck=1`.
8. **slim only:** `! command -v gst-launch-1.0` [REQ-IMG-03]
9. `. /etc/os-release && [ "$ID" = rhel ] && [ "$VERSION_ID" = <tag of ${UBI_IMAGE}, e.g. 9.8> ]` [REQ-IMG-01]
10. Resolution order [REQ-IMG-02]: `dnf -q repoquery --installed --qf '%{from_repo}' <pkg>` prints `rocky-fallback-appstream` for `qemu-kvm-core`, `epel9` for `tini`, and a repo id starting with `ubi-9-` for `jq`. Only packages installed by the Containerfile's own `dnf install` are meaningful here: for packages that came with the base image layer (e.g. `bash`) dnf 4.14 prints an opaque transaction id instead of a repo **[V]** (checked on `rockylinux:9`, dnf 4.14.0).

---

## 7. In-container scripts

### 7.1 `vmtc-entrypoint` (bash, `set -euo pipefail`)

**Inputs:** environment variables. Java always sets every one of them; the script **also** applies the defaults below with `${VAR:-default}` so it can be run by hand (PLAN-STEPS.md step 1.8).

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

### 7.2 `vmtc-rec` (bash; media image only)

```
vmtc-rec start  <name> <screen-index> <fps> <bitrate-bps>
vmtc-rec stop   <name> [timeout-seconds=10]
vmtc-rec status <name>
```

**`start`:**

1. Run detached (`setsid … < /dev/null > /vm/out/<name>.log 2>&1 &`):

   ```
   gst-launch-1.0 -q -e rfbsrc host=127.0.0.1 port=$((5900+i)) view-only=true shared=true incremental=false \
     ! videorate ! video/x-raw,framerate=<fps>/1 ! videoconvert \
     ! vp8enc deadline=1 cpu-used=8 threads=2 target-bitrate=<bitrate> ! webmmux ! filesink location=/vm/out/<name>.webm
   ```

2. Write the PID to `/vm/run/<name>.pid` and print `VMTC_REC_STARTED <pid>`. If `gst-launch-1.0` is missing or exits within 1 s, print `VMTC_REC_INVALID` and the log instead (Java maps that to `RecordingException`, §9.8).

This is the verified `gst-record.sh` + `rec.sh` **[V]** (the spike also verified `rfbsrc password=…`; passwords were dropped by D8).

**`stop`:**

0. If the PID from `/vm/run/<name>.pid` is already gone (or a zombie) **before** the signal is sent, the recorder died on its own: print `VMTC_REC_DIED` followed by the last 20 lines of `/vm/out/<name>.log` and stop here (Java maps it to `RecordingException`, §9.8). A silently truncated file is never reported as success.
1. `kill -INT <pid>`.
2. Poll every 100 ms. The process counts as alive only if `/proc/<pid>` exists **and** its `State:` is not `Z`. Give up after the timeout.
3. If it is still alive: `kill -9` it and print `VMTC_REC_KILLED`.
4. Validate with `gst-discoverer-1.0 /vm/out/<name>.webm`. Its output contains lines `Duration: H:MM:SS.nnnnnnnnn`, `Width: <w>`, `Height: <h>` **[V]**.
   - On success, print `VMTC_REC_OK duration=<seconds> width=<w> height=<h>`, where `<seconds>` is the duration converted to **decimal seconds with 3 fractional digits** (e.g. `duration=12.346`).
   - Otherwise print `VMTC_REC_INVALID` followed by the last 20 log lines.
5. **Ignore gst-launch's exit code** (it is 1 even on success) **[V]**.

**`status`:** prints `running` or `stopped`.

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
  -netdev user,id=net0 -device virtio-net-pci,netdev=net0
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
| UEFI | not exercised | §8.4 [U] |

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

---

## 9. Java library design

### 9.1 Packages, types and runtime overrides

| Package | Types |
|---------|-------|
| `${BASE_PACKAGE}` | `QemuVmContainer`, `VmImage` (sealed interface; nested records `PathImage(Path file)`, `UrlImage(URI url, UrlOptions options)`, `OciImage(DockerImageName image, OciOptions options)`, `BakedImage()`), `KvmMode`, `Accelerator`, `RunnerVariant`, `Firmware`, `ExecRequest` (record: `List<String> argv`, `Map<String,String> env`, `byte[] stdin`, `Duration timeout`; static `of(String... argv)` + `with*` copies), `GuestExecResult` (record, §9.4), `Warnings` (§9.11), `VmtcConstants` (generated), `VmtcCleanup`, `VmtcException` and its subclasses `KvmUnavailableException`, `GuestAgentTimeoutException`, `GuestCommandDisabledException`, `GuestAgentConfinedException`, `GuestExecTimeoutException`, `RecordingException`, `HotplugTimeoutException`, `ImageChecksumException`, `ImageAuthException`, `ImageDownloadException` |
| `${BASE_PACKAGE}.qmp` | `QmpClient`, `QmpEvent`, `QmpException` (extends `VmtcException`) |
| `${BASE_PACKAGE}.qga` | `GuestAgentClient` |
| `${BASE_PACKAGE}.image` | `VmImageCache`, `ContainerDiskExtractor`, `ImageDownloader`, `UrlOptions`, `OciOptions`, `HttpCredentials`, `RegistryCredentials`, `Compression` (`AUTO`, `NONE`, `GZIP`, `XZ`, `ZSTD`), `Secret`, `Qcow2Header`, `SparseCopy` |
| `${BASE_PACKAGE}.display` | `VmScreen`, `VncEndpoint`, `ScreenRecording`, `RecordingOptions`, `KeyboardInput` |
| `${BASE_PACKAGE}.audio` | `AudioRecording` |
| `${BASE_PACKAGE}.hotplug` | `Devices`, `DeviceSpec` (sealed interface; nested records `UsbStorage(Path image, boolean readOnly)`, `UsbHid(HidKind kind)` with enum `HidKind {KEYBOARD, TABLET, MOUSE}`, `VirtioDisk(Path image, boolean readOnly)`, `BlankDisk(String size, DiskBus bus)`, `Nic(String mac)` (`mac` nullable), `Cpu()`, `Memory(int sizeMiB)`), `DiskBus` (`USB`, `VIRTIO`), `HotplugHandle`, `CdromDrive` |
| `${BASE_PACKAGE}.cloudinit` | `CloudInitSeed` |
| `${BASE_PACKAGE}.internal` | `KvmDetector`, `KvmDecision`, `Overrides`, `StartupMessages`, `VmWaitStrategy`, `SerialLogTailer`, `PortBindings`, `Json`, `Backoff`. Not exported: `module-info` exports every package above except `internal` |

Every exception class declares `private static final long serialVersionUID = 1L;` (the build uses `-Xlint:all -Werror`, §11.1).

**Dependencies:**

| Scope | Dependencies |
|-------|--------------|
| `api` | `org.testcontainers:testcontainers:${TESTCONTAINERS_VERSION}` |
| `implementation` | `jackson-databind:${JACKSON_VERSION}`, `commons-compress:${COMMONS_COMPRESS_VERSION}`, `org.tukaani:xz:${XZ_VERSION}`, `com.github.luben:zstd-jni:${ZSTD_JNI_VERSION}` |
| `testImplementation` | JUnit BOM `${JUNIT_VERSION}` + `junit-jupiter`, AssertJ, `slf4j-simple:${SLF4J_VERSION}`, `:test-support` |
| `testRuntimeOnly` | `org.junit.platform:junit-platform-launcher` (Gradle 9 no longer adds it [D]) |

`checkDependencies` (§11.5) fails the build when `:lib`'s `api`/`implementation` sets differ from the two rows above (REQ-BLD-03, REQ-OCI-06).

**Runtime overrides.** Each key exists as system property `-D${SYSPROP_PREFIX}.<key>` and as environment variable `${ENV_PREFIX}<KEY_IN_UPPER_SNAKE>`, for example `-Dvmtc.runnerImageMedia` or `VMTC_RUNNER_IMAGE_MEDIA`.

**Precedence (D9):** **builder call > system property > environment variable > default.** An override changes the default a container starts from; a value the code sets explicitly always wins. An unparsable override value (for example `kvm=maybe`, `tcgTimeoutFactor=0`, `cleanup=yes`) throws `IllegalArgumentException("<key>: …")` at `start()`.

| Key | Values | Default | Effect |
|-----|--------|---------|--------|
| `kvm` | `REQUIRE`, `PREFER`, `DISABLE` (case-insensitive) | `PREFER` | Default for `withKvm` |
| `cacheDir` | absolute path | `${XDG_CACHE_HOME:-~/.cache}/${CACHE_DIR_NAME}` | Image cache root |
| `cleanup` | `true`, `false` | `true` | Orphan cleanup (§9.12) |
| `runnerImage` | image ref | `VmtcConstants.defaultRunnerImage(SLIM)` | Slim runner for the no-arg and `RunnerVariant` constructors |
| `runnerImageMedia` | image ref | `VmtcConstants.defaultRunnerImage(MEDIA)` | Media runner, likewise. Neither applies to the `DockerImageName` constructor |
| `guestImage` | image ref | `VmtcConstants.referenceGuestImage()` | Used by `VmImage.referenceGuest()` |
| `tcgTimeoutFactor` | double > 0 | `6.0` | Default for `withTcgTimeoutFactor` |

### 9.2 `QemuVmContainer` (public API, normative)

```java
public class QemuVmContainer extends GenericContainer<QemuVmContainer> {
  public QemuVmContainer();                                     // RunnerVariant.SLIM
  public QemuVmContainer(RunnerVariant variant);                // SLIM | MEDIA (image per §9.1 overrides)
  public QemuVmContainer(DockerImageName runnerImage);          // custom/derived runner; variant read from the image label

  // configuration (each returns this; ranges are validated in the call → IllegalArgumentException)
  withVmImage(VmImage image)                    // REQUIRED. For derived runner images pass VmImage.baked()
  withKvm(KvmMode mode)                         // default from override 'kvm', else PREFER
  withCpus(int boot) / withMaxCpus(int max)     // defaults 2 / max(4, boot); boot ≥ 1, max ≥ boot
  withMemoryMb(int mb) / withMemoryHotplug(int slots, String maxmem)   // 2048 / 4, "8G"; slots ≥ 0
  withScreens(int n)                            // 1..4, default 1
  withVncBindAddress(String hostIp)             // default "127.0.0.1"
  withHotplugPorts(int n)                       // 0..8, default 4
  withFirmware(Firmware f)                      // BIOS (default) | UEFI
  withCloudInit(CloudInitSeed seed)             // optional (§10.2)
  withGuestAgentTimeout(Duration d)             // default 180 s (scaled under TCG); an explicit value is never scaled
  withExecTimeout(Duration d)                   // default 60 s (scaled under TCG); explicit never scaled
  withTcgTimeoutFactor(double f)                // default from override, else 6.0; > 0
  withSerialLogConsumer(Consumer<String> line)  // receives every serial-log line (step 13)
  withQemuArgs(String... extra)                 // → VM_EXTRA_ARGS; any arg with whitespace → IllegalArgumentException

  // state
  Accelerator accelerator();                    // KVM | TCG, from VMTC_ACCEL (the source of truth after start)
  RunnerVariant variant();                      // SLIM | MEDIA
  QmpClient qmp();
  GuestAgentClient guestAgent();
  KeyboardInput keyboard();
  Map<String,Object> describe();                // the vm-config evidence of REQUIREMENTS.md §3.3 (Jackson-serialisable)
  Map<String,Long> timings();                   // milliseconds from start(): containerRunning, vmtcReady, qgaReady, started

  // R3 / R4
  GuestExecResult exec(String... argv);                 // absolute argv[0] recommended
  GuestExecResult exec(Duration timeout, String... argv);
  GuestExecResult exec(ExecRequest request);            // argv, env, stdin bytes, timeout
  GuestExecResult execShell(String script);             // /bin/sh -c script
  void copyToVm(Path local, String guestPath);          // mkdir -p parent first (guest-exec)
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

**`start()` override (rule e, REQ-ERR-03).** `start()` calls `super.start()` inside `try`; on `ContainerLaunchException` it walks `getCause()` and rethrows the **first** `VmtcException` found (preserving it as-is); if none is found it throws `new VmtcException(e.getMessage(), e)`. Testcontainers wraps every start-up failure **[V]**, so without this no caller could catch `KvmUnavailableException` and friends.

**Start-time validation** (before anything is created; all `IllegalArgumentException`): `withVmImage` was called; `VM_MAXMEM` ≥ `withMemoryMb` (parse `G`/`M` suffixes); override values parse (§9.1).

**Runner variant.** For the `RunnerVariant` constructors it is the argument. For `QemuVmContainer(DockerImageName)` the library reads the image label `${LABEL_PREFIX}.variant` with `inspectImageCmd` after the image is pulled (in `configure()`); a missing or unknown label means `SLIM` plus one WARN.

**Start sequence** (`configure()`, `containerIsStarting`, `containerIsStarted`):

1. **Image:** resolve the `VmImage` to a cache file (§9.3), unless it is `BakedImage`.
2. **Bind** the cache file read-only at `/vm/base/disk.img` with `addFileSystemBind(hostPath, "/vm/base/disk.img", BindMode.READ_ONLY, selinux)` (the four-argument form is `addFileSystemBind`, not `withFileSystemBind`, in 2.0.5 **[V]** javap), where `selinux` is `SelinuxContext.SHARED` when `/sys/fs/selinux/enforce` exists and contains `1`, else `SelinuxContext.NONE`. Only cache files are ever bound (§9.3.2).
3. **KVM:** `KvmDecision.decide(mode, detector)` (§9.6); apply `HostConfig.withDevices(...)` and, if needed, `withAnnotations(...)` in `withCreateContainerCmdModifier`.
4. **Ports:** `withExposedPorts(4444, 4445, 5900, …, 5900+n-1)`. In the create-cmd modifier: `hostConfig.withPublishAllPorts(false)` (rule l) and replace `PortBindings` with `PortBinding(Ports.Binding.bindIp(addr), ExposedPort.tcp(p))` for each port, which keeps a random host port. `addr` is `127.0.0.1` for 4444 and 4445, and the configured VNC address for VNC ports.
5. **Labels:** `${LABEL_PREFIX}.managed=true`, `${LABEL_PREFIX}.version=<VmtcConstants.VERSION>`, `${LABEL_PREFIX}.session=<UUID created once per JVM>`, `${LABEL_PREFIX}.created=<epoch seconds>`.
6. **Env:** set §7.1 env. For a seed, add `withCopyToContainer(Transferable.of(seed.userData()), "/vm/seed/user-data")` and the same for `meta-data`.
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
10. **QGA readiness.**
    - **Seeded start first waits for the seed:** if a seed is configured, start the `SerialLogTailer` (step 13) now and block until a line contains `VMTC_SEED_DONE` (guest-agent timeout; on timeout throw `GuestAgentTimeoutException`). Only then continue. Reason: the seed relaxes the RPC filter and restarts the agent, so steps 11–12 would otherwise see the stock filter and fail. The spike harness waited for seed completion before its exec checks in the same way **[V]**.
    - Then repeat until the guest-agent timeout. Each attempt:
    1. TCP connect to the mapped port 4445 with a 3 s socket timeout.
    2. `guest-sync-delimited {id}`: skip a leading `0xFF` byte and read until `return == id`.
    3. `guest-ping`.

    If any step fails or times out: close the socket, sleep 1 s, and retry.

    On overall timeout, throw `GuestAgentTimeoutException(... + "\n" + StartupMessages.tail(serialLines, 50))`. This is the verified `vmctl.py wait-qga` behaviour [V].
11. **RPC filter check:** call `guest-info`. If `guest-exec` or `guest-file-open` has `enabled:false`, throw `GuestCommandDisabledException` [V].
    - Independently, any QGA error with class `CommandNotFound` and a desc containing `has been disabled` is mapped to `GuestCommandDisabledException` [V].
    - The message points to §10.1 G3/G4.
12. **SELinux check:** `exec("/usr/bin/id","-Z")`. A non-zero exit or empty stdout (non-SELinux guest) → skip this step. If stdout contains `virt_qemu_ga_t`:
    - Run `execShell("ls /lib/modules/$(uname -r) >/dev/null")`.
    - Non-zero exit with stderr containing `Permission denied` means the domain is enforcing: throw `GuestAgentConfinedException` (remediation G4). This exact denial was observed **[V]**.
13. **Serial log:** if a consumer is set and the tailer is not already running (step 10), start `SerialLogTailer`.
    - It is a daemon thread that polls every 1 s: `execInContainer("tail","-c","+<offset+1>","/vm/out/serial.log")`.
    - It tracks the byte offset, strips `\r`, splits on `\n`, and keeps partial last lines.
    - Lines read before the consumer was attached (the seed wait) are replayed to the consumer when it attaches (REQ-LIFE-07 "lines emitted before `start()` returned").
14. Record `timings()`; cleanup (§9.12) ran once per JVM before step 1 of the **first** container.

**`stop()`:**
1. Stop every open `ScreenRecording` and `AudioRecording` (best effort, log failures through `Warnings`).
2. QMP `quit` with a 5 s timeout (not scaled); ignore errors.
3. Close the clients.
4. `super.stop()`.

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
  - If `<key>.img` is missing, **copy** the file with `SparseCopy.copy(src, tmp)`: read in 1 MiB blocks; for an all-zero block `position()` the output channel forward instead of writing; after the last block `truncate`/extend the file to the source length so trailing holes keep the apparent size. This preserves sparseness of raw images (a 20 GB raw file with 1 GB of data stays about 1 GB on disk) [D, POSIX sparse-file semantics]. **Never hard-link**: the `:z` relabel and later edits of the user's file must not touch the cache entry.
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

`Secret` wraps a `Supplier<char[]>`. `toString()` prints `***`. `Secret.fromEnv("NAME")` reads the environment variable lazily on first `value()`; a missing variable throws `IllegalStateException("environment variable NAME is not set")` then, not at construction.

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
- **Pull:** through the engine, per §9.3.3 step 1. Explicit credentials come from `OciOptions.credentials(RegistryCredentials.basic(user, Secret pw))`; otherwise the engine's own credential stores are used.
- **Extraction:** `/disk/` is extracted per §9.3.3 steps 2–4 into the cache, keyed by image ID. A tag that moves yields a new image ID and therefore a new cache entry; an unchanged tag reuses the entry (REQ-DISK-12).
- **No JVM-side registry client:** neither the ORAS SDK nor our own (§15 L7).
- **Publishing format:** §10.4.

### 9.4 Exec (R3)

**`GuestAgentClient` protocol:**
- Newline-delimited JSON over TCP.
- Resync with `guest-sync-delimited` on every (re)connect [V].
- One request in flight, guarded by a `ReentrantLock`.
- On I/O error: reconnect once (resync per §9.2 step 10) and re-issue the interrupted request; a `guest-exec-status` poll simply continues with the same `pid`. A second failure propagates as `VmtcException`.

**Exec:**
1. `guest-exec {path: argv[0], arg: argv[1..], env: ["K=V", …] (omitted when empty), input-data: base64(stdin) (omitted when empty), capture-output: true}` → `pid`.
2. Poll `guest-exec-status {pid}`, starting at 50 ms and backing off ×1.5 to a 500 ms cap, until `exited` or the timeout.
3. On timeout: best-effort `guest-exec {path:"/bin/kill", arg:["-9", pid]}`, then throw `GuestExecTimeoutException`.
4. Return `GuestExecResult(int exitCode, Integer signal, String stdout, String stderr, boolean stdoutTruncated, boolean stderrTruncated)`, decoding base64 as UTF-8 with U+FFFD replacement. QGA omits `exitcode` when the process died from a signal: then `signal` is set and `exitCode` is **-1**.

**Notes:**
- QGA caps captured output at 16 MiB per stream [D]. The truncated flags come from `out-truncated` / `err-truncated`.
- PATH inside guest-exec is `/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin` **[V]**. The Javadoc recommends absolute paths.

### 9.5 File copy (R4)

| Direction | Steps |
|-----------|-------|
| Write | `guest-file-open {path, mode:"w"}` → for each 1 MiB chunk `guest-file-write {handle, buf-b64}` → `guest-file-close` |
| Read | `guest-file-open {mode:"r"}` → loop `guest-file-read {handle, count:1048576}` until `eof` (QGA per-call maximum is 48 MiB [D]) → `guest-file-close` |

- Always close in `finally`.
- `copyToVm` first runs `exec("/usr/bin/mkdir","-p",parent)`.
- **Throughput [V]:** KVM 20–21 MB/s write, 31–46 MB/s read; TCG 1.4–2.8 MB/s write, 4.8–6.6 MB/s read.
- The Javadoc recommends a hot-plugged disk image (§9.10) for data ≥ 100 MB.

### 9.6 KVM detection and Podman group handling (R12)

**`KvmDetector`:**

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
- **`forceModeset()`:**
  1. For index 0 it is a no-op (returns without any guest call).
  2. Load classpath resource `<${BASE_PACKAGE} as path>/display/vmtc-fb-activate.py` (Appendix A.4).
  3. `copyToVm(bytes, "/tmp/vmtc-fb-activate.py")`.
  4. `exec("/usr/bin/python3","/tmp/vmtc-fb-activate.py","/dev/fb"+index)`. A non-zero exit throws `VmtcException` with the script's output.
  5. **Wait for the modeset to land:** poll `screenshot()` every 200 ms until the image is no longer 640×480 (or 10 s elapse → `VmtcException("screen <i> did not change mode")`). The VNC server resizes its framebuffer only when QEMU processes the new mode; a recorder attached before that sees a desktop-size change. One CI run (spike run 18) produced a 0.8 s recording of head 1 that had been modeset immediately before; the recorder's log was not captured, so the cause is not proven. The window is real: with the wait in place, the spike measured **0.2 s** between `fb-activate.py` returning and `screendump` showing the new mode (run 24, both BIOS boots) **[V]**, so a recorder attached inside that window sees a framebuffer resize (§15 R10).

  Guest `python3` is present in Rocky GenericCloud [V].
- **`type(text)`:**
  - **Validate first:** map every character per Appendix A.3; any unmapped character → `IllegalArgumentException("unsupported character U+XXXX")` **before** any key is sent.
  - Then one QMP `send-key {keys:[{type:"qcode",data:…}…]}` per character, with **30 ms** between characters [V].
  - Mapping per Appendix A.3: letters (shift for upper case), digits, US punctuation; `\n` → `ret`, space → `spc`.
- **`press(qcodes…)`:** a single `send-key` with all qcodes held together, for example `press("ctrl","alt","f2")`.
- **Making a screen change (tests):** output must go to the VGA console, e.g. `execShell("for i in $(seq 12); do echo tick$i > /dev/tty1; sleep 1; done >/dev/null 2>&1 &")`; writes to `/dev/ttyS0` do not change the screen.

### 9.8 Screen recording (R8)

- **Media only:** if `variant()` is not `MEDIA`, `startRecording` throws `UnsupportedOperationException("screen recording requires the media runner image")` (§6.1).
- **Naming:** `name` = `screen<i>-<uuid8>`.
- **Start:**
  - `execInContainer("/usr/local/bin/vmtc-rec","start",name,i,fps,bitrate)`.
  - Expect `VMTC_REC_STARTED <pid>` in stdout; anything else → `RecordingException("recorder did not start: " + stdout + stderr)`.
- **`ScreenRecording`** (`AutoCloseable`):
  - `close()` runs `vmtc-rec stop <name> 10`.
    - `VMTC_REC_OK duration=<s> width=<w> height=<h>` → `copyFileFromContainer("/vm/out/<name>.webm", hostWebm)`; parse the three values (`duration` is decimal seconds).
    - `VMTC_REC_INVALID` or `VMTC_REC_DIED` → `RecordingException` with the whole stop output (it includes the recorder's log tail).
    - `VMTC_REC_KILLED` followed by `VMTC_REC_OK` → success plus a WARN through `Warnings`.
    - `close()` is idempotent; a second call returns immediately.
  - Accessors: `Path file()`, `Duration duration()`, `int width()`, `int height()`, valid after `close()`.
- **Concurrency:** any number of recordings, including two on one screen (separate VNC clients under force-shared [V]).
- The container keeps a registry of open recordings for `stop()` (§9.2) and the JUnit extension (§9.13).

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

### 9.12 Cleanup without Ryuk

- **Ryuk:** on rootless Podman, Ryuk must be disabled (`TESTCONTAINERS_RYUK_DISABLED=true`) [V].
- **`int VmtcCleanup.removeOrphans()`:**
  - Returns 0 immediately when the `cleanup` override is `false`.
  - Lists containers in **any** state (`listContainersCmd().withShowAll(true)`) labelled `${LABEL_PREFIX}.managed=true` whose `${LABEL_PREFIX}.session` differs from the current one **and** whose `${LABEL_PREFIX}.created` is more than 1 h old.
  - Force-removes them and returns the count.
- `QemuVmContainer.start()` calls it exactly once per JVM, before the first container is created (REQ-CLN-02). The call goes through a package-private `static` seam so U17 can substitute it.

### 9.13 `${ART_JUNIT}` (optional)

- **`@VmTest`** (on a class) registers `VmArtifactsExtension`.
- The extension implements **`AfterTestExecutionCallback`** and checks `context.getExecutionException().isPresent()`. This callback runs **before** `@AfterEach` methods and before Testcontainers' own extension stops containers; `TestWatcher.testFailed` runs after them, when the container is already gone [D, JUnit 5 extension lifecycle], so it is **not** used for artifact capture.
- On failure, for every **running** `QemuVmContainer` found in the test instance's fields and the class's static fields (reflection, including superclasses):
  1. Close each open `ScreenRecording` and `AudioRecording`, which finalizes them at their own host paths.
  2. `saveSerialLog(dir.resolve("serial.log"))`.
  3. One screenshot per screen → `dir.resolve("screen<i>.png")`.

  `dir` = `build/vm-artifacts/<class simple name>/<method name>/` (created on demand).
- To support that, `QemuVmContainer` keeps a registry of its open recordings (§9.8, §9.9).

---

## 10. Guest contract and reference guest image

### 10.1 Guest requirements (normative)

| # | Requirement | How (Rocky/RHEL family) | Detected by |
|---|-------------|-------------------------|-------------|
| G1 | x86-64 Linux with virtio-blk/net/serial drivers | standard | boot |
| G2 | `qemu-guest-agent` on port `org.qemu.guest_agent.0` | preinstalled in Rocky GenericCloud [V] | §9.2 step 10 |
| G3 | exec and file RPCs allowed | `sed -i 's/^FILTER_RPC_ARGS=.*/FILTER_RPC_ARGS=""/' /etc/sysconfig/qemu-ga` + restart the agent [V] | §9.2 step 11 |
| G4 | QGA SELinux domain permissive | `semanage permissive -a virt_qemu_ga_t` [V] | §9.2 step 12 |
| G5 | Serial console on ttyS0 | GenericCloud default | serial log non-empty |
| G6 | Audio tests: `alsa-utils` installed, mixer unmuted | `dnf -y install alsa-utils`; `amixer -c 0 sset Master 90% unmute`; `amixer -c 0 sset PCM 90% unmute \|\| true` [V] | T11 |
| G7 | Multi-screen on console-only guests | `VmScreen.forceModeset()` | T5 |

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

### 10.3 Reference guest image `${IMAGE_GUEST}`

**`images/guest/customize.sh <engine> <runner-slim-image> <base.qcow2> <workdir> <out-tag> <kvm|tcg>`**

This is the verified spike `build-guest.sh` **[V]** (CI run 5, KVM: customization boot 40 s with `DNF_RC=0`, flatten 48 s, resulting qcow2 and containerDisk 836 MB). It is run by `buildGuestDisk`, which `dependsOn buildRunnerSlim`. The base file is downloaded and checked by Gradle first, against `guest.properties`.

The spike ran the boot through `start-vm.sh`, with the seed ISO built at `/vm/seed.iso`. In production, `vmtc-entrypoint` builds it from `/vm/seed` (§7.1 step 4).

1. Write `<workdir>/seed/user-data`:

   ```yaml
   #cloud-config
   write_files:
     - path: /etc/cloud/cloud.cfg.d/99-vmtc-datasource.cfg
       content: |
         datasource_list: [ NoCloud, None ]
   runcmd:
     - sh -c "dnf -y install alsa-utils > /var/log/seed-dnf.log 2>&1; echo DNF_RC=\$? > /dev/ttyS0"
     - sed -i 's/^FILTER_RPC_ARGS=.*/FILTER_RPC_ARGS=""/' /etc/sysconfig/qemu-ga
     - semanage permissive -a virt_qemu_ga_t
     - systemctl enable qemu-guest-agent
     - amixer -c 0 sset Master 90% unmute || true
     - amixer -c 0 sset PCM 90% unmute || true
     - alsactl store
     - cloud-init clean --logs
     - truncate -s0 /etc/machine-id
     - echo VMTC_CUSTOMIZE_DONE > /dev/ttyS0
     - poweroff
   ```

   Write `meta-data` with `instance-id: vmtc-customize`.
   - `datasource_list: [NoCloud, None]` stops cloud-init from probing network metadata services on later seedless boots, while still honouring a user's NoCloud seed.
   - `alsactl store` persists the unmuted mixer.
2. **Customization boot.** The guest powers itself off, QEMU exits, and so does the container:

   ```
   timeout 1800 <engine> run --rm [--device /dev/kvm] -e VM_ACCEL=auto \
     -v <base>:/vm/base/disk.img:ro[,z] -v <workdir>/run:/vm/run[:z] -v <workdir>/out:/vm/out[:z] -v <workdir>/seed:/vm/seed:ro[,z] <runner-slim-image>
   ```

   Then require `VMTC_CUSTOMIZE_DONE` in `<workdir>/out/serial.log` (the `/vm/out` mount keeps the log after the container exits; the spike copied it out the same way **[V]**). Print its `DNF_RC=` line. `VM_ACCEL=auto` (not `kvm`) because on a `0660 root:kvm` host the CLI container may not be able to open the device even when Gradle's user can; the script reads the `VMTC_ACCEL` line from the container log and prints it, and the `<kvm|tcg>` argument only decides whether `--device /dev/kvm` is passed. The production entrypoint builds the seed ISO from `/vm/seed`.
3. **Flatten.** The overlay's backing path is `/vm/base/disk.img`, so the same mounts are used:

   ```
   <engine> run --rm --entrypoint qemu-img -v <base>:/vm/base/disk.img:ro -v <workdir>/run:/vm/run <runner-slim-image> \
     convert -O qcow2 -c /vm/run/overlay.qcow2 /vm/run/rocky9-vmtc.qcow2
   ```
4. **containerDisk.** Create `<workdir>/run/Containerfile` with:

   ```dockerfile
   FROM scratch
   ADD --chown=107:107 rocky9-vmtc.qcow2 /disk/rocky9-vmtc.qcow2
   ```

   Then `<engine> build -t <out-tag> <workdir>/run`. The tag is `${IMAGE_GUEST}:${VERSION}`.

**Acceptance (M3, test T23):** the reference guest boots **with no seed**:
- QGA ready ≤ 30 s (KVM);
- `exec` works (G3/G4 persisted);
- `amixer -c 0 get Master` shows `[on]`;
- T11 audio passes.

Result **[V]** (CI run 5, KVM, no seed attached):
- QGA ready 11.2 s after container start;
- exec works and the context is `virt_qemu_ga_t` (permissive persisted);
- `amixer` Master `[on]` at boot (persisted by `alsactl store`);
- the 1 kHz audio test passes;
- all 16 feature checks pass, including console login after `chpasswd` via QGA.

### 10.4 Publishing VM disks as OCI images (R13)

We control the publishers, so every OCI-hosted disk uses the KubeVirt containerDisk layout:

```dockerfile
FROM scratch
ADD --chown=107:107 <name>.qcow2 /disk/<name>.qcow2      # exactly ONE regular file under /disk/
```

- **qcow2:** produce it with `qemu-img convert -O qcow2 -c` (internally compressed). Do not `.xz` it: registry layers are already compressed.
- **Push:** `podman push` or `docker push` to any OCI registry.
- **References:** consume by tag, or by digest for reproducible tests (`oci://registry/repo@sha256:…`).
- **Labels (optional, informational):** `${LABEL_PREFIX}.guest.os`, `${LABEL_PREFIX}.guest.contract=G1-G6`.
- **The reference guest (§10.3) is published exactly this way.**

---

## 11. Build (Gradle)

### 11.1 Conventions

- Java toolchain `${JAVA_RELEASE}`. javac `-Xlint:all,-processing,-requires-automatic,-requires-transitive-automatic,-this-escape -Werror` for every module (the three exclusions are needed because Testcontainers/docker-java/zstd-jni are automatic modules and builder methods are called from constructors; every exception has a `serialVersionUID`, so `serial` stays on).
- Formatting: adopt the target repo's formatter, if it has one.
- Every `Test` task: `useJUnitPlatform()`, `testRuntimeOnly 'org.junit.platform:junit-platform-launcher'`, Allure results per §11.4.
- `:lib` and `:junit5` unit tests need no engine.
- `:it` (sources in `it/src/test/java`) holds the integration tests. Its `Test` tasks all set `failOnNoDiscoveredTests = false` (Gradle 9 defaults it to `true` [D]) and `dependsOn ':images:verifyRunnerImages', ':images:buildGuestDisk'`:
  - `integrationTest`: everything except `KvmCompatIT`, `OciAuthIT` and classes tagged `testkit-only`. **Tag mapping:** `-P<syspropPrefix>.kvm=REQUIRE` → `excludeTags 'tcg'`; `DISABLE` → `excludeTags 'kvm'`; `PREFER` or absent → no exclusion. Untagged tests always run.
  - `compatTest`: `KvmCompatIT` only (T35).
  - `ociAuthTestA`..`ociAuthTestD`: `OciAuthIT` only, one JVM each with `systemProperty '<syspropPrefix>.it.ociScenario', 'a'..'d'`, chained with `mustRunAfter`; `ociAuthTest` aggregates them.
  - All of them forward the mode: `systemProperty "<syspropPrefix>.kvm", providers.gradleProperty("<syspropPrefix>.kvm").getOrElse("PREFER")` — the CI passes it as **`-P`**, not `-D` (`findProperty`/`gradleProperty` read project properties only).
  - They pass `systemProperty "<syspropPrefix>.it.guestQcow2", file("../images/build/guest/work/run/rocky9-vmtc.qcow2").absolutePath` and `"<syspropPrefix>.it.baseQcow2"` = `images/build/guest/base.qcow2` (served by in-JVM HTTP servers in T19/T25, so CI never downloads the 1 GB stock image twice).
- `TestImages.guest()` (in `:it`) returns `VmImage.fromPath(Path.of(System.getProperty("<syspropPrefix>.it.guestQcow2")))` until M3 and `VmImage.referenceGuest()` from M3 on.
- `VmIT` (base class in `:it`): `@BeforeAll` warm-up (resolves `TestImages.guest()` once so the cache is populated before timing tests, REQ-LIFE-02); `register(container)`; `@AfterEach` attaches the standard evidence (REQUIREMENTS.md §3.3) and stops registered containers.

### 11.2 `:images` tasks (all `Exec` with declared inputs/outputs; engine from `-PcontainerEngine`, default `podman`)

| Task | Command |
|------|---------|
| `buildRunnerSlim` | `<engine> build -f images/runner/Containerfile --build-arg UBI_IMAGE=<ubiImage> --build-arg VARIANT=slim --build-arg VERSION=<version> --build-arg LABEL_PREFIX=<labelPrefix> --build-arg IMAGE_TITLE=<imageRunnerName> -t <imageRegistry>/<imageRunnerName>:<version>-<imageTagSuffix> images/runner`; with `-PnoCache=true` add `--no-cache --pull` (used by the weekly CI run, REQ-BLD-05) |
| `buildRunnerMedia` | same with `VARIANT=media`, tag suffix `-media` |
| `verifyRunnerImages` | §6.4 checks; `dependsOn` both builds |
| `downloadGuestBase` | download `baseUrl` to `images/build/guest/base.qcow2`, verify `baseSha256`; skipped when the file already verifies |
| `buildGuestDisk` | `images/guest/customize.sh <engine> <slim tag> images/build/guest/base.qcow2 images/build/guest/work <imageRegistry>/<imageGuestName>:<version> <kvm if /dev/kvm usable else tcg>`, then the REQ-GUEST-05 format checks (one layer, empty `Config.Cmd`/`Entrypoint`, one regular file uid/gid 107 under `/disk/`); `dependsOn buildRunnerSlim, downloadGuestBase` |
| `pushImages` | `<engine> push` the three tags to `${IMAGE_REGISTRY}`; `onlyIf { project.findProperty('pushImages') == 'true' }` |

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
  public static DockerImageName defaultRunnerImage(RunnerVariant v) {
    return DockerImageName.parse("<imageRegistry>/<imageRunnerName>:" + VERSION + "-" + IMAGE_TAG_SUFFIX + (v == RunnerVariant.MEDIA ? "-media" : ""));
  }
  public static DockerImageName referenceGuestImage() { return DockerImageName.parse("<imageRegistry>/<imageGuestName>:" + VERSION); }
}
```

### 11.4 Allure reporting and `test-support` (D10, D11)

**`test-support` module** (not published; `java-library`):

- Dependencies: `platform("io.qameta.allure:allure-bom:${ALLURE_JAVA_VERSION}")`, `io.qameta.allure:allure-jupiter`, `io.qameta.allure:allure-java-commons`, `org.junit.jupiter:junit-jupiter-api`, `jackson-databind` (all `api` so test modules inherit them).
- `processResources` copies `../docs/vm-testcontainer/REQUIREMENTS.md` to `vmtc-requirements/REQUIREMENTS.md` (declared input, so edits to the document rebuild the jar).
- Types in `${BASE_PACKAGE}.testsupport`: `Requirement`, `Requirements`, `RequirementExtension`, `RequirementsCatalog` (parser, REQUIREMENTS.md §3.2), `RequirementsCheck` (`main`, §11.5), `Evidence` (REQUIREMENTS.md §3.3), `AllureEnvironment` (writes `environment.properties`).
- Resource `allure.properties` is **not** here; each test module has its own (below).

**Every test module** (`:lib`, `:junit5`, `:it`):

- `testImplementation project(':test-support')` (plus `allure-jupiter` transitively; `:it` adds `junit-platform-testkit` for T34).
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
| `checkParameterHygiene` | The §0 grep rules. Plus these greps on `lib/src/main`: no `ProcessBuilder`, no `Runtime.getRuntime().exec`, no `throw new RuntimeException(`, no `throw new UncheckedIOException(` (REQ-ENG-03, REQ-ERR-01). Plus: `.github/workflows/ci.yml` contains `cron: '0 6 * * 1'` (REQ-BLD-05). Plus: no `@Disabled` in any test source set (REQUIREMENTS.md §3.1) |
| `checkRequirementTraceability` | `JavaExec` of `RequirementsCheck` (test-support) with the REQUIREMENTS.md path, the test source roots and `traceabilityRequireFullCoverage`; writes `build/reports/requirements/traceability.md` (REQUIREMENTS.md §3.4) |
| `checkDependencies` | Resolves `:lib` `configurations.api.dependencies` and `implementation.dependencies` (direct declarations) and compares `group:name` sets with the §9.1 table; any difference fails (REQ-BLD-03, REQ-OCI-06) |

---

## 12. CI (GitHub Actions)

### 12.1 Workflow `ci.yml`

- **Triggers:** `push`, `pull_request`, and `schedule: cron '0 6 * * 1'` (weekly rebuild against fresh repos; the scheduled run passes `-PnoCache=true`; R1).
- **Common steps:** checkout; `actions/setup-java` (Temurin `${JAVA_RELEASE}`, Gradle cache); `export <envPrefix>IT_ENGINE_VERSION="$(podman --version | awk '{print $3}')"`.
- **Jobs:**

| Job | Runner(s) | Runs | Timeout |
|-----|-----------|------|---------|
| `unit` | ubuntu-24.04 | `./gradlew checkParameterHygiene checkDependencies checkRequirementTraceability :lib:test :junit5:test publishToMavenLocal allureReport`; then the REQ-BLD-02 POM assertions | 20 min |
| `integration` | matrix `{ubuntu-24.04, kvm}`, `{ubuntu-24.04, tcg}`, `{ubuntu-26.04, kvm}` | steps below | 30 min KVM / 90 min TCG |
| `compat` | matrix ubuntu-24.04, ubuntu-26.04 | `./gradlew :images:verifyRunnerImages`; for S in A, B, C: `it/fixtures/kvm-scenario.sh S` then `:it:compatTest` (PLAN-STEPS.md step 1.25); `allureReport` | 30 min |
| `oci-auth` | ubuntu-24.04, KVM | KVM udev rule; `it/fixtures/test-registry.sh start`; `./gradlew :it:ociAuthTest -P<syspropPrefix>.kvm=REQUIRE`; `test-registry.sh stop` (always); `allureReport` | 45 min |

- **Steps for `integration`:**
  1. If kvm: udev rule `KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"`, then `udevadm control --reload-rules && udevadm trigger --name-match=kvm` **[V]**. The tcg job does **not** apply it (T20 relies on `/dev/kvm` being unusable there).
  2. `podman system service --time=0 unix://$XDG_RUNTIME_DIR/podman/podman.sock &` (the same command form as `run-scenario.sh` [V]).
  3. Export `DOCKER_HOST=unix://$XDG_RUNTIME_DIR/podman/podman.sock` and `TESTCONTAINERS_RYUK_DISABLED=true` [V].
  4. `actions/cache` for `images/build/guest/base.qcow2`, keyed by `baseSha256` (the spike cached the base image the same way [V]). The customized guest is rebuilt each run, about 1.5 min on KVM [V].
  5. `./gradlew :it:integrationTest -P<syspropPrefix>.kvm=<REQUIRE for kvm | DISABLE for tcg>`
  6. `./gradlew allureReport` with `if: always()`.
  7. Upload, all `if: always()`: `build/allure-report/` as `allure-report-integration-<os>-<accel>`; `**/build/allure-results/` as `allure-results-…`; `it/build/vm-artifacts/**` and `it/build/reports/**`.
- Every other job uploads `build/allure-report/` and `**/build/allure-results/` the same way (`allure-report-unit`, `allure-report-compat-<os>`, `allure-report-oci-auth`), plus `build/reports/requirements/traceability.md` from `unit`.

### 12.2 Expected durations

| Item | Duration |
|------|----------|
| Runner image build | about 51 s each [V] |
| Guest disk build | about 1.5 min on KVM (40 s boot + 48 s flatten + packaging) [V] |
| KVM integration suite | about 5 min [U, estimate] |
| TCG integration suite | about 20 min [U, estimate] |
| Allure report generation | well under 1 min [U, estimate] |

### 12.3 Parameter hygiene

The `checkParameterHygiene` task (§0 rules, §11.5) runs in the `unit` job.

---

## 13. Test plan

**Acceptance criteria and evidence are normative in [REQUIREMENTS.md](REQUIREMENTS.md).** This section says how each test is set up and which requirements it verifies (its `@Requirement` annotations). The evidence each test attaches is the *Evidence* cell of every requirement it verifies, plus the standard VM evidence (REQUIREMENTS.md §3.3).

**Rules:**
- **Guest:** every `:it` test uses `TestImages.guest()` unless stated otherwise (§11.1).
- **Jobs:** KVM jobs exclude `tcg`-tagged tests; TCG jobs exclude `kvm`-tagged tests; untagged tests run everywhere.
- **Runner variant:** tests marked *media* use `new QemuVmContainer(RunnerVariant.MEDIA)`; all others use the slim runner.
- **Fixtures:** `:it` test fixtures may call the engine CLI (`podman build`, `podman push`, `podman login`) and generate files with `xorriso`/`qemu-img` inside a runner container. The library itself never does (REQ-ENG-03).
- **System property hygiene:** tests that must control the KVM mode regardless of the CI job (`accelPreferDefault*`, T20, T35) clear `<syspropPrefix>.kvm` for their duration and restore it in `finally`. With D9 this only matters for the *default*; tests calling `withKvm(...)` explicitly are unaffected.
- **Evidence:** [V] = the behaviour was verified by a spike (§17); [U] = new, not yet run.

### 13.1 Integration tests (`:it`, real Podman)

| ID | Class.method | Setup / procedure | Verifies | Spike evidence |
|----|--------------|-------------------|----------|----------------|
| T1 | `AccelIT.accelKvm` (`kvm`), `accelTcg` (`tcg`), `accelPreferDefault` (`kvm`), `accelPreferDefaultTcg` (`tcg`) | `REQUIRE` / `DISABLE` / no `withKvm` with the property cleared; QMP `query-kvm`; inspect `HostConfig.Devices`; `withQemuArgs("-name","vmtc-extra")` + `query-name` in `accelKvm`/`accelTcg` | REQ-KVM-01, REQ-KVM-03, REQ-KVM-04, REQ-CFG-03 | [V] |
| T2 | `LifecycleIT.bootToAgent` | After the `VmIT` warm-up: time `start()`; `exec("/usr/bin/true")`; `infoCmd`; inspect labels of two containers; `pgrep -c -x qemu-kvm`; `cat /etc/os-release; uname -a` | REQ-LIFE-01, REQ-LIFE-02, REQ-LIFE-10, REQ-ENG-01, REQ-PLAT-02 | [V] 10.9–12.1 s KVM / 46.7–66.5 s TCG (seeded) |
| T3 | `ExecIT.execBasics` | The commands in REQ-EXEC-01..03 and REQ-EXEC-06 (`kill -9 $$`) | REQ-EXEC-01, REQ-EXEC-02, REQ-EXEC-03, REQ-EXEC-06 | [V] (01); others [U] |
| T4 | `CopyIT.copyRoundTrip` | Random 5 MB / 50 MB files, `test ! -e /tmp/vmtc` then a deep target path, 0- and 3-byte arrays | REQ-FILE-01, REQ-FILE-02, REQ-FILE-03 | [V] |
| T5 | `DisplayIT.screenshotEachScreen` | `withScreens(2)`; screenshot both (count non-black pixels on head 0); `screen(0).forceModeset()` (no-op); `screen(1).forceModeset()`; screenshot head 1 again; `screenshot(Path)` | REQ-DISP-02, REQ-DISP-03 | [V] |
| T6 | `DisplayIT.vncExposed` | `withScreens(4)`: `query-vnc-servers`; raw RFB handshake per `vnc()` (read `RFB 003.008`, send it back, read the security-type list); `query-status`, `guestAgent().ping()`, `vnc()` fields; inspect port bindings and `PublishAllPorts`; then a second container with `withVncBindAddress("0.0.0.0")` | REQ-VNC-01, REQ-VNC-02, REQ-LIFE-08, REQ-LIFE-12 | [V] (handshake, 2 screens); 4 screens and bindings [U] |
| T7 | — | *Removed (D8: no VNC password). Number kept so older references stay valid.* | — | — |
| T8 | `RecordingIT.recordTwoScreens` (*media*) | `withScreens(2)`, `screen(1).forceModeset()`, start a `tick` loop on `/dev/tty1` (§9.7), record both for 12 s, measure `close()`; then one 6 s recording with `RecordingOptions.fps(5)` + `gst-discoverer-1.0` in the container; then two simultaneous recordings of screen 0 | REQ-REC-01, REQ-REC-02, REQ-REC-07 | [V] (two screens); options/concurrency [U] |
| T9 | `RecordingIT.recordingSurvivesExclusiveClient` (*media*) | While recording, a raw RFB client completes the handshake with security type None and sends ClientInit `shared-flag=0`, holds 3 s, disconnects | REQ-VNC-05 | [V] (sandbox) |
| T10 | `RecordingIT.recordStaticScreen` (*media*) | Record 10 s without guest activity | REQ-REC-03 | [V] (sandbox) |
| T11 | `AudioIT.audioTone` | `info capture` before; start WAV; guest `/usr/bin/speaker-test -t sine -f 1000 -c 2 -l 1`; second `startAudioRecording` → exception; close; `info capture` after; RMS and dominant frequency (DFT of ≤ 65536 samples) in Java; start and close a second recording | REQ-AUD-01, REQ-AUD-02, REQ-AUD-03 | [V] (01); 02, 03 [U] |
| T12 | `DisplayIT.keyboardType` | `execShell("echo root:vmtc \| chpasswd")`; `type("root\n")`, wait 3 s; `type("vmtc\n")`, wait 5 s; `type("echo TYPED_OK > /dev/ttyS0\n")`; a serial-log consumer must see `TYPED_OK` within 60 s; screenshot | REQ-KEY-01 | [V] (run 5) |
| T13 | `HotplugIT.usbStorage`, `usbHid`, `usbReadOnly` | Storage: 16 MiB raw image made with `qemu-img` in a runner container. HID: keyboard, tablet, mouse in turn. Read-only: `usbStorage(img, true)` + `dd` + host digest; `close()` unplugs | REQ-HOT-01, REQ-HOT-02, REQ-HOT-10 | storage [V]; HID, read-only [U] |
| T14 | `HotplugIT.virtioDisk`, `nic`, `portsExhausted` | Host image and `newBlankDisk("64M", VIRTIO)` (`lsblk -b`); `nic("52:54:00:12:34:56")`; `withHotplugPorts(1)` + plug, plug (fails), unplug, plug | REQ-HOT-03, REQ-HOT-04, REQ-HOT-05 | [V] (03, 04); 05 [U] |
| T15 | `HotplugIT.cdromInsertEject` | ISO with label `VMTCCD` and one file, made with `xorriso` in a runner container; `blkid` after eject | REQ-HOT-06 | [V] |
| T16 | `HotplugIT.cpuKvm` (`kvm`), `cpuTcg` (`tcg`) | `hotplug(Devices.cpu())`; `unplug()` → `UnsupportedOperationException`; `close()` no-op | REQ-HOT-07, REQ-HOT-10 | [V] (KVM; TCG failure observed) |
| T17 | `HotplugIT.memory` | `hotplug(Devices.memory(512))`; handle semantics as T16 | REQ-HOT-08, REQ-HOT-10 | [V] |
| T18 | `ImageSourcesIT` | (a) `fromPath` of the guest qcow2; guest writes 1 MiB + `sync`; stop; cache file SHA-256 unchanged; a second container does not see the file. (b) `fromUrl` from an in-JVM `HttpServer` serving that qcow2, with SHA-256; request log. (c) `fromContainerDisk(<local never-pushed tag>)` → boots + `using local image` WARN once. (d) `baked()` on an image built by the fixture `FROM <slim>` + `COPY disk.qcow2 /vm/base/disk.img`; inspect mounts. (e) containerDisks with 0 files (`FROM scratch` + `WORKDIR /disk`) and 2 files → `IllegalArgumentException`. (f) repeat (a)–(c): cache listing unchanged, no HTTP request. (g) an overlay qcow2 with a backing file (made with `qemu-img create -b` in a runner container) → `IllegalArgumentException` | REQ-DISK-01, REQ-DISK-02, REQ-DISK-03, REQ-DISK-04, REQ-DISK-05, REQ-DISK-06, REQ-DISK-13, REQ-LIFE-03, REQ-GUEST-05, REQ-OCI-07 | partly [V] |
| T19 | `GuestContractIT.guestNotPrepared` | Stock Rocky served from an in-JVM `HttpServer` (`<syspropPrefix>.it.baseQcow2`) with its SHA-256, no seed → expect `GuestCommandDisabledException`; attach `guest-info`. Then a seed from `CloudInitSeed.builder().runcmd(<G3 sed line>, "systemctl restart qemu-guest-agent")` → expect `GuestAgentConfinedException`; attach `id -Z` | REQ-GUEST-01, REQ-GUEST-02 | [V] (behaviour) |
| T20 | `AccelIT.kvmRequireWithoutKvm` (`tcg`) | `assumeFalse(KvmDetector.usable())`; property cleared; `withKvm(REQUIRE)` → `start()` throws exactly `KvmUnavailableException`; message equals the §9.6 text for `!present()` or `present() && !usable()` | REQ-KVM-02, REQ-ERR-03 | [U] |
| T21 | `CleanupIT.orphanCleanup` | With docker-java, create (do not start) three containers from the slim runner image, labelled: (1) other session, created now − 2 h; (2) other session, created now − 10 min; (3) current session, now − 2 h. `VmtcCleanup.removeOrphans()` → returns 1, only (1) is gone. Repeat with override `cleanup=false` → returns 0 | REQ-CLN-01 | [U] |
| T22 | `UefiIT.uefiBoot` | `withFirmware(Firmware.UEFI)`; `test -d /sys/firmware/efi`; screenshot | REQ-LIFE-09 | [V] (run 19: efivars present, all checks pass under OVMF) |
| T23 | `GuestContractIT.referenceGuestNoSeed` | `VmImage.referenceGuest()`, no seed; `id -Z`, `ls /lib/modules/$(uname -r)`, `amixer -c 0 get Master` | REQ-GUEST-04, REQ-DISK-11 | [V] (run 5: 11.2 s) |
| T24 | `RecordingIT.noZombieRecorders` (*media*) | `cat /proc/1/comm`; start + stop a recording; within 2 s `execInContainer("sh","-c","grep -H '^State:' /proc/[0-9]*/status")` has no `Z (zombie)` | REQ-IMG-05 | [V] (tini, sandbox) |
| T25 | `GuestContractIT.stockRockyWithSeed` | Stock Rocky from the in-JVM server (as T19) + `CloudInitSeed.builder().stockRocky().writeFile("/etc/vmtc-test","x\n").build()`; `saveSerialLog` right after `start()`; T3's first command; read `/etc/vmtc-test`; `amixer` | REQ-GUEST-06, REQ-GUEST-07, REQ-DISK-02 | [V] (seed), file [U] |
| T26 | `HttpDownloadTest` (`:lib`, engine-free) | In-JVM `HttpServer`s with request logs: (a) no/wrong credentials → `ImageAuthException`, redaction; (b) Basic, Bearer, custom header; (c) `.gz` URL with compressed vs decompressed digest; (d) `.gz`/`.xz`/`.zst`, `AUTO` unknown suffix, `NONE`, `GZIP` without suffix, redirect to `.gz`; (e) cross-origin vs same-origin redirect per credential kind; (f) no checksum: one WARN (via `Warnings.listen`), 304 reuse, 200 replace, server down → cache + WARN; (g) 5 and 6 redirects; (h) 503,503,200 / 503×3 / reset×2 then 200 / 404 / 401 / wrong digest request counts with an injected zero backoff; (i) header stall and read stall, `Secret.fromEnv` laziness | REQ-HTTP-01, REQ-HTTP-02, REQ-HTTP-03, REQ-HTTP-04, REQ-HTTP-05, REQ-HTTP-06, REQ-HTTP-07, REQ-HTTP-08, REQ-HTTP-10 | [V] (a–e); (f)–(i) [U] |
| T27 | `OciAuthIT.pull` — run by `ociAuthTestA..D`, **one JVM per scenario** (`-D<syspropPrefix>.it.ociScenario=a\|b\|c\|d`) | Fixture registry (§12.1 `oci-auth`). Once, before scenario (a): `podman tag <local guest> <ref>`, `podman login`, `podman push --digestfile <file> <ref>`, `podman logout`. Before each scenario: `podman rmi -f <ref>`, remove `~/.docker/config.json` and `$XDG_RUNTIME_DIR/containers/auth.json`. (a) nothing → `ImageAuthException`; (b) write `config.json`; (c) nothing stored + `OciOptions.credentials`; (d) `podman login` only | REQ-OCI-02, REQ-OCI-03, REQ-OCI-04, REQ-OCI-07 | [V] pull for a–d (run 16: S4 = explicit `AuthConfig`, S5 = wrong password → `UnauthorizedException`) |
| T28 | `OciAuthIT.tagMove` — in scenario (b) | Fixture: v1 = the reference guest qcow2; v2 = the same disk re-encoded with `qemu-img convert -c -O qcow2 -o cluster_size=128k` (bootable, different bytes). Push v1 as `:t` (`--digestfile`), start, `execInContainer("sha256sum","/vm/base/disk.img")` = v1, cache listing; start again (same inode); push v2 as `:t`, start → v2 and one more cache entry; start `@sha256:<v1 digest>` → v1 | REQ-OCI-05, REQ-DISK-03, REQ-DISK-12 | [U] |
| T29 | `LifecycleIT.startFailureReported` | Slim runner + `VmImage.baked()` (the runner has no `/vm/base/disk.img`) → exactly `VmtcException` containing `no base disk` | REQ-LIFE-04, REQ-ERR-03 | [U] |
| T30 | `LifecycleIT.guestAgentTimeout` | (a) `fromPath(<16 MiB raw file of zeros>)`, `withGuestAgentTimeout(10 s)`, `withLogConsumer` to time `VMTC_READY`; exactly `GuestAgentTimeoutException`. (b) reference guest with `withGuestAgentTimeout(1 s)` → message contains `Linux version` or `login:` | REQ-LIFE-05, REQ-DISK-01, REQ-ERR-03 | [U] |
| T31 | `RecordingIT.stopFinalizesRecordings` (*media*) | Start a screen recording and an audio recording; call `stop()` without closing them; both host files valid | REQ-LIFE-06 | [U] |
| T32 | `ExecIT.execTimeout` | `exec(Duration.ofSeconds(2), "/usr/bin/sleep", "61")`; then `exec("/usr/bin/pgrep","-f","sleep 61")` | REQ-EXEC-04 | [U] |
| T33 | `RecordingIT.recordingRequiresMedia` | Slim runner; `screen(0).startRecording(...)` | REQ-REC-04 | [U] |
| T34 | `VmTestExtensionIT.artifactsOnFailure` (`:junit5` tests, tagged `testkit-only`) | JUnit Platform `EngineTestKit` runs a nested `@VmTest` class (tagged `testkit-only`, excluded from the normal run) whose test starts a media VM, opens a screen recording, and fails; its `@AfterEach` asserts the artifact files already exist | REQ-JUN-01 | [U] |
| T35 | `KvmCompatIT` (task `compatTest`) | Reads `<envPrefix>IT_KVM_SCENARIO` and `<envPrefix>IT_PODMAN_VERSION` (set by `it/fixtures/kvm-scenario.sh`); property cleared. Case 1 (`PREFER`): `fromPath(<16 MiB raw zeros>)`, `withGuestAgentTimeout(5 s)`, capture logs; expect `GuestAgentTimeoutException`; assert `VMTC_ACCEL`, `HostConfig.Devices`, `Annotations`, `GroupAdd` and the WARN per REQ-KVM-05's scenario table. Case 2 (`REQUIRE`): where case 1 expects `tcg`, expect `KvmUnavailableException` with the group-restricted text; elsewhere a boot that times out at the agent with `VMTC_ACCEL kvm` | REQ-KVM-05, REQ-KVM-06 | [V] (matrix via the spike probe; run 16: the login-session JVM sees `/dev/kvm` readable+writable, the CI-shell JVM does not) |
| T36 | `LifecycleIT.resources` | `withCpus(3).withMaxCpus(6).withMemoryMb(1536).withMemoryHotplug(2,"6G")`: `nproc`, `/proc/meminfo`, `query-hotpluggable-cpus`, `query-memory-size-summary`; then `withMemoryMb(4096)` with default maxmem `8G` → ok, and `withMemoryMb(16384)` → `IllegalArgumentException` at `start()` | REQ-LIFE-11 | [U] |
| T37 | `LifecycleIT.serialLog` | `withSerialLogConsumer` collecting lines; after start: `echo VMTC_MARK_<uuid> > /dev/ttyS0`; time to the consumer; `saveSerialLog` | REQ-LIFE-07 | [U] |

### 13.2 Engine-free tests (`:lib:test`, `:junit5:test`)

| ID | Class | Covers | Verifies |
|----|-------|--------|----------|
| U1 | `QmpClientTest` | In-JVM fake QMP server: greeting + `qmp_capabilities`; event lines skipped while waiting for a return; error → `QmpException(class, desc)`; response timeout | REQ-ERR-02 |
| U2 | `GuestAgentClientTest` | Fake QGA: leading `0xFF` resync; `guest-sync-delimited` id matching; base64 in/out; `0xFF 0x41` → `�A`; exec polling back-off; `out-truncated` / `err-truncated`; omitted `exitcode` + `signal` → `exitCode == -1`; reconnect once and continue polling; `CommandNotFound` + "has been disabled" → `GuestCommandDisabledException` | REQ-EXEC-05, REQ-EXEC-06, REQ-GUEST-01 |
| U3 | `VmImageCacheTest` | In-JVM lock + file lock; two threads and two JVMs (helper `main` via `ProcessBuilder`) on one key; `fromPath` copy (inode differs) and `index.json` memo (copy count); `prune` incl. side files and index rows; SELinux flag → `SelinuxContext`; `Qcow2Header` on a crafted header with and without backing file; `SparseCopy` of a 64 MiB file with 1 MiB of data: content equal, apparent size 64 MiB, allocated size (`stat -c '%b %B'`, blocks × block size; Java has no portable API for it) below 4 MiB; a file ending in a hole keeps its length | REQ-DISK-06, REQ-DISK-07, REQ-DISK-08, REQ-DISK-09, REQ-DISK-10, REQ-DISK-14 |
| U4 | `KeyboardInputTest` | Appendix A.3 table → qcodes; unsupported character → exception and zero `send-key`; `press` chord | REQ-KEY-01, REQ-KEY-02 |
| U5 | `VmtcConstantsTest` | Generated constants equal the `gradle.properties` values passed to the test JVM as system properties | REQ-BLD-01 |
| U6 | `OverridesTest` | Every §9.1 key: builder > property > env > default (env injected through a `Map<String,String>` seam); invalid values → `IllegalArgumentException` naming the key; `referenceGuest()` resolution | REQ-CFG-01, REQ-DISK-11 |
| U7 | `InfoCaptureParserTest` | `[<n>]: Capturing audio(44100,16,2) to <path>: <bytes> bytes`; several lines; fallback when exactly one line; empty output → no capture | REQ-AUD-03 |
| U8 | `KvmDecisionTest` | §8.2 table × §9.6 detector states → device / annotation / `VM_ACCEL` / exception; never `GroupAdd`; exact message strings; once-per-JVM WARN observed through `Warnings.listen` | REQ-KVM-02, REQ-KVM-03, REQ-KVM-04, REQ-KVM-05, REQ-KVM-06 |
| U9 | `ConfigValidationTest` | Builder ranges (REQ-CFG-02 list, screens, fps); the timeout table of REQ-KVM-07 for factors 6 and 2.5; explicit values unscaled | REQ-CFG-02, REQ-DISP-01, REQ-KVM-07 |
| U10 | `CloudInitSeedTest` | `forStockRocky().userData()` equals §10.2 byte for byte (expected text stored as a test resource copied from PLAN.md); every builder rule of REQ-GUEST-06; `VMTC_SEED_DONE` last even for `builder().build()` | REQ-GUEST-03, REQ-GUEST-06 |
| U11 | `OciReferenceTest` | Tag, digest, default `latest`, registry with port, `oras://` and missing scheme rejected | REQ-OCI-01 |
| U12 | `SecretAndRedactionTest` | `Secret.toString()`; `Secret.fromEnv` laziness; exception messages contain no secret, user-info or query | REQ-HTTP-02, REQ-HTTP-09, REQ-HTTP-10 |
| U13 | `HotplugUnplugTimeoutTest` | Fake QMP whose `qom-list` always contains the device → `HotplugTimeoutException` | REQ-HOT-09 |
| U14 | `ApiShapeTest` | All public exception types are unchecked and extend `VmtcException`; module descriptor read with `ModuleDescriptor.read(QemuVmContainer.class.getResourceAsStream("/module-info.class"))` exports every package except `internal`; class-file major version of `QemuVmContainer.class` = 44 + `${JAVA_RELEASE}` | REQ-ERR-01, REQ-BLD-04, REQ-PLAT-03 |
| U15 | `RecorderCommandTest` | `vmtc-rec` argv built by Java; parsing of `VMTC_REC_STARTED`, `VMTC_REC_OK duration=12.346 width=1280 height=800`, `VMTC_REC_KILLED`+`VMTC_REC_OK` (WARN), `VMTC_REC_INVALID` + log → `RecordingException`, missing `VMTC_REC_STARTED` → `RecordingException` | REQ-REC-06 |
| U16 | `StartupMessagesTest` | `StartupMessages.tail` (60 → lines 11–60; 5 → all); the `VmWaitStrategy` table of §9.2 step 7 against fake log lines; `start()` unwrapping: a `QemuVmContainer` subclass whose `configure()` throws `KvmUnavailableException` → `start()` throws exactly that type (no engine needed: the throw happens before any client call) | REQ-LIFE-04, REQ-ERR-03 |
| U17 | `CleanupTriggerTest` | Package-private cleanup seam counts invocations: two `start()`s of containers whose `configure()` short-circuits → one invocation; `cleanup=false` → zero | REQ-CLN-02 |

---

## 14. Implementation milestones

The step-by-step checklist for each milestone is [PLAN-STEPS.md](PLAN-STEPS.md). A milestone is done when its tests pass on **every** `integration` matrix entry (or are excluded there by their `kvm`/`tcg` tag), `unit` is green, `checkRequirementTraceability` passes, and the Allure report of each job shows the milestone's requirements with their evidence attached.

| M | Content | Tests that must pass |
|---|---------|----------------------|
| M1 | Gradle skeleton (§5, §11), `test-support` (`@Requirement`, extension, catalog, `Evidence`), check tasks and `allureReport`, runner images (§6, §7), reference guest build (§10.3), CI workflow (§12) incl. `compat`; `QemuVmContainer` start/stop (incl. the `start()` override), QMP, QGA, exec, copy, `fromPath`, `baked`, basic `fromUrl` (SHA-256, no credentials, no decompression), `CloudInitSeed`, `KvmMode`/§9.6, `Warnings` | U1–U3, U5, U6, U8–U10, U14, U16, U17; T1–T4, T20, T25, T29, T30, T32, T35, T36, T37; §6.4; `compat` job |
| M2 | Screens, VNC endpoints, screenshots, keyboard, `forceModeset`, UEFI | U4; T5, T6, T12, T22 |
| M3 | `fromContainerDisk`, `fromOci` (engine pull + credentials + local fallback), full `fromUrl` (§9.3.5), `referenceGuest`, test registry fixture and `oci-auth` CI job; switch `TestImages.guest()` to `referenceGuest()` | U11, U12, T26; T18, T19, T23, T27, T28 |
| M4 | Screen recording (media), audio | U7, U15; T8–T11, T24, T31, T33 |
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
| R5 | UEFI firmware paths | — | T22 |
| R6 | ubuntu-26.04 runner image is in public preview | CI label may change | 24.04 stays primary |
| L6 | **Image mounts not used** (they would avoid the extraction copy): Docker needs API ≥ 1.48 while Testcontainers defaults to 1.44; Podman's inspect output breaks docker-java's `Binds` parser | one extra disk copy per image digest | Extraction + bind (§9.3.6). Revisit when Podman/docker-java fix the inspect issue |
| L7 | ORAS artifacts (`oras push`) are **not supported**: disks are published as OCI images instead (§10.4). The ORAS Java SDK was prototyped and rejected (dependency weight) | — | Publish with §10.4 |
| R7 | Large HTTP downloads over slow links | long first-run time | cache; retries; per-request timeout option |
| R8 | Allure Java 2.x is the previous major (3.0.0 shipped 2026-09); its JUnit adapter declares JUnit 5.10.3 runtime deps that the 5.14 BOM overrides | a future 2.x drop would force the 3.x/JUnit 6 move | D10; versions live in §0 |
| R9 | `--single-file` Allure reports embed every attachment; a job with many WebM recordings can produce a report of tens of MB | slow to open | keep recordings short (≤ 12 s, 1 Mbps); the raw results are uploaded too |
| R10 | **Intermittent short recording** (spike run 18, image-mount boot): head 1 recorded 0.8 s of a 12 s window; the same check passed in the two other boots of that run and in all other runs; the recorder's own log was not captured | a truncated video would pass silently without the `VMTC_REC_DIED` rule | `vmtc-rec stop` reports a recorder that exited early (§7.2 step 0); `forceModeset()` waits for the new mode (§9.7; measured window 0.2 s, run 24); the spike harness now keeps `screen<i>.log`, so a recurrence is diagnosable; T8/T10 assert the duration |
| L8 | VNC is unauthenticated (D8) | anyone who can reach the published port can view and type into the VM | loopback bind by default; `withVncBindAddress` is an explicit opt-in |

---

## 16. Assumptions to confirm

1. §0 values are examples; replace them at adoption.
2. `${ASSERTJ_VERSION}` was not checked against release pages; the other versions in §0 were checked on 2026-10-18. Use the current versions when adopting.
3. Maximum screens = 4 and default hot-plug ports = 4 are design choices; only 2 screens and 4 ports were exercised (T6 exercises 4 screens).
4. `${ART_JUNIT}` is optional.
5. JUnit tags cannot be set dynamically through a meta-annotation, so `@Requirement` does not create JUnit tags; this is by design (REQUIREMENTS.md §3.1).
6. **The Maven publishing destination is undecided** (Maven Central / GitHub Packages / internal). The plan configures `maven-publish` for `publishToMavenLocal` only; PLAN-STEPS.md step 6.7 is a placeholder until the decision is made.

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

**Sandbox-only evidence** (Docker 29.8, TCG):
- `tini` reaping (T24);
- dual QMP monitors and single TCP client;
- exclusive VNC client vs `force-shared`;
- `incremental=false`;
- `guest-info` `enabled:false`;
- guest-exec PATH;

---

## 18. In Summary

- **Design:** `QemuVmContainer` runs one QEMU VM per Podman container on a UBI 9.8 runner image. QEMU and `gstreamer1-plugins-good` come from a pinned Rocky 9.8 fallback; `tini` comes from EPEL. Java drives QEMU through QMP and the guest through QGA, over loopback-published TCP ports.
- **Verified on GitHub-hosted runners** with real KVM (Podman 4.9.3 and 5.7.0) and TCG:
  - exec and copy;
  - per-screen screenshots and VNC (exposed, unauthenticated, loopback by default);
  - per-screen GStreamer WebM recording;
  - WAV audio;
  - keyboard;
  - hot-plug of USB, PCIe disk/NIC, CD, memory, and vCPU (KVM only).
- **Images:** every source ends up as a cached, read-only base disk plus a per-start overlay. Sources: host paths, `http(s)://` (optional SHA-256, credentials, gz/xz/zst), and `oci://` containerDisk images. OCI images are pulled by the engine with engine or explicit credentials, then extracted. No ORAS SDK. A pre-customized Rocky 9 guest is shipped as a containerDisk; the pipeline is verified, and that guest is ready about 11 s after start under KVM.
- **Rules that matter** (§1 a–l): `tini`; `incremental=false`; validate recordings by file; `force-shared`; unwrap `ContainerLaunchException` in `start()`; guest RPC filter **and** SELinux permissive domain; the keep-groups **annotation**, never `GroupAdd`; internal QMP socket; builder beats overrides; in-JVM lock beside the file lock; `PublishAllPorts=false`.
- **Verification:** 122 requirement stories (3 retired) in REQUIREMENTS.md, each with evidence attached to a per-job Allure report; the build enforces that every test names the requirement it proves.
- **Open:**
  - an SELinux-host acceptance run (R3);
  - UEFI paths (T22).
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
