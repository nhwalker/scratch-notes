# Phase 0 — Spike #1 results (2026-10-18)

**Result: PASS.** The UBI 9.8 base installed QEMU and ffmpeg-free cleanly. The repo order was UBI (priority 10) → EPEL (20) → Rocky 9.8 (90, fallback).

## How it was run

- **Image:** `registry.access.redhat.com/ubi9/ubi:9.8`.
- **Script:** `spike1.sh`. Repo files are in `repos/`. The full log is `out/spike1.log`, and the device list is `out/device-help.txt`.
- **Engine:** **Docker 29.8.2 (rootful)** in the Claude cloud sandbox. Podman is not installed there.
  - `dnf` resolution and the binaries inside the image don't depend on the container engine.
  - Podman-specific behaviour (rootless, `/dev/kvm`, compat API) is **not** covered here. That is spike #5.
- **Sandbox wrapper:** `run-spike1-sandbox.sh` adds the sandbox's proxy and CA. On a normal host, run:
  `podman run --rm -v "$PWD":/spike:Z registry.access.redhat.com/ubi9/ubi:9.8 /spike/spike1.sh`
- **No `/dev/kvm`** was available.

## Findings

| Check | Result |
|-------|--------|
| `dnf install qemu-kvm-core qemu-img edk2-ovmf seabios-bin` | exit 0 |
| `dnf install ffmpeg-free` | exit 0 |
| `dnf install xorriso jq socat passt` | exit 0 |
| `redhat-release` still installed | yes (`redhat-release-9.8-1.0.el9`) |
| Any `rocky-*` release, logo or repo package installed | **none** (excluded via `excludepkgs`) |
| Any pre-existing UBI package upgraded or removed | **none** |
| `rpm -Va` (excluding config and doc files) | clean, apart from `/` mtime |
| QEMU version | `10.1.0 (qemu-kvm-10.1.0-17.el9_8.5)` |
| Accelerators | `tcg`, `mshv`, `kvm`; without `/dev/kvm` it logs "falling back to tcg" |
| Default machine type | `pc-i440fx-rhel7.6.0`, which prints a deprecation warning. **Always pass `-M q35`** |
| Netdev backends | `user` (slirp) **and** `passt` both available; this answers spike #3 |
| Audio drivers (core only) | `none`, `wav` |

### Packages per source repo (packages that were newly installed)

| Repo | Count |
|------|-------|
| ubi-9-appstream | 62 |
| ubi-9-baseos | 27 |
| epel9 | 60 (mostly ffmpeg-free codec libraries) |
| rocky98-appstream | 33 |
| rocky98-baseos | 11 (samba client libs, ndctl/daxctl, snappy, slang, ...) |
| rocky98-crb | 1 (`ladspa`, needed by ffmpeg-free) |

**Rocky packages were needed for:**

