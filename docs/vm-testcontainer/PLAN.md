# QEMU VM Testcontainer: Implementation Plan

> **Audience:** the agent (or engineer) who will implement this module in a **different repository**. Everything needed is in this document. Appendix A inlines every file you must copy verbatim.
> **Status:** final plan. Behaviour marked **[V]** was verified by an experiment; the evidence is in §17.
> **Scope:** x86-64 hosts and Linux guests only.

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
| `${GRADLE_VERSION}` | `9.1.0` | Gradle wrapper (verify it is current when adopting) | `gradle/wrapper/gradle-wrapper.properties` |
| `${TESTCONTAINERS_VERSION}` | `2.0.5` | `org.testcontainers:testcontainers` **[V]** | `gradle/libs.versions.toml` |
| `${JUNIT_VERSION}` | `5.12.2` | JUnit BOM **[V]** | `gradle/libs.versions.toml` |
| `${JACKSON_VERSION}` | `2.20.0` | jackson-databind (verify it is current when adopting) | `gradle/libs.versions.toml` |
| `${COMMONS_COMPRESS_VERSION}` | `1.27.1` | commons-compress (tar extraction) **[V]** | `gradle/libs.versions.toml` |
| `${SLF4J_VERSION}` | `2.0.17` | slf4j-api / slf4j-simple (tests) **[V]** | `gradle/libs.versions.toml` |
| `${ASSERTJ_VERSION}` | `3.27.3` | AssertJ (tests; verify it is current when adopting) | `gradle/libs.versions.toml` |
| `${UBI_IMAGE}` | `registry.access.redhat.com/ubi9/ubi:9.8` | Runner base image **[V]** | `gradle.properties` → `ubiImage` (passed as `--build-arg UBI_IMAGE`) |
| `${ROCKY_RELEASE}` | `9.8` | Rocky fallback repo release **[V]** | `images/runner/repos/rocky.repo` (literal in `baseurl`; this file is its only home) |
| `${GUEST_BASE_URL}` | `https://dl.rockylinux.org/pub/rocky/9/images/x86_64/Rocky-9-GenericCloud-Base-9.8-20260525.0.x86_64.qcow2` | Reference guest source image **[V]** | `images/guest/guest.properties` → `baseUrl` |
| `${GUEST_BASE_SHA256}` | `92c206cc6f790c61583247eefe87890f8828420662c17cacf247cec78ab4eec8` | SHA-256 of the image above **[V]** | `images/guest/guest.properties` → `baseSha256` |

**Fixed protocol strings.** These are **not** parameters; they are identical in every adoption:

- Log markers: `VMTC_READY`, `VMTC_ERROR`, `VMTC_ACCEL`, `VMTC_QEMU_STARTING`, `VMTC_REC_*`, `VMTC_SEED_DONE`.
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

It fails if any appears outside its *Lives in* file and generated sources.

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
| One VNC server per screen | exposed to library users |
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
| URL + SHA-256 | `VmImage.fromUrl` |
| containerDisk image, extracted through the Docker API | `VmImage.fromContainerDisk` **[V]** |
| Disk baked into a derived runner image | `VmImage.baked()` |

A pre-customized Rocky 9 reference guest is shipped as a containerDisk (§10.3).

**Rules the implementer must not get wrong:**

| # | Rule | Why |
|---|------|-----|
| a | Run `tini` as PID 1 | QEMU does not reap orphans, so recorders become zombies otherwise **[V]** |
| b | Use `rfbsrc incremental=false` | Static screens otherwise produce no frames **[V]** |
| c | Judge a recording by validating its file | `gst-launch` exits 1 after a normal SIGINT stop **[V]** |
| d | Use VNC `share=force-shared` | An exclusive client otherwise disconnects recorders **[V]** |
| e | Set VNC passwords per display with QMP `set_password` | `change-vnc-password` sets only the default display **[V]** |
| f | Guests need the QGA RPC filter relaxed **and** `semanage permissive -a virt_qemu_ga_t` | SELinux otherwise blocks guest-exec **[V]** |
| g | Secondary screens are dark until the guest modesets them | Expected; plan for it **[V]** |
| h | Group-restricted `/dev/kvm` (`0660`) needs annotation `run.oci.keep_original_groups=1`. Never use `GroupAdd keep-groups` | keep-groups breaks container start **[V]** |
| i | QMP TCP accepts **one** client at a time, so the entrypoint uses a second, internal QMP socket | **[V]** |

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
13. Test plan and acceptance criteria
14. Implementation milestones
15. Limits, risks, open items
16. Assumptions to confirm
17. Evidence index
18. In Summary
- Appendix A: files to copy verbatim

---

## 2. Requirements and decisions

| # | Requirement / decision | Where |
|---|------------------------|-------|
| R1 | Container engine **Podman**; rootless is first-class, rootful works | §9.6, §12 |
| R2 | Images based on **UBI 9.8** | §6 |
| R3 | Run commands in the VM | §9.4 |
| R4 | Copy files to and from the VM | §9.5 |
| R5 | Run QEMU images (qcow2, raw) | §8, §9.3 |
| R6 | Screenshots, per screen | §9.7 |
| R7 | Audio recording, **separate WAV** files | §9.9 |
| R8 | Screen recording: **GStreamer records each screen's VNC; one video per screen** | §7.2, §9.8 |
| R9 | **VNC for display output, exposed to library users** | §8.3, §9.7 |
| R10 | Hot-plug simulation | §9.10 |
| R11 | Repos: UBI first, then EPEL, with Rocky as fallback | §6.2 |
| R12 | Use `/dev/kvm` when available; work without it | §8.2, §9.6 |
| D1 | x86-64 only | — |
| D2 | **Linux guests only** | §10 |
| D3 | Reference test guest: **Rocky Linux 9** | §10.3 |
| D4 | Build tool: **Gradle** | §5, §11 |
| D5 | Screens: **1..N configurable**, default 1, maximum 4 | §8.3 |
| D6 | Runner variants: **slim** (no GStreamer) and **media** (GStreamer) | §6.1 |
| D7 | SELinux guests: `semanage permissive -a virt_qemu_ga_t` | §10.1 |

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
├─ settings.gradle               rootProject.name='${ROOT_PROJECT}'; include 'lib','junit5','images','it'
├─ gradle.properties             group, version, artifactLib, artifactJunit, basePackage, imageRegistry,
│                                imageRunnerName, imageGuestName, imageTagSuffix, labelPrefix, syspropPrefix,
│                                envPrefix, cacheDirName, javaRelease, ubiImage      (§0)
├─ gradle/libs.versions.toml     testcontainers, junit-bom, jackson-databind, commons-compress, slf4j, assertj
├─ gradle/wrapper/…              ${GRADLE_VERSION}
├─ lib/        (${ART_LIB})      java-library + maven-publish + generateConstants (§11.3)
│  ├─ src/main/java/<${BASE_PACKAGE} as path>/…          (§9.1)
│  ├─ src/main/resources/<${BASE_PACKAGE} as path>/display/vmtc-fb-activate.py   (Appendix A.4)
│  └─ src/test/java/…            unit tests (no container engine)
├─ junit5/     (${ART_JUNIT})    @VmTest extension (§9.13)
├─ images/
│  ├─ runner/  Containerfile (§6.3), repos/epel.repo, repos/rocky.repo (A.1, A.2),
│  │           scripts/vmtc-entrypoint (§7.1), scripts/vmtc-rec (§7.2)
│  ├─ guest/   guest.properties, customize.sh (§10.3), seed-template/ (§10.2)
│  └─ build.gradle   buildRunnerSlim, buildRunnerMedia, verifyRunnerImages, buildGuestDisk, pushImages (§11.2)
└─ it/                           integration tests against real Podman (§13); not published
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

