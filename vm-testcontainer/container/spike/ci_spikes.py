#!/usr/bin/env python3
"""Phase 0 spike runner: boots the Rocky 9 guest in the spike image and runs every feature check.

Runs on the HOST (CI runner or sandbox) and drives the container with `<engine> exec`.
Writes <out>/results.json, <out>/results.md and copies artifacts (PNGs, WebMs, WAVs, logs) into <out>/.

Usage:
  ci_spikes.py --engine podman --image vmtc-spike:media --disk rocky.qcow2 --accel kvm --out out-kvm
Options:
  --network-host   use host networking (sandbox only; VNC then reachable on 127.0.0.1:5900+i directly)
  --skip a,b       skip named checks
Exit code: 0 if every non-skipped check passed, 1 otherwise.
"""
import argparse, json, os, re, subprocess, sys, time, traceback

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import analyze_wav  # noqa: E402  (module form of analyze_wav.py, see analyze() below)

ap = argparse.ArgumentParser()
ap.add_argument("--engine", default="podman")
ap.add_argument("--image", default="vmtc-spike:media")
ap.add_argument("--disk", help="host qcow2 bind-mounted read-only")
ap.add_argument("--disk-image", help="containerDisk image ref, mounted with --mount type=image at /vm/image")
ap.add_argument("--disk-file", default="disk.qcow2", help="file name under /disk/ in --disk-image")
ap.add_argument("--accel", choices=["kvm", "tcg"], required=True)
ap.add_argument("--out", required=True)
ap.add_argument("--name", default="spikevm")
ap.add_argument("--screens", type=int, default=2)
ap.add_argument("--network-host", action="store_true")
ap.add_argument("--skip", default="")
ap.add_argument("--boot-timeout", type=float, default=1500)
ap.add_argument("--no-seed", action="store_true", help="boot without cloud-init seed (pre-customized guest)")
ap.add_argument("--firmware", choices=["bios", "uefi"], default="bios", help="VM_FIRMWARE for start-vm.sh (plan §8.4, T22)")
args = ap.parse_args()
os.makedirs(args.out, exist_ok=True)
SKIP = set(x for x in args.skip.split(",") if x)
# Known limitations recorded as XFAIL (still executed; XPASS if they unexpectedly work).
# hotplug_cpu under TCG: device_add succeeds but the guest never sees the vCPU (2/2 runs, root cause unknown).
XFAIL = {"tcg": {"hotplug_cpu"}, "kvm": set()}[args.accel]
E, N = args.engine, args.name
results = []


def sh(cmd, timeout=600, check=True):
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError(f"{' '.join(cmd)} -> rc={r.returncode}\nstdout={r.stdout[-2000:]}\nstderr={r.stderr[-2000:]}")
    return r


def cx(*argv, timeout=600, check=True):           # run inside the container
    return sh([E, "exec", N, *argv], timeout=timeout, check=check)


def qmp(obj):
    return json.loads(cx("python3", "/spike/vmctl.py", "qmp", json.dumps(obj)).stdout)


def hmp(line):
    return cx("python3", "/spike/vmctl.py", "hmp", line).stdout


def gexec(*argv, timeout=120):                    # guest-exec via QGA, returns dict(exit,out,err)
    r = cx("python3", "/spike/vmctl.py", "exec-json", str(timeout), *argv, timeout=timeout + 30, check=False)
    return json.loads(r.stdout.strip().splitlines()[-1])


def gsh(script, timeout=120):
    return gexec("/bin/sh", "-c", script, timeout=timeout)


def wait_until(fn, timeout, interval=1.0):
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            if fn():
                return time.time() - t0
        except Exception:
            pass
        time.sleep(interval)
    return None


