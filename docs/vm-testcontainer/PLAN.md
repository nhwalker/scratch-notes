# QEMU VM Testcontainer: Implementation Plan

> **Audience:** the agent (or engineer) who will implement this module, in a different repository.
> **Status:** final plan. Every behaviour marked **[V]** was verified by an experiment in this repo (see §17 for evidence).
> **Scope:** x86-64 hosts and Linux guests only.

---

## 0. Project parameters (the ONLY place to retarget names)

Every other section refers to these parameters by name, for example `${GROUP_ID}`. To adopt the plan, change the **Value** column here. Do not hard-code any of these values elsewhere, in the plan or in the implementation. In the implementation, each of them lives in exactly one place: the Gradle properties file or the constants class named in the *Lives in* column.

| Parameter | Example value | Meaning | Lives in (implementation) |
|-----------|---------------|---------|---------------------------|
| `${GROUP_ID}` | `io.github.nhwalker` | Maven group ID for every published artifact | `gradle.properties` → `group` |
| `${VERSION}` | `0.1.0-SNAPSHOT` | Project version (artifacts **and** image tags) | `gradle.properties` → `version` |
| `${ROOT_PROJECT}` | `vm-testcontainer` | Gradle root project name / repo directory | `settings.gradle` |
| `${ART_LIB}` | `vm-testcontainer` | Artifact ID of the Java library (the Testcontainers module) | `lib/build.gradle` |
| `${ART_JUNIT}` | `vm-testcontainer-junit5` | Artifact ID of the optional JUnit 5 extension | `junit5/build.gradle` |
| `${BASE_PACKAGE}` | `io.github.nhwalker.vmtestcontainer` | Root Java package | source tree |
| `${IMAGE_REGISTRY}` | `ghcr.io/nhwalker` | Registry/namespace that runner and guest images are pushed to | `gradle.properties` → `imageRegistry` |
| `${IMAGE_RUNNER}` | `${IMAGE_REGISTRY}/vmtc-runner` | Runner image repository (tags in §6.4) | `gradle.properties` → `imageRunner` |
| `${IMAGE_GUEST}` | `${IMAGE_REGISTRY}/vmtc-guest-rocky9` | Reference guest containerDisk repository | `gradle.properties` → `imageGuest` |
| `${LABEL_PREFIX}` | `io.github.nhwalker.vmtc` | Prefix for OCI labels the library sets on containers | `VmtcConstants.LABEL_PREFIX` |
| `${SYSPROP_PREFIX}` | `vmtc` | Prefix for JVM system properties (`vmtc.kvm`, …) | `VmtcConstants.SYSPROP_PREFIX` |
| `${ENV_PREFIX}` | `VMTC_` | Prefix for host environment variables (`VMTC_KVM`, …) | `VmtcConstants.ENV_PREFIX` |
| `${CACHE_DIR_NAME}` | `vm-testcontainer` | Directory name under `$XDG_CACHE_HOME` (default `~/.cache`) | `VmtcConstants.CACHE_DIR_NAME` |
| `${JAVA_RELEASE}` | `21` | Java language/bytecode level | `gradle.properties` → `javaRelease` |
| `${GRADLE_VERSION}` | `9.1.0` | Gradle wrapper version (verify it is current when adopting) | `gradle/wrapper/gradle-wrapper.properties` |
| `${TESTCONTAINERS_VERSION}` | `2.0.5` | `org.testcontainers:testcontainers` version **[V]** (latest on Maven Central on 2026-10-18) | `gradle/libs.versions.toml` |
| `${JUNIT_VERSION}` | `5.12.2` | JUnit BOM version | `gradle/libs.versions.toml` |
| `${JACKSON_VERSION}` | `2.20.0` | Jackson databind version (verify it is current when adopting) | `gradle/libs.versions.toml` |
| `${UBI_IMAGE}` | `registry.access.redhat.com/ubi9/ubi:9.8` | Runner base image **[V]** | `images/runner/Containerfile` `ARG` |
| `${ROCKY_RELEASE}` | `9.8` | Rocky fallback repo release pin **[V]** | `images/runner/repos/rocky.repo` |
| `${GUEST_BASE_URL}` | `https://dl.rockylinux.org/pub/rocky/9/images/x86_64/Rocky-9-GenericCloud-Base-9.8-20260525.0.x86_64.qcow2` | Reference guest source image **[V]** | `images/guest/guest.properties` |
| `${GUEST_BASE_SHA256}` | `92c206cc6f790c61583247eefe87890f8828420662c17cacf247cec78ab4eec8` | SHA-256 of the image above **[V]** | `images/guest/guest.properties` |

**Rule for the implementer:**
- Java code reads the image names through `VmtcConstants.defaultRunnerImage(Variant)`. That method is generated at build time from `gradle.properties`; see §11.3.
- Scripts and the Containerfile receive the values as build arguments.
- A `grep` for any example value outside the files in the *Lives in* column must return nothing. CI enforces this (§12.3).

---

## 1. BLUF (Bottom Line Up Front)

**What it is:** a Testcontainers module, `${ART_LIB}`, whose `QemuVmContainer` runs **one QEMU VM per Podman container** on a UBI 9.8 runner image.

- **With `/dev/kvm`:** it uses KVM, and the guest agent was ready about 11 s after container start on CI.
- **Without it:** it falls back to TCG (software emulation), which is 5–10× slower but functional.

**Every requirement is verified end to end** with a Rocky 9.8 guest on GitHub-hosted runners under KVM and under TCG:

| Capability | How it works |
|------------|--------------|
| Run commands, copy files | QEMU guest agent (QGA) |
| Screenshots, keyboard, hot-plug | QEMU monitor protocol (QMP) |
| One VNC server per screen | exposed to library users |
| Screen recording | one WebM (VP8) per screen, recorded from VNC with GStreamer `rfbsrc` |
| Audio recording | separate WAV file, via QEMU `wavcapture` |
| Hot-plug | USB storage/HID, PCIe disk, PCIe NIC, CD media change, memory; CPU under KVM only |

**Packaging:**
- UBI repos have **no QEMU at all**. QEMU and `gstreamer1-plugins-good` come from **Rocky 9.8**, which is pinned and allowed only as a fallback.
- `tini` comes from **EPEL**.
- Everything else comes from UBI.

**VM image supply:** every source resolves to **a read-only base disk file in a host cache**. It is bind-mounted into the container, and the container writes to a throwaway qcow2 overlay.

| Source | API |
|--------|-----|
| Host path | `VmImage.fromPath` |
| URL + SHA-256 | `VmImage.fromUrl` |
| containerDisk OCI image, extracted through the Docker API | `VmImage.fromContainerDisk` **[V]** |
| Disk baked into a derived runner image | `VmImage.baked()` |

**Things the implementer must not get wrong:**

| # | Rule | Why |
|---|------|-----|
| a | Run `tini` as PID 1 | QEMU does not reap children, so recorders become zombies otherwise |
| b | Use `rfbsrc incremental=false` | Static screens otherwise produce no frames |
| c | Judge a recording by validating the file | `gst-launch` exits 1 after a normal SIGINT stop |
| d | Use VNC `share=force-shared` | An exclusive VNC client otherwise kills recorders |
| e | Set VNC passwords per display with `set_password` | `change-vnc-password` only covers the default display |
| f | Guests need the QGA RPC filter relaxed **and** `semanage permissive -a virt_qemu_ga_t` | SELinux otherwise blocks guest-exec |
| g | Secondary screens stay dark until the guest modesets them | Expected behaviour; plan for it |
| h | `/dev/kvm` passthrough through Testcontainers works when the host device is `0666` (systemd default). For a group-restricted `0660` device, set the per-container annotation `run.oci.keep_original_groups=1` (works on Podman 5.7). On Podman 4.9, use the same annotation in `containers.conf` instead (§9.6) | Group access does not cross the compat API by default |

---

## Table of contents

0. Project parameters
1. BLUF
2. Requirements and decisions
3. Verified facts
4. Architecture
5. Repository and module layout (Gradle)
6. Runner container image
7. In-container scripts (entrypoint, recorder)
8. QEMU configuration
9. Java library design
10. Guest contract and reference guest image
11. Build (Gradle) details
12. CI
13. Test plan and acceptance criteria
14. Implementation milestones
15. Limits, risks, open items
16. Assumptions to confirm
17. Evidence index
18. In Summary

---

## 2. Requirements and decisions

| # | Requirement / decision | Where |
|---|------------------------|-------|
| R1 | Container engine **Podman** (rootless first-class; rootful works) | §9.6, §12 |
| R2 | Images based on **UBI 9.8** | §6 |
| R3 | Run commands in the VM | §9.4 |
| R4 | Copy files to and from the VM | §9.5 |
| R5 | Run QEMU images (qcow2, raw) | §8, §9.3 |
| R6 | Screenshots (per screen) | §9.7 |
| R7 | Audio recording, as **separate WAV** files | §9.9 |
| R8 | Screen recording: **GStreamer recording each screen's VNC, one video per screen** | §7.2, §9.8 |
| R9 | **VNC for display output, exposed to library users** | §8.3, §9.7 |
| R10 | Hot-plug simulation | §9.10 |
| R11 | Repos: UBI first, then EPEL, with Rocky as fallback | §6.2 |
| R12 | Use `/dev/kvm` when available; work without it | §8.2, §9.6 |
| D1 | x86-64 hosts and guests only | — |
| D2 | **Linux guests only** | §10 |
| D3 | Reference test guest: **Rocky Linux 9** | §10.3 |
| D4 | Build tool: **Gradle** | §5, §11 |
| D5 | Screens: **1..N, configurable** (default 1, max 4) | §8.3 |
| D6 | Image variants: **slim** (no GStreamer) and **media** (with GStreamer) | §6.4 |
| D7 | Guests with SELinux: `semanage permissive -a virt_qemu_ga_t` | §10.1 |

