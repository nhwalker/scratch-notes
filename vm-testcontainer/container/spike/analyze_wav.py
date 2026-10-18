#!/usr/bin/env python3
"""Spike helper: basic WAV stats + dominant frequency (zero crossings) in the loudest 0.5 s window.
CLI: analyze_wav.py <file.wav>   |   module: analyze(path) -> dict"""
import math, struct, sys, wave


def analyze(path):
    w = wave.open(path); n = w.getnframes(); fr = w.getframerate(); ch = w.getnchannels()
    res = {"frames": n, "rate": fr, "channels": ch, "bits": 8 * w.getsampwidth(), "dur": round(n / fr, 2),
           "rms": 0, "freq": 0}
    if n == 0:
        return res
    d = struct.unpack('<%dh' % (n * ch), w.readframes(n)); L = d[0::ch]
    res["rms"] = round(math.sqrt(sum(x * x for x in L) / len(L)))
    win = fr // 2
    best = max(range(0, max(1, len(L) - win), win // 2), key=lambda i: sum(abs(x) for x in L[i:i + win]))
    seg = L[best:best + win]; zc = sum(1 for a, b in zip(seg, seg[1:]) if (a < 0) != (b < 0))
    res["freq"] = round(zc / 2 / (len(seg) / fr))
    return res


if __name__ == "__main__":
    r = analyze(sys.argv[1])
    print(f"frames={r['frames']} rate={r['rate']} ch={r['channels']} bits={r['bits']} dur={r['dur']}s")
    print(f"rms={r['rms']}  dominant ~{r['freq']} Hz")
    sys.exit(0 if r["frames"] else 1)