def check(name):
    def deco(fn):
        def run():
            if name in SKIP:
                results.append({"check": name, "status": "SKIP", "detail": "skipped by --skip", "secs": 0}); return
            t0 = time.time()
            try:
                detail = fn() or ""
                results.append({"check": name, "status": "XPASS" if name in XFAIL else "PASS", "detail": detail,
                                "secs": round(time.time() - t0, 1)})
            except Exception as e:
                results.append({"check": name, "status": "XFAIL" if name in XFAIL else "FAIL",
                                "detail": f"{e}\n{traceback.format_exc()[-1500:]}", "secs": round(time.time() - t0, 1)})
            print(f"[{results[-1]['status']}] {name} ({results[-1]['secs']}s): {str(results[-1]['detail'])[:300]}", flush=True)
        run.__name__ = name
        return run
    return deco


def copy_out(path_in_container, name=None):
    dst = os.path.join(args.out, name or os.path.basename(path_in_container))
    sh([E, "cp", f"{N}:{path_in_container}", dst]); return dst


# ---------------------------------------------------------------- start container
sh([E, "rm", "-f", N], check=False)
run = [E, "run", "-d", "--init", "--name", N,
       "-e", f"VM_SCREENS={args.screens}", "-e", f"VM_ACCEL={args.accel}", "-e", f"VM_FIRMWARE={args.firmware}",
       "-e", "VNC_OPTS=,share=force-shared"]
if args.accel == "kvm":
    run += ["--device", "/dev/kvm"]
if args.network_host:
    run += ["--network", "host"]
else:
    run += ["-e", "VNC_BIND=0.0.0.0"] + sum((["-p", f"127.0.0.1::{5900 + i}"] for i in range(args.screens)), [])
if args.disk_image:
    run += ["--mount", f"type=image,source={args.disk_image},destination=/vm/image", "-e", f"VM_BASE=/vm/image/disk/{args.disk_file}"]
else:
    run += ["-v", f"{os.path.abspath(args.disk)}:/vm/base/disk.img:ro"]
run += ["-v", f"{HERE}:/spike:ro", args.image,
        "bash", "-c", "exec /spike/start-vm.sh" if args.no_seed else
        "xorriso -as mkisofs -quiet -V cidata -J -r -o /vm/seed.iso /spike/seed/user-data /spike/seed/meta-data && exec /spike/start-vm.sh"]
if args.no_seed:
    SKIP.add("cloud_init_done")
T_START = time.time()
sh(run)
boot = {}


@check("accel")
def c_accel():
    wait_until(lambda: "QEMU_STARTING" in sh([E, "logs", N], check=False).stdout, 60, 0.5)
    k = qmp({"execute": "query-kvm"})
    want = args.accel == "kvm"
    if k.get("enabled") != want:
        raise RuntimeError(f"query-kvm={k}, expected enabled={want}; logs:\n{sh([E, 'logs', N], check=False).stdout[-1500:]}")
    return f"query-kvm={k}"


@check("qga_ready")
def c_qga():
    r = cx("python3", "/spike/vmctl.py", "wait-qga", str(args.boot_timeout), timeout=args.boot_timeout + 60, check=False)
    if "TIMEOUT" in r.stdout:
        raise RuntimeError("QGA never answered")
    boot["qga_ready_s"] = round(time.time() - T_START, 1)
    return f"QGA answered {boot['qga_ready_s']} s after container start"


@check("cloud_init_done")
def c_ci():
    secs = wait_until(lambda: cx("grep", "-aq", "SPIKE_RUNCMD_DONE", "/vm/out/serial.log", check=False).returncode == 0,
                      args.boot_timeout, 3)
    if secs is None:
        raise RuntimeError("SPIKE_RUNCMD_DONE not seen")
    boot["runcmd_done_s"] = round(time.time() - T_START, 1)
    rc = re.findall(r"DNF_RC=(\d+)", cx("cat", "/vm/out/serial.log").stdout)
    return f"runcmd done {boot['runcmd_done_s']} s after start; seed dnf exit codes={rc}"


@check("exec")
def c_exec():
    r = gexec("/usr/bin/uname", "-m")
    assert r["exit"] == 0 and r["out"].strip() == "x86_64", r
    r2 = gsh("echo out; echo err >&2; exit 3")
    assert (r2["exit"], r2["out"], r2["err"]) == (3, "out\n", "err\n"), r2
    ctx = gsh("id -Z")["out"].strip()
    return f"uname -m ok; exit/stdout/stderr ok; guest-exec SELinux context={ctx}"