---

## 3. Verified facts

Legend:
- **[V]** = verified by an experiment in this repo. Evidence is listed in §17.
- **[D]** = taken from upstream documentation or source.
- **[U]** = not verified; the plan accounts for it.

### 3.1 Packaging (runner image)

| Fact | Status |
|------|--------|
| `ubi9/ubi:9.8` exists. RHEL 9.8 GA was 2026-05-19 | [V] |
| UBI 9 baseos, appstream and codeready-builder repos contain **no** `qemu-*` package | [V] |
| Rocky 9.8 AppStream provides `qemu-kvm-core`, `qemu-img` and `qemu-kvm-common` (10.1.0-17.el9_8.5), plus `edk2-ovmf` and `seabios-bin`. Installing them onto UBI 9.8 replaced **no** UBI package, installed no `rocky-release`, and `rpm -Va` was clean | [V] |
| UBI AppStream provides `gstreamer1`, `gstreamer1-plugins-base` and `gstreamer1-plugins-bad-free` (contains `rfbsrc`). **Rocky-only:** `gstreamer1-plugins-good` (`vp8enc`, `webmmux`, `matroskamux`) and `gstreamer1-plugins-base-tools` (`gst-discoverer-1.0`). All are version 1.22.12 | [V] |
| Not available anywhere in the repo set: `x264enc`, `openh264enc`, `av1enc` | [V] |
| EPEL 9 provides `tini` 0.19.0 (also `dumb-init`). Rocky CRB provides `catatonit` | [V] |
| Image build with rootless Podman on a GitHub runner took about 51 s; the media variant is 734 MB uncompressed | [V] |

### 3.2 QEMU (RHEL build, `/usr/libexec/qemu-kvm`)

| Fact | Status |
|------|--------|
| The binary is `/usr/libexec/qemu-kvm`; there is no `qemu-system-x86_64` | [V] |
| Accelerators: `kvm`, `tcg`, `mshv`. TCG works without `/dev/kvm` | [V] |
| The default machine type is deprecated, so **always pass `-M q35`** | [V] |
| Display devices: `VGA`, `secondary-vga`, `bochs-display`, `cirrus-vga`. **No `virtio-vga`** | [V] |
| Sound: `ich9-intel-hda` + `hda-duplex`. **No `usb-audio` and no `virtio-sound-pci`** | [V] |
| Audio backends compiled in: `none`, `wav` (`pa` comes from a separate subpackage and is not used) | [V] |
| `-netdev user` (slirp) and `passt` are both available | [V] |
| Hot-plug devices present: `qemu-xhci`, `usb-storage`, `usb-kbd`, `usb-tablet`, `usb-mouse`, `pcie-root-port`, `virtio-blk-pci`, `virtio-net-pci`, `virtio-scsi-pci`, `scsi-cd`, `pc-dimm` | [V] |
| `-vnc <addr>:<n>,id=vnc<n>,display=video<n>` gives one VNC server per display device; `query-vnc-servers` confirms the mapping | [V] |
| `screendump {device: "video<n>", format: "png"}` captures the screen of that display | [V] |
| `share=force-shared` keeps recorders connected while another client connects | [V] |
| `password=on` + `set_password {protocol: vnc, password, display: vnc<n>}` enforces VNC auth; a wrong password is rejected | [V] |
| `wavcapture <path> <audiodev>` / `stopcapture <n>` (HMP via QMP) works with `-audiodev none` and recorded a 1 kHz tone correctly | [V] |
| Red Hat calls direct `qemu-kvm` use and TCG "unsupported". Acceptable for test infrastructure | [V] (binary message) |

### 3.3 Timings measured on GitHub-hosted runners (4 vCPU, 16 GB)

| Measurement | KVM (ubuntu-24.04) | KVM (ubuntu-26.04) | TCG (ubuntu-24.04) |
|-------------|-------------------|--------------------|--------------------|
| Container start → QGA ready | 12.1 s | 10.9 s | 46.7–66.5 s (2 runs) |
| Seed with `dnf install alsa-utils` done | about 100 s | 45.5 s | 322–481 s |
| QGA file write 50 MB | 2.5 s (20 MB/s) | 2.3 s (21 MB/s) | 18.0–36.4 s (1.4–2.8 MB/s) |
| QGA file read 50 MB | 1.1 s (46 MB/s) | 1.6 s (31 MB/s) | 7.5–10.5 s (4.8–6.6 MB/s) |
| USB stick visible in guest | 1.7 s | — | 3.2 s |
| PCIe disk / NIC visible | 0.3–0.4 s | — | 0.4 s |
| vCPU hot-plug | 0.4 s, auto-online | pass | **guest never sees the vCPU** (XFAIL, 3/3 runs) |
| Memory hot-plug +512 MiB | 0.4 s, auto-online | pass | 1.7 s |
| Recorder stop latency | about 106 ms | — | about 108 ms |

### 3.4 Guest (Rocky 9.8 GenericCloud)

| Fact | Status |
|------|--------|
| The image ships `qemu-guest-agent` (enabled), `cloud-init` and `kernel-modules` (HDA driver) | [V] |
| `/etc/sysconfig/qemu-ga` sets `FILTER_RPC_ARGS="--allow-rpcs=..."`, which **omits** `guest-exec*` and `guest-file-*`. The error returned is class `CommandNotFound`, desc "Command guest-exec has been disabled: the command is not allowed" | [V] |
| guest-exec children run as SELinux domain `virt_qemu_ga_t`, which denies ordinary files (`/usr/share/alsa`, `/lib/modules`, `journalctl`) | [V] |
| `virt_qemu_ga_run_unconfined=on` does **not** help. `semanage permissive -a virt_qemu_ga_t` **does**, and the rest of the guest stays Enforcing | [V] |
| The HDA mixer must be unmuted (`amixer -c 0 sset Master 90% unmute`). There may be no `PCM` control, so ignore its failure | [V] |
| Every bochs-drm head appears as `/dev/fbN` / `cardN`. A text console only drives head 0. Heads 1..N-1 report "Guest has not initialized the display (yet)" at 640×480 until a modeset (`FBIOPUT_VSCREENINFO` with `FB_ACTIVATE_FORCE`, or a desktop session) | [V] |
| Memory and CPU hot-add are auto-onlined by the guest (udev). PCIe unplug and USB unplug complete in ≤ 0.2 s | [V] |

### 3.5 GStreamer VNC recording

| Fact | Status |
|------|--------|
| The pipeline in §7.2 records each VNC display concurrently; the duration matches wall clock within 0.3 s | [V] |
| `incremental=true` (the default) **blocks with no frames** on a static screen | [V] |
| With `incremental=false`, QEMU answers on its adaptive refresh timer (≈0.1–0.7 s idle), and `videorate` fills the gaps | [V] |
| SIGINT → `-e` EOS → the file is finalized in about 110 ms. **The exit code is 1, with a benign "Error on setup VNC connection" line** | [V] |
| `rfbsrc password=...` works with QEMU VNC auth | [V] |

### 3.6 Podman / Testcontainers (compat API)

| Fact | Status |
|------|--------|
| Testcontainers 2.0.5 works against rootless Podman 4.9.3 (ubuntu-24.04) and 5.7.0 (ubuntu-26.04) via `DOCKER_HOST=unix://…/podman.sock` with `TESTCONTAINERS_RYUK_DISABLED=true` | [V] |
| `HostConfig.Devices=[/dev/kvm]` works when the host `/dev/kvm` is `0666` (both versions) | [V] |
| `HostConfig.GroupAdd=["keep-groups"]` through the compat API **fails container start** (both versions) | [V] |
| `HostConfig.Init=true` → PID 1 is `podman-init` | [V] |
| A read-only file bind is enforced (writes fail with EROFS) | [V] |
| containerDisk extraction (`createContainerCmd` + `copyArchiveFromContainerCmd`) is byte-exact on Docker 29.8, Podman 4.9.3 and Podman 5.7.0 | [V] |
| With `/dev/kvm` `0660 root:kvm` and the user in `kvm`, through the compat API: <ul><li>device-only → EACCES</li><li>annotation `run.oci.keep_original_groups=1` in `HostConfig` → **works on 5.7.0**, EACCES on 4.9.3 (annotation ignored)</li><li>the same annotation in `containers.conf` → **works on both**</li></ul> | [V] |

---

## 4. Architecture

```
┌──────────────────────────────── Host (x86-64 Linux, Podman) ────────────────────────────────┐
│                                                                                              │
│  Test (JUnit 5) ──► QemuVmContainer  (${BASE_PACKAGE})                                        │
│                      │ QmpClient ─────────── TCP 127.0.0.1:<mapped 4444> ─┐                  │
│                      │ GuestAgentClient ──── TCP 127.0.0.1:<mapped 4445> ─┤                  │
│  library users' VNC viewers ──────────────── TCP <bind>:<mapped 5900+i> ──┤                  │
│                      │ execInContainer(vmtc-rec …), copyFileFromContainer ┤                  │
│                      │                                                    │                  │
│  ~/.cache/${CACHE_DIR_NAME}/images/<sha256>.<fmt>  ── ro bind ──┐         │                  │
│  /dev/kvm (optional) ─────────────────────────── --device ──┐   │         │                  │
│                                                              ▼   ▼         ▼                  │
│  ┌─────────────── container: ${IMAGE_RUNNER}:${VERSION}-ubi9.8[-media] ───────────────┐      │
│  │ PID 1 tini ─► vmtc-entrypoint ─► /usr/libexec/qemu-kvm -M q35 …                    │      │
│  │   /vm/base/disk.img (ro)  /vm/run/overlay.qcow2  /vm/out/{serial.log,*.png,*.wav,*.webm}│  │
│  │   QMP :4444   QGA chardev :4445 ◄─virtio-serial─► guest qemu-ga                    │      │
│  │   VNC :5900+i per screen (share=force-shared[,password=on])                        │      │
│  │   vmtc-rec start/stop ─► gst-launch rfbsrc ► vp8enc ► webmmux (media only)         │      │
│  │   ┌───────────────── Guest (Linux) ─────────────────┐                              │      │
│  │   │ qemu-ga (exec/file RPCs allowed, permissive SELinux domain)                    │      │
│  │   └────────────────────────────────────────────────┘                              │      │
│  └────────────────────────────────────────────────────────────────────────────────────┘      │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
```