### 6.3 Containerfile (normative; `images/runner/Containerfile`)

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
EXPOSE 4444 4445 5900 5901 5902 5903
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/vmtc-entrypoint"]
```

Notes:

- All `ARG`s are passed by Gradle (§11.2): `UBI_IMAGE`=`${UBI_IMAGE}`, `LABEL_PREFIX`=`${LABEL_PREFIX}`, `IMAGE_TITLE`=`${IMAGE_RUNNER_NAME}`.
  - `rpm --import` and `dnf install` were verified with the same repos via `install-packages.sh` **[V]**.
  - The `LABEL` key containing an ARG is checked by §6.4 check 6.
- The `rpm -q redhat-release` line is a guard: the build fails if a Rocky release package sneaks in.
- `tini` without `-g`: SIGTERM reaches `vmtc-entrypoint`, which shuts QEMU down through the internal QMP socket (§7.1 step 10).
- The container runs as root inside. Under rootless Podman, that is the invoking host user.

### 6.4 Image verification (`verifyRunnerImages`)

Run each check inside every built image; all must succeed:

1. `/usr/libexec/qemu-kvm -accel help | grep -qx tcg`
2. `/usr/libexec/qemu-kvm -device help` lists every device in §3.2 (rows *Display*, *Sound*, *Hot-plug*).
3. **media only:** `gst-inspect-1.0 <e>` succeeds for `rfbsrc`, `vp8enc`, `webmmux`, `videorate`, `videoconvert`.
4. `rpm -q redhat-release` succeeds, and `rpm -qa 'rocky-*'` is empty.
5. `test -x /usr/bin/tini`
6. On the host: `podman image inspect --format '{{index .Labels "<labelPrefix>.variant"}}' <image>` equals the variant.

---

## 7. In-container scripts

### 7.1 `vmtc-entrypoint` (bash, `set -euo pipefail`)

**Inputs:** environment variables, all set by Java.

| Env var | Default | Meaning |
|---------|---------|---------|
| `VM_ACCEL` | `auto` | `kvm`, `tcg` or `auto` (auto = `kvm` if `/dev/kvm` is readable and writable, else `tcg`) |
| `VM_CPUS` | `2` | Boot vCPUs |
| `VM_MAXCPUS` | `max(4, VM_CPUS)` | Hot-plug ceiling |
| `VM_MEM_MB` | `2048` | Boot RAM |
| `VM_MEM_SLOTS` | `4` | DIMM slots |
| `VM_MAXMEM` | `8G` | Hot-plug memory ceiling |
| `VM_SCREENS` | `1` | 1..4 heads |
| `VNC_BIND` | `0.0.0.0` | Bind inside the container |
| `VNC_PASSWORD_ENABLED` | `0` | `1` → `password=on` on every VNC server |
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
   `xorriso -as mkisofs -quiet -V cidata -J -r -o /vm/run/seed.iso /vm/seed/user-data /vm/seed/meta-data` (also `/vm/seed/network-config` if present).
5. Resolve the accelerator:
   - `VM_ACCEL=kvm` and `/dev/kvm` is not readable and writable → print `VMTC_ERROR kvm requested but /dev/kvm unusable` and exit 3.
   - Print `VMTC_ACCEL kvm|tcg`.
6. If `VM_FIRMWARE=uefi`: `cp /usr/share/edk2/ovmf/OVMF_VARS.fd /vm/run/OVMF_VARS.fd`
7. Build the QEMU argument array exactly as in §8.1. Print `VMTC_QEMU_STARTING <epoch>`.
8. Start QEMU **in the background**. Poll every 50 ms, for up to 30 s, until `(exec 3<>/dev/tcp/127.0.0.1/4445)` succeeds.
   - This uses the QGA port, **not** the QMP port, so it never occupies the single QMP TCP client slot.
   - If QEMU exits first: print `VMTC_ERROR qemu exited <code>` and exit 4.
9. Print `VMTC_READY`, then `wait` on QEMU and exit with its status.
10. `trap` on TERM/INT:
    - Run `printf '{"execute":"qmp_capabilities"}\n{"execute":"quit"}\n' | socat -t2 - UNIX-CONNECT:/vm/run/qmp-internal.sock` **[V]**.
    - Wait up to 10 s for QEMU. If it is still running, `kill -9` it.

**Graceful shutdown happens only through Java `stop()`** (§9.2): it stops open recordings, sends QMP `quit` over the Java connection, then lets Testcontainers stop the container. The trap only covers a manual `podman stop`.

### 7.2 `vmtc-rec` (bash; media image only)

```
vmtc-rec start  <name> <screen-index> <fps> <bitrate-bps>      # env VNC_PASSWORD optional
vmtc-rec stop   <name> [timeout-seconds=10]
vmtc-rec status <name>
```

**`start`:**

1. Run detached (`setsid … < /dev/null > /vm/out/<name>.log 2>&1 &`):

   ```
   gst-launch-1.0 -q -e rfbsrc host=127.0.0.1 port=$((5900+i)) view-only=true shared=true incremental=false ${VNC_PASSWORD:+password=$VNC_PASSWORD} \
     ! videorate ! video/x-raw,framerate=<fps>/1 ! videoconvert \
     ! vp8enc deadline=1 cpu-used=8 threads=2 target-bitrate=<bitrate> ! webmmux ! filesink location=/vm/out/<name>.webm
   ```

2. Write the PID to `/vm/run/<name>.pid` and print `VMTC_REC_STARTED <pid>`.

This is the verified `gst-record.sh` + `rec.sh` **[V]**.

**`stop`:**

1. `kill -INT <pid>`.
2. Poll every 100 ms. The process counts as alive only if `/proc/<pid>` exists **and** its `State:` is not `Z`. Give up after the timeout.
3. If it is still alive: `kill -9` it and print `VMTC_REC_KILLED`.
4. Validate with `gst-discoverer-1.0 /vm/out/<name>.webm`:
   - On success, print `VMTC_REC_OK duration=<seconds> width=<w> height=<h>`.
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
     -vnc ${VNC_BIND}:$i,id=vnc$i,display=video$i,share=force-shared[,password=on]
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
| `password=on` | not set (verified separately with an otherwise identical line [V]) | `[,password=on]` |
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

### 8.3 Screens and VNC (D5, R9)

- `withScreens(n)`, n = 1..4 (default 1). Head 0 is `VGA`; heads 1..n-1 are `secondary-vga` **[V]** (n=2).
- **Ports:** head *i* has a VNC server on container port `5900+i`, published per §9.2 step 4.
- **Sharing:** `share=force-shared` is always on.
- **Password:**
  - `withVncPassword(pw)` adds `password=on`.
  - After `VMTC_READY`, Java calls `set_password {protocol:"vnc", password, display:"vnc<i>"}` for **every** i **[V]**.
  - Recorders receive the password through env `VNC_PASSWORD` (§9.8).
- **Password limits:** VNC auth uses at most 8 characters [D]. Java rejects longer passwords with `IllegalArgumentException`. Passwords are also restricted to printable ASCII without whitespace, because they are passed through a GStreamer pipeline string.
- **Dark secondary heads:** until the guest modesets them, they show "Guest has not initialized the display (yet)" at 640×480 [V]. Use `VmScreen.forceModeset()` for console-only guests.

### 8.4 UEFI (`VM_FIRMWARE=uefi`) **[U]**

```
-drive if=pflash,format=raw,unit=0,readonly=on,file=/usr/share/edk2/ovmf/OVMF_CODE.fd
-drive if=pflash,format=raw,unit=1,file=/vm/run/OVMF_VARS.fd
```

- The file names come from `edk2-ovmf` and were not exercised. M2 acceptance test T22 settles them.
- If they differ, take the paths from `rpm -ql edk2-ovmf` in the image and update this section and the entrypoint together.

---

## 9. Java library design

### 9.1 Packages, types and runtime overrides

| Package | Types |
|---------|-------|
| `${BASE_PACKAGE}` | `QemuVmContainer`, `VmImage`, `KvmMode`, `Accelerator`, `RunnerVariant`, `Firmware`, `ExecRequest`, `GuestExecResult`, `VmtcConstants` (generated), `VmtcCleanup`, `VmtcException` and its subclasses `KvmUnavailableException`, `GuestAgentTimeoutException`, `GuestCommandDisabledException`, `GuestAgentConfinedException`, `GuestExecTimeoutException`, `RecordingException`, `HotplugTimeoutException`, `ImageChecksumException` |
| `${BASE_PACKAGE}.qmp` | `QmpClient`, `QmpEvent`, `QmpException` (extends `VmtcException`) |
| `${BASE_PACKAGE}.qga` | `GuestAgentClient` |
| `${BASE_PACKAGE}.image` | `VmImageCache`, `ContainerDiskExtractor`, `ImageDownloader` |
| `${BASE_PACKAGE}.display` | `VmScreen`, `VncEndpoint`, `ScreenRecording`, `RecordingOptions`, `KeyboardInput` |
| `${BASE_PACKAGE}.audio` | `AudioRecording` |
| `${BASE_PACKAGE}.hotplug` | `Devices`, `DeviceSpec` (sealed), `DiskBus` (`USB`, `VIRTIO`), `HotplugHandle`, `CdromDrive` |
| `${BASE_PACKAGE}.cloudinit` | `CloudInitSeed` |
| `${BASE_PACKAGE}.internal` | `KvmDetector`, `VmWaitStrategy`, `SerialLogTailer`, `PortBindings`, `Json`, `Backoff`. Not exported: `module-info` exports every package above except `internal` |

**Dependencies:**

| Scope | Dependencies |
|-------|--------------|
| `api` | `org.testcontainers:testcontainers:${TESTCONTAINERS_VERSION}` |
| `implementation` | `jackson-databind:${JACKSON_VERSION}`, `commons-compress:${COMMONS_COMPRESS_VERSION}` |
| `testImplementation` | JUnit BOM, AssertJ, `slf4j-simple:${SLF4J_VERSION}` |

**Runtime overrides.** Each key exists as system property `-D${SYSPROP_PREFIX}.<key>` and as environment variable `${ENV_PREFIX}<KEY_IN_UPPER_SNAKE>`, for example `-Dvmtc.runnerImageMedia` or `VMTC_RUNNER_IMAGE_MEDIA`.

**Precedence:** system property > environment variable > builder call > default.

| Key | Values | Default | Effect |
|-----|--------|---------|--------|
| `kvm` | `REQUIRE`, `PREFER`, `DISABLE` (case-insensitive) | `PREFER` | Overrides `withKvm` |
| `cacheDir` | absolute path | `${XDG_CACHE_HOME:-~/.cache}/${CACHE_DIR_NAME}` | Image cache root |
| `cleanup` | `true`, `false` | `true` | Orphan cleanup (§9.12) |
| `runnerImage` | image ref | `VmtcConstants.defaultRunnerImage(SLIM)` | Slim runner |
| `runnerImageMedia` | image ref | `VmtcConstants.defaultRunnerImage(MEDIA)` | Media runner |
| `guestImage` | image ref | `VmtcConstants.referenceGuestImage()` | Used by `VmImage.referenceGuest()` |
| `tcgTimeoutFactor` | double > 0 | `6.0` | Overrides `withTcgTimeoutFactor` |

### 9.2 `QemuVmContainer` (public API, normative)

```java
public class QemuVmContainer extends GenericContainer<QemuVmContainer> {
  public QemuVmContainer();                                     // RunnerVariant.SLIM
  public QemuVmContainer(RunnerVariant variant);                // SLIM | MEDIA (image per §9.1 overrides)
  public QemuVmContainer(DockerImageName runnerImage);          // custom/derived runner