- **Slim image:** qemu-kvm-core/common, qemu-img, seabios, edk2-ovmf, ipxe-roms, libfdt, libpmem, ndctl/daxctl, snappy, samba client libs (for QEMU's SMB support) and xorriso.
- **Media image:** ffmpeg-free's dependencies — libvpx, tesseract, SDL2, speex, openal and others.

### Devices (answers spike #2)

| Device | Present? |
|--------|----------|
| `VGA`, `bochs-display`, `cirrus-vga`, `secondary-vga` | yes |
| `virtio-vga` | **NO**. The plan now uses `VGA` (or `bochs-display` for UEFI guests) |
| `qemu-xhci`, `usb-storage`, `usb-kbd`, `usb-tablet`, `usb-mouse` | yes |
| `pcie-root-port`, `virtio-blk-pci`, `virtio-net-pci`, `virtio-scsi-pci`, `scsi-cd`, `ide-cd`, `virtio-rng-pci` | yes |
| `virtio-serial-pci`, `virtserialport` | yes |
| `pc-dimm` | yes |
| `ich9-intel-hda`, `hda-duplex` | yes |
| `usb-audio`, `virtio-sound-pci` | **NO** |

### ffmpeg-free encoders (answers spike #6)

- **Video:** `libvpx` (VP8), `libvpx-vp9`, `libaom-av1`, `librav1e`, `libsvtav1` and `mjpeg` are present.
- **H.264:** `libopenh264` is listed, but EPEL installed **`noopenh264`**, a stub library. Do **not** rely on H.264.
- **Audio:** `libopus`, `libvorbis`, `pcm_s16le`.
- **Choice:** VP9/WebM with Opus audio, and MJPEG as a fallback.
- **Image size:** ffmpeg-free pulls in a lot, including tesseract, SDL2 and 60 EPEL packages. That supports the slim/media image split.

## Not covered by this spike

- **#4:** `wavcapture` with the `none` audio backend.
- **#5:** Podman compat API with `/dev/kvm` and keep-groups.
- **#7:** booting the Rocky 9 reference guest.
- Final image size.
- **Licensing (K2):** this was a technical check only.

---

# Phase 0 — Spikes #4 and #7 (+ extras) results (2026-10-18)

**Result: PASS**, with one new guest requirement (SELinux, below).

## Setup

- **Runner image:** `vmtc-spike:media`, built from `Containerfile.spike`.
  - In the sandbox it was built with `build-spike-image-sandbox.sh`, which runs `install-packages.sh` and then `docker commit`s the container.
- **Guest:** `Rocky-9-GenericCloud-Base-9.8-20260525.0.x86_64.qcow2`, SHA-256 `92c206cc6f790c61583247eefe87890f8828420662c17cacf247cec78ab4eec8`. The checksum matched the published `.CHECKSUM`.
- **Disk:** the base image was mounted read-only, with a 20G qcow2 overlay created per run (`start-vm.sh`).
- **QEMU:** `-M q35 -accel tcg -cpu max -smp 2 -m 2048`. The sandbox host has 4 vCPUs, 15 GB RAM and **no KVM**.
- **Seed:** a cloud-init NoCloud ISO, built with `xorriso`. The sandbox's proxy and CA were injected so the guest could run `dnf`.
- **Helpers:** `vmctl.py` (QMP/QGA client), `typekeys.py` (QMP `send-key`), `record.py` (screendump→ffmpeg), `analyze_wav.py`.

## Spike #7: Rocky 9 reference guest

| Check | Result |
|-------|--------|
| QEMU start → QGA `guest-ping` answers | **85 s** (TCG) |
| qemu-guest-agent in the GenericCloud image | **preinstalled and enabled** (10.1.0-17.el9_8) |
| `kernel-modules` (snd-hda-intel) in the image | **already installed** |
| `alsa-utils` in the image | not installed; it was installed through the seed |
| Default QGA RPC policy | `guest-exec`, `guest-exec-status` and `guest-file-*` **disabled**. The error is `{"class":"CommandNotFound","desc":"Command guest-exec has been disabled: the command is not allowed"}` |
| After `FILTER_RPC_ARGS=""` and an agent restart | exec works |
| **SELinux** | `id -Z` from guest-exec gives `system_u:system_r:virt_qemu_ga_t:s0`. Exec'd commands get *Permission denied* on `/usr/share/alsa/alsa.conf` and `/lib/modules/...`, and cannot run `journalctl` or `getenforce` |
| `setsebool -P virt_qemu_ga_run_unconfined on` | **no effect.** Commands still ran as `virt_qemu_ga_t` |
| `semanage permissive -a virt_qemu_ga_t` | **fixed it**; the guest otherwise stays `Enforcing` |
| cloud-init runcmd complete (with `dnf install` under TCG) | ~569 s |

## Spike #4: audio capture with `-audiodev none`

Device setup: `ich9-intel-hda` + `hda-duplex,audiodev=snd0` with `-audiodev none,id=snd0`.

| Step | Result |
|------|--------|
| HMP `wavcapture /vm/out/cap.wav snd0` via QMP `human-monitor-command` | capture slot `[0]` created |
| Guest `speaker-test -t sine -f 1000 -c 2 -l 1` (mixer at defaults) | WAV written (6 s) but **all samples zero** |
| After `amixer sset Master 90% unmute; amixer sset PCM 90% unmute` | **44.1 kHz/16-bit/2ch, 5.97 s, RMS 7375, dominant ~1000 Hz** |
| `stopcapture 0` | file finalized; `info capture` empty |

## Extras, verified in the same VM

- **Exec:** stdout and stderr come back separated, and the exit code (3) is preserved.
- **File copy:** a 5 MB random blob was sent into the guest (4.1 s) and back (1.3 s) using 1 MiB QGA chunks. The SHA-256 matched both ways.
- **Screenshot:** QMP `screendump` produced a 1280×800 PNG of the login console.
- **Keyboard input:** QMP `send-key` logged in as root on the VGA console and ran shell commands.
- **Screen recording:** a screendump loop at 4 fps for 8 s, piped to `ffmpeg -c:v libvpx-vp9`.
  - Output: 33 frames, VP9, 1280×800, 389 KB.
  - Each screendump took 33 ms on average (72 ms max) under TCG.
  - The last frame decoded correctly.
- **USB hot-plug:**
  - Plug: `blockdev-add` (raw 64M) + `device_add usb-storage,bus=xhci.0` → the guest shows `sda 64M usb`.
  - Unplug: `device_del` → removed from `/machine/peripheral` → `blockdev-del` → `sda` is gone in the guest.

## Not covered

- **#5:** Podman rootless, `/dev/kvm`, compat API.
- **KVM timings:** no KVM here.
- PCIe hot-plug (virtio-blk / NIC on `pcie-root-port`).
- CD media change.
- CPU and memory hot-plug.
