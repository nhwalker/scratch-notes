# QEMU VM Testcontainer: Requirements

> **Purpose:** the testable requirements the module must meet, for as long as the module exists. Automated tests point at these IDs. This document outlives the implementation; [PLAN.md](PLAN.md) says *what* we build and *why*, and [PLAN-STEPS.md](PLAN-STEPS.md) walks through *building* it.
> **Names:** `${…}` names (for example `${LABEL_PREFIX}`) are the project parameters. They are defined in PLAN.md §0 and, once implemented, live in the files that table names (mostly `gradle.properties`). Type and method names (`QemuVmContainer`, `VmImage.fromUrl`, …) are fixed.

---

## 1. BLUF

- **106 requirements in 22 areas**, each with an ID, a "shall" statement, measurable acceptance criteria and the test(s) that verify it.
- **Tests point here:** every automated test carries a JUnit tag per requirement it verifies, for example `@Tag("REQ-EXEC-01")`. A build check fails when a tag names an unknown ID, or when a requirement whose method is *Test* has no tagged test (§3).
- **Test IDs** (`T1`–`T34` integration, `U1`–`U15` unit) are catalogued in PLAN.md §13; the criteria here are normative, the catalogue says how each test is set up.
- **A few requirements cannot be verified on GitHub-hosted runners** (rootful Podman, SELinux-enforcing hosts); they are listed in §5 with the manual check that covers them.

---

## 2. Conventions

| Item | Rule |
|------|------|
| ID format | `REQ-<AREA>-<NN>`, for example `REQ-HTTP-03`. IDs are never reused or renumbered. A dropped requirement stays in the table, marked **Retired** with the reason |
| "shall" | Mandatory. Every requirement here is mandatory unless marked *(optional component)* |
| Method | **Test** = automated test (integration `T*` or unit `U*`). **Check** = automated build/CI check that is not a JUnit test (e.g. image verification, hygiene grep). **Inspection** = review of code, build files or documentation. **Manual** = a person runs a documented procedure |
| KVM / TCG | "under KVM" = `KvmMode.REQUIRE` on a KVM host; "under TCG" = `KvmMode.DISABLE`. Timing limits under TCG are the KVM limit × `tcgTimeoutFactor` unless a TCG limit is given |
| Reference guest | Unless a requirement says otherwise, tests use `VmImage.referenceGuest()` (the pre-customized Rocky 9 containerDisk) |
| Source | The original request or decision the requirement comes from: R1–R13, D1–D7 (PLAN.md §2), G1–G7 (guest contract, PLAN.md §10.1) |

---

## 3. Traceability rules

1. A test that verifies requirement `REQ-X-NN` carries `@Tag("REQ-X-NN")` (one tag per requirement; JUnit tags allow `-`).
2. The build task `checkRequirementTraceability` (PLAN-STEPS.md step 1.5):
   - reads every ID from the first column of the §4 tables (regex `^\| (REQ-[A-Z]+-[0-9]{2})`, between the lines starting with `## 4.` and `## 5.`) together with its *Method* column and a **Retired** marker;
   - scans `@Tag("REQ-…")` in all test sources;
   - **fails** if a tag names an ID that does not exist or is Retired;
   - **fails** if a requirement whose *Method* column contains **Test** has no tagged test;
   - writes `build/reports/requirements/traceability.md` (ID → test classes and methods).
3. A requirement is *met* when every test tagged with its ID passes in the CI jobs named in its row (default: every `integration` matrix entry, plus `unit`).

---

## 4. Requirements

### 4.1 Platform and scope (PLAT)

| ID | Requirement | Acceptance criteria | Method / verified by | Source |
|----|-------------|---------------------|----------------------|--------|
| REQ-PLAT-01 | The module shall support x86-64 Linux hosts | CI `integration` jobs on `ubuntu-24.04` and `ubuntu-26.04` (x86-64) pass | Check (CI matrix) | D1 |
| REQ-PLAT-02 | The module shall run Linux x86-64 guests that meet the guest contract (PLAN.md §10.1, G1–G7). Other guest OSes are out of scope | The reference guest (Rocky 9) passes all integration tests | Test: T2 (every `T*` exercises it) | D2, D3 |
| REQ-PLAT-03 | The library shall compile to Java `${JAVA_RELEASE}` bytecode and run on a Java `${JAVA_RELEASE}` runtime | `javap -v` of any library class shows the major version for `${JAVA_RELEASE}`; CI runs tests on that JDK | Check (build) | D4 |