  // configuration (each returns this)
  withVmImage(VmImage image)                    // REQUIRED. For derived runner images pass VmImage.baked()
  withKvm(KvmMode mode)                         // default PREFER
  withCpus(int boot) / withMaxCpus(int max)     // defaults 2 / max(4, boot)
  withMemoryMb(int mb) / withMemoryHotplug(int slots, String maxmem)   // 2048 / 4, "8G"
  withScreens(int n)                            // 1..4, default 1
  withVncBindAddress(String hostIp)             // default "127.0.0.1"
  withVncPassword(String pw)                    // 1..8 printable ASCII, no whitespace
  withHotplugPorts(int n)                       // 0..8, default 4
  withFirmware(Firmware f)                      // BIOS (default) | UEFI
  withCloudInit(CloudInitSeed seed)             // optional (§10.2)
  withGuestAgentTimeout(Duration d)             // default 180 s, ×tcgTimeoutFactor under TCG
  withExecTimeout(Duration d)                   // default 60 s, ×tcgTimeoutFactor under TCG
  withTcgTimeoutFactor(double f)                // default 6.0
  withSerialLogConsumer(Consumer<String> line)  // receives every serial-log line (§9.2 step 13)
  withQemuArgs(String... extra)                 // → VM_EXTRA_ARGS; any arg with whitespace → IllegalArgumentException

