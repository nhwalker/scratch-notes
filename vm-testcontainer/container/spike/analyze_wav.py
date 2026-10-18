#!/usr/bin/env python3
"""Spike helper: basic WAV stats + dominant frequency (zero crossings) in the loudest 0.5 s window."""
import wave, struct, math, sys
w = wave.open(sys.argv[1]); n = w.getnframes(); fr = w.getframerate(); ch = w.getnchannels()
print(f"frames={n} rate={fr} ch={ch} bits={8*w.getsampwidth()} dur={n/fr:.2f}s")
if n == 0: sys.exit(1)
d = struct.unpack('<%dh' % (n*ch), w.readframes(n)); L = d[0::ch]
print(f"rms={math.sqrt(sum(x*x for x in L)/len(L)):.0f} nonzero={sum(1 for x in L if x)}/{len(L)}")
win = fr // 2
best = max(range(0, max(1, len(L)-win), win//2), key=lambda i: sum(abs(x) for x in L[i:i+win]))
seg = L[best:best+win]; zc = sum(1 for a, b in zip(seg, seg[1:]) if (a < 0) != (b < 0))
print(f"loudest window @ {best/fr:.2f}s: ~{zc/2/(len(seg)/fr):.0f} Hz")