### 4.2 Container engine (ENG)

| ID | Requirement | Acceptance criteria | Method / verified by | Source |
|----|-------------|---------------------|----------------------|--------|
| REQ-ENG-01 | The library shall work against **rootless Podman** through its Docker-compatible API, selected by `DOCKER_HOST`, with Ryuk disabled (`TESTCONTAINERS_RYUK_DISABLED=true`) | All integration tests pass on Podman 4.9.3 (ubuntu-24.04) and 5.7.0 (ubuntu-26.04) | Test: T2 (every `T*` exercises it in CI) | R1 |
| REQ-ENG-02 | The library shall work against **rootful** Podman | T3 and T4 pass against a rootful `podman.socket` | Manual (§5) | R1 |
| REQ-ENG-03 | Only the Testcontainers/docker-java client shall talk to the engine; the library shall not invoke an engine CLI | No `ProcessBuilder`/`Runtime.exec` of `podman`/`docker` in `:lib` main sources | Inspection | PLAN §4 |

### 4.3 Runner image (IMG)

| ID | Requirement | Acceptance criteria | Method / verified by | Source |
|----|-------------|---------------------|----------------------|--------|
| REQ-IMG-01 | The runner images shall be built `FROM ${UBI_IMAGE}` and remain UBI-based | `rpm -q redhat-release` succeeds; `rpm -qa 'rocky-*'` is empty | Check: `verifyRunnerImages` check 4 | R2 |
| REQ-IMG-02 | Packages shall resolve from UBI first, then EPEL, then Rocky `${ROCKY_RELEASE}` as fallback | Repo priorities UBI 10, EPEL 20, Rocky 90; Rocky repos exclude `rocky-release* rocky-logos* rocky-gpg-keys* rocky-repos*`; every repo has `gpgcheck=1` | Check: `verifyRunnerImages` check 7 | R11 |
| REQ-IMG-03 | Two variants shall exist: **slim** (no GStreamer) and **media** (slim + GStreamer), tagged `${IMAGE_RUNNER}:${VERSION}-${IMAGE_TAG_SUFFIX}` and `…-media` | Image label `${LABEL_PREFIX}.variant` equals `slim` / `media`; on media `gst-inspect-1.0` finds `rfbsrc`, `vp8enc`, `webmmux`, `videorate`, `videoconvert`; on slim `gst-launch-1.0` is absent | Check: `verifyRunnerImages` checks 3, 6, 8 | D6 |
| REQ-IMG-04 | The images shall provide `/usr/libexec/qemu-kvm` with TCG and every device the library uses | `-accel help` lists `tcg`; `-device help` lists `VGA`, `secondary-vga`, `ich9-intel-hda`, `hda-duplex`, `qemu-xhci`, `usb-storage`, `usb-kbd`, `usb-tablet`, `usb-mouse`, `pcie-root-port`, `virtio-blk-pci`, `virtio-net-pci`, `virtio-scsi-pci`, `scsi-cd`, `pc-dimm` | Check: `verifyRunnerImages` checks 1, 2 | R5 |
| REQ-IMG-05 | PID 1 in the runner container shall reap orphaned processes | After a screen recording is started and stopped, no process in the container is in state `Z` | Test: T24; Check: `verifyRunnerImages` check 5 (`/usr/bin/tini` exists) | PLAN §1 rule a |

### 4.4 Acceleration (KVM)