@check("firmware")
def c_fw():
    """Plan T22 / REQ-LIFE-09: under UEFI the guest exposes /sys/firmware/efi; under BIOS it must not."""
    r = gexec("/usr/bin/test", "-d", "/sys/firmware/efi")
    efi = r["exit"] == 0
    want = args.firmware == "uefi"
    assert efi == want, f"/sys/firmware/efi present={efi} but firmware={args.firmware}; test exit={r['exit']}"
    detail = gsh("ls /sys/firmware/efi/efivars 2>/dev/null | head -3; cat /sys/firmware/efi/fw_platform_size 2>/dev/null")["out"].strip().replace("\n", " ") if efi else "no /sys/firmware/efi"
    return f"firmware={args.firmware}: efi={efi}; {detail}"


@check("file_copy")
def c_copy():
    det = []
    for mb in (5, 50):
        cx("bash", "-c", f"head -c {mb * 1000000} /dev/urandom > /vm/run/blob{mb} && sha256sum /vm/run/blob{mb} > /vm/run/blob{mb}.sha")
        want = cx("cat", f"/vm/run/blob{mb}.sha").stdout.split()[0]
        t0 = time.time(); cx("python3", "/spike/vmctl.py", "put", f"/vm/run/blob{mb}", f"/tmp/blob{mb}"); t_put = time.time() - t0
        got_guest = gexec("/usr/bin/sha256sum", f"/tmp/blob{mb}")["out"].split()[0]
        t0 = time.time(); cx("python3", "/spike/vmctl.py", "get", f"/tmp/blob{mb}", f"/vm/run/back{mb}"); t_get = time.time() - t0
        got_back = cx("sha256sum", f"/vm/run/back{mb}").stdout.split()[0]
        assert want == got_guest == got_back, (want, got_guest, got_back)
        det.append(f"{mb} MB: put {t_put:.1f}s ({mb / t_put:.1f} MB/s), get {t_get:.1f}s ({mb / t_get:.1f} MB/s)")
    return "; ".join(det)


@check("screenshot")
def c_shot():
    det = []
    for i in range(args.screens):
        qmp({"execute": "screendump", "arguments": {"filename": f"/vm/out/shot{i}.png", "device": f"video{i}", "format": "png"}})
        d = open(copy_out(f"/vm/out/shot{i}.png"), "rb").read(24)
        assert d[:8] == b"\x89PNG\r\n\x1a\n"
        det.append(f"video{i}: {int.from_bytes(d[16:20], 'big')}x{int.from_bytes(d[20:24], 'big')}")
    return ", ".join(det)


@check("send_key")
def c_keys():
    r = gsh("echo root:spike | chpasswd")
    assert r["exit"] == 0, r
    cx("python3", "/spike/typekeys.py", "root\\n"); time.sleep(3)
    cx("python3", "/spike/typekeys.py", "spike\\n"); time.sleep(5)
    cx("python3", "/spike/typekeys.py", "echo TYPED_OK > /dev/ttyS0\\n")
    secs = wait_until(lambda: cx("grep", "-aq", "TYPED_OK", "/vm/out/serial.log", check=False).returncode == 0, 60, 1)
    if secs is None:
        raise RuntimeError("typed command did not reach serial log")
    return "logged in on VGA console via QMP send-key; typed command executed"


@check("vnc_exposed")
def c_vnc():
    det = []
    for i in range(args.screens):
        if args.network_host:
            host, port = "127.0.0.1", 5900 + i
        else:
            hp = sh([E, "port", N, f"{5900 + i}/tcp"]).stdout.strip().splitlines()[0]
            host, port = hp.rsplit(":", 1)
        assert host == "127.0.0.1", f"published on {host}, expected loopback"
        r = sh([sys.executable, os.path.join(HERE, "rfbprobe.py"), str(port)], timeout=30)
        m = re.search(r"size (\d+x\d+)", r.stdout)
        assert m, r.stdout
        det.append(f"display {i} at {host}:{port} -> {m.group(1)}")
    return "; ".join(det) + " (RFB handshake from the host through the published port)"