  // state
  Accelerator accelerator();                    // KVM | TCG, from VMTC_ACCEL
  QmpClient qmp();
  GuestAgentClient guestAgent();
  KeyboardInput keyboard();

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
  Path saveSerialLog(Path hostFile);
}
```

**Start sequence** (`configure()`, `containerIsStarting`, `containerIsStarted`):

1. **Image:** resolve the `VmImage` to a cache file (§9.3), unless it is `baked()`.
2. **Bind** the cache file read-only at `/vm/base/disk.img`. Use a docker-java `Bind` with `AccessMode.ro`.
   - On SELinux-enforcing hosts (`/sys/fs/selinux/enforce` contains `1`), add `SELContext.shared` (`:z`). Only cache files are ever bound (§9.3.2).
3. **KVM:** decide per §8.2 / §9.6, then apply `HostConfig.withDevices(...)` and, if needed, `withAnnotations(...)` in `withCreateContainerCmdModifier`.
4. **Ports:**
   - Call `withExposedPorts(4444, 4445, 5900, …, 5900+n-1)`.
   - In the create-cmd modifier, replace `HostConfig.PortBindings` with `PortBinding(Ports.Binding.bindIp(addr), ExposedPort.tcp(p))` for each port, which keeps a random host port.
   - `addr` is `127.0.0.1` for 4444 and 4445, and the configured VNC address for VNC ports.
5. **Labels:**
   - `${LABEL_PREFIX}.managed=true`
   - `${LABEL_PREFIX}.version=<VmtcConstants.VERSION>`
   - `${LABEL_PREFIX}.session=<UUID created once per JVM>`
   - `${LABEL_PREFIX}.created=<epoch seconds>`
6. **Env:** set §7.1 env. For a seed, add `withCopyToContainer(Transferable.of(bytes), "/vm/seed/user-data")` (also `meta-data`).
7. **Wait strategy:** use `VmWaitStrategy` (custom `AbstractWaitStrategy`). Every 200 ms it reads the container logs:

   | Condition | Result |
   |-----------|--------|
   | `VMTC_READY` | ready |
   | `VMTC_ERROR <msg>` or container exited, with exit code 3 or a message containing `kvm requested` | throw `KvmUnavailableException(msg)` |
   | `VMTC_ERROR <msg>` or container exited, otherwise | throw `VmtcException(msg + last 50 log lines)` |
   | 60 s elapsed (**not** scaled by the TCG factor) | timeout |

8. **QMP:** connect to the mapped port 4444 and send `qmp_capabilities`. Parse `VMTC_ACCEL` from the logs and apply the PREFER WARN rule (§8.2).
9. **VNC password:** if configured, call `set_password` for each display.
10. **QGA readiness:** repeat until the guest-agent timeout. Each attempt:
    1. TCP connect to the mapped port 4445 with a 3 s socket timeout.
    2. `guest-sync-delimited {id}`: skip a leading `0xFF` byte and read until `return == id`.
    3. `guest-ping`.

    If any step fails or times out: close the socket, sleep 1 s, and retry.

    On overall timeout, throw `GuestAgentTimeoutException` with the last 50 serial-log lines. This is the verified `vmctl.py wait-qga` behaviour [V].
11. **RPC filter check:** call `guest-info`. If `guest-exec` or `guest-file-open` has `enabled:false`, throw `GuestCommandDisabledException` [V].
    - Independently, any QGA error with class `CommandNotFound` and a desc containing `has been disabled` is mapped to `GuestCommandDisabledException` [V].
    - The message points to §10.1 G3/G4.
12. **SELinux check:** `exec("/usr/bin/id","-Z")`. If stdout contains `virt_qemu_ga_t`:
    - Run `execShell("ls /lib/modules/$(uname -r) >/dev/null")`.
    - Non-zero exit with stderr containing `Permission denied` means the domain is enforcing: throw `GuestAgentConfinedException` (remediation G4). This exact denial was observed **[V]**.
13. **Serial log:** if a consumer is set or a seed is configured, start `SerialLogTailer`.
    - It is a daemon thread that polls every 1 s: `execInContainer("tail","-c","+<offset+1>","/vm/out/serial.log")`.
    - It tracks the byte offset, strips `\r`, splits on `\n`, and keeps partial last lines.
    - With a seed, `start()` additionally blocks until a line contains `VMTC_SEED_DONE` (guest-agent timeout). On timeout it throws `GuestAgentTimeoutException`.

**`stop()`:**
1. Stop every open `ScreenRecording` and `AudioRecording` (best effort, log failures).
2. QMP `quit` with a 5 s timeout; ignore errors.
3. Close the clients.
4. `super.stop()`.

### 9.3 VM images

```java
public sealed interface VmImage {
  static VmImage fromPath(Path file);                          // qcow2 or raw
  static VmImage fromUrl(URI url, String sha256);              // sha256 required (lower-case hex)
  static VmImage fromContainerDisk(DockerImageName image);     // KubeVirt containerDisk: exactly one file under /disk/
  static VmImage referenceGuest();                             // fromContainerDisk(override 'guestImage' or VmtcConstants.referenceGuestImage())
  static VmImage baked();                                      // runner image already contains /vm/base/disk.img
}
```

#### 9.3.1 Cache (`VmImageCache`)

- **Root:** `<cacheDir>/images/`, where `cacheDir` comes from §9.1. Entries are named `<key>.img`.
- **Concurrency:** writers take `FileChannel.lock()` on `<key>.lock`, write `<key>.tmp-<pid>-<nanotime>`, then `Files.move(…, ATOMIC_MOVE)`.
- **`fromPath`:**
  - `key` = SHA-256 of the file content.
  - The key is memoized in `<cacheDir>/index.json` by `(absolutePath, size, mtimeMillis)`.
  - If `<key>.img` is missing, **copy** the file (`Files.copy`). **Never hard-link**: the `:z` relabel and later edits of the user's file must not touch the cache entry.
- **`fromUrl`:**
  - `key` = the given sha256.
  - Download with `java.net.http.HttpClient` (follow redirects; honours JVM proxy properties) to the tmp file while hashing, verify, then move.
  - On mismatch: delete the tmp file and throw `ImageChecksumException`.
- **`fromContainerDisk`:** `key` = `sha256(imageId + "/" + fileName)` (§9.3.3).
- **Eviction:** none automatic. Public helper: `VmImageCache.prune(Duration olderThan)`, which deletes entries whose last-modified time is older than the given duration. Entries are touched on every use.

#### 9.3.2 Why every source goes through the cache

- **Speed:** a bind plus overlay means O(1) start and per-start disposable state.
- **SELinux:** relabeling (`:z`) touches only library-owned files.
- **Simplicity:** one in-container path, `/vm/base/disk.img`.

#### 9.3.3 containerDisk extraction (`ContainerDiskExtractor`; uses the Testcontainers `DockerClient`, no CLI)

1. `String imageId = new RemoteDockerImage(image).get()`. That resolves the image and applies Testcontainers' pull policy [D].
2. `id = createContainerCmd(image).withCmd("/none").exec().getId()` creates without starting **[V]**.
3. `copyArchiveFromContainerCmd(id, "/disk/")` → tar stream.
   - Skip directory entries.
   - Require **exactly one** regular-file entry, else throw `IllegalArgumentException("containerDisk must contain exactly one file under /disk/")`.
   - Stream that entry into the cache tmp file; its base name is `fileName`.
   - Status: directory copy **[V]** on Docker 29.8, Podman 4.9.3 and 5.7.0 (run 5).
4. `removeContainerCmd(id).withForce(true)` in `finally`.

#### 9.3.4 `baked()`

- The user builds `FROM <runner>` plus `COPY disk.qcow2 /vm/base/disk.img`.
- No bind. Intended for remote engines and podman-machine setups.

### 9.4 Exec (R3)

**`GuestAgentClient` protocol:**
- Newline-delimited JSON over TCP.
- Resync with `guest-sync-delimited` on every (re)connect [V].
- One request in flight, guarded by a `ReentrantLock`.
- On I/O error: reconnect once, then fail.

**Exec:**
1. `guest-exec {path, arg, env?, input-data? (base64), capture-output: true}` → `pid`.
2. Poll `guest-exec-status {pid}`, starting at 50 ms and backing off ×1.5 to a 500 ms cap, until `exited` or the timeout.
3. On timeout: best-effort `guest-exec {path:"/bin/kill", arg:["-9", pid]}`, then throw `GuestExecTimeoutException`.
4. Return `GuestExecResult(int exitCode, Integer signal, String stdout, String stderr, boolean stdoutTruncated, boolean stderrTruncated)`, decoding base64 as UTF-8 with replacement.

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
  ScreenRecording startRecording(Path hostWebm, RecordingOptions opts);   // fps 1..30 (default 10), bitrate bps (default 1_000_000)
  void forceModeset();
}
public record VncEndpoint(String host, int port, Optional<String> password) { public String uri() { return "vnc://" + host + ":" + port; } }
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
  1. Load classpath resource `<${BASE_PACKAGE} as path>/display/vmtc-fb-activate.py` (Appendix A.4).
  2. `copyToVm(bytes, "/tmp/vmtc-fb-activate.py")`.
  3. `exec("/usr/bin/python3","/tmp/vmtc-fb-activate.py","/dev/fb"+index)`. A non-zero exit throws `VmtcException`.

  For index 0 it is a no-op. Guest `python3` is present in Rocky GenericCloud [V].
- **`type(text)`:**
  - One QMP `send-key {keys:[{type:"qcode",data:…}…]}` per character, with **30 ms** between characters [V].
  - Mapping per Appendix A.3: letters (shift for upper case), digits, US punctuation; `\n` → `ret`, space → `spc`.
  - Any other character → `IllegalArgumentException`.
- **`press(qcodes…)`:** a single `send-key` with all qcodes held together, for example `press("ctrl","alt","f2")`.

### 9.8 Screen recording (R8)

- **Naming:** `name` = `screen<i>-<uuid8>`.
- **Start:**
  - `execInContainer(ExecConfig.builder().command(new String[]{"/usr/local/bin/vmtc-rec","start",name,i,fps,bitrate}).envVars(passwordOrEmptyMap).build())` [D].
  - Never put the password on the command line.
  - Expect `VMTC_REC_STARTED`.
- **`ScreenRecording`** (`AutoCloseable`):
  - `close()` runs `vmtc-rec stop <name> 10`.
    - `VMTC_REC_OK` → `copyFileFromContainer("/vm/out/<name>.webm", hostWebm)`.
    - `VMTC_REC_INVALID` → `RecordingException` with the log tail.
    - `VMTC_REC_KILLED` followed by `VMTC_REC_OK` → success plus a WARN.
  - Accessors: `Path file()`, `Duration duration()`, `int width()`, `int height()`, parsed from `VMTC_REC_OK`.
- **Concurrency:** any number of recordings, including two on one screen (separate VNC clients under force-shared [V]).
- **Slim image:** throws per §6.1.

### 9.9 Audio recording (R7, separate WAV)

1. `startAudioRecording(path)`: HMP via QMP `human-monitor-command`: `wavcapture /vm/out/audio-<uuid8>.wav snd0 44100 16 2`.
2. HMP `info capture`: find the line ending in that path and parse `[<n>]` [V]. If parsing fails and exactly one capture line exists, use its index.
3. `close()`: `stopcapture <n>`, copy the file out, and validate it (RIFF/WAVE header, `data` size > 0).
4. Only one capture at a time.

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
public interface HotplugHandle extends AutoCloseable { String id(); void unplug(Duration timeout); @Override void close(); }
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

- **`close()`:** calls `unplug(defaultTimeout)` where supported.
- **Default unplug timeout:** 30 s (×TCG factor). On timeout, throw `HotplugTimeoutException("guest did not release <id>")`.
- **Measured [V]:** guest visibility ≤ 3.2 s (USB, TCG) and ≤ 0.5 s (PCIe); unplug ≤ 0.4 s.

### 9.11 Errors

- Every exception is unchecked and extends `VmtcException`.
- `QmpException` carries the QMP `class` and `desc`.

### 9.12 Cleanup without Ryuk

- **Ryuk:** on rootless Podman, Ryuk must be disabled (`TESTCONTAINERS_RYUK_DISABLED=true`) [V].
- **`VmtcCleanup.removeOrphans()`:**
  - Lists containers labelled `${LABEL_PREFIX}.managed=true` whose `${LABEL_PREFIX}.session` differs from the current one **and** whose `${LABEL_PREFIX}.created` is more than 1 h old.
  - Force-removes them.
  - Runs once per JVM on the first `start()`, unless the `cleanup` override is `false`.

### 9.13 `${ART_JUNIT}` (optional)

- **`@VmTest`** (on a class) registers `VmArtifactsExtension`.
- **On test failure** (`TestWatcher.testFailed`), for every **running** `QemuVmContainer` found in the test instance's fields and the class's static fields (reflection, including superclasses):
  1. Stop each open `ScreenRecording` and `AudioRecording`, which finalizes and copies them.
  2. Save the serial log.
  3. Take one screenshot per screen.

  Output goes to `build/vm-artifacts/<class>/<method>/`.
- **Ordering:** artifacts are written in `testFailed`, which runs before `@AfterEach` and container shutdown.
- To support that, `QemuVmContainer` keeps a registry of its open recordings.

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

**`CloudInitSeed.forStockRocky()`** brings a stock Rocky 9 GenericCloud image up to G2–G6. It is the verified `make-seed.sh` content, in this order **[V]**:

```yaml
#cloud-config
runcmd:
  - sh -c "dnf -y install alsa-utils > /var/log/seed-dnf.log 2>&1; echo DNF_RC=\$? > /dev/ttyS0; tail -5 /var/log/seed-dnf.log > /dev/ttyS0"
  - update-ca-trust
  - sed -i 's/^FILTER_RPC_ARGS=.*/FILTER_RPC_ARGS=""/' /etc/sysconfig/qemu-ga
  - semanage permissive -a virt_qemu_ga_t
  - systemctl enable qemu-guest-agent
  - systemctl restart qemu-guest-agent
  - amixer -c 0 sset Master 90% unmute || true
  - amixer -c 0 sset PCM 90% unmute || true
  - echo VMTC_SEED_DONE > /dev/ttyS0