| ID | Requirement | Acceptance criteria | Method / verified by | Source |
|----|-------------|---------------------|----------------------|--------|
| REQ-KVM-01 | With `KvmMode.REQUIRE` on a host where `/dev/kvm` is usable, the VM shall run under KVM | `accelerator()` = `KVM`; QMP `query-kvm` → `enabled: true` | Test: T1 (`kvm`) | R12 |
| REQ-KVM-02 | With `KvmMode.REQUIRE` where `/dev/kvm` is absent or unusable, `start()` shall fail with `KvmUnavailableException` | Exception type and the matching message from PLAN.md §9.6 table | Test: T20 (`tcg`), U8 | R12 |
| REQ-KVM-03 | `KvmMode.PREFER` shall be the default: KVM when usable, otherwise TCG with **one** WARN per JVM | Unit: decision table PLAN.md §8.2; integration: on a KVM job `accelerator()` = `KVM` without `withKvm` | Test: U8, T1 | R12 |
| REQ-KVM-04 | With `KvmMode.DISABLE` the VM shall run under TCG and the container shall get no `/dev/kvm` device | `accelerator()` = `TCG`; `query-kvm.enabled` = false; container `HostConfig.Devices` is empty | Test: T1 (`tcg`), U8 | R12 |
| REQ-KVM-05 | For a group-restricted `/dev/kvm` (not world read/write) the library shall add the annotation `run.oci.keep_original_groups=1` and shall never set `GroupAdd keep-groups` | U8 decision table; CI `compat` job scenarios A, B, C give the results in PLAN.md §9.6 | Test: U8; Check: `compat` job | R12, PLAN §1 rule h |
| REQ-KVM-06 | When KVM was intended but the container fell back to TCG (`VMTC_ACCEL tcg` or exit 3), the library shall log the remediation text from PLAN.md §9.6 (PREFER) or throw it (REQUIRE) | U8 asserts the exact strings | Test: U8 | R12 |
| REQ-KVM-07 | Under TCG every default timeout shall be multiplied by `tcgTimeoutFactor` (default 6.0), except the 60 s container-start wait | Unit: effective guest-agent timeout under TCG = 180 s × 6 by default, and × the configured factor | Test: U9 | PLAN §8.2 |

### 4.5 Lifecycle (LIFE)

| ID | Requirement | Acceptance criteria | Method / verified by | Source |
|----|-------------|---------------------|----------------------|--------|
| REQ-LIFE-01 | Each `QemuVmContainer` shall run exactly one VM in one container | The engine lists exactly one container with the session label per started `QemuVmContainer` | Test: T2 | PLAN §4 |
| REQ-LIFE-02 | `start()` shall return only when the guest agent answers, within 30 s under KVM and 180 s under TCG (reference guest) | Measured from `start()` call to return | Test: T2 | R3 |
| REQ-LIFE-03 | The base disk shall never be written; each start shall use a fresh overlay | SHA-256 of the cache file is unchanged after the guest wrote and synced 1 MiB to its root disk; a second container from the same image does not see that file | Test: T18 | PLAN §4 |
| REQ-LIFE-04 | If the container cannot start the VM, `start()` shall throw `VmtcException` whose message includes the entrypoint error and the last 50 log lines | Runner without a base disk → message contains `no base disk` | Test: T29 | PLAN §9.2 |
| REQ-LIFE-05 | If the guest agent is not ready within the guest-agent timeout, `start()` shall throw `GuestAgentTimeoutException` containing the last 50 serial-log lines | Raw blank disk, timeout 10 s → exception within timeout + 10 s | Test: T30 | PLAN §9.2 |
| REQ-LIFE-06 | `stop()` shall finalize every open screen and audio recording before the container stops | Recordings left open at `stop()` produce valid files at their host paths | Test: T31 | PLAN §9.2 |
| REQ-LIFE-07 | Serial console output shall be available while running (`withSerialLogConsumer`) and on demand (`saveSerialLog`) | A line written to `/dev/ttyS0` in the guest reaches the consumer within 5 s and appears in the saved file | Test: T12 | PLAN §9.2 |
| REQ-LIFE-08 | QMP and QGA host ports shall be published on `127.0.0.1` only | Container port bindings for 4444 and 4445 have host IP `127.0.0.1` | Test: T6 | PLAN §4 |
| REQ-LIFE-09 | `withFirmware(Firmware.UEFI)` shall boot a UEFI-capable guest under UEFI | Guest agent ready; in the guest `test -d /sys/firmware/efi` exits 0 | Test: T22 | PLAN §8.4 |

### 4.6 Run commands (EXEC)