def screen_size(i):
    qmp({"execute": "screendump", "arguments": {"filename": f"/vm/out/size{i}.png", "device": f"video{i}", "format": "png"}})
    d = open(copy_out(f"/vm/out/size{i}.png"), "rb").read(24)
    return int.from_bytes(d[16:20], "big"), int.from_bytes(d[20:24], "big")


modeset_wait = []


@check("record_screens")
def c_record():
    if args.screens > 1:
        cx("python3", "/spike/vmctl.py", "put", "/spike/fb-activate.py", "/tmp/fb-activate.py")
        for i in range(1, args.screens):
            r = gexec("/usr/bin/python3", "/tmp/fb-activate.py", f"/dev/fb{i}")
            assert r["exit"] == 0, r
            # plan §9.7 forceModeset(): return only once the head shows its new mode (run 18 recorded 0.8 s of a
            # 12 s window on head 1 right after the modeset; a recorder attached before the VNC framebuffer resize
            # is the prime suspect)
            t = wait_until(lambda: screen_size(i) != (640, 480), 10, 0.2)
            assert t is not None, f"video{i} still 640x480 10 s after fb-activate"
            modeset_wait.append(f"video{i} modeset visible after {t:.1f}s")
    for i in range(args.screens):
        cx("/spike/rec.sh", "start", f"screen{i}", str(5900 + i))
    t0 = time.time()
    noise = " ".join(f"head -c 4096000 /dev/urandom > /dev/fb{i};" for i in range(1, args.screens))
    gsh(f"for i in 1 2 3 4 5 6 7 8; do {noise} sleep 1; done", timeout=60)
    cx("python3", "/spike/typekeys.py", "for i in 1 2 3 4 5; do date; sleep 1; done\\n")
    time.sleep(max(0, 12 - (time.time() - t0)))
    wall = time.time() - t0
    det = []
    for i in range(args.screens):
        r = cx("/spike/rec.sh", "stop", f"screen{i}", check=False)
        try:
            copy_out(f"/vm/out/screen{i}.log")
        except Exception:
            pass
        assert "stopped in" in r.stdout, r.stdout + r.stderr
        stop_ms = re.search(r"stopped in (\d+) ms", r.stdout).group(1)
        info = cx("gst-discoverer-1.0", f"/vm/out/screen{i}.webm").stdout
        dur = re.search(r"Duration: (\d+):(\d+):([\d.]+)", info)
        secs = int(dur.group(1)) * 3600 + int(dur.group(2)) * 60 + float(dur.group(3))
        wh = re.search(r"Width: (\d+).*?Height: (\d+)", info, re.S)
        assert abs(secs - wall) < 3, f"screen{i} duration {secs} vs wall {wall:.1f}"
        copy_out(f"/vm/out/screen{i}.webm")
        det.append(f"screen{i}: {secs:.1f}s (wall {wall:.1f}s) {wh.group(1)}x{wh.group(2)} stop={stop_ms}ms")
    return "; ".join(det + modeset_wait)


@check("audio_capture")
def c_audio():
    if gexec("/usr/bin/test", "-x", "/usr/bin/speaker-test")["exit"] != 0:
        raise RuntimeError("alsa-utils not installed in guest (seed dnf failed?)")
    mixer = gsh("amixer -c 0 get Master | tail -2")["out"].strip().replace("\n", " ")
    hmp("wavcapture /vm/out/cap.wav snd0")
    r = gexec("/usr/bin/speaker-test", "-t", "sine", "-f", "1000", "-c", "2", "-l", "1", timeout=60)
    hmp("stopcapture 0")
    stats = analyze_wav.analyze(copy_out("/vm/out/cap.wav"))
    assert stats["frames"] > 0 and stats["rms"] > 100 and 900 <= stats["freq"] <= 1100, stats
    return f"speaker-test exit={r['exit']}; mixer at start: {mixer}; {stats}"


def lsblk():
    return gsh("lsblk -dn -o NAME,SIZE,TRAN,TYPE")["out"]