Principles:

1. **One container = one VM.** The VM's lifecycle is the container's lifecycle.
2. **Java speaks QMP and QGA directly over TCP** (JSON lines).
   - `execInContainer` is used only for container-side helpers (`vmtc-rec`, `qemu-img`, `xorriso`).
   - Files are moved with `copyFileToContainer` / `copyFileFromContainer`.
3. **The base disk is never written.** Each container start creates a qcow2 overlay.
4. **All published host ports bind to `127.0.0.1` by default.** VNC's bind address is configurable (§9.7).

---

## 5. Repository and module layout (Gradle)

```
${ROOT_PROJECT}/
├─ settings.gradle                      rootProject.name = '${ROOT_PROJECT}'; include 'lib','junit5','images','it'
├─ gradle.properties                    group, version, imageRegistry, imageRunner, imageGuest, javaRelease (§0)
├─ gradle/libs.versions.toml            testcontainers, junit, jackson, commons-compress, slf4j
├─ gradle/wrapper/…                     ${GRADLE_VERSION}
├─ lib/                                 artifact ${ART_LIB}  (the Testcontainers module)
│  ├─ build.gradle                      java-library, maven-publish, generateConstants task (§11.3)
│  └─ src/main/java/${BASE_PACKAGE}/…   (§9.1 package list)
│     src/test/java/…                   unit tests (no container engine needed)
├─ junit5/                              artifact ${ART_JUNIT}: @VmTest extension: artifacts on failure (§9.13)
├─ images/
│  ├─ runner/
│  │  ├─ Containerfile                  (§6.3)
│  │  ├─ repos/epel.repo, repos/rocky.repo
│  │  └─ scripts/vmtc-entrypoint, vmtc-rec, vmtc-fb-activate.py   (§7)
│  ├─ guest/
│  │  ├─ guest.properties               ${GUEST_BASE_URL}, ${GUEST_BASE_SHA256}
│  │  ├─ customize.sh                   (§10.3), Containerfile.containerdisk
│  │  └─ seed/ (cloud-init templates)   (§10.2)
│  └─ build.gradle                      tasks: buildRunnerSlim, buildRunnerMedia, buildGuestDisk, push* (§11.2)
└─ it/                                  integration tests against real Podman (§13); not published
```

---

## 6. Runner container image

### 6.1 Variants and tags

| Variant | Tag | Contents |
|---------|-----|----------|
| slim | `${IMAGE_RUNNER}:${VERSION}-ubi9.8` | QEMU, qemu-img, firmware, tini, xorriso, python3 (helpers), jq, socat |
| media | `${IMAGE_RUNNER}:${VERSION}-ubi9.8-media` | slim + GStreamer (`base`, `base-tools`, `good`, `bad-free`) |

Screen recording requires **media**. On slim, `startScreenRecording` throws `UnsupportedOperationException("screen recording requires the media runner image")`. All other features work on both variants.

### 6.2 Repositories (R11)

| Repo id | Source | Priority | Restrictions |
|---------|--------|----------|--------------|
| `ubi-9-*` | built into UBI | **10** (set by sed on `/etc/yum.repos.d/ubi.repo`) | none |
| `epel9` | `https://dl.fedoraproject.org/pub/epel/9/Everything/x86_64/` | 20 | gpgcheck=1, key `RPM-GPG-KEY-EPEL-9` |
| `rocky98-baseos` / `-appstream` / `-crb` | `https://dl.rockylinux.org/pub/rocky/${ROCKY_RELEASE}/{BaseOS,AppStream,CRB}/x86_64/os/` | 90 | gpgcheck=1, key `RPM-GPG-KEY-Rocky-9`, `excludepkgs=rocky-release* rocky-logos* rocky-gpg-keys* rocky-repos*` |

The exact repo files are `vm-testcontainer/container/spike/repos/*.repo` in this repo **[V]**. Copy them verbatim, with `9.8` replaced by `${ROCKY_RELEASE}`.

> When Rocky moves 9.8 to `vault.rockylinux.org`, change only the baseurl host. As of 2026-10-18, `pub/rocky/9.8` is live and `vault/rocky/9.8` returns 404 **[V]**.

### 6.3 Containerfile (normative)

```dockerfile
ARG UBI_IMAGE=registry.access.redhat.com/ubi9/ubi:9.8
FROM ${UBI_IMAGE}
ARG VARIANT=slim
ARG VERSION
ARG LABEL_PREFIX
LABEL org.opencontainers.image.title="vmtc-runner" org.opencontainers.image.version="${VERSION}" \
      ${LABEL_PREFIX}.variant="${VARIANT}"
COPY repos/ /etc/yum.repos.d/
RUN sed -i '/^\[/a priority=10' /etc/yum.repos.d/ubi.repo \
 && rpm --import https://dl.rockylinux.org/pub/rocky/RPM-GPG-KEY-Rocky-9 \
 && rpm --import https://dl.fedoraproject.org/pub/epel/RPM-GPG-KEY-EPEL-9 \
 && PKGS="qemu-kvm-core qemu-img edk2-ovmf seabios-bin tini xorriso jq socat python3" \
 && if [ "$VARIANT" = media ]; then PKGS="$PKGS gstreamer1 gstreamer1-plugins-base gstreamer1-plugins-base-tools gstreamer1-plugins-good gstreamer1-plugins-bad-free"; fi \
 && dnf -y --setopt=install_weak_deps=False install $PKGS \
 && dnf clean all \
 && rpm -q redhat-release && ! rpm -qa 'rocky-*' | grep -q .
COPY scripts/ /usr/local/bin/
RUN chmod 0755 /usr/local/bin/vmtc-*
EXPOSE 4444 4445 5900 5901 5902 5903
ENTRYPOINT ["/usr/bin/tini", "-g", "--", "/usr/local/bin/vmtc-entrypoint"]
```

Notes:

- `${LABEL_PREFIX}` is substituted by the Gradle build (§11.2). The Containerfile receives it as `--build-arg LABEL_PREFIX=…` and uses `${LABEL_PREFIX}`.
- The last `RUN` line is a guard: the build fails if `redhat-release` was replaced or any `rocky-*` package was installed.
- The container runs as root *inside* the container. In rootless Podman that maps to the invoking host user.
- `tini` (EPEL `tini-0.19.0-5.el9`) installs `/usr/bin/tini` **[V]**. `tini -g` forwards signals to the whole process group. tini reaps orphans, which fixes the recorder-zombie issue **[V]**: with QEMU as PID 1, stopped recorders stayed zombies.
- There is no `nc` in the image **[V]**. Scripts use bash `/dev/tcp` or `socat` for port checks.

### 6.4 Image build checks (part of `images` build, §11.2)

After building, run these inside the image; each must succeed:

1. `/usr/libexec/qemu-kvm -accel help | grep -qx tcg`
2. `/usr/libexec/qemu-kvm -device help` contains every device listed in §3.2.
3. **media only:** `gst-inspect-1.0` succeeds for `rfbsrc`, `vp8enc`, `webmmux`, `videorate`, `videoconvert`.
4. `rpm -q redhat-release` succeeds, and no `rocky-release` is installed.
5. `test -x /usr/bin/tini`.

---

## 7. In-container scripts

### 7.1 `vmtc-entrypoint` (bash; normative behaviour)

**Inputs:** environment variables, all set by Java.

| Env var | Default | Meaning |
|---------|---------|---------|
| `VM_ACCEL` | `auto` | `kvm` / `tcg` / `auto` (auto = `kvm` if `/dev/kvm` is readable and writable, else `tcg`) |
| `VM_CPUS` | `2` | Boot vCPUs |
| `VM_MAXCPUS` | `max(4, VM_CPUS)` | Hot-plug ceiling |
| `VM_MEM_MB` | `2048` | Boot RAM |
| `VM_MEM_SLOTS` / `VM_MAXMEM` | `4` / `8G` | DIMM hot-plug |
| `VM_SCREENS` | `1` | 1..4 display heads |
| `VNC_BIND` | `0.0.0.0` | Bind inside the container (host exposure is controlled by port publishing) |
| `VNC_PASSWORD_ENABLED` | `0` | `1` adds `password=on` to every VNC server |
| `VM_HOTPLUG_PORTS` | `4` | Number of `pcie-root-port`s, named `hp1..hpN` |
| `VM_FIRMWARE` | `bios` | `bios` (SeaBIOS) or `uefi` (OVMF, §8.4) |
| `VM_OVERLAY_SIZE` | *(empty)* | Optional virtual size for the overlay, e.g. `20G` |
| `VM_EXTRA_ARGS` | *(empty)* | Appended to the QEMU command line, word-split (escape hatch) |

**Steps:**

