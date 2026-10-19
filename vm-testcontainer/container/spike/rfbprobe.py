#!/usr/bin/env python3
"""Spike: minimal RFB handshake (no auth) -> print server version, size, pixel format, name.
Usage: rfbprobe.py <port> [shared=1|0] [hold_seconds]"""
import socket, struct, sys
s = socket.create_connection(("127.0.0.1", int(sys.argv[1])), timeout=5)
ver = s.recv(12); print("server", ver.strip()); s.sendall(b"RFB 003.008\n")
n = s.recv(1)[0]; types = list(s.recv(n)); print("sec types", types)
if 1 not in types: print("auth required (no type 1)"); sys.exit(0)
s.sendall(bytes([1])); res = struct.unpack(">I", s.recv(4))[0]; print("sec result", res)
s.sendall(bytes([int(sys.argv[2]) if len(sys.argv) > 2 else 1]))  # shared flag
init = b""
while len(init) < 24: init += s.recv(24 - len(init))
w, h = struct.unpack(">HH", init[:4]); bpp, depth = init[4], init[5]; nlen = struct.unpack(">I", init[20:24])[0]
print(f"size {w}x{h} bpp {bpp} depth {depth} name {s.recv(nlen)!r}")
import time; time.sleep(float(sys.argv[3]) if len(sys.argv) > 3 else 0)