```

**Builder API:**

| Method | Effect |
|--------|--------|
| `packages(String…)` | Emitted as the **first** runcmd line, in exactly the `sh -c "dnf …"` form above. Needs guest network. `DNF_RC≠0` is logged as WARN, not failure: in one run dnf reported failure yet the package was installed [V] |
| `runcmd(String…)` | Appended after the built-ins |
| `writeFile(path, content)` | → `write_files` |
| `rootPassword(pw)` | → `chpasswd: {expire: false, users: [{name: root, password: pw, type: text}]}` |
| `trustCaFile(Path pem)` | → `write_files` to `/etc/pki/ca-trust/source/anchors/` (for proxied networks) |

- The `VMTC_SEED_DONE` line is always last.
- `meta-data` is `instance-id: <uuid>` and `local-hostname: vm`.

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
   timeout 1800 <engine> run --rm [--device /dev/kvm] -e VM_ACCEL=<accel> \
     -v <base>:/vm/base/disk.img:ro[,z] -v <workdir>/run:/vm/run[:z] -v <workdir>/seed:/vm/seed:ro[,z] <runner-slim-image>
   ```

   Then require `VMTC_CUSTOMIZE_DONE` in the serial log. The production entrypoint builds the seed ISO from `/vm/seed`.
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

---