1. Require `/vm/base/disk.img` to exist; otherwise print `VMTC_ERROR no base disk` and exit 2.
2. `FMT=$(qemu-img info --output=json /vm/base/disk.img | jq -r .format)`.
3. `qemu-img create -q -f qcow2 -F "$FMT" -b /vm/base/disk.img /vm/run/overlay.qcow2 ${VM_OVERLAY_SIZE}`.
4. If `/vm/seed/user-data` exists, build the NoCloud ISO: `xorriso -as mkisofs -quiet -V cidata -J -r -o /vm/run/seed.iso /vm/seed/user-data /vm/seed/meta-data` (and `network-config` if present).
5. Resolve the accelerator:
   - `VM_ACCEL=kvm` while `/dev/kvm` is unusable → print `VMTC_ERROR kvm requested but /dev/kvm unusable` and exit 3.
   - Print `VMTC_ACCEL kvm|tcg`.
6. Build the QEMU argument array exactly as in §8.1.
7. Print `VMTC_QEMU_STARTING <epoch>`.
8. Run QEMU as a **child** of the script (not `exec`), so the script can report readiness:
   - Start QEMU in the background.
   - Poll every 50 ms, for up to 30 s, until `(exec 3<>/dev/tcp/127.0.0.1/4444)` succeeds.
   - Print `VMTC_READY`.
   - `wait` on QEMU and exit with its status.
9. Trap SIGTERM:
   - Run `printf '{"execute":"qmp_capabilities"}\n{"execute":"quit"}\n' | socat -t2 - TCP:127.0.0.1:4444`.
   - If QEMU has not exited after 10 s, `kill -9` it.

The Java wait strategy waits for the log line `VMTC_READY` (§9.2).

### 7.2 `vmtc-rec` (bash; screen recorder control; media image only)

```
vmtc-rec start <name> <screen-index> <fps> <bitrate-bps>   # env VNC_PASSWORD optional
vmtc-rec stop  <name> [timeout-seconds=10]
vmtc-rec status <name>
```

**`start`:**

1. Launch, detached (`setsid … &`), with stdin `/dev/null` and stdout/stderr to `/vm/out/<name>.log`:

   ```
   gst-launch-1.0 -q -e rfbsrc host=127.0.0.1 port=$((5900+i)) view-only=true shared=true incremental=false [password=$VNC_PASSWORD] \
     ! videorate ! video/x-raw,framerate=<fps>/1 ! videoconvert \
     ! vp8enc deadline=1 cpu-used=8 threads=2 target-bitrate=<bitrate> ! webmmux ! filesink location=/vm/out/<name>.webm
   ```

2. Write the PID to `/vm/run/<name>.pid` and print `VMTC_REC_STARTED <pid>`.

**`stop`:**

1. `kill -INT <pid>`.
2. Poll every 100 ms. A process counts as alive if `/proc/<pid>` exists **and** its `State` is not `Z`. Give up after the timeout.
3. If it is still alive, `kill -9` it and print `VMTC_REC_KILLED`.
4. Validate with `gst-discoverer-1.0 /vm/out/<name>.webm`:
   - On success, print `VMTC_REC_OK duration=<s> width=<w> height=<h>`.
   - Otherwise print `VMTC_REC_INVALID` followed by the log tail.
5. **The gst-launch exit code is ignored** (it is 1 even on success) **[V]**.

**`status`:** prints `running` or `stopped`.

**Defaults:** fps 10, bitrate 1 000 000 **[V]**.

### 7.3 `vmtc-fb-activate.py` (guest helper; copied into the guest on demand)

This is the verified script `vm-testcontainer/container/spike/fb-activate.py` **[V]**. It opens `/dev/fbN`, issues `FBIOGET_VSCREENINFO`, sets `activate=FB_ACTIVATE_FORCE` (offset 84, value 0x80), issues `FBIOPUT_VSCREENINFO`, then `FBIOBLANK(0)`.

The Java side uses it in `VmScreen.forceModeset()` (§9.7).

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
  -chardev socket,id=qga0,host=0.0.0.0,port=4445,server=on,wait=off
  -device virtio-serial-pci -device virtserialport,chardev=qga0,name=org.qemu.guest_agent.0
  -serial file:/vm/out/serial.log
  -drive file=/vm/run/overlay.qcow2,format=qcow2,if=none,id=disk0 -device virtio-blk-pci,drive=disk0,bootindex=0
  [seed: -drive file=/vm/run/seed.iso,format=raw,if=none,id=seed,readonly=on -device virtio-blk-pci,drive=seed]
  -netdev user,id=net0 -device virtio-net-pci,netdev=net0
  -audiodev none,id=snd0 -device ich9-intel-hda -device hda-duplex,audiodev=snd0
  -device qemu-xhci,id=xhci
  -device virtio-scsi-pci,id=scsi0 -device scsi-cd,id=cd0,bus=scsi0.0
  for j in 1..VM_HOTPLUG_PORTS: -device pcie-root-port,id=hp$j,chassis=$j
  [uefi: §8.4]
  ${VM_EXTRA_ARGS}
```

**[V]**: every element above ran in CI (`vm-testcontainer/container/spike/start-vm.sh` is the verified reference). The only differences in the reference script are that it bound QMP and QGA to 127.0.0.1 and accessed them with `podman exec`. Production binds `0.0.0.0` *inside the container* so the ports can be published. Host-side publishing binds to `127.0.0.1` (§9.2).

### 8.2 Acceleration (R12)

| `KvmMode` (Java) | Host `/dev/kvm` usable (§9.6) | Effect |
|------------------|-------------------------------|--------|
| `REQUIRE` | yes | `--device /dev/kvm`, `VM_ACCEL=kvm` |
| `REQUIRE` | no | `start()` throws `KvmUnavailableException` with the remediation text from §9.6 |
| `PREFER` (default) | yes | as REQUIRE |
| `PREFER` | no | no device; `VM_ACCEL=tcg`; WARN log once per JVM |
| `DISABLE` | any | no device; `VM_ACCEL=tcg` |

Under TCG, every default timeout is multiplied by `tcgTimeoutFactor` (default **6**, from the measured 66.5 s / 12.1 s ≈ 5.5).

### 8.3 Screens and VNC (D5, R9)

- `withScreens(n)` takes n in 1..4 (default 1). Head 0 is `VGA`; heads 1..n-1 are `secondary-vga`. **[V]** with n=2.
- Each head *i* has one VNC server on container port `5900+i`, which Testcontainers publishes. The host bind address is `127.0.0.1` by default; `withVncBindAddress("0.0.0.0")` makes it remotely reachable.
- `share=force-shared` is always set. With `withVncPassword(String)`, `password=on` is added. After `VMTC_READY`, Java calls QMP `set_password {protocol:"vnc", password, display:"vnc<i>"}` **for every i** **[V]**. Recorders get the password through the `VNC_PASSWORD` env of `vmtc-rec`.
- VNC passwords are limited to 8 significant characters (RFB VNC auth). Java rejects longer passwords with `IllegalArgumentException` **[D]**.
- Secondary heads show "Guest has not initialized the display (yet)" at 640×480 until the guest modesets them **[V]**. This is guest behaviour, not a library bug.

### 8.4 UEFI (optional; `VM_FIRMWARE=uefi`)

```
-drive if=pflash,format=raw,unit=0,readonly=on,file=/usr/share/edk2/ovmf/OVMF_CODE.fd
-drive if=pflash,format=raw,unit=1,file=/vm/run/OVMF_VARS.fd     # copied from /usr/share/edk2/ovmf/OVMF_VARS.fd at start
```

**[U]**: the OVMF file paths come from the `edk2-ovmf` package and were not exercised in CI.

**Milestone M2** includes one acceptance test, `uefi_boot`, which boots the Rocky GenericCloud image (it supports UEFI **[D]**) with `VM_FIRMWARE=uefi` and checks QGA comes up. If the file names differ, take them from `rpm -ql edk2-ovmf` in the image.

---

## 9. Java library design

### 9.1 Packages

| Package | Contents |
|---------|----------|
| `${BASE_PACKAGE}` | `QemuVmContainer`, `VmImage`, `KvmMode`, `RunnerVariant`, `VmtcConstants` (generated), exceptions |
| `${BASE_PACKAGE}.qmp` | `QmpClient`, `QmpEvent`, `QmpException` |
| `${BASE_PACKAGE}.qga` | `GuestAgentClient`, `GuestExecResult`, `GuestAgentException`, `GuestCommandDisabledException` |
| `${BASE_PACKAGE}.image` | `VmImageResolver`, `VmImageCache`, `ContainerDiskExtractor`, `ImageDownloader` |
| `${BASE_PACKAGE}.display` | `VmScreen`, `VncEndpoint`, `ScreenRecording`, `RecordingOptions`, `KeyboardInput` |
| `${BASE_PACKAGE}.audio` | `AudioRecording` |
| `${BASE_PACKAGE}.hotplug` | `Devices`, `DeviceSpec` (sealed), `HotplugHandle`, `CdromDrive` |
| `${BASE_PACKAGE}.cloudinit` | `CloudInitSeed` (builder for user-data/meta-data) |
| `${BASE_PACKAGE}.internal` | `KvmDetector`, `PortBindings`, `Json` (Jackson), `Backoff` (not exported; JPMS `module-info` exports only the packages above) |

**Dependencies:**

| Scope | Dependencies |
|-------|--------------|
| `api` | `org.testcontainers:testcontainers:${TESTCONTAINERS_VERSION}` |
| `implementation` | `com.fasterxml.jackson.core:jackson-databind:${JACKSON_VERSION}`, `org.apache.commons:commons-compress` (tar extraction; **[V]** in probe) |
| tests | JUnit, AssertJ, slf4j-simple |

### 9.2 `QemuVmContainer` (public API, normative)

```java
public class QemuVmContainer extends GenericContainer<QemuVmContainer> {
  public QemuVmContainer();                                    // default runner image, SLIM
  public QemuVmContainer(RunnerVariant variant);               // SLIM | MEDIA → VmtcConstants.defaultRunnerImage(variant)
  public QemuVmContainer(DockerImageName runnerImage);         // custom/derived runner image