def peripherals():
    return [x["name"] for x in qmp({"execute": "qom-list", "arguments": {"path": "/machine/peripheral"}})]


def unplug(dev_id, timeout=60):
    qmp({"execute": "device_del", "arguments": {"id": dev_id}})
    secs = wait_until(lambda: dev_id not in peripherals(), timeout, 0.5)
    if secs is None:
        raise RuntimeError(f"{dev_id} still present {timeout}s after device_del (guest did not release it)")
    return secs


@check("hotplug_usb_storage")
def c_usb():
    cx("qemu-img", "create", "-q", "-f", "raw", "/vm/run/stick.img", "64M")
    qmp({"execute": "blockdev-add", "arguments": {"driver": "raw", "node-name": "stick0",
         "file": {"driver": "file", "filename": "/vm/run/stick.img"}}})
    qmp({"execute": "device_add", "arguments": {"driver": "usb-storage", "id": "usbstick", "bus": "xhci.0", "drive": "stick0"}})
    t = wait_until(lambda: re.search(r"64M\s+usb", lsblk()), 60)
    assert t is not None, lsblk()
    u = unplug("usbstick"); qmp({"execute": "blockdev-del", "arguments": {"node-name": "stick0"}})
    assert wait_until(lambda: "usb" not in lsblk(), 30) is not None, lsblk()
    return f"guest saw usb disk after {t:.1f}s; unplug {u:.1f}s"


@check("hotplug_pcie_disk")
def c_pcie_disk():
    cx("qemu-img", "create", "-q", "-f", "qcow2", "/vm/run/hp.qcow2", "128M")
    qmp({"execute": "blockdev-add", "arguments": {"driver": "qcow2", "node-name": "hpdisk0",
         "file": {"driver": "file", "filename": "/vm/run/hp.qcow2"}}})
    qmp({"execute": "device_add", "arguments": {"driver": "virtio-blk-pci", "id": "hpdisk", "bus": "hp1", "drive": "hpdisk0"}})
    t = wait_until(lambda: re.search(r"vd\w+\s+128M", lsblk()), 60)
    assert t is not None, lsblk()
    u = unplug("hpdisk"); qmp({"execute": "blockdev-del", "arguments": {"node-name": "hpdisk0"}})
    assert wait_until(lambda: not re.search(r"128M", lsblk()), 30) is not None, lsblk()
    return f"virtio-blk on pcie-root-port hp1 visible after {t:.1f}s; unplug {u:.1f}s"


@check("hotplug_pcie_nic")
def c_pcie_nic():
    before = set(gsh("ls /sys/class/net")["out"].split())
    qmp({"execute": "netdev_add", "arguments": {"type": "user", "id": "hpnet0"}})
    qmp({"execute": "device_add", "arguments": {"driver": "virtio-net-pci", "id": "hpnic", "bus": "hp2", "netdev": "hpnet0",
                                                 "mac": "52:54:00:12:34:99"}})
    new = []
    t = wait_until(lambda: new.extend(set(gsh("ls /sys/class/net")["out"].split()) - before) or new, 60)
    assert t is not None, "no new interface"
    u = unplug("hpnic"); qmp({"execute": "netdev_del", "arguments": {"id": "hpnet0"}})
    return f"new guest interface {sorted(set(new))} after {t:.1f}s; unplug {u:.1f}s"


@check("cdrom_media_change")
def c_cd():
    cx("bash", "-c", "mkdir -p /vm/run/cdsrc && echo hello-cd > /vm/run/cdsrc/HELLO.TXT && "
                     "xorriso -as mkisofs -quiet -V SPIKECD -J -r -o /vm/run/cd.iso /vm/run/cdsrc")
    qmp({"execute": "blockdev-change-medium", "arguments": {"id": "cd0", "filename": "/vm/run/cd.iso", "format": "raw",
                                                             "read-only-mode": "read-only"}})
    t = wait_until(lambda: "SPIKECD" in gsh("blkid /dev/sr0")["out"], 60)
    assert t is not None, gsh("blkid /dev/sr0; lsblk")
    content = gsh("mkdir -p /mnt/cd && mount -o ro /dev/sr0 /mnt/cd && cat /mnt/cd/HELLO.TXT; umount /mnt/cd")["out"].strip()
    qmp({"execute": "eject", "arguments": {"id": "cd0", "force": True}})
    t2 = wait_until(lambda: gsh("blkid /dev/sr0")["exit"] != 0, 30)
    return f"inserted ISO visible after {t:.1f}s, content={content!r}; eject -> media gone after {t2}s"


