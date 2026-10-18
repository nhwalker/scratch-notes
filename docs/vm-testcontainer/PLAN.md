# VM Testcontainer — Design & Implementation Plan

> Status: **Plan / proposal** — nothing implemented yet.
> Scope: **x86-64 only** (host and guest).
> Date: 2026-10-18

---

## BLUF (Bottom Line Up Front)

- **What:** A Java Testcontainers module (`QemuVmContainer`) that starts one QEMU VM inside a Podman container.
  - Java drives the VM over **QMP**, the QEMU control protocol. QMP covers screenshots, audio capture and hot-plugging.
  - Java drives the guest OS over **QGA**, the QEMU guest agent. QGA covers running commands and copying files.
- **KVM:** Uses `/dev/kvm` when the host exposes it. Otherwise it falls back to software emulation (TCG). TCG has been **verified to work** with the RHEL/Rocky 9.8 `qemu-kvm` build.
- **Biggest packaging fact:** UBI 9 repos contain **no QEMU packages at all**.
  - UBI 9.8 stays the base image, as required.
  - `qemu-kvm`, `qemu-img` and the PulseAudio audio module come from the **Rocky 9.8** AppStream (the fallback repo).
  - `ffmpeg-free` (for screen recording) comes from **EPEL 9**.
  - Mixing Rocky packages onto a UBI base is the top risk. Phase 0 tests it in a spike before anything else is built.
- **What the RHEL QEMU build allows and doesn't:**
  - There is no SPICE and no `usb-audio`.
  - VNC, PNG screendump, Intel HDA audio, the `wav` capture backend and `wavcapture` are all present and were verified in a test.
- **VM image supply — recommendation:** a `VmImage` abstraction that always reduces to **a local, read-only base disk file**.
  - That file is bind-mounted into the container. The container creates a **throwaway qcow2 overlay** on top of it, so the base image is never modified.
  - Supported sources, in priority order:
    1. A host file path.
    2. An HTTP(S) URL with checksum, downloaded into a local cache.
    3. An OCI "containerDisk" image (KubeVirt convention) that is pulled and extracted into the same cache.
  - Baking the disk into the container image is supported as a secondary option, mainly for CI.
- **Guest image requirement:** the guest must run `qemu-guest-agent` with `guest-exec` and `guest-file-*` **allowed**.
  - RHEL/Rocky guests **block these by default** through an allow-list in `/etc/sysconfig/qemu-ga`.

---

## Table of contents