  // configuration (all return this)
  withVmImage(VmImage image)                                   // required unless image is VmImage.baked()
  withKvm(KvmMode mode)                                        // default PREFER (overridable: -D${SYSPROP_PREFIX}.kvm / ${ENV_PREFIX}KVM)
  withCpus(int boot) / withMaxCpus(int max)                    // defaults 2 / max(4,boot)
  withMemoryMb(int mb) / withMemoryHotplug(int slots, String maxmem)   // defaults 2048 / 4,"8G"
  withScreens(int n)                                           // 1..4, default 1
  withVncBindAddress(String hostIp)                            // default "127.0.0.1"
  withVncPassword(String pw)                                   // ≤ 8 chars; default none
  withHotplugPorts(int n)                                      // 0..8, default 4
  withFirmware(Firmware f)                                     // BIOS (default) | UEFI
  withCloudInit(CloudInitSeed seed)                            // optional NoCloud seed (§10.2)
  withGuestAgentTimeout(Duration d)                            // default 180 s (×tcgTimeoutFactor under TCG)
  withTcgTimeoutFactor(double f)                               // default 6.0
  withSerialLogConsumer(Consumer<String> lineConsumer)         // tails /vm/out/serial.log
  withQemuArgs(String... extra)                                // → VM_EXTRA_ARGS (escape hatch)

  // lifecycle: start() inherited; ready = VMTC_READY log line + QMP handshake + QGA guest-ping success

  // state
  Accelerator accelerator();                                   // KVM | TCG (from VMTC_ACCEL line)
  QmpClient qmp();  GuestAgentClient guestAgent();

  // R3 / R4
  GuestExecResult exec(String... argv);                        // timeout = default exec timeout (60 s ×factor)
  GuestExecResult exec(Duration timeout, String... argv);
  GuestExecResult execShell(String script);                    // /bin/sh -c script
  void copyToVm(Path local, String guestPath);                 // creates parent dirs via guest-exec mkdir -p
  void copyToVm(byte[] content, String guestPath);
  void copyFromVm(String guestPath, Path local);
  byte[] readFromVm(String guestPath);

  // R6 / R9
  List<VmScreen> screens();                                    // size == withScreens(n)
  VmScreen screen(int index);

  // R7
  AudioRecording startAudioRecording(Path hostWav);            // one active at a time; else IllegalStateException

  // R10
  HotplugHandle hotplug(DeviceSpec spec);
  CdromDrive cdrom();                                          // the always-present cd0