| ID | Requirement | Acceptance criteria | Method / verified by | Source |
|----|-------------|---------------------|----------------------|--------|
| REQ-EXEC-01 | `exec(argv)` shall run a program in the guest and return exit code, stdout and stderr | `/usr/bin/uname -m` → exit 0, stdout `x86_64\n`; `/bin/sh -c 'echo out; echo err >&2; exit 3'` → (3, `out\n`, `err\n`) | Test: T3 | R3 |
| REQ-EXEC-02 | `exec(ExecRequest)` shall pass environment variables and stdin bytes | `env FOO=bar` + `/bin/sh -c 'echo $FOO; cat'` with stdin `hi` → stdout `bar\nhi` | Test: T3 | R3 |
| REQ-EXEC-03 | `execShell(script)` shall run `/bin/sh -c script` | `execShell("echo a \| tr a b")` → stdout `b\n` | Test: T3 | R3 |
| REQ-EXEC-04 | A command exceeding its timeout shall be killed and raise `GuestExecTimeoutException` | `exec(2 s, "/usr/bin/sleep", "60")` throws within 2 s + 5 s; afterwards `pgrep -x sleep` exits 1 | Test: T32 | R3 |
| REQ-EXEC-05 | Output truncated by the guest agent shall be reported via `stdoutTruncated` / `stderrTruncated` | Fake QGA returning `out-truncated: true` → flag true | Test: U2 | R3 |

### 4.7 File copy (FILE)

| ID | Requirement | Acceptance criteria | Method / verified by | Source |
|----|-------------|---------------------|----------------------|--------|
| REQ-FILE-01 | `copyToVm` and `copyFromVm` shall copy files byte-exactly in both directions | 5 MB and 50 MB random files: SHA-256 equal after host→guest→host | Test: T4 | R4 |
| REQ-FILE-02 | `copyToVm` shall create missing parent directories | Copy to `/tmp/vmtc/a/b/c.bin` succeeds on a fresh guest | Test: T4 | R4 |
| REQ-FILE-03 | `copyToVm(byte[], …)` and `readFromVm` shall round-trip byte arrays, including empty arrays | 0-byte and 3-byte arrays round-trip | Test: T4 | R4 |

### 4.8 VM disk sources (DISK)

| ID | Requirement | Acceptance criteria | Method / verified by | Source |
|----|-------------|---------------------|----------------------|--------|
| REQ-DISK-01 | `VmImage.fromPath` shall boot a host qcow2 or raw file | qcow2: reference guest boots (T18). raw: QEMU starts from a raw file (`VMTC_READY` logged, T30) | Test: T18, T30 | R5 |
| REQ-DISK-02 | `VmImage.fromUrl` shall boot a disk downloaded over HTTP(S) | Local HTTP server serving the reference guest qcow2 → boots; the stock Rocky image from `${GUEST_BASE_URL}` → boots | Test: T18, T25 | R13 |
| REQ-DISK-03 | `VmImage.fromOci` / `fromContainerDisk` shall boot a disk published as a containerDisk image (PLAN.md §10.4) | Reference guest by tag and by `@sha256:` digest → boots | Test: T18, T28 | R13 |
| REQ-DISK-04 | A containerDisk with zero or more than one regular file under `/disk/` shall be rejected with `IllegalArgumentException("containerDisk must contain exactly one file under /disk/")` | Images with 0 and with 2 files | Test: T18 | R13 |
| REQ-DISK-05 | `VmImage.baked()` shall boot a disk baked into a derived runner image at `/vm/base/disk.img` | Derived image `FROM <slim>` + `COPY` → boots; no bind mount on the container | Test: T18 | PLAN §9.3.4 |
| REQ-DISK-06 | Every non-baked source shall be materialized once into the host cache and reused | Second `start()` with the same source: the cache entry's inode is unchanged; `fromPath` does not copy again; `fromUrl` with a SHA-256 sends no HTTP request; `fromOci` pulls (to detect tag moves) but does not extract again | Test: T18, U3 | PLAN §9.3.1 |
| REQ-DISK-07 | Concurrent writers of one cache entry shall produce one complete file | Two threads materializing the same key → one `<key>.img`, both callers get it, no `*.tmp-*` left | Test: U3 | PLAN §9.3.1 |
| REQ-DISK-08 | `fromPath` shall copy the user's file into the cache, never hard-link it | Cache file inode ≠ source inode | Test: U3 | PLAN §9.3.1 |
| REQ-DISK-09 | `VmImageCache.prune(olderThan)` shall delete only entries not used within `olderThan` | Entries touched by use survive; untouched older entries are deleted | Test: U3 | PLAN §9.3.1 |
| REQ-DISK-10 | On SELinux-enforcing hosts the cache bind shall be relabeled (`:z`) and the VM shall boot | T3 passes on a Fedora/RHEL host in enforcing mode | Manual (§5) | PLAN §15 R3 |
| REQ-DISK-11 | `VmImage.referenceGuest()` shall resolve to the `guestImage` override if set, else `${IMAGE_GUEST}:${VERSION}` | U6 for resolution; T23 boots it | Test: U6, T23 | D3 |