1. [Requirements](#1-requirements)
2. [Verified facts this plan relies on](#2-verified-facts-this-plan-relies-on)
3. [Architecture](#3-architecture)
4. [Container image design](#4-container-image-design)
5. [QEMU launch configuration](#5-qemu-launch-configuration)
6. [Feature design (per requirement)](#6-feature-design-per-requirement)
7. [VM image supply — options & recommendation](#7-vm-image-supply--options--recommendation)
8. [Guest image requirements](#8-guest-image-requirements)
9. [Java API sketch](#9-java-api-sketch)
10. [Podman / Testcontainers integration](#10-podman--testcontainers-integration)
11. [Project layout](#11-project-layout)
12. [Testing strategy](#12-testing-strategy)
13. [Phased delivery plan](#13-phased-delivery-plan)
14. [Risks & open questions](#14-risks--open-questions)
15. [In Summary](#15-in-summary)

---

## 1. Requirements

| # | Requirement | Covered in |
|---|-------------|-----------|
| R1 | Container engine is **Podman** | §10 |
| R2 | Container images based on **UBI 9.8** | §4 |
| R3 | Run commands in the VM | §6.1 |
| R4 | Copy files to and from the VM | §6.2 |
| R5 | Run QEMU images (qcow2, raw) | §5, §7 |
| R6 | Screenshots | §6.3 |
| R7 | Audio recordings | §6.4 |
| R8 | Screen recordings | §6.5 |
| R9 | Simulate hot-plugging devices | §6.6 |
| R10 | Repos: UBI first, then **EPEL**, with **Rocky** as fallback | §4 |
| R11 | Use `/dev/kvm` when available; work without it (slower) | §5.1 |
| R12 | x86-64 only | throughout |
| TBD | Best way to supply the VM image | §7 |

---

## 2. Verified facts this plan relies on

A research pass checked these facts against primary sources and an actual test run on 2026-10-18.

The test ran in a Rocky Linux 9.8 container with `qemu-kvm-core-10.1.0-17.el9_8.5` installed and **no `/dev/kvm`**.

Legend: **V** = verified (source or test), **L** = likely but not tested, **U** = unverified.

| Topic | Fact | Conf. |
|-------|------|-------|
| RHEL 9.8 | GA 2026-05-19 | V |
| UBI 9.8 tags | `registry.access.redhat.com/ubi9/{ubi,ubi-minimal,ubi-init,ubi-micro}:9.8` all resolve | V |
| QEMU in UBI repos | **None.** The UBI baseos, appstream and codeready-builder repos have no `qemu-*` package | V |
| QEMU in Rocky 9.8 AppStream | `qemu-kvm-core`, `qemu-img`, `qemu-guest-agent`, `qemu-kvm-audio-pa`, version 10.1.0-17.el9_8.x | V |
| QEMU version | 10.1.0 | V |
| Binary path | `/usr/libexec/qemu-kvm`; there is no `qemu-system-x86_64` | V |
| TCG (no KVM) | The spec builds with `--enable-tcg`. A test with `-accel tcg -M q35` reached `running` | V |
| Red Hat support | The binary prints "Direct use of qemu-kvm from the command line is not supported by Red Hat". It works, but it is unsupported | V |
| Default CPU | `qemu64` prints a deprecation warning, so pass `-cpu max` (TCG) or `-cpu host` (KVM) | V |
| Display | `-display none` only (no GTK/SDL). **VNC is enabled** | V |
| SPICE | Removed in RHEL 9 | V |
| Screendump PNG | QMP `screendump` with `"format":"png"` produced a valid PNG | V |
| Audio backends | `none` and `wav` are built in; `pa` comes from the `qemu-kvm-audio-pa` subpackage; no alsa, pipewire or spice | V |
| `dbus` audio | Not packaged in Rocky 9.8 | V |
| `wavcapture` HMP command | Works through QMP `human-monitor-command` and produced a valid WAV file | V |
| Sound devices | `ich9-intel-hda`, `intel-hda`, `hda-duplex`, `hda-output`, `hda-micro` | V |
| `usb-audio` | **Not available** (RHEL device config) | V |
| `virtio-sound` | Strings are present in the binary, but it is not listed as a sound device | U |
| EPEL 9 QEMU | No full QEMU, only `qemu-sanity-check` | V |
| EPEL 9 ffmpeg | `ffmpeg-free` 7.1.5 is available; it probably needs CRB for dependencies | V / L |
| QGA commands | `guest-exec`, `guest-exec-status`, `guest-file-open/read/write/close` exist in 10.1 | V |
| QGA limits | `guest-file-read` reads at most 48 MiB per call. `guest-exec` captures at most 16 MiB per stream, then sets a `*-truncated` flag | V |
| QGA default on RHEL/Rocky guests | `/etc/sysconfig/qemu-ga` has an allow-list that **omits guest-exec and guest-file-\*** | V |
| Testcontainers + Podman | Set `DOCKER_HOST=unix://$XDG_RUNTIME_DIR/podman/podman.sock`; rootless needs `TESTCONTAINERS_RYUK_DISABLED=true` | V |
| Testcontainers latest | 2.0.5 (2026-04-20) | V |
| Rootless `--device /dev/kvm` | A bind of the host device; the user must already have access. SELinux hosts may need `container_use_devices=true` | V |
| `/dev/kvm` default mode | `0666` with systemd defaults, so usually no group workaround is needed | V (default) / L (per distro) |
| `--group-add keep-groups` | crun only; "not available for remote commands" | V |
| `keep-groups` through the compat API | Not confirmed | U |
| Podman `--mount type=image` | Since 2.2.0 | V |
| Podman `--mount type=artifact` | Since 5.5.0; stable artifacts since 5.6.0 | V |
| Podman in Rocky 9.8 | 5.8.x | V |
| KubeVirt containerDisk | Disk goes in `/disk/` inside the image, readable by UID 107; qcow2 is recommended | V |

---

## 3. Architecture

```
 ┌──────────────────────────── Host (x86-64 Linux) ──────────────────────────────┐
 │                                                                                │
 │  JUnit test ──► QemuVmContainer (Java, Testcontainers GenericContainer)        │
 │                    │  QmpClient ───────────── TCP (mapped port) ──┐            │
 │                    │  GuestAgentClient ────── TCP (mapped port) ──┤            │
 │                    │  VNC (optional, debug) ─ TCP (mapped port) ──┤            │
 │                    │  copyFileFromContainer (artifacts) ──────────┤            │
 │                    │                                              │            │
 │  VmImageCache  ~/.cache/qemu-testcontainer/<sha256>.qcow2 ─┐      │            │
 │                                                            │ro    │            │
 │  /dev/kvm (optional) ───────────────────────────────┐      │bind  │            │
 │                                                     ▼      ▼      ▼            │
 │  ┌──────────────── Podman container (UBI 9.8 + Rocky qemu-kvm) ───────────┐    │
 │  │  /vm/base/disk.img (ro)   /vm/run/overlay.qcow2 (rw, backing=base)      │    │
 │  │  /vm/out/  (screenshots, wav, webm, serial.log)                         │    │
 │  │                                                                         │    │
 │  │  vm-entrypoint.sh                                                       │    │
 │  │    └─ /usr/libexec/qemu-kvm  -accel kvm -accel tcg  -M q35 ...          │    │
 │  │         ├─ QMP   chardev socket  :4444                                  │    │
 │  │         ├─ QGA   virtio-serial ──► chardev socket :4445                 │    │
 │  │         ├─ VNC   :5900                                                  │    │
 │  │         ├─ serial console ──► /vm/out/serial.log                        │    │
 │  │         └─ audiodev (wav/none) + ich9-intel-hda + hda-duplex            │    │
 │  │  vm-recorder.sh (screendump loop ─► ffmpeg-free ─► webm)  [on demand]   │    │
 │  │                                                                         │    │
 │  │    ┌──────────── Guest VM ────────────┐                                 │    │
 │  │    │  qemu-guest-agent (exec/file ok) │                                 │    │
 │  │    └──────────────────────────────────┘                                 │    │
 │  └─────────────────────────────────────────────────────────────────────────┘    │
 └────────────────────────────────────────────────────────────────────────────────┘
```

Design principles:

1. **Container = one VM.** The container's lifecycle is the VM's lifecycle. This matches the Testcontainers model.
2. **Java talks protocols, not shell.**
   - QMP and QGA are JSON over a socket. Java implements both directly, without going through `podman exec`.
   - This keeps calls fast and avoids quoting problems.
   - `execInContainer` is used only for container-side helpers such as recorder start and stop.
3. **Base disk is never written.** A qcow2 overlay is created per container start. Each test gets a fresh VM, and a cached base image is never corrupted.
4. **TCP chardevs, not unix sockets, for host access.**
   - Testcontainers already maps TCP ports. Bind-mounting a unix socket out of a rootless container runs into UID and SELinux problems.
   - QMP and QGA are unauthenticated, so the mapped ports bind to `127.0.0.1` only. This is acceptable for test infrastructure.

---

## 4. Container image design

### 4.1 Base and repo layering (R2, R10)

```
registry.access.redhat.com/ubi9/ubi:9.8          ← base (R2)
  repos (dnf priority, lower number = preferred):
    priority=10  ubi-9-baseos / ubi-9-appstream / ubi-9-codeready-builder   (built in)
    priority=20  epel-9                                                      (EPEL)
    priority=90  rocky-9.8-baseos / rocky-9.8-appstream / rocky-9.8-crb      (fallback)
```

- **Base image choice:** use `ubi9/ubi:9.8`, the full variant with `dnf`.
  - `ubi-minimal` uses `microdnf`, which has weaker repo-priority handling. That is **L**, untested.
  - `ubi-init` (systemd) isn't needed; one supervisor script is enough.
- **Pin Rocky to 9.8**, not the floating `9`, so its libraries match the UBI 9.8 base.
  - Proposed baseurl: `https://dl.rockylinux.org/vault/rocky/9.8/...` once 9.8 is vaulted, or `pub/rocky/9.8/...` while it's current. The exact path is **U** and goes into the spike.
- **Restrict what Rocky may supply** with `includepkgs=` or `excludepkgs=`.
  - Exclude `rocky-release*`, `rocky-logos*` and anything that would replace `redhat-release`.
  - Ideally allow only `qemu-*` plus the dependencies the spike shows are actually missing from UBI.
- **GPG:** import the Rocky and EPEL keys with `rpm --import` and keep `gpgcheck=1`.
- **Weak dependencies:** install with `--setopt=install_weak_deps=False` to keep the image small.

### 4.2 Packages

| Package | From | Purpose |
|---------|------|---------|
| `qemu-kvm-core` | Rocky AppStream | The emulator (`/usr/libexec/qemu-kvm`) |
| `qemu-img` | Rocky AppStream | Creates the overlay; converts formats |
| `qemu-kvm-audio-pa` | Rocky AppStream | *Optional.* Not needed for wav capture, so probably omit it |
| `edk2-ovmf` | Rocky AppStream (L) / UBI? (U) | UEFI firmware for UEFI guests |
| `seabios-bin` | Rocky AppStream (L) | BIOS for legacy guests (likely pulled in as a dependency) |
| `ffmpeg-free` | EPEL (+CRB deps) | Screen recording encode; mux audio and video |
| `xorriso` or `genisoimage` | UBI? / Rocky (U) | Build the cloud-init NoCloud seed ISO (§8) |
| `jq`, `socat` | UBI (L) | Small helper scripts in the container |

### 4.3 Image variants (published tags)

| Image | Contents | Size target |
|-------|----------|-------------|
| `qemu-testcontainer-runner:<ver>-ubi9.8` | QEMU + helpers; **no** ffmpeg | small |
| `qemu-testcontainer-runner:<ver>-ubi9.8-media` | + `ffmpeg-free` for screen recording | larger |

Splitting the images keeps the common case (exec, copy, screenshot) small. The Java side checks for `ffmpeg` and reports a clear error if screen recording is requested on the slim image.

Open question: a single image may be simpler. See §14.

### 4.4 Container entrypoint (`vm-entrypoint.sh`)

The entrypoint takes its configuration from environment variables set by Java. Steps:

1. Validate `/vm/base/disk.img` and detect its format with `qemu-img info --output=json`.
2. Create the overlay: `qemu-img create -f qcow2 -F <fmt> -b /vm/base/disk.img /vm/run/overlay.qcow2 [size]`.
3. Choose the accelerator:
   - If `/dev/kvm` is present and writable, use `-accel kvm -cpu host`.
   - Otherwise use `-accel tcg -cpu max` and log a warning.
   - Java can force either mode with `VM_ACCEL=kvm|tcg|auto`.
4. `exec` QEMU, so QEMU becomes PID 1's child or PID 1 itself and signals propagate.
5. Print `QEMU_READY` once the QMP socket is listening. Java's wait strategy keys on this line.

---

## 5. QEMU launch configuration

### 5.1 Baseline command (illustrative)

```bash
/usr/libexec/qemu-kvm \
  -name testvm -nodefaults -no-user-config \
  -machine q35 \
  -accel kvm -accel tcg \                # QEMU tries in order; or chosen explicitly by entrypoint
  -cpu host|max -smp ${VM_CPUS} -m ${VM_MEM_MB} \
  -display none -vnc 0.0.0.0:0 \         # VNC also gives a framebuffer for screendump
  -device virtio-vga \                   # or VGA/bochs-display; must be on RHEL allow-list (spike)
  -qmp tcp:0.0.0.0:4444,server=on,wait=off \
  -chardev socket,id=qga0,host=0.0.0.0,port=4445,server=on,wait=off \
  -device virtio-serial-pci -device virtserialport,chardev=qga0,name=org.qemu.guest_agent.0 \
  -serial file:/vm/out/serial.log \
  -drive if=none,id=disk0,file=/vm/run/overlay.qcow2,format=qcow2 \
  -device virtio-blk-pci,drive=disk0,bootindex=0 \
  -netdev user,id=net0[,hostfwd=tcp::2222-:22] -device virtio-net-pci,netdev=net0 \
  -audiodev none,id=snd0 \               # or wav,path=... (see §6.4)
  -device ich9-intel-hda -device hda-duplex,audiodev=snd0 \
  -device qemu-xhci,id=xhci \            # USB hot-plug bus
  -device pcie-root-port,id=hp1,chassis=1 -device pcie-root-port,id=hp2,chassis=2 ...  # PCIe hot-plug slots
  [-drive if=pflash,... OVMF]            # UEFI guests only
```

Notes:

- **Container ports:** QMP and QGA bind `0.0.0.0` *inside* the container. Testcontainers maps them to random host ports on loopback.
- **q35 hot-plug:** q35 needs **pre-created `pcie-root-port`s** to hot-plug PCIe devices. The number of ports is configurable and defaults to 4.
- **User-mode networking (`-netdev user`):**
  - It needs libslirp to be compiled in. Whether the RHEL 9 build includes it is **U**; the spike checks.
  - If it is missing, use `passt` with `-netdev stream`. RHEL 9 ships passt.
  - Networking is not required for QGA-based exec and copy.

### 5.2 KVM vs TCG

| | KVM | TCG |
|-|-----|-----|
| Requires | `/dev/kvm` passed into the container | nothing |
| CPU model | `host` | `max` |
| Speed | near native | roughly 5–50× slower (workload dependent, **L**) |
| Default timeouts | base | the Java side multiplies boot and exec timeouts by `tcgTimeoutFactor` (default 10) |

`KvmMode` in Java:

- `REQUIRE`: fail fast if the host has no usable `/dev/kvm`.
- `PREFER` (default): use KVM when available, otherwise TCG.
- `DISABLE`: always use TCG.

---

## 6. Feature design (per requirement)

### 6.1 Run commands in the VM (R3)

- **Primary path: QGA `guest-exec`** with `capture-output: "separated"`, then poll `guest-exec-status` until `exited: true`.
  - Polling starts at 50 ms and backs off to 500 ms.
  - Output comes back base64-encoded, so Java decodes it.
  - Returns `VmExecResult(exitCode, stdout, stderr, truncatedOut, truncatedErr)`.
  - The **16 MiB per-stream cap** is documented. If a truncation flag is set, the API raises a warning or exception, depending on configuration.
  - For large output, the recommended pattern is to redirect it to a file in the guest and then `copyFromVm`.
- **Shell convenience:** `execShell("cmd | grep x")` wraps the command as `/bin/sh -c`.
- **Optional fallback: SSH.** It uses `hostfwd` port 2222→22, the guest's sshd and a key injected by cloud-init. It covers guests without QGA, such as minimal or non-Linux images.
  - This is a Phase 3 nice-to-have, not core.
- **Timeouts:**
  - Each call has a timeout.
  - When it expires, QGA cannot kill the guest process directly. The module can run `guest-exec` of `kill <pid>`, because `guest-exec` returns a pid.

### 6.2 Copy files to and from the VM (R4)

- **To the VM:**
  1. `guest-file-open(path, "w")`.
  2. Loop `guest-file-write` with base64-encoded chunks, 1 MiB raw by default and configurable.
  3. `guest-file-close`.
  4. Optional `guest-exec chmod`.
- **From the VM:** loop `guest-file-read(handle, count=1 MiB)` until `eof`. The 48 MiB cap applies per call, not per file.
- **Directories:**
  - v1 handles them by running `tar` in the guest through `guest-exec` and transferring the archive as one file.
  - The alternative is walking the tree with `guest-exec ls`.
- **Throughput:** QGA is base64 over a serial channel, so it is slow (**L**: roughly tens of MB/s at best).
  - For bulk data such as large test fixtures, document the alternatives:
    - **Attach a disk:** build a raw or ISO image in the container (`qemu-img` / `xorriso`) and hot-plug it (§6.6). That is fast and needs no guest network.
    - Use SSH/SCP if it is enabled.
- **virtiofs / 9p:** not planned.
  - 9p/virtfs is very likely disabled in the RHEL build (**U**).
  - virtiofs needs a separate `virtiofsd` daemon and shared memory. That is possible later, but complex.

### 6.3 Screenshots (R6)

- QMP: `{"execute":"screendump","arguments":{"filename":"/vm/out/shot-N.png","format":"png"}}` (verified).
- Java then calls `copyFileFromContainer` to the caller's path, or returns a `BufferedImage`.
- The VM needs a display device, such as `virtio-vga`, `VGA` or `bochs-display`. Which of these the RHEL build allows is checked in the spike.
- **Bonus:** `VmScreen.waitFor(Predicate<BufferedImage>)` provides polling helpers. Image matching or OCR is **out of scope** for v1.

### 6.4 Audio recording (R7)

Guest audio goes to an Intel HDA codec (`ich9-intel-hda` + `hda-duplex`). Capture options:

| Option | How | Start/stop at will? | Notes |
|--------|-----|---------------------|-------|
| **A. `wavcapture` (recommended)** | `-audiodev none,id=snd0`; then HMP through QMP: `wavcapture /vm/out/a.wav snd0 [freq bits ch]`, and `stopcapture <n>` | **Yes** | Verified to produce WAV with the `wav` backend. **Must verify with the `none` backend** (spike); otherwise use B's backend plus wavcapture |
| B. `wav` audiodev | `-audiodev wav,id=snd0,path=/vm/out/all.wav` | No; records the whole VM lifetime | Simple fallback. Java can trim by timestamps |
| C. `pa` audiodev + PulseAudio in the container | Run pulseaudio and `parec` in the container | Yes | Heavier; not needed |

- **API:** `try (AudioRecording r = vm.startAudioRecording(path)) { ... }`. On `close()`, the module sends `stopcapture`, copies the file out and returns its metadata.
- **HMP caveat:** HMP output is text. The module parses `info capture` to find the capture index for `stopcapture`.
- Microphone input (feeding audio *into* the guest) is **out of scope** and a possible extension.

### 6.5 Screen recording (R8)

QEMU has no built-in video recorder. VNC + ffmpeg is not an option, because ffmpeg has no VNC input (**L**). The recommended approach is a **frame-grab loop in the container**:

```
vm-recorder.sh start <fps> <out.webm>
   loop: QMP screendump → /vm/run/frames/%08d.png  (or pipe as image2pipe)
   ffmpeg-free -f image2pipe -framerate <fps> -i - -c:v libvpx-vp9 ... out.webm
vm-recorder.sh stop   → finalize, optionally mux wav from §6.4 into the webm (opus)
```

- **Codec:** `ffmpeg-free` excludes patent-encumbered encoders such as x264/H.264. Use **VP8/VP9 in WebM**, which ffmpeg-free *likely* includes (**L**, spike), or MJPEG/AVI as a guaranteed fallback.
- **Frame rate:** default 5 fps; configurable.
  - Each screendump is a full-frame PNG encode inside QEMU, and under TCG that competes with the guest for CPU.
- **Frame timing:** the loop is wall-clock driven and duplicates frames if a grab runs late.
- **Pure-Java alternative (slim image):**
  - Java pulls PNG frames and writes them out as an image sequence or Motion-JPEG AVI, with no ffmpeg dependency.
  - This is the fallback when the media image isn't used.
  - Each frame then crosses the container boundary, so it is slower.
- **API:** `try (ScreenRecording r = vm.startScreenRecording(path, RecordingOptions.fps(5).withAudio(true))) {...}`.

### 6.6 Hot-plug simulation (R9)

Everything goes through QMP. The module waits for completion events such as `DEVICE_DELETED`.

| Device type | Plug | Unplug | Prerequisite |
|-------------|------|--------|--------------|
| USB mass storage | `blockdev-add` (file → node) + `device_add usb-storage,bus=xhci.0,drive=<node>` | `device_del` → wait `DEVICE_DELETED` → `blockdev-del` | `qemu-xhci` present at boot |
| USB HID (keyboard/mouse/tablet) | `device_add usb-kbd` / `usb-tablet` | `device_del` | xhci |
| virtio-blk disk | `blockdev-add` + `device_add virtio-blk-pci,bus=hpN` | `device_del` (needs guest cooperation) | free `pcie-root-port` |
| NIC | `netdev_add user,id=..` + `device_add virtio-net-pci,bus=hpN` | `device_del` + `netdev_del` | free root port |
| CD-ROM media change | `blockdev-change-medium` / `eject` | — | a SCSI or AHCI CD drive present at boot |
| Serial / virtio-serial port | `chardev-add` + `device_add virtserialport` | `device_del` + `chardev-remove` | virtio-serial bus |
| vCPU / memory (stretch) | `device_add <cpu-type>` / `object-add memory-backend-ram` + `pc-dimm` | — | `-smp maxcpus=`, `-m slots=,maxmem=` |

- **Allowed devices:** the RHEL build only allows certain device types, so the exact list must be confirmed with `-device help` in the spike.
  - `usb-audio` is already known to be **unavailable**.
- **PCIe unplug needs the guest:** it is a cooperative ACPI/PCIe handshake. If the guest ignores it, `DEVICE_DELETED` never arrives, so the module applies a timeout and reports the failure.
- **Real host USB passthrough** (`usb-host`) is **out of scope**: it needs device access from the host, which rootless Podman can't give.
- **Images for plugged storage:** create them on the fly with `qemu-img create` inside the container, or copy them in from the host.
- **Java API:**

  ```java
  HotplugHandle h = vm.hotplug(Devices.usbStorage(Path.of("data.img")).readOnly());
  // ... assert guest saw it via vm.exec("lsblk")
  h.unplug(Duration.ofSeconds(30));
  ```

---

## 7. VM image supply — options & recommendation

### 7.1 Options considered

| # | Option | How | Pros | Cons |
|---|--------|-----|------|------|
| 1 | **Host file bind mount** | `withFileSystemBind(host, "/vm/base/disk.img", READ_ONLY)` | Zero copy; instant; simple | Image must exist locally; SELinux relabel (`:z`) on a host file can be intrusive (see §10.3) |
| 2 | Copy into container | `withCopyFileToContainer` (tar stream over the API) | Works with remote engines | Copies multi-GB on **every** start: slow |
| 3 | **Bake into derived image** | `FROM runner` + `COPY disk.qcow2 /vm/base/disk.img` | Distributable through a registry; cached by layers; good for CI | One image per disk; large layers; rebuild on runner upgrade |
| 4 | **containerDisk OCI image** (KubeVirt convention) | `FROM scratch` + `/disk/*.qcow2`; consumed by mount or by extract-and-cache | Versioned in a registry independently of the runner; known convention | Needs a mount or extract step (see 7.3) |
| 5 | OCI artifact (`podman artifact`) | `--mount type=artifact` (Podman 5.5+/5.6+) | Purpose-built for blobs; no fake filesystem | Newer; docker-java/Testcontainers can't express this mount type (**L**, the `MountType` enum); Podman-only |
| 6 | **URL + checksum download, cached** | Java downloads to `~/.cache/...`, verifies sha256, then option 1 | Easy for public cloud images (Rocky, Fedora, etc.) | First-run download time; needs network |
| 7 | Named Podman volume pre-seeded | Volume holds the disk; mount the volume | Survives runs; works for remote engines | Lifecycle management; opaque to the user |

### 7.2 Recommendation

**Every source resolves to a local, read-only base file. It is bind-mounted, and a per-run qcow2 overlay sits on top.** One runtime path; many front-ends.

```
 VmImage.fromPath(Path)                ─┐
 VmImage.fromUrl(URI, sha256)          ─┤──► VmImageResolver ──► local file in cache ──► ro bind mount
 VmImage.fromContainerDisk("reg/img:t")─┤        (download / podman pull+extract,          /vm/base/disk.img
 VmImage.fromClasspath("vm/x.qcow2")   ─┘         verify checksum, lock, dedupe)               │
                                                                                               ▼
 VmImage.baked()  ── image already contains /vm/base/disk.img ─────────────────► qemu-img create overlay
```

Why:

1. **Speed:** a bind mount is O(1). The overlay means the cached base is never written and every test starts clean.
2. **One code path** inside the container, whatever the source. That means fewer things to test.
3. **Supply chain:** containerDisk images let teams version and distribute guest images through the same registry and tooling as everything else.
   - Extracting them to the cache avoids depending on mount types that docker-java's compat API may not support (`type=image` and `type=artifact`, **L/U**).
   - Extraction uses the `podman` CLI (`podman create` + `podman cp`, or `podman image mount`) and is keyed by image digest.
4. **CI friendliness:** `VmImage.baked()` (option 3) covers environments where bind mounts are impossible, such as a remote Podman or a podman-machine VM on macOS.

**Cache details:**

- Location: `${XDG_CACHE_HOME:-~/.cache}/qemu-testcontainer/images/<sha256>.<fmt>`.
- A file lock (`FileChannel.lock`) guards concurrent test JVMs.
- Downloads are atomic: write to a temp file, then rename.
- Eviction is manual; a helper is provided.

**Phasing:**

- Phase 1: `fromPath`, `fromUrl`.
- Phase 2: `fromContainerDisk`, `baked`.
- Phase 3: `fromClasspath`, which is rarely sensible for GB-sized files.

---

## 8. Guest image requirements

The module defines a **guest contract** and ships a reference build recipe.

| Need | Why | How |
|------|-----|-----|
| x86-64, virtio drivers | virtio-blk, virtio-net, virtio-serial | standard in modern distros |
| `qemu-guest-agent` running | exec and copy (§6.1, §6.2) | package + enable service |
| **QGA RPC filter relaxed** | RHEL/Rocky `/etc/sysconfig/qemu-ga` blocks guest-exec and guest-file-\* by default (V) | set `FILTER_RPC_ARGS=""` (or add the needed RPCs) |
| Serial console | boot diagnostics in `serial.log` | `console=ttyS0` on the kernel cmdline |
| ACPI hot-plug support | §6.6 unplug | standard |
| Optional: cloud-init | first-boot config without rebuilding the image | NoCloud seed ISO generated by the container |

**Using stock cloud images:**

- Public cloud images such as Rocky 9 GenericCloud usually ship cloud-init, but often don't have QGA enabled with exec allowed.
- The module can generate a **NoCloud seed ISO** (`cidata` volume label) whose `user-data`:
  - installs and enables `qemu-guest-agent`;
  - rewrites `/etc/sysconfig/qemu-ga`;
  - restarts the agent.
- **Caveats:**
  - First boot needs network access to install the package; the alternative is the `virt-customize` path below.
  - This only works for cloud-init-enabled images.
- **Pre-customized images are better for CI.**
  - Ship a `build-guest-image` recipe that uses `virt-customize`. Whether libguestfs is available on UBI or Rocky in the container is **U**; it can run on the host.
  - Publish the result as a containerDisk (§7).

**Reference test guest:** choosing a small, QGA-capable x86-64 guest image for this module's own integration tests is an open question (§14).

---

## 9. Java API sketch

```java
@Testcontainers
class MyVmTest {

  @Container
  static QemuVmContainer vm = new QemuVmContainer(QemuVmContainer.DEFAULT_IMAGE)   // runner image
      .withVmImage(VmImage.fromUrl(URI.create("https://.../rocky-9-qga.qcow2"), "sha256:..."))
      .withKvm(KvmMode.PREFER)
      .withCpus(2).withMemoryMb(2048)
      .withDisplay(Display.VIRTIO_VGA)
      .withAudio(true)
      .withHotplugSlots(4)
      .withSerialLogConsumer(new Slf4jLogConsumer(log))   // stream serial.log
      .withGuestAgentStartupTimeout(Duration.ofMinutes(3)); // ×tcgTimeoutFactor under TCG

  @Test
  void example() throws Exception {
    VmExecResult r = vm.exec("uname", "-m");
    assertEquals("x86_64", r.stdout().strip());

    vm.copyToVm(Path.of("src/test/resources/app.tar"), "/tmp/app.tar");
    vm.copyFromVm("/var/log/messages", tempDir.resolve("messages"));

    vm.screenshot(tempDir.resolve("boot.png"));

    try (var rec = vm.startScreenRecording(tempDir.resolve("run.webm"), RecordingOptions.fps(5).withAudio(true));
         var aud = vm.startAudioRecording(tempDir.resolve("run.wav"))) {
      vm.exec("speaker-test", "-t", "sine", "-l", "1");
    }

    try (HotplugHandle usb = vm.hotplug(Devices.usbStorage(tempDir.resolve("stick.img")))) {
      await().until(() -> vm.exec("lsblk").stdout().contains("sdb"));
    } // auto-unplug
  }
}
```

### Main types

| Type | Responsibility |
|------|----------------|
| `QemuVmContainer extends GenericContainer<QemuVmContainer>` | Container config, `/dev/kvm` wiring, ports, wait strategy, facade methods |
| `QmpClient` | QMP handshake (`qmp_capabilities`), request/response correlation by `id`, async event stream, `human-monitor-command` helper |
| `GuestAgentClient` | QGA JSON protocol, `guest-sync-delimited` resync (needed after reconnects), exec, file ops |
| `VmImage`, `VmImageResolver`, `VmImageCache` | §7 |
| `ScreenRecording`, `AudioRecording`, `HotplugHandle` | `AutoCloseable` handles |
| `Devices` | Builders for hot-plug devices, mapped to `blockdev-add` / `device_add` argument maps |
| `KvmMode`, `KvmDetector` | Host-side check that `/dev/kvm` exists and is R/W by the current user |

### Dependencies

- `org.testcontainers:testcontainers` 2.0.x (latest verified: 2.0.5).
- JSON: Jackson. Plain JSON-over-socket is used, with no QMP library.
  - Existing Java QMP libraries are few and likely unmaintained (**U**).
- Java 21, the same as the repo.

### Wait strategy

1. Wait for `QEMU_READY` in the container logs.
2. Connect to QMP.
3. Poll QGA `guest-ping` until it answers or the boot timeout expires.

### Integration with existing code

- The serial log and any recordings can feed into the repo's existing `LoggingAttachmentExtension` as test attachments. This is optional.

---

## 10. Podman / Testcontainers integration

### 10.1 Engine connection (R1)

- Enable the socket: `systemctl --user enable --now podman.socket`.
- `DOCKER_HOST=unix://${XDG_RUNTIME_DIR}/podman/podman.sock`.
- Rootless: `TESTCONTAINERS_RYUK_DISABLED=true`. Without Ryuk, Java cleanup relies on `@Container` / `close()`. Document how to clean up containers left behind by a crashed JVM, by filtering on a label the module adds.
- Rootful: Testcontainers ≥ 1.19.0 needs no special Ryuk flag. `ryuk.container.privileged=true` is still available.

### 10.2 `/dev/kvm` passthrough

```java
withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
    .withDevices(Device.parse("/dev/kvm")));   // only when KvmDetector says usable
```

- **Rootless:** the device is bind-mounted from the host, so the invoking user must already have read and write access.
  - With the systemd default mode `0666`, they usually do.
  - With `0660`, the user must be in group `kvm`, and the group must reach the container. `--group-add keep-groups` is the CLI way.
  - **Whether that works through the Docker-compatible API is unverified.** Phase 0 tests it.
  - Fallback: the annotation `run.oci.keep_original_groups=1` through `HostConfig` annotations (crun).
- **SELinux hosts:** may need `setsebool -P container_use_devices=true`. `KvmDetector` cannot see this, so start-up checks it inside the container and falls back or fails according to `KvmMode`.
- **Under TCG:** no device, no capabilities, no privileged mode.

### 10.3 Bind-mount & SELinux

- On SELinux-enforcing hosts, a read-only bind of a cache file needs the right label.
  - Options: relabel once, e.g. `chcon -t container_file_t` on the cache dir, or use `:z` on the mount.
  - `:z` relabels the host file, which is fine for files in the module's own cache directory.
  - **Never** relabel arbitrary user paths automatically. For `fromPath` outside the cache, document the requirement or copy the file into the cache.
- **UID mapping:** QEMU runs as the container's root user, which maps to the host user in rootless mode. The base file therefore only needs to be readable by the invoking user.

### 10.4 Resources

- Memory is VM RAM plus QEMU overhead, around 256 MB+ (**L**). Optionally set a container memory limit of VM RAM + 512 MB.
- `/vm/run` (the overlay) and `/vm/out` live in the container's writable layer by default. A tmpfs is an option for speed when RAM allows.

---

## 11. Project layout

The repo is currently a single-module Maven project (`scratch-notes`). Proposal: convert it to a multi-module build, or keep the VM work in a self-contained subdirectory.

```
scratch-notes/
├─ pom.xml                         (becomes <packaging>pom</packaging> aggregator — or leave as-is, see §14)
├─ vm-testcontainer/
│  ├─ pom.xml
│  ├─ container/
│  │  ├─ Containerfile             (UBI 9.8 + repos + qemu)
│  │  ├─ Containerfile.media       (FROM runner + ffmpeg-free)
│  │  ├─ repos/epel.repo, rocky-9.8.repo
│  │  └─ scripts/vm-entrypoint.sh, vm-recorder.sh, mk-seed-iso.sh
│  ├─ guest/
│  │  └─ build-guest-image.sh      (virt-customize recipe → containerDisk)
│  └─ src/
│     ├─ main/java/io/github/nhwalker/vmtc/...   (QemuVmContainer, qmp/, qga/, image/, media/, hotplug/)
│     └─ test/java/...                            (unit + integration)
└─ docs/vm-testcontainer/PLAN.md   (this file)
```

Image build:

- `podman build` is driven by a Maven profile (`exec-maven-plugin`) or by a script.
- Tests can alternatively use Testcontainers' `ImageFromDockerfile` so they need no pre-built image. It works with Podman's compat API, **L**.

---

## 12. Testing strategy

| Level | What | Needs |
|-------|------|-------|
| Unit | QMP and QGA codecs, chunking, base64, event correlation, `HotplugHandle` state machine | Fake socket servers (in-JVM) |
| Container smoke | Image builds; `qemu-kvm -version`; `-accel help` contains `tcg`; `-device help` contains expected devices; `ffmpeg -encoders` contains `libvpx` | Podman |
| Integration (TCG) | Boot the reference guest under TCG; exec; copy; screenshot; audio; record; hot-plug | Podman, ~2–4 GB RAM, **slow** |
| Integration (KVM) | Same suite with `KvmMode.REQUIRE` | Podman + `/dev/kvm` |

- JUnit 5 tags: `@Tag("container")`, `@Tag("vm-tcg")`, `@Tag("vm-kvm")`.
- `Assumptions` skip a suite when Podman or KVM is absent.
- **Media assertions:**
  - PNG decodes with non-zero dimensions.
  - WAV header is valid with duration greater than 0.
  - For audio *content*, generate a known tone in the guest and check its RMS or dominant frequency with a small FFT. That is a stretch goal.
  - The WebM is probed with `ffprobe`, and its frame count is checked.

---

## 13. Phased delivery plan

```
Phase 0  Spikes (de-risk)      ──► Phase 1  Core VM        ──► Phase 2  Media & hot-plug  ──► Phase 3  Polish
 repo mixing, device list,          container, QMP, QGA,         screenshots, audio,            SSH fallback,
 slirp/passt, wavcapture+none,      exec, copy, fromPath/        screen recording,              classpath images,
 keep-groups via API, ffmpeg        fromUrl, KVM/TCG             hot-plug, containerDisk        docs, CI recipes
 codecs, guest image                                             baked images
```

### Phase 0 — Spikes (each has a yes/no exit criterion)

1. **UBI 9.8 + Rocky 9.8 repo mix:** `dnf install qemu-kvm-core qemu-img` succeeds on `ubi9/ubi:9.8` with Rocky at priority 90 and `rocky-release` excluded.
   - Record which dependencies came from Rocky.
   - Confirm `rpm -V` is clean and no UBI package was replaced.
2. **Device allow-list:** capture `/usr/libexec/qemu-kvm -device help` and confirm the needed devices exist:
   - `virtio-vga`, `qemu-xhci`, `usb-storage`, `usb-kbd`, `usb-tablet`, `pcie-root-port`, `virtio-blk-pci`, `virtio-net-pci`, `virtserialport`, `pc-dimm`.
3. **Networking:** is `-netdev user` (slirp) available? If not, does passt work in the container?
4. **Audio:** does `wavcapture` work with `-audiodev none`? If not, choose a backend per §6.4.
5. **Podman compat API:**
   - Do `/dev/kvm` device mapping, `GroupAdd: ["keep-groups"]` and the annotation fallback take effect through the docker-java socket?
   - Run this both rootless and rootful.
6. **ffmpeg-free on UBI + EPEL:** does it install? Which CRB packages come from UBI CRB, and which need Rocky CRB? Is `libvpx-vp9` listed among the encoders?
7. **Reference guest:** choose and build a small x86-64 guest with QGA (exec allowed) and record its boot time under KVM and TCG.

**Exit criteria:**

- All spikes are green or have a decided fallback.
- §2 and §14 are updated with the results.

### Phase 1 — Core

- Containerfile (slim) and entrypoint; KVM/TCG selection; overlay creation.
- `QemuVmContainer` with ports, wait strategy and serial log.
- `QmpClient`, `GuestAgentClient`, `exec`, `copyToVm`, `copyFromVm`.
- `VmImage.fromPath`, `VmImage.fromUrl`, and the cache.
- Unit tests and the TCG integration suite.

### Phase 2 — Media & hot-plug

- `screenshot`.
- `startAudioRecording`.
- Media image, `vm-recorder.sh`, `startScreenRecording`, and the pure-Java fallback.
- `Devices` and `hotplug` for USB storage, USB HID, virtio-blk, NIC and CD media change.
- `VmImage.fromContainerDisk` and `VmImage.baked()`; the guest image build recipe.

### Phase 3 — Polish

- Optional SSH exec and copy.
- Cleanup tooling for leftover containers when Ryuk is off.
- Documentation and CI examples (GitHub Actions, with and without KVM).
- Optional cloud-init seed ISO path for stock cloud images.

---

## 14. Risks & open questions

### Risks

| # | Risk | Impact | Mitigation |
|---|------|--------|-----------|
| K1 | **Rocky packages on a UBI base**: dependency conflicts, or replacing UBI packages | Image won't build or is subtly inconsistent | Pin to 9.8, priorities, `excludepkgs`, Phase 0 spike #1. Fallback: base the image on `rockylinux:9.8`. That violates R2, so **it needs your approval** |
| K2 | **Licensing / redistribution** of an image that mixes UBI and Rocky/EPEL content | Can't publish the image publicly | Needs a legal/policy check against the UBI EULA. **Not assessed in this plan**; I can't give an authoritative answer |
| K3 | Red Hat says direct `qemu-kvm` use and TCG are **unsupported** | No vendor support | Accept for test infrastructure; document |
| K4 | TCG is very slow | Long or flaky CI | `tcgTimeoutFactor`, small guests, prefer KVM runners |
| K5 | QGA is slow for big files | Slow tests | Disk-attach path (§6.2); SSH fallback |
| K6 | Guest images block QGA exec/file by default | "Nothing works" for new users | Clear error message that detects the `CommandDisabled` error; guest recipe; cloud-init seed |
| K7 | docker-java can't express Podman-only mounts (`image`, `artifact`) | containerDisk via mount is unavailable | Extract to the cache with the `podman` CLI (§7.2) |
| K8 | Exposed QMP and QGA ports give full VM control | Local privilege surface | Bind to loopback only (Testcontainers default host IP); document |
| K9 | RHEL device allow-list missing something needed (e.g. a VGA type or USB device) | Feature gap | Spike #2; document unsupported devices |

### Open questions (for you)

1. **Branch name:** you asked for `vm-testcontainer`. This session is restricted to pushing to `claude/vm-testcontainer-plan-lqhfa6`, so the plan lives there. Rename or merge as you like.
2. **K1 fallback:** if UBI + Rocky mixing proves unworkable, is a Rocky 9.8 base acceptable? Or is a RHEL-entitled build (subscription inside the image build) available to you?
3. **One image or two:** a slim runner plus a `-media` variant with ffmpeg, or one image with everything?
4. **Module placement:** convert `scratch-notes` to a multi-module Maven build, or keep `vm-testcontainer/` standalone?
5. **Reference guest OS** for this module's own tests: Rocky 9 (heavy but matches the ecosystem), Fedora Cloud, Alpine (small; QGA availability and an init without systemd would need checking), or something else?
6. **Guest OS scope:** Linux guests only for v1? Windows guests would need virtio-win drivers and a Windows QGA, which are not covered here.

---

## 15. In Summary

- **Design:** a Podman container on UBI 9.8 runs `/usr/libexec/qemu-kvm` from Rocky 9.8 AppStream.
  - Java controls the VM through **QMP** (screenshots, audio capture, hot-plug) and the guest through **QGA** (exec, file copy). Both run over TCP ports mapped to loopback.
- **KVM is optional.** `/dev/kvm` is passed through when it is usable; otherwise TCG is used. TCG has been verified working on the RHEL/Rocky 9.8 build. Expect it to be much slower.
- **Every requirement has a concrete mechanism.** The RHEL QEMU build's limits (no SPICE, no usb-audio, unsupported CLI use) are known and designed around:
  - Screenshots: QMP `screendump` PNG.
  - Audio: `wavcapture` / `wav` backend with Intel HDA.
  - Screen recording: a screendump frame loop encoded by EPEL `ffmpeg-free` (VP8/VP9 WebM), with a pure-Java fallback.
  - Hot-plug: QMP `device_add` / `device_del` with pre-provisioned xhci and PCIe root ports.
- **VM image supply:** a single runtime path, *read-only base bind mount + per-run qcow2 overlay*.
  - Front-ends: host path, URL+sha256 cache, KubeVirt-style containerDisk (pulled and extracted into the cache), or a baked image for CI.
- **Do first:** Phase 0 spikes.
  - The UBI + Rocky repo mix (K1) is the riskiest item.
  - Others: the device allow-list, slirp vs passt, `wavcapture` with the `none` backend, `/dev/kvm` group handling through Podman's compat API, ffmpeg-free codecs, and a reference guest.
- **Your decisions needed:** the open questions in §14, especially the K1 fallback and the licensing check (K2).