  // artifacts
  Path saveSerialLog(Path hostFile);
}
```

**Startup sequence (`containerIsStarting` / `containerIsStarted` overrides):**

1. Resolve `VmImage` → host file (§9.3).
2. Bind it read-only at `/vm/base/disk.img`.
   - On SELinux-enforcing hosts (`/sys/fs/selinux/enforce` reads `1`), use `Bind` with `SELContext.shared` (`:z`).
   - Only files inside the cache dir are ever bound; see §9.3.
3. KVM decision (§8.2 / §9.6) → `HostConfig.withDevices(Device.parse("/dev/kvm"))` when used.
4. Publish ports 4444, 4445 and 5900..5900+n-1 with explicit `PortBinding(Ports.Binding.bindIp("127.0.0.1"), …)`. VNC ports use the configured bind address.
5. Labels:
   - `${LABEL_PREFIX}.managed=true`
   - `${LABEL_PREFIX}.version=${VERSION}`
   - `${LABEL_PREFIX}.session=<random uuid per JVM>`
6. Set the env vars of §7.1. Write the cloud-init seed files with `withCopyToContainer(Transferable, "/vm/seed/…")`.
7. Wait strategy: `Wait.forLogMessage(".*VMTC_READY.*", 1)` with a 60 s timeout. Also fail fast if `VMTC_ERROR` appears.
8. Connect QMP (handshake `qmp_capabilities`) and parse the `VMTC_ACCEL` log line.
9. If a VNC password is configured, call `set_password` for each display.
10. Connect QGA and poll `guest-ping` every 1 s until the guest-agent timeout.
    - On timeout: throw `GuestAgentTimeoutException` with the last 50 serial-log lines attached.
11. Call `guest-info`. If `guest-exec` or `guest-file-open` is listed with `enabled:false`, throw `GuestCommandDisabledException`. Its message points to §10.1 (RPC filter and SELinux).
12. Run `id -Z`. If the result contains `virt_qemu_ga_t`, check that the domain is permissive by running `cat /proc/self/attr/current` **and** test-reading `/usr/share`.
    - On EACCES, throw `GuestAgentConfinedException` with the remediation from §10.1.

`stop()`: send QMP `quit`, then let Testcontainers stop the container.

### 9.3 VM images (R5, image supply)

```java
public sealed interface VmImage {
  static VmImage fromPath(Path file);                          // qcow2 or raw
  static VmImage fromUrl(URI url, String sha256);              // sha256 required
  static VmImage fromContainerDisk(DockerImageName image);     // KubeVirt containerDisk: /disk/<single file>
  static VmImage baked();                                      // runner image already contains /vm/base/disk.img
}
```

#### 9.3.1 Cache (`VmImageCache`)

- **Root:** `${XDG_CACHE_HOME:-$HOME/.cache}/${CACHE_DIR_NAME}/images/`. Override with `-D${SYSPROP_PREFIX}.cacheDir` or `${ENV_PREFIX}CACHE_DIR`.
- **Entry name:** `<sha256>.img`. The format is detected by QEMU at run time.
- **Concurrency:** a `FileChannel.lock()` on `<sha256>.lock` serializes concurrent JVMs. Writes go to `<sha256>.tmp-<pid>`, then `ATOMIC_MOVE`.
- **`fromPath`:**
  1. Hash the file (SHA-256, streaming).
  2. If `<sha256>.img` is missing, try `Files.createLink`. If the hard link fails (cross-device), copy.
  3. Return the cache entry.

  Hashing a multi-GB file takes seconds. Hashes are memoized by `(path, size, mtime)` in `<cache>/index.json`.
- **`fromUrl`:** stream the download to tmp while hashing, verify, then move. A mismatch throws `ImageChecksumException`. Uses `java.net.http.HttpClient`, which honours the JVM proxy properties.
- **`fromContainerDisk`:** see §9.3.3. The cache key is `sha256(imageId + "/" + entryName)` and is stored as `<key>.img`.
- **Eviction:** none automatic. `VmImageCache.prune(Duration olderThan)` is a public helper.

#### 9.3.2 Why the base disk is always bind-mounted from the cache

- The overlay makes per-start state disposable: no copy, and O(1) start.
- The SELinux relabel (`:z`) only ever touches files the library owns.
- One code path inside the container (`/vm/base/disk.img`), whatever the source.

#### 9.3.3 containerDisk extraction (`ContainerDiskExtractor`)

Uses the Testcontainers `DockerClient`; **no `podman` CLI**.

1. Pull if absent (`PullPolicy.defaultPolicy()`).
2. `inspectImageCmd(image).getId()` → `imageId`.
3. `createContainerCmd(image).withCmd("/none")` creates without starting. The command is required because containerDisk images have no CMD.
4. `copyArchiveFromContainerCmd(id, "/disk/")` → tar stream. Require **exactly one** regular file under `/disk/`; otherwise throw `IllegalArgumentException`. Stream it into the cache tmp file.
5. `removeContainerCmd(id).withForce(true)` in `finally`.

| Engine | Result |
|--------|--------|
| Docker 29.8 (sandbox) | **[V]** byte-exact |
| Podman 4.9.3 / 5.7.0 (rootless, compat API) | **[V]** byte-exact (CI run 4) |

#### 9.3.4 `baked()`

- The user builds `FROM ${IMAGE_RUNNER}:…` plus `COPY disk.qcow2 /vm/base/disk.img`.
- No bind is created. The entrypoint uses the baked file.
- Intended for remote engines or podman-machine setups where host bind mounts are impossible.

### 9.4 Exec (R3): `GuestAgentClient`

**Protocol:**
- JSON objects separated by newlines over TCP.
- On every (re)connect, resync with `guest-sync-delimited {id}`: send it, skip a leading `0xFF` byte, and read until `return == id` **[V]**.
- Only one request is in flight per connection. Wrap calls in a `ReentrantLock`; QGA is not multiplexed.

**`exec(timeout, argv)`:**
1. Send `guest-exec {path: argv[0], arg: argv[1..], capture-output: true}` → `pid`.
   - Optional: `env: [...]` and `input-data` (base64 stdin) through the overloads `exec(ExecRequest)`.
2. Poll `guest-exec-status {pid}`. Start at 50 ms and back off ×1.5 up to 500 ms, until `exited` or the timeout.
3. On timeout, run `guest-exec /bin/kill -9 <pid>` (best effort) and throw `GuestExecTimeoutException`.
4. Return `GuestExecResult(int exitCode, Integer signal, String stdout, String stderr, boolean stdoutTruncated, boolean stderrTruncated)`, decoding base64 as UTF-8 with replacement.

**Limits:**
- Each output stream is capped at 16 MiB by QGA **[D]**. Truncation is reported through the flags.
- The guest runs the command with `PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin` **[V]**.

### 9.5 File copy (R4)

**Write:**
1. `guest-file-open {path, mode:"w"}` → handle.
2. For each 1 MiB chunk, `guest-file-write {handle, buf-b64}`.
3. `guest-file-close`.

**Read:**
1. `guest-file-open {mode:"r"}`.
2. Loop `guest-file-read {handle, count: 1048576}` until `eof`. The per-call maximum is 48 MiB **[D]**.
3. `guest-file-close`.

Always close in `finally`.

**Throughput [V]:**

| Accel | Write | Read |
|-------|-------|------|
| KVM | 20 MB/s | 46 MB/s |
| TCG | 1.4 MB/s | 4.8 MB/s |

Bigger data (≥ 100 MB) should go through a hot-plugged disk image; the Javadoc should say so.

**Parent directories:** `copyToVm` runs `mkdir -p <parent>` through guest-exec first.

### 9.6 KVM detection and Podman group handling (R12)

`KvmDetector.usable()` returns true if and only if `/dev/kvm` exists **and** `Files.isReadable && Files.isWritable` for the JVM user.

> With rootless Podman, the device is bind-mounted with the host permissions, so the JVM user's access is what counts **[D]**.

**What Testcontainers can pass through the compat API [V]:**

| Host `/dev/kvm` | Podman | Device only | `GroupAdd keep-groups` | Annotation `run.oci.keep_original_groups=1` (API) | `containers.conf` annotation (server side) |
|-----------------|--------|-------------|------------------------|---------------------------------------------------|--------------------------------------------|
| `0666` | 4.9.3, 5.7.0 | **works** | container fails to start | works | — |
| `0660 root:kvm`, user in `kvm` | 4.9.3 | EACCES | fails to start | EACCES | **works** |
| `0660 root:kvm`, user in `kvm` | 5.7.0 | EACCES | fails to start | **works** | **works** |

All of these were measured with the Podman service running in a normal login session, so `kvm` was a *supplementary* group of the service process. Under `sg kvm`, `kvm` becomes the *primary* group, keep-groups does not preserve it, and every variant fails.

**Library behaviour:**

1. If `usable()` and the device is world-RW (`0666`), pass `--device /dev/kvm` and nothing else.
2. If `usable()` but **not** world-RW (group-only access):
   - Pass the device **and** `HostConfig.withAnnotations(Map.of("run.oci.keep_original_groups","1"))`. This requires the crun runtime, which is the Podman default.
   - **Never** use `GroupAdd keep-groups`: it prevents the container from starting.
   - The entrypoint resolves `VM_ACCEL=auto` by testing `/dev/kvm` itself. The Java side reads the `VMTC_ACCEL` line.
   - If `VMTC_ACCEL tcg` appears although KVM was intended (for example Podman 4.9 ignores the API annotation): under `REQUIRE`, the entrypoint exits 3 because Java passes `VM_ACCEL=kvm`, and Java throws `KvmUnavailableException`. Under `PREFER`, keep running on TCG and log a WARN.
   - In both cases include this remediation text:
     > "Your /dev/kvm is group-restricted and the container could not open it. Fix one of: (a) make /dev/kvm 0666 (systemd default; udev rule `KERNEL=="kvm", MODE="0666"`), or (b) add to `~/.config/containers/containers.conf`: `[containers]\nannotations = ["run.oci.keep_original_groups=1"]` and restart `podman.socket` (needed on Podman < 5.7, where the per-container annotation is ignored)."
3. If not `usable()`: follow §8.2.

### 9.7 Screens, VNC, screenshots, keyboard (R6, R9)

```java
public interface VmScreen {
  int index();
  VncEndpoint vnc();                       // host, mapped port, Optional<String> password  → for library users' viewers
  BufferedImage screenshot();              // QMP screendump {filename:/vm/out/shot-<uuid>.png, device:"video<i>", format:"png"} → copyFileFromContainer
  void screenshot(Path hostPng);
  ScreenRecording startRecording(Path hostWebm);                         // RecordingOptions.defaults()
  ScreenRecording startRecording(Path hostWebm, RecordingOptions opts);  // fps (1..30, default 10), bitrate (default 1_000_000)
  void forceModeset();                     // copies vmtc-fb-activate.py into the guest and runs it on /dev/fb<i> (§7.3); for console-only guests
}
public record VncEndpoint(String host, int port, Optional<String> password) { String uri(); } // "vnc://host:port"
public interface KeyboardInput {            // container.keyboard()
  void type(String text);                  // US layout mapping, verified table in spike typekeys.py [V]
  void press(String... qcodes);            // QMP send-key, e.g. press("ctrl","alt","f2")
}
```

- **Screenshots:** PNG; dimensions are the head's current mode **[V]**.
- **Keyboard:** `send-key` goes to the active console. The mapping table must be copied from `vm-testcontainer/container/spike/typekeys.py` (letters, digits, US punctuation with shift) **[V]**. Characters outside the table throw `IllegalArgumentException`.

### 9.8 Screen recording (R8)

- `ScreenRecording` is `AutoCloseable`. `close()` stops the recording and copies `/vm/out/<name>.webm` to the host path, then returns.
  - `<name>` = `screen<i>-<uuid8>`.
  - Accessors: `Path file()`, `Duration duration()`, `int width()`, `int height()`, all from `VMTC_REC_OK`.
- **Start:** `execInContainer("vmtc-rec","start",name,i,fps,bitrate)`, with env `VNC_PASSWORD` if configured. Expect `VMTC_REC_STARTED`.
- **Stop:** `execInContainer("vmtc-rec","stop",name,"10")`.
  - `VMTC_REC_OK` → success.
  - `VMTC_REC_INVALID` → `RecordingException` with the log tail.
  - `VMTC_REC_KILLED` followed by OK → success with a WARN.
- **Concurrency:** any number of screens may record at once. A second recording on the same screen is allowed (separate VNC clients **[V]** under `force-shared`).
- **On the slim image:** throw `UnsupportedOperationException`. Detect the variant from the image label `${LABEL_PREFIX}.variant`.
- **When the container stops while recordings are open:** `containerIsStopping` stops every open recording first (best effort).

### 9.9 Audio recording (R7, separate WAV)

1. `startAudioRecording(path)`: HMP `wavcapture /vm/out/audio-<uuid8>.wav snd0 44100 16 2`.
2. Parse `info capture` to find the new capture index *n*; the output looks like `[n]: Capturing audio(44100,16,2) to <path>: <bytes> bytes` **[V]**.
3. `close()`: HMP `stopcapture <n>`, then copy the file out and validate it (RIFF header, data > 0 bytes).
4. Only one capture at a time; otherwise `IllegalStateException`.

Guest requirement: the mixer must be unmuted (§10.1). If a capture comes back all zeros, the javadoc points to that requirement.

### 9.10 Hot-plug (R10)

```java
public final class Devices {
  static DeviceSpec usbStorage(Path hostImage, boolean readOnly);   // raw or qcow2
  static DeviceSpec usbKeyboard(); static DeviceSpec usbTablet(); static DeviceSpec usbMouse();
  static DeviceSpec virtioDisk(Path hostImage, boolean readOnly);   // on a free pcie-root-port
  static DeviceSpec nic(String macOrNull);                          // user-mode netdev
  static DeviceSpec cpu();                                          // KVM only (TCG → UnsupportedOperationException)
  static DeviceSpec memory(int sizeMiB);                            // pc-dimm
  static DeviceSpec newBlankDisk(String size, DiskBus bus);         // qemu-img create inside the container (USB|VIRTIO)
}
public interface HotplugHandle extends AutoCloseable { String id(); void unplug(Duration timeout); void close(); }
public interface CdromDrive { void insert(Path hostIso); void eject(); }
```

Every QMP sequence below is **[V]** (CI `hotplug_*`, `cdrom_media_change`):

| Spec | Plug | Unplug |
|------|------|--------|
| usbStorage | copy image into `/vm/run/hp-<id>.img` (`copyFileToContainer`) → `blockdev-add {driver:<fmt>, node-name:n-<id>, read-only, file:{driver:file, filename}}` → `device_add {driver:usb-storage, id, bus:"xhci.0", drive:n-<id>}` | `device_del {id}` → wait until `id ∉ qom-list /machine/peripheral` → `blockdev-del {node-name}` |
| usbKeyboard/Tablet/Mouse | `device_add {driver:usb-kbd\|usb-tablet\|usb-mouse, id, bus:"xhci.0"}` | `device_del` + wait |
| virtioDisk | as usbStorage but `device_add {driver:virtio-blk-pci, id, bus:"hp<k>", drive}` (k = lowest free root port) | as usbStorage |
| nic | `netdev_add {type:user, id:nd-<id>}` → `device_add {driver:virtio-net-pci, id, bus:"hp<k>", netdev, mac?}` | `device_del` + wait → `netdev_del` |
| cpu | `query-hotpluggable-cpus` → first entry without `qom-path` → `device_add {driver:<type>, id, ...props}` | not supported (throws) |
| memory | `object-add {qom-type:memory-backend-ram, id:mem-<id>, size}` → `device_add {driver:pc-dimm, id, memdev}` | not supported (throws). Unplugging DIMMs needs guest cooperation and is out of scope |
| cdrom.insert | copy ISO to `/vm/run/cd-<id>.iso` → `blockdev-change-medium {id:"cd0", filename, format:"raw", read-only-mode:"read-only"}` | `eject {id:"cd0", force:true}` |

Rules:

- The wait for removal polls every 250 ms, default timeout 30 s (×factor). On timeout, throw `HotplugTimeoutException("guest did not release <id>")`.
- `id` = `hp-<counter>`. Root-port allocation is tracked in Java; when none is free, throw `IllegalStateException`.
- Measured guest visibility: USB ≤ 3.2 s, PCIe ≤ 0.4 s, CPU and memory ≤ 0.4 s (KVM) **[V]**.

### 9.11 Error model

| Exception | Raised when |
|-----------|-------------|
| `KvmUnavailableException` | `REQUIRE` and no usable KVM |
| `GuestAgentTimeoutException` | QGA never answers; message includes the serial-log tail |
| `GuestCommandDisabledException` | the RPC filter blocks a command (§10.1) |
| `GuestAgentConfinedException` | the SELinux domain is enforcing (§10.1) |
| `GuestExecTimeoutException` | an exec runs past its timeout |
| `QmpException` | QMP returned an error: class + desc |
| `RecordingException` | a recording file is invalid |
| `HotplugTimeoutException` | the guest did not release an unplugged device |
| `ImageChecksumException` | a downloaded image's SHA-256 did not match |

Every exception is unchecked and extends `VmtcException`.

### 9.12 Cleanup without Ryuk

- **Rootless Podman:** Ryuk must be disabled (`TESTCONTAINERS_RYUK_DISABLED=true`) **[V]**.
- **Orphan cleanup:** `VmtcCleanup.removeOrphans()` removes containers labelled `${LABEL_PREFIX}.managed=true` whose `${LABEL_PREFIX}.session` differs from the current JVM's **and** whose age is > 1 h.
  - It runs once per JVM on first `start()`.
  - Disable it with `-D${SYSPROP_PREFIX}.cleanup=false`.

### 9.13 `${ART_JUNIT}` extension (optional module)

- `@VmTest` registers an extension. When a test fails, it saves the following to `build/vm-artifacts/<test>/`:
  - the serial log;
  - one screenshot per screen;
  - the recordings in progress.
- If the repo also uses the earlier `LoggingAttachmentExtension`, it can attach these files. That integration is out of scope.

---

## 10. Guest contract and reference guest image

### 10.1 Guest requirements (normative)

| # | Requirement | How (Rocky/RHEL family) | Detected by |
|---|-------------|-------------------------|-------------|
| G1 | x86-64 Linux with virtio-blk, virtio-net, virtio-serial | standard | boot |
| G2 | `qemu-guest-agent` running on `org.qemu.guest_agent.0` | preinstalled in Rocky GenericCloud **[V]** | `guest-ping` |
| G3 | exec and file RPCs allowed | `sed -i 's/^FILTER_RPC_ARGS=.*/FILTER_RPC_ARGS=""/' /etc/sysconfig/qemu-ga && systemctl restart qemu-guest-agent` **[V]** | `guest-info` (§9.2 step 11) |
| G4 | QGA SELinux domain permissive | `semanage permissive -a virt_qemu_ga_t` **[V]** | `id -Z` + read test (§9.2 step 12) |
| G5 | Serial console on ttyS0 | `console=ttyS0` (GenericCloud default) | serial log non-empty |
| G6 | Audio tests: `alsa-utils`, mixer unmuted | `dnf install alsa-utils; amixer -c 0 sset Master 90% unmute` (ignore a missing `PCM`) **[V]** | audio test |
| G7 | Multi-screen tests on console-only guests | `VmScreen.forceModeset()` (§9.7) | — |

### 10.2 Cloud-init seed (`CloudInitSeed`)

`CloudInitSeed.forReferenceGuest()` produces the verified seed (`make-seed.sh` **[V]**):

```yaml
#cloud-config
runcmd:
  - sed -i 's/^FILTER_RPC_ARGS=.*/FILTER_RPC_ARGS=""/' /etc/sysconfig/qemu-ga
  - semanage permissive -a virt_qemu_ga_t
  - systemctl enable qemu-guest-agent
  - systemctl restart qemu-guest-agent
  - amixer -c 0 sset Master 90% unmute || true
  - echo VMTC_SEED_DONE > /dev/ttyS0