## 11. Build (Gradle)

### 11.1 Conventions

- Java toolchain `${JAVA_RELEASE}`. javac `-Xlint:all,-processing -Werror`.
- Formatting: adopt the target repo's formatter, if it has one.
- `:lib` unit tests need no engine.
- `:it` holds the integration tests, run by the `integrationTest` task:
  - `dependsOn :images:verifyRunnerImages, :images:buildGuestDisk`;
  - JUnit tags `kvm`, `tcg`, `any`;
  - forwards `-D${SYSPROP_PREFIX}.kvm` from the Gradle command line to the test JVM (`systemProperty "${syspropPrefix}.kvm", findProperty(...)`).

### 11.2 `:images` tasks (all `Exec` with declared inputs/outputs; engine from `-PcontainerEngine`, default `podman`)

| Task | Command |
|------|---------|
| `buildRunnerSlim` | `<engine> build -f images/runner/Containerfile --build-arg UBI_IMAGE=<ubiImage> --build-arg VARIANT=slim --build-arg VERSION=<version> --build-arg LABEL_PREFIX=<labelPrefix> --build-arg IMAGE_TITLE=<imageRunnerName> -t <imageRegistry>/<imageRunnerName>:<version>-<imageTagSuffix> images/runner` |
| `buildRunnerMedia` | same with `VARIANT=media`, tag suffix `-media` |
| `verifyRunnerImages` | §6.4 checks; `dependsOn` both builds |
| `downloadGuestBase` | download `baseUrl` to `build/guest/base.qcow2`, verify `baseSha256` |
| `buildGuestDisk` | `images/guest/customize.sh <engine> <slim tag> build/guest/base.qcow2 build/guest/work <imageRegistry>/<imageGuestName>:<version> <kvm if /dev/kvm usable else tcg>`; `dependsOn buildRunnerSlim, downloadGuestBase` |
| `pushImages` | `<engine> push` the three tags; only with `-PpushImages=true` |

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

