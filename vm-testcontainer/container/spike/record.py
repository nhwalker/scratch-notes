#!/usr/bin/env python3
"""Spike helper: screendump loop -> ffmpeg (VP9/WebM). Usage: record.py <seconds> <fps> <out.webm>"""
import subprocess, sys, time, os
sys.path.insert(0, "/spike"); import vmctl
secs, fps, out = float(sys.argv[1]), float(sys.argv[2]), sys.argv[3]
ff = subprocess.Popen(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "image2pipe", "-framerate", str(fps),
                       "-i", "-", "-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8", "-b:v", "500k", out],
                      stdin=subprocess.PIPE)
c = vmctl.qmp(); tmp = "/vm/run/frame.png"; t0 = time.time(); n = 0; grab = []
while time.time() - t0 < secs:
    due = t0 + n / fps
    if time.time() < due: time.sleep(due - time.time())
    g0 = time.time(); c.call({"execute": "screendump", "arguments": {"filename": tmp, "format": "png"}}); grab.append(time.time() - g0)
    ff.stdin.write(open(tmp, "rb").read()); n += 1
ff.stdin.close(); ff.wait()
print(f"frames={n} avg_screendump_ms={1000*sum(grab)/len(grab):.0f} max_ms={1000*max(grab):.0f} size={os.path.getsize(out)}")