### 4.9 HTTP(S) downloads (HTTP)

All HTTP tests use in-JVM HTTP servers (no network).

| ID | Requirement | Acceptance criteria | Method / verified by | Source |
|----|-------------|---------------------|----------------------|--------|
| REQ-HTTP-01 | Downloads shall support Basic, Bearer and custom-header credentials | Each credential kind succeeds against a server requiring it | Test: T26 (b) | R13 |
| REQ-HTTP-02 | HTTP 401/403 shall raise `ImageAuthException`; the message shall not contain user-info, query string or secret values | Missing and wrong credentials → `ImageAuthException`; message checked for the secret and query | Test: T26 (a), U12 | R13 |
| REQ-HTTP-03 | Credentials shall be sent only to the original `scheme://authority`, including across redirects | A redirect to another port receives no `Authorization`/custom header; a same-origin redirect does | Test: T26 (e) | R13 |
| REQ-HTTP-04 | When a SHA-256 is given, it shall be checked over the downloaded bytes; a mismatch shall raise `ImageChecksumException` and leave no cache file | Wrong digest → exception, no `<key>.img`, no `*.tmp-*` | Test: T26 (c) | R13 |
| REQ-HTTP-05 | Without a SHA-256, the library shall log one WARN per URL and revalidate cache hits with `If-None-Match` / `If-Modified-Since` | 304 → cache reused, no body transferred; 200 → entry replaced; server down → cache reused with WARN | Test: T26 (f) | R13 |
| REQ-HTTP-06 | `Compression.AUTO` shall decompress by URL path suffix `.gz`, `.xz`, `.zst`; explicit `GZIP`/`XZ`/`ZSTD`/`NONE` shall override | Each format yields the original bytes; `NONE` on a `.gz` URL stores the compressed bytes | Test: T26 (d) | R13 |
| REQ-HTTP-07 | At most 5 redirects shall be followed | 6 chained redirects → `IOException("too many redirects")` | Test: T26 (g) | R13 |
| REQ-HTTP-08 | I/O errors and 5xx shall be retried up to 3 attempts in total; 401, 403, 404 and checksum mismatch shall not be retried | 503, 503, 200 → success with 3 requests; 404 → exactly 1 request | Test: T26 (h) | R13 |
| REQ-HTTP-09 | `Secret.toString()` shall print `***` | Unit assertion | Test: U12 | R13 |

### 4.10 OCI sources (OCI)