```

The builder supports:
- `packages(String...)`, which becomes `dnf -y install …; echo DNF_RC=$? > /dev/ttyS0` and requires guest network;
- `runcmd(String...)`;
- `writeFile(path, content)`;
- `rootPassword(String)`, for console debugging.

`meta-data` is `instance-id: <uuid>` plus `local-hostname: vm`.

**Startup:** when a seed is configured, `start()` additionally waits for `VMTC_SEED_DONE` in the serial log (timeout = guest-agent timeout) before returning.

**Timings [V]:**

| Seed | Accel | Time to done |
|------|-------|--------------|
| Offline seed (no packages) | TCG | 281 s |
| With `dnf install alsa-utils` | KVM | about 100 s |
| With `dnf install alsa-utils` | TCG | about 480–670 s |

That is why the pre-baked guest (§10.3) is the default for tests.

> **CI note [V]:** in one KVM run, `dnf` reported failure even though `alsa-utils` did get installed. The seed now logs `DNF_RC` and the dnf tail. Treat `DNF_RC≠0` as a WARN, not a failure, and verify the package instead.

### 10.3 Reference guest image `${IMAGE_GUEST}` (D3)

**Build (`images/guest/customize.sh`, run by the Gradle task `buildGuestDisk`):**

1. Download `${GUEST_BASE_URL}` and verify `${GUEST_BASE_SHA256}`.
2. Boot it **once** in the runner image (KVM if available) with a seed whose runcmd:
   - applies G3, G4 and G6 (installs `alsa-utils`);
   - runs `cloud-init clean --logs` and `truncate -s0 /etc/machine-id`;
   - finishes with `poweroff`.
3. Wait for QEMU to exit. The result is the overlay.
4. Flatten the overlay: `qemu-img convert -O qcow2 -c overlay.qcow2 rocky9-vmtc.qcow2`.
5. Package it as a containerDisk:

   ```dockerfile
   FROM scratch
   ADD --chown=107:107 rocky9-vmtc.qcow2 /disk/rocky9-vmtc.qcow2
   ```

6. Tag `${IMAGE_GUEST}:${VERSION}`.

This approach reuses only verified mechanisms: seed, boot and qemu-img. No libguestfs or `virt-customize` is needed; their availability on UBI is **[U]**.

Tests then use `VmImage.fromContainerDisk(${IMAGE_GUEST}:${VERSION})` with **no** seed, so the guest is ready at QGA-ready time (about 12 s under KVM).

---

## 11. Build (Gradle) details

### 11.1 Conventions

- Java toolchain `${JAVA_RELEASE}`. `-Werror` on javac lint `all,-processing`. Spotless (google-java-format) is optional; adopt whatever the target repo already uses.
- Unit tests use JUnit 5 and need no container engine.
- Integration tests live in `:it`. They are tagged and run by a separate `integrationTest` task that depends on `:images:buildRunnerSlim`, `:images:buildRunnerMedia` and `:images:buildGuestDisk`.

### 11.2 `:images` tasks

| Task | Command |
|------|---------|
| `buildRunnerSlim` | `podman build -f images/runner/Containerfile --build-arg VARIANT=slim --build-arg VERSION=${VERSION} --build-arg LABEL_PREFIX=${LABEL_PREFIX} -t ${IMAGE_RUNNER}:${VERSION}-ubi9.8 images/runner` |
| `buildRunnerMedia` | same as above with `VARIANT=media`, tagged `…-ubi9.8-media` |
| `verifyRunnerImages` | the checks in §6.4 |
| `buildGuestDisk` | `images/guest/customize.sh` (§10.3) → `${IMAGE_GUEST}:${VERSION}` |
| `pushImages` | `podman push` all three tags; only when `-PpushImages=true` |

Each task is an `Exec` with declared inputs (the `images/**` files and `gradle.properties`), so it is up-to-date aware. The engine CLI comes from `-PcontainerEngine=podman`, default `podman`.

### 11.3 Generated constants

`:lib:generateConstants` writes `build/generated/sources/vmtc/${BASE_PACKAGE path}/VmtcConstants.java` from `gradle.properties`:

```java
public final class VmtcConstants {
  public static final String VERSION = "${VERSION}";
  public static final String LABEL_PREFIX = "${LABEL_PREFIX}";
  public static final String SYSPROP_PREFIX = "${SYSPROP_PREFIX}";
  public static final String ENV_PREFIX = "${ENV_PREFIX}";
  public static final String CACHE_DIR_NAME = "${CACHE_DIR_NAME}";
  public static DockerImageName defaultRunnerImage(RunnerVariant v) { … "${IMAGE_RUNNER}:" + VERSION + "-ubi9.8" + (v==MEDIA?"-media":"") }
  public static DockerImageName referenceGuestImage() { … "${IMAGE_GUEST}:" + VERSION }
}
```

The runner and guest images can be overridden at run time with `-D${SYSPROP_PREFIX}.runnerImage` / `-D${SYSPROP_PREFIX}.runnerImageMedia`.

---

## 12. CI

### 12.1 Workflow (GitHub Actions; derived from the verified spike workflow)

- **Jobs:**
  - `unit` on ubuntu-24.04;
  - `integration` with matrix `{ubuntu-24.04 + kvm, ubuntu-24.04 + tcg, ubuntu-26.04 + kvm}`;
  - `compat` (the §9.6 scenarios, kept as a regression probe).
- **Steps for `integration`** (all **[V]** in `.github/workflows/vm-testcontainer-spikes.yml`):
  1. If accel=kvm, add the udev rule (`KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"`), then `udevadm control --reload-rules && udevadm trigger --name-match=kvm`.
  2. Start `podman system service --time=0 unix://$XDG_RUNTIME_DIR/podman/podman.sock &`.
  3. Export `DOCKER_HOST=unix://$XDG_RUNTIME_DIR/podman/podman.sock` and `TESTCONTAINERS_RYUK_DISABLED=true`.
  4. Cache `~/.cache/${CACHE_DIR_NAME}`, keyed by `${GUEST_BASE_SHA256}` + `${VERSION}`.
  5. Run `./gradlew integrationTest -Pvmtc.accel=<kvm|tcg>`.
  6. Upload `build/vm-artifacts/**` and the test reports.
- **Timeouts:** 30 min for KVM jobs, 90 min for TCG.

### 12.2 Expected durations [V]

| Job | Duration |
|-----|----------|
| Image build | about 1 min each |
| Guest disk build | about 2–3 min (KVM) |
| KVM integration suite | about 3–5 min |
| TCG integration suite | about 15–20 min |

### 12.3 Parameter-hygiene check

A CI step greps the repo for each *example value* in §0 (as configured in `gradle.properties`). It fails if any value appears outside the files in the "Lives in" column.

---

## 13. Test plan and acceptance criteria

Each item maps to a verified spike check (`ci_spikes.py`) and must pass under KVM. Under TCG, everything except `hotplug_cpu` must pass.

| ID | Test (`:it`) | Acceptance |
|----|--------------|------------|
| T1 | `accelKvm` / `accelTcg` | `accelerator()` matches mode; `query-kvm.enabled` agrees |
| T2 | `bootToAgent` | QGA ready ≤ 30 s (KVM) / ≤ 180 s (TCG) with the reference guest |
| T3 | `execBasics` | `uname -m` = `x86_64`; exit code 3, stdout and stderr preserved |
| T4 | `copyRoundTrip` | 5 MB and 50 MB SHA-256 equal both ways |
| T5 | `screenshotEachScreen` | n=2: PNG decodes; head 0 is 1280×800 (Rocky console), head 1 is 640×480 before `forceModeset()` and 1280×800 after |
| T6 | `vncExposed` | for each screen, a raw RFB handshake from the host to `vnc().host():port()` returns version `RFB 003.008`; with a password set, security types = [2] |
| T7 | `vncPasswordRejectsWrong` | `rfbsrc` with a wrong password logs "Authentication failed" |
| T8 | `recordTwoScreens` | 12 s recording: each WebM is VP8, \|duration − wall\| < 1 s, width 1280; stop ≤ 2 s |
| T9 | `recordingSurvivesExclusiveClient` | an exclusive (`shared=0`) client connects during recording; recording still valid |
| T10 | `recordStaticScreen` | 10 s with no screen change still yields duration ≥ 9 s |
| T11 | `audioTone` | guest `speaker-test -t sine -f 1000 -l 1` → WAV 44.1 kHz/16/2, RMS > 100, dominant 900–1100 Hz |
| T12 | `keyboardType` | console login via `type()`, then a typed command writes a marker to `/dev/ttyS0` |
| T13 | `hotplugUsbStorage`, `hotplugUsbHid` | guest sees `usb` disk / input device; unplug removes it |
| T14 | `hotplugVirtioDisk`, `hotplugNic` | new `vdX` / new interface; unplug removes it |
| T15 | `cdromInsertEject` | `blkid /dev/sr0` shows the label; mounted content matches; eject removes the media |
| T16 | `hotplugCpu` (KVM only) | `nproc` +1 |
| T17 | `hotplugMemory` | MemTotal grows by > 400 MiB |
| T18 | `imageSources` | `fromPath`, `fromUrl` (served by a local `HttpServer`), `fromContainerDisk`, `baked()` all boot; the cache is hit on the second run (no download) |
| T19 | `guestNotPrepared` | stock Rocky image without seed → `GuestCommandDisabledException`; with only G3 → `GuestAgentConfinedException` |
| T20 | `kvmRequireWithoutKvm` | `REQUIRE` on a TCG runner → `KvmUnavailableException` |
| T21 | `orphanCleanup` | labelled container from a fake older session gets removed |
| T22 | `uefiBoot` | `VM_FIRMWARE=uefi` reaches QGA |

**Unit tests (no engine):**
- QMP and QGA codecs against an in-JVM fake server: sync-delimited `0xFF` handling, chunking, base64, timeouts, event skipping.
- Cache locking and atomic moves.
- Keyboard mapping table.
- Constants generation.

---

## 14. Implementation milestones

| M | Content | Exit criteria |
|---|---------|---------------|
| M1 | Gradle skeleton (§5, §11); runner images (§6, §7); `QemuVmContainer` start/stop, QMP, QGA, exec, copy, `fromPath`, KvmMode | T1–T4, T20 green on KVM and TCG; §6.4 checks pass |
| M2 | Screens, VNC endpoints, password, screenshots, keyboard, `forceModeset`, UEFI | T5–T7, T12, T22 |
| M3 | Image sources (`fromUrl`, `fromContainerDisk`, `baked`), cache, reference guest image build, cloud-init seed | T18, T19 |
| M4 | Recording (media image), audio | T8–T11 |
| M5 | Hot-plug API | T13–T17 |
| M6 | JUnit extension, orphan cleanup, docs, CI hardening, §12.3 hygiene check | T21; all jobs green |

---

## 15. Limits, risks, open items

| # | Item | Impact | Handling |
|---|------|--------|----------|
| L1 | vCPU hot-plug under TCG: device added but guest never sees it (3/3 runs, root cause unknown) | `Devices.cpu()` unusable without KVM | Throw `UnsupportedOperationException` under TCG |
| L2 | Secondary screens dark until the guest modesets them | Recording/screenshot of a 640×480 placeholder | Document; `forceModeset()` for console guests |
| L3 | VNC auth ≤ 8-char passwords (RFB) | Weak auth | Default bind 127.0.0.1; password only as a guard against accidents |
| L4 | No H.264/AV1 encoders | WebM/VP8 only | Accept (VP8 plays in browsers and VLC) |
| L5 | Red Hat: direct qemu-kvm use and TCG unsupported | No vendor support | Accept for test infrastructure |
| R1 | Mixing Rocky RPMs into UBI | Future point releases could conflict | Pin `${ROCKY_RELEASE}`; build-time guard (§6.3); CI rebuild weekly |
| R2 | **Licensing / redistribution** of a published image mixing UBI with Rocky and EPEL content | May block publishing `${IMAGE_RUNNER}` publicly | **Not assessed. Needs a legal/policy decision before `pushImages` to a public registry** |
| R3 | SELinux-enforcing hosts (Fedora/RHEL): `:z` relabel of cache files, `container_use_devices` for `/dev/kvm` | Not testable on GitHub runners (AppArmor) | Acceptance test on a Fedora/RHEL host before 1.0 **[U]** |
| R4 | `/dev/kvm` `0660` hosts | Needs the keep-groups annotation; Podman 4.9 needs it in `containers.conf` | §9.6: annotation + detection + remediation [V] |
| R5 | UEFI firmware paths | — | T22 in M2 |
| R6 | ubuntu-26.04 runner is public preview | CI label may change | Keep 24.04 as the primary |

---

## 16. Assumptions to confirm

1. §0 values (coordinates, package, registry) are examples; replace them at adoption.
2. `${GRADLE_VERSION}` and `${JACKSON_VERSION}` are believed current but were not checked against release pages. Verify when adopting.
3. Max screens = 4 and default hot-plug ports = 4 are design choices, not limits found by testing (only 2 screens and 4 ports were exercised).
4. The `${ART_JUNIT}` module is optional; drop it if not wanted.
5. Publishing images publicly depends on R2.

---

## 17. Evidence index

All in this repository on branch `claude/vm-testcontainer-plan-lqhfa6` (PR #9).

| Evidence | Location |
|----------|----------|
| Spike write-ups (packaging, audio, SELinux, GStreamer, multi-screen) | `vm-testcontainer/container/spike/RESULTS.md` |
| Verified reference scripts | `vm-testcontainer/container/spike/` (`start-vm.sh`, `make-seed.sh`, `gst-record.sh`, `rec.sh`, `vmctl.py`, `typekeys.py`, `fb-activate.py`, `install-packages.sh`, `repos/`) |
| End-to-end feature checks | `vm-testcontainer/container/spike/ci_spikes.py` |
| Compat-API probe | `vm-testcontainer/spike-java/` (`PodmanKvmProbeTest`, `run-scenario.sh`) |
| CI workflow | `.github/workflows/vm-testcontainer-spikes.yml` |
| CI runs | see the table below |

| Run | What it showed |
|-----|----------------|
| 1 (`39137816568`) | KVM all pass; TCG all pass except vCPU hot-plug; compat probe (Podman 4.9.3) |
| 2 (`39141980865`) | + ubuntu-26.04 KVM all pass; compat probe on Podman 5.7.0 (scenario B/C harness used `sg`, superseded by run 3/4) |
| 4 (`39142697864`) | login-session 0660 scenarios A/B/C on Podman 4.9.3 and 5.7.0; containerDisk extraction on both (§3.6, §9.3.3, §9.6) |

---

## 18. In Summary

- **Design:** `QemuVmContainer` runs one QEMU VM per Podman container on a UBI 9.8 runner image. QEMU and `gstreamer1-plugins-good` come from a pinned Rocky 9.8 fallback; `tini` comes from EPEL. Java drives the VM with **QMP** and the guest with **QGA** over loopback-published TCP ports.
- **Verified end to end** on GitHub-hosted runners with real KVM and with TCG:
  - exec and file copy;
  - per-screen screenshots and per-screen VNC (exposed, optionally password-protected);
  - per-screen GStreamer WebM recording;
  - WAV audio capture;
  - keyboard input;
  - hot-plug of USB, PCIe disk/NIC, CD, memory, and CPU (KVM only).
- **Images:** every source resolves to a cached read-only base disk plus a per-run overlay. The reference Rocky 9 guest is pre-customized and shipped as a containerDisk, so tests start in about 12 s under KVM.
- **Hard-won rules:** `tini` as PID 1; `rfbsrc incremental=false`; validate recordings by file, not exit code; `share=force-shared`; per-display `set_password`; guest RPC filter **and** SELinux permissive domain; for a group-restricted `/dev/kvm`, the `run.oci.keep_original_groups=1` annotation (never `GroupAdd keep-groups`).
- **Still open:**
  - publishing/licensing of the mixed image (R2);
  - an SELinux-host acceptance run (R3);
  - the UEFI firmware path check (R5, T22).
- **Adoption:** retarget everything by editing **§0 only**.