---

## 12. CI (GitHub Actions)

### 12.1 Workflow `ci.yml`

- **Triggers:** `push`, `pull_request`, and `schedule: cron '0 6 * * 1'` (weekly rebuild against fresh repos; R1).
- **Jobs:**

| Job | Runner(s) | Runs | Timeout |
|-----|-----------|------|---------|
| `unit` | ubuntu-24.04 | `./gradlew :lib:test :junit5:test` | — |
| `integration` | matrix `{ubuntu-24.04, kvm}`, `{ubuntu-24.04, tcg}`, `{ubuntu-26.04, kvm}` | steps below | 30 min KVM / 90 min TCG |
| `compat` | — | the §9.6 probe, kept as a regression test (port of `spike-java` + `run-scenario.sh`) | — |

- **Steps for `integration`:**
  1. If kvm: udev rule `KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"`, then `udevadm control --reload-rules && udevadm trigger --name-match=kvm` **[V]**.
  2. `podman system service --time=0 unix://$XDG_RUNTIME_DIR/podman/podman.sock &` (the same command form as `run-scenario.sh` [V]).
  3. Export `DOCKER_HOST=unix://$XDG_RUNTIME_DIR/podman/podman.sock` and `TESTCONTAINERS_RYUK_DISABLED=true` [V].
  4. `actions/cache` for `build/guest/base.qcow2`, keyed by `baseSha256` (the spike cached the base image the same way [V]). The customized guest is rebuilt each run, about 1.5 min on KVM [V].
  5. `./gradlew integrationTest -D<syspropPrefix>.kvm=<REQUIRE for kvm | DISABLE for tcg>`
  6. Upload `it/build/vm-artifacts/**` and `it/build/reports/**`.

### 12.2 Expected durations

| Item | Duration |
|------|----------|
| Runner image build | about 51 s each [V] |
| Guest disk build | about 1.5 min on KVM (40 s boot + 48 s flatten + packaging) [V] |
| KVM integration suite | about 5 min [U, estimate] |
| TCG integration suite | about 20 min [U, estimate] |

### 12.3 Parameter hygiene

The CI step from §0 (fixed-string, whole-word grep).

---

## 13. Test plan and acceptance criteria

- Every test uses `VmImage.referenceGuest()` unless stated otherwise.
- KVM jobs must pass everything. TCG jobs must pass everything except `kvm`-tagged tests.

| ID | Test (`:it`) | Acceptance | Evidence |
|----|--------------|------------|----------|
| T1 | `accelKvm` / `accelTcg` | `accelerator()` matches; `query-kvm.enabled` agrees | [V] |
| T2 | `bootToAgent` | QGA ready ≤ 30 s (KVM) / ≤ 180 s (TCG) | [V] seeded: 10.9–12.1 s / 46.7–66.5 s |
| T3 | `execBasics` | `/usr/bin/uname -m` = `x86_64`; `/bin/sh -c 'echo out; echo err >&2; exit 3'` → (3, `out\n`, `err\n`) | [V] |
| T4 | `copyRoundTrip` | 5 MB and 50 MB SHA-256 equal both ways | [V] |
| T5 | `screenshotEachScreen` | n=2: head 0 1280×800; head 1 640×480 before and 1280×800 after `forceModeset()` | [V] |
| T6 | `vncExposed` | per screen, a raw RFB handshake from the host to `vnc()` returns `RFB 003.008`; with a password, security types = [2] | [V] |
| T7 | `vncPasswordRejectsWrong` | `rfbsrc` with a wrong password logs "Authentication failed" | [V] |
| T8 | `recordTwoScreens` | n=2, `screen(1).forceModeset()` first; 12 s; each WebM is VP8, \|duration − wall\| < 1 s, width 1280; each `close()` < 2 s | [V] |
| T9 | `recordingSurvivesExclusiveClient` | an exclusive (`shared=0`) client connects mid-recording; the recording remains valid | [V] (sandbox) |
| T10 | `recordStaticScreen` | 10 s of an unchanged screen → duration ≥ 9 s | [V] (sandbox, incremental=false) |
| T11 | `audioTone` | guest `/usr/bin/speaker-test -t sine -f 1000 -c 2 -l 1` → WAV 44.1 kHz/16/2, RMS > 100, dominant 900–1100 Hz | [V] |
| T12 | `keyboardType` | `execShell("echo root:vmtc | chpasswd")`; `type("root\n")`, wait 3 s; `type("vmtc\n")`, wait 5 s; `type("echo TYPED_OK > /dev/ttyS0\n")` → `TYPED_OK` appears in the serial log within 60 s | [V] (run 5, chpasswd via QGA) |
| T13 | `hotplugUsbStorage`, `hotplugUsbHid` | `lsblk` shows a `usb` disk / `/proc/bus/input/devices` gains a device; unplug removes it | storage [V]; HID [U] |
| T14 | `hotplugVirtioDisk`, `hotplugNic` | new `vdX` / new interface; unplug removes it | [V] |
| T15 | `cdromInsertEject` | `blkid /dev/sr0` shows the label; mounted content matches; eject removes the media | [V] |
| T16 | `hotplugCpu` (tag `kvm`) | `nproc` +1 | [V] |
| T17 | `hotplugMemory` | MemTotal +> 400 MiB | [V] |
| T18 | `imageSources` | `fromPath`, `fromUrl` (local `HttpServer`), `fromContainerDisk`, `baked()` all boot; the second run hits the cache (no download/extract) | partly [V] |
| T19 | `guestNotPrepared` | stock Rocky image without seed → `GuestCommandDisabledException`; with only G3 applied → `GuestAgentConfinedException` | [V] (behaviour) |
| T20 | `kvmRequireWithoutKvm` (tag `tcg`) | `REQUIRE` → `KvmUnavailableException` | [U] |
| T21 | `orphanCleanup` | a labelled container with an older session and `created` > 1 h is removed | [U] |
| T22 | `uefiBoot` | `Firmware.UEFI` reaches QGA | [U] |
| T23 | `referenceGuestNoSeed` | §10.3 acceptance | [V] (run 5: 11.2 s) |
| T24 | `noZombieRecorders` | after start+stop of a recording, no process in the container is in state `Z` | [V] (tini, sandbox) |
| T25 | `stockRockyWithSeed` | `fromUrl(${GUEST_BASE_URL}, ${GUEST_BASE_SHA256})` + `CloudInitSeed.forStockRocky()` → T3 passes | [V] |