| ID | Requirement | Acceptance criteria | Method / verified by | Source |
|----|-------------|---------------------|----------------------|--------|
| REQ-OCI-01 | `oci://<registry>/<repo>[:tag\|@sha256:<hex>]` shall be parsed; a missing tag means `latest`; other schemes shall be rejected with `IllegalArgumentException` | Table-driven unit test incl. `oras://` and missing scheme | Test: U11 | R13 |
| REQ-OCI-02 | Without explicit credentials, pulls shall use the engine-side credentials: Docker `config.json` / credential helpers and, on Podman, `podman login` | Private registry: config.json only → boots; `podman login` only → boots | Test: T27 (b), (d) | R13 |
| REQ-OCI-03 | `OciOptions.credentials(...)` shall authenticate the pull explicitly | No stored credentials + explicit credentials → boots | Test: T27 (c) | R13 |
| REQ-OCI-04 | A pull rejected for authentication shall raise `ImageAuthException` | No credentials → `ImageAuthException` | Test: T27 (a) | R13 |
| REQ-OCI-05 | Every use of a tag shall pull, so a moved tag yields the new disk; a digest reference shall always yield the same disk | After the tag moves from v1 to v2, a new start bases the VM on v2 (`/vm/base/disk.img` in the container has v2's SHA-256) through a new cache entry; `@sha256:<v1 digest>` still yields v1's SHA-256 | Test: T28 | R13 |
| REQ-OCI-06 | The library shall contain no JVM-side registry client (no ORAS SDK) | `:lib` runtime classpath has no `land.oras` artifact | Inspection (`./gradlew :lib:dependencies`) | R13, PLAN §15 L7 |

### 4.11 Screens and screenshots (DISP)

| ID | Requirement | Acceptance criteria | Method / verified by | Source |
|----|-------------|---------------------|----------------------|--------|
| REQ-DISP-01 | `withScreens(n)` shall accept 1..4 (default 1) and reject other values with `IllegalArgumentException` | Unit validation | Test: U9 | D5 |
| REQ-DISP-02 | `screen(i).screenshot()` shall capture screen *i* as an image | n=2: head 0 is 1280×800 | Test: T5 | R6 |
| REQ-DISP-03 | `forceModeset()` shall make a secondary screen scan out on a console-only guest | Head 1 is 640×480 before and 1280×800 after | Test: T5 | R6, PLAN §15 L2 |

### 4.12 VNC (VNC)

| ID | Requirement | Acceptance criteria | Method / verified by | Source |
|----|-------------|---------------------|----------------------|--------|
| REQ-VNC-01 | Each screen shall have its own VNC server, exposed to library users via `screen(i).vnc()` | For every screen, a raw RFB handshake from the host to `vnc().host():vnc().port()` returns `RFB 003.008` | Test: T6 | R9 |
| REQ-VNC-02 | VNC ports shall bind to `127.0.0.1` by default; `withVncBindAddress` shall change it | Default binding host IP `127.0.0.1`; with `0.0.0.0` the binding host IP is `0.0.0.0` | Test: T6 | R9 |
| REQ-VNC-03 | With `withVncPassword`, every screen shall require that password | Security types = `[2]` on every screen; `rfbsrc` with a wrong password logs `Authentication failed` | Test: T6, T7 | R9 |
| REQ-VNC-04 | VNC passwords shall be 1..8 printable ASCII characters without whitespace, otherwise `IllegalArgumentException` | Unit validation | Test: U9 | PLAN §8.3 |
| REQ-VNC-05 | A library user's exclusive VNC client shall not disconnect recorders | Exclusive (`shared=0`) client connects mid-recording; the recording is still valid | Test: T9 | PLAN §1 rule d |

### 4.13 Keyboard (KEY)

| ID | Requirement | Acceptance criteria | Method / verified by | Source |
|----|-------------|---------------------|----------------------|--------|
| REQ-KEY-01 | `keyboard().type(text)` shall type US-layout text per PLAN.md Appendix A.3; any other character shall raise `IllegalArgumentException` | Unit: table; integration: console login and command typed via keyboard succeed | Test: U4, T12 | R6 |
| REQ-KEY-02 | `keyboard().press(qcodes…)` shall send the keys as one chord | Unit: one `send-key` with all qcodes | Test: U4 | R6 |

### 4.14 Screen recording (REC)

| ID | Requirement | Acceptance criteria | Method / verified by | Source |
|----|-------------|---------------------|----------------------|--------|
| REQ-REC-01 | `screen(i).startRecording(path)` shall record that screen's VNC output to one WebM (VP8) file per recording | n=2, 12 s, both screens: each file is VP8 WebM, width 1280, \|duration − wall time\| < 1 s | Test: T8 | R8 |
| REQ-REC-02 | Closing a recording shall take less than 2 s | Measured on each `close()` in T8 | Test: T8 | R8 |
| REQ-REC-03 | A static (unchanging) screen shall still produce a full-length recording | 10 s unchanged screen → duration ≥ 9 s | Test: T10 | R8, PLAN §1 rule b |
| REQ-REC-04 | Screen recording on the slim runner shall raise `UnsupportedOperationException("screen recording requires the media runner image")` | Slim runner → exception text exact | Test: T33 | D6 |
| REQ-REC-05 | The VNC password shall never appear on a process command line | Recorder argv contains no password; it is passed by environment | Test: U15 | PLAN §9.8 |
| REQ-REC-06 | A recording whose file is invalid shall raise `RecordingException` including the recorder's log tail | Fake `VMTC_REC_INVALID` output → exception with log lines | Test: U15 | PLAN §9.8 |

### 4.15 Audio recording (AUD)

| ID | Requirement | Acceptance criteria | Method / verified by | Source |
|----|-------------|---------------------|----------------------|--------|
| REQ-AUD-01 | `startAudioRecording(path)` shall record guest audio output to a separate WAV file | Guest `speaker-test -t sine -f 1000 -c 2 -l 1` → WAV 44.1 kHz / 16-bit / 2 ch, RMS > 100, dominant frequency 900–1100 Hz | Test: T11 | R7 |
| REQ-AUD-02 | Only one audio recording shall be active at a time | Second `startAudioRecording` while one is open → `IllegalStateException` | Test: T11 | R7 |
| REQ-AUD-03 | HMP `info capture` output shall be parsed to find the capture index | Table-driven unit test with the verified output format | Test: U7 | R7 |

### 4.16 Hot-plug (HOT)

| ID | Requirement | Acceptance criteria | Method / verified by | Source |
|----|-------------|---------------------|----------------------|--------|
| REQ-HOT-01 | USB storage shall hot-plug and unplug | `lsblk -o TRAN` gains a `usb` disk; after `unplug` it is gone | Test: T13 | R10 |
| REQ-HOT-02 | USB keyboard, tablet and mouse shall hot-plug and unplug | `/proc/bus/input/devices` gains a device per plug; it is gone after unplug | Test: T13 | R10 |
| REQ-HOT-03 | A virtio disk (PCIe) shall hot-plug and unplug, from a host image or a new blank disk | New `vdX` appears; gone after unplug | Test: T14 | R10 |
| REQ-HOT-04 | A NIC (PCIe) shall hot-plug and unplug | New interface in `ip -o link`; gone after unplug | Test: T14 | R10 |
| REQ-HOT-05 | When all hot-plug root ports are in use, a further PCIe hot-plug shall raise `IllegalStateException` | `withHotplugPorts(1)`, plug two virtio disks → second throws | Test: T14 | R10 |
| REQ-HOT-06 | CD media shall be insertable and ejectable | `blkid /dev/sr0` shows the ISO label; mounted content matches; after eject no medium | Test: T15 | R10 |
| REQ-HOT-07 | A vCPU shall hot-plug under KVM; under TCG `hotplug(Devices.cpu())` shall raise `UnsupportedOperationException` | KVM: `nproc` +1. TCG: exception | Test: T16 | R10, PLAN §15 L1 |
| REQ-HOT-08 | Memory shall hot-plug | `MemTotal` grows by more than 400 MiB after `memory(512)` | Test: T17 | R10 |
| REQ-HOT-09 | An unplug the guest does not complete within the timeout shall raise `HotplugTimeoutException` | Fake QMP that never removes the device | Test: U13 | R10 |

### 4.17 Guest contract and seeding (GUEST)

| ID | Requirement | Acceptance criteria | Method / verified by | Source |
|----|-------------|---------------------|----------------------|--------|
| REQ-GUEST-01 | A guest whose agent blocks exec/file RPCs shall be reported with `GuestCommandDisabledException` at start | Stock Rocky image, no seed → exception | Test: T19, U2 | G3 |
| REQ-GUEST-02 | A guest whose agent domain is SELinux-enforcing shall be reported with `GuestAgentConfinedException` at start | Stock Rocky with only G3 applied → exception | Test: T19 | G4 |
| REQ-GUEST-03 | `CloudInitSeed.forStockRocky()` shall bring a stock Rocky 9 GenericCloud image to the guest contract; `start()` shall wait for `VMTC_SEED_DONE` | `fromUrl(${GUEST_BASE_URL}, sha256 ${GUEST_BASE_SHA256})` + seed → T3 criteria pass; generated YAML equals PLAN.md §10.2 | Test: T25, U10 | G2–G6 |
| REQ-GUEST-04 | The reference guest `${IMAGE_GUEST}:${VERSION}` shall boot with no seed and meet G2–G6 | QGA ready ≤ 30 s (KVM); exec works; `amixer -c 0 get Master` shows `[on]`; T11 passes | Test: T23 | D3 |
| REQ-GUEST-05 | The reference guest shall be published in the containerDisk format of PLAN.md §10.4 | `FROM scratch`, exactly one qcow2 under `/disk/`, owned 107:107 | Test: T18 (via `fromContainerDisk`); Inspection | R13 |

### 4.18 Configuration (CFG)

| ID | Requirement | Acceptance criteria | Method / verified by | Source |
|----|-------------|---------------------|----------------------|--------|
| REQ-CFG-01 | Each runtime override key (PLAN.md §9.1) shall be settable as system property `${SYSPROP_PREFIX}.<key>` and environment variable `${ENV_PREFIX}<KEY>`, with precedence system property > environment > builder > default | Table-driven test over every key | Test: U6 | PLAN §9.1 |
| REQ-CFG-02 | Builder arguments outside their documented ranges shall raise `IllegalArgumentException` at the builder call | Screens (1..4), hot-plug ports (0..8), CPUs (boot ≥ 1, max ≥ boot), VNC password, QEMU args containing whitespace, recording fps (1..30) | Test: U9 | PLAN §9.2 |

### 4.19 Errors, cleanup, JUnit extension (ERR, CLN, JUN)

| ID | Requirement | Acceptance criteria | Method / verified by | Source |
|----|-------------|---------------------|----------------------|--------|
| REQ-ERR-01 | Every exception the library throws on purpose shall be unchecked and extend `VmtcException` (JDK argument/state exceptions excepted) | Reflection over public exception types | Test: U14 | PLAN §9.11 |
| REQ-ERR-02 | `QmpException` shall carry the QMP error `class` and `desc` | Fake QMP error → fields set | Test: U1 | PLAN §9.11 |
| REQ-CLN-01 | On first `start()` per JVM, containers labelled `${LABEL_PREFIX}.managed=true` from another session and older than 1 h shall be force-removed, unless the `cleanup` override is `false` | Seeded orphan removed; with `cleanup=false` it remains; a younger or same-session container remains | Test: T21 | PLAN §9.12 |
| REQ-JUN-01 *(optional component)* | With `@VmTest`, a failing test shall leave, per running `QemuVmContainer`, the serial log and one screenshot per screen under `build/vm-artifacts/<class>/<method>/`, and shall finalize every open recording at its own host path | EngineTestKit runs a failing test class that has an open screen recording; the serial log, the screenshot(s) and the recording file exist and are non-empty | Test: T34 | PLAN §9.13 |

### 4.20 Build, publishing and retargeting (BLD)

| ID | Requirement | Acceptance criteria | Method / verified by | Source |
|----|-------------|---------------------|----------------------|--------|
| REQ-BLD-01 | Each project parameter shall be stored in exactly one file; changing it there shall retarget artifacts, packages, images, labels, prefixes and cache directory | Hygiene grep (PLAN.md §12.3) passes; U5 asserts generated constants equal `gradle.properties` | Check: hygiene job; Test: U5 | PLAN §0 |
| REQ-BLD-02 | The library shall be published as `${GROUP_ID}:${ART_LIB}:${VERSION}` and the extension as `${GROUP_ID}:${ART_JUNIT}:${VERSION}` | `./gradlew publishToMavenLocal` produces both POMs with those coordinates | Check: CI `unit` job | D4 |
| REQ-BLD-03 | The `api` dependency surface shall be Testcontainers only; implementation dependencies shall be limited to PLAN.md §9.1 | `:lib:dependencies` matches the §9.1 table | Inspection | PLAN §9.1 |
| REQ-BLD-04 | `module-info` shall export every package except `${BASE_PACKAGE}.internal` | Unit test reads the module descriptor | Test: U14 | PLAN §9.1 |
| REQ-BLD-05 | CI shall rebuild the runner images against fresh repositories weekly | `ci.yml` has `schedule: cron '0 6 * * 1'` | Inspection | R1 (PLAN §15 R1) |
| REQ-BLD-06 | Requirement traceability (§3) shall be enforced in CI | `checkRequirementTraceability` runs in the `unit` job and fails the build on violations | Check: CI `unit` job | this document |

---

## 5. Requirements not verifiable on GitHub-hosted runners

| ID | Why not automated | Manual procedure (before each minor release) |
|----|-------------------|-----------------------------------------------|
| REQ-ENG-02 | GitHub runners use rootless Podman for the user; a rootful socket is possible but not part of the planned CI | `sudo systemctl start podman.socket`; `sudo env DOCKER_HOST=unix:///run/podman/podman.sock TESTCONTAINERS_RYUK_DISABLED=true ./gradlew :it:integrationTest --tests '*ExecIT' --tests '*CopyIT'` |
| REQ-DISK-10 | GitHub Ubuntu runners use AppArmor, not SELinux | On Fedora or RHEL with `getenforce` = `Enforcing`, rootless Podman: `./gradlew :it:integrationTest --tests '*ExecIT' --tests '*CopyIT'` (T3, T4); record the host OS and Podman version in the release notes |

---

## 6. In Summary

- One table row per requirement: ID, "shall", measurable criteria, how it is verified, where it came from.
- Tests tag the IDs they verify; `checkRequirementTraceability` keeps the tags and this file in sync.
- Two requirements need a manual run (§5); everything else is automated in CI.