@check("hotplug_cpu")
def c_cpu():
    n0 = int(gsh("nproc")["out"])
    slots = qmp({"execute": "query-hotpluggable-cpus"})
    free = [s for s in slots if "qom-path" not in s]
    assert free, slots
    s = free[0]
    qmp({"execute": "device_add", "arguments": {"driver": s["type"], "id": "cpu-hp1", **s["props"]}})
    t = wait_until(lambda: int(gsh("nproc")["out"]) == n0 + 1, 60)
    if t is None:  # guest may need manual onlining
        gsh("for c in /sys/devices/system/cpu/cpu[0-9]*/online; do echo 1 > $c; done")
        t = wait_until(lambda: int(gsh("nproc")["out"]) == n0 + 1, 30)
        assert t is not None, gsh("lscpu")
        return f"nproc {n0}->{n0 + 1} after MANUAL onlining; driver={s['type']} props={s['props']}"
    return f"nproc {n0}->{n0 + 1} after {t:.1f}s (auto-online); driver={s['type']} props={s['props']}"


@check("hotplug_memory")
def c_mem():
    def memtotal():
        return int(re.search(r"MemTotal:\s+(\d+)", gsh("cat /proc/meminfo")["out"]).group(1))
    m0 = memtotal()
    qmp({"execute": "object-add", "arguments": {"qom-type": "memory-backend-ram", "id": "mem-hp1", "size": 512 * 1024 * 1024}})
    qmp({"execute": "device_add", "arguments": {"driver": "pc-dimm", "id": "dimm-hp1", "memdev": "mem-hp1"}})
    t = wait_until(lambda: memtotal() > m0 + 400 * 1024, 60)
    if t is None:
        gsh("for m in /sys/devices/system/memory/memory*/state; do grep -q offline $m && echo online > $m; done")
        t = wait_until(lambda: memtotal() > m0 + 400 * 1024, 30)
        assert t is not None, (m0, memtotal())
        return f"MemTotal {m0}->{memtotal()} kB after MANUAL onlining"
    return f"MemTotal {m0}->{memtotal()} kB after {t:.1f}s (auto-online)"


for fn in [c_accel, c_qga, c_ci, c_exec, c_fw, c_copy, c_shot, c_keys, c_vnc, c_record, c_audio,
           c_usb, c_pcie_disk, c_pcie_nic, c_cd, c_cpu, c_mem]:
    fn()

# ---------------------------------------------------------------- artifacts + report
for f in ("/vm/out/serial.log",):
    try:
        copy_out(f)
    except Exception:
        pass
open(os.path.join(args.out, "container.log"), "w").write(sh([E, "logs", N], check=False).stdout)
env = {"engine": sh([E, "--version"], check=False).stdout.strip(), "accel": args.accel, "firmware": args.firmware, "boot": boot,
       "host_kernel": os.uname().release, "nproc": os.cpu_count()}
json.dump({"env": env, "results": results}, open(os.path.join(args.out, "results.json"), "w"), indent=2)
with open(os.path.join(args.out, "results.md"), "w") as md:
    md.write(f"### VM spikes: accel={args.accel} firmware={args.firmware}\n\n`{json.dumps(env)}`\n\n| Check | Status | Secs | Detail |\n|---|---|---|---|\n")
    for r in results:
        d = str(r["detail"]).replace("\n", " ").replace("|", "\\|")[:400]
        md.write(f"| {r['check']} | {r['status']} | {r['secs']} | {d} |\n")
sh([E, "rm", "-f", N], check=False)
sys.exit(0 if all(r["status"] in ("PASS", "SKIP", "XFAIL", "XPASS") for r in results) else 1)