**Unit tests (no engine):**
- QMP/QGA codecs against an in-JVM fake server: `0xFF` resync, chunking, base64, timeouts, event skipping.
- Cache locking and atomic move.
- Keyboard table.
- Constants generation.
- Overrides precedence.
- `info capture` parsing.

---

## 14. Implementation milestones

| M | Content | Exit criteria |
|---|---------|---------------|
| M1 | Gradle skeleton (§5, §11); runner images (§6, §7); `QemuVmContainer` start/stop; QMP; QGA; exec; copy; `fromPath`, `fromUrl`; `CloudInitSeed.forStockRocky()`; KvmMode/§9.6 | T1–T4, T20, T24, T25 green on KVM and TCG; §6.4 passes |
| M2 | Screens, VNC endpoints and password, screenshots, keyboard, `forceModeset`, UEFI | T5–T7, T12, T22 |
| M3 | `fromContainerDisk`, `baked`, `referenceGuest`, cache, `buildGuestDisk`; switch M1/M2 tests to `referenceGuest()` | T18, T19, T23 |
| M4 | Screen recording (media), audio | T8–T11 |
| M5 | Hot-plug | T13–T17 |
| M6 | JUnit extension, orphan cleanup, CI hardening, §12.3 hygiene check, docs | T21; all jobs green |

---

## 15. Limits, risks, open items

| # | Item | Impact | Handling |
|---|------|--------|----------|
| L1 | vCPU hot-plug under TCG: device added, but the guest never sees it (every TCG run; root cause unknown) | no CPU hot-plug without KVM | `Devices.cpu()` → `UnsupportedOperationException` under TCG |
| L2 | Secondary screens dark until the guest modesets them | placeholder 640×480 frames | `forceModeset()` for console guests; document |
| L3 | VNC auth: ≤ 8 characters | weak auth | default bind 127.0.0.1; password is an accident guard |
| L4 | No H.264/AV1 encoders | WebM/VP8 only | accept |
| L5 | Red Hat: direct qemu-kvm and TCG unsupported | no vendor support | accept for test infrastructure |
| R1 | Rocky RPMs on UBI could conflict in future point releases | build break | pinned `${ROCKY_RELEASE}`; build-time guard; weekly scheduled CI |
| R2 | **Licensing/redistribution** of an image mixing UBI with Rocky/EPEL content | may block publishing images publicly | **not assessed; needs a legal/policy decision before `pushImages` to a public registry** |
| R3 | SELinux-enforcing hosts (Fedora/RHEL): `:z` relabel, `container_use_devices` for `/dev/kvm` | untestable on GitHub runners (AppArmor) | acceptance run on a Fedora/RHEL host before 1.0 [U] |
| R4 | `/dev/kvm` 0660 hosts on Podman < 5.7 | needs user config | §9.6 detection and message [V] |
| R5 | UEFI firmware paths | — | T22 |
| R6 | ubuntu-26.04 runner image is in public preview | CI label may change | 24.04 stays primary |

---

## 16. Assumptions to confirm

1. §0 values are examples; replace them at adoption.
2. `${GRADLE_VERSION}`, `${JACKSON_VERSION}` and `${ASSERTJ_VERSION}` were not checked against release pages. Use the current versions.
3. Maximum screens = 4 and default hot-plug ports = 4 are design choices; only 2 screens and 4 ports were exercised.
4. `${ART_JUNIT}` is optional.
5. Publishing images publicly depends on R2.

---

## 17. Evidence index

All on branch `claude/vm-testcontainer-plan-lqhfa6` of this repository (PR #9).

| Evidence | Location |
|----------|----------|
| Spike write-ups (packaging, audio, SELinux, GStreamer, multi-screen, CI matrices) | `vm-testcontainer/container/spike/RESULTS.md` |
| Reference scripts | `vm-testcontainer/container/spike/`: `start-vm.sh`, `make-seed.sh`, `build-guest.sh`, `gst-record.sh`, `rec.sh`, `vmctl.py`, `typekeys.py`, `fb-activate.py`, `install-packages.sh`, `repos/` |
| Feature checks | `vm-testcontainer/container/spike/ci_spikes.py` |
| Compat probe | `vm-testcontainer/spike-java/` (`PodmanKvmProbeTest`, `run-scenario.sh`) |
| Workflow | `.github/workflows/vm-testcontainer-spikes.yml` |

| CI run | Showed |
|--------|--------|
| 1 (`39137816568`) | 24.04 KVM all pass; 24.04 TCG all pass except vCPU hot-plug; first compat probe |
| 2 (`39141980865`) | + 26.04 KVM all pass; TCG XFAIL vCPU; seed `DNF_RC` logging |
| 4 (`39142697864`) | login-session 0660 matrix on Podman 4.9.3 and 5.7.0; containerDisk (file path) on both |
| 5 (`39143866181`) | reference-guest pipeline + seedless boot, all checks pass (§10.3, T23); `/disk/` directory copy on Podman 4.9.3/5.7.0; chpasswd via QGA before console login |

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
  - per-screen screenshots and VNC (exposed, optional password);
  - per-screen GStreamer WebM recording;
  - WAV audio;
  - keyboard;
  - hot-plug of USB, PCIe disk/NIC, CD, memory, and vCPU (KVM only).
- **Images:** all sources end up as a cached, read-only base disk plus a per-start overlay. A pre-customized Rocky 9 guest is shipped as a containerDisk; the pipeline is verified, and that guest is ready about 11 s after start under KVM.
- **Rules that matter** (§1 a–i): `tini`; `incremental=false`; validate recordings by file; `force-shared`; per-display `set_password`; guest RPC filter **and** SELinux permissive domain; the keep-groups **annotation**, never `GroupAdd`; internal QMP socket.
- **Open:**
  - publishing/licensing of the mixed image (R2);
  - an SELinux-host acceptance run (R3);
  - UEFI paths (T22).
- **Adoption:** edit **§0 only**.

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
