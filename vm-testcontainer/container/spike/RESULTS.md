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
