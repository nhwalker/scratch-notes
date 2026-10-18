#!/usr/bin/env python3
"""Spike helper: minimal QMP / QGA client. Not production code (Phase 1 does this in Java).
Usage:
  vmctl.py qmp '<json command>'          e.g. '{"execute":"query-status"}'
  vmctl.py hmp '<hmp command line>'      via human-monitor-command
  vmctl.py wait-qga <timeout_s>          poll guest-ping until it answers; prints seconds waited
  vmctl.py exec <timeout_s> <argv...>    guest-exec + poll guest-exec-status; prints exit/stdout/stderr
  vmctl.py put <local> <guest_path>      guest-file-write
  vmctl.py get <guest_path> <local>      guest-file-read
  vmctl.py exec-json <timeout_s> <argv...>  like exec, prints {"exit":..,"out":..,"err":..} (exit=null on timeout)
"""
import base64, json, socket, sys, time

QMP = ("127.0.0.1", 4444)
QGA = ("127.0.0.1", 4445)

class Chan:
    def __init__(self, addr, timeout=10):
        self.s = socket.create_connection(addr, timeout=timeout)
        self.f = self.s.makefile("rwb")
    def send(self, obj):
        self.f.write(json.dumps(obj).encode() + b"\n"); self.f.flush()
    def recv(self):
        while True:
            line = self.f.readline()
            if not line:
                raise EOFError("connection closed")
            line = line.strip().lstrip(b"\xff")
            if not line:
                continue
            msg = json.loads(line)
            if "event" in msg:          # QMP async events: ignore in spike
                continue
            return msg
    def call(self, obj):
        self.send(obj); r = self.recv()
        if "error" in r:
            raise RuntimeError(json.dumps(r["error"]))
        return r.get("return")

def qmp():
    c = Chan(QMP); c.recv()                      # greeting
    c.call({"execute": "qmp_capabilities"}); return c

def qga(timeout=10):
    c = Chan(QGA, timeout)
    # resync: guest-sync-delimited makes the agent emit 0xFF before the reply
    sid = int(time.time() * 1000) % 2**31
    c.send({"execute": "guest-sync-delimited", "arguments": {"id": sid}})
    while c.recv().get("return") != sid:
        pass
    return c

def main(a):
    cmd = a[0]
    if cmd == "qmp":
        print(json.dumps(qmp().call(json.loads(a[1]))))
    elif cmd == "hmp":
        print(qmp().call({"execute": "human-monitor-command", "arguments": {"command-line": a[1]}}))
    elif cmd == "wait-qga":
        t0 = time.time(); deadline = t0 + float(a[1])
        while time.time() < deadline:
            try:
                qga(timeout=3).call({"execute": "guest-ping"}); print(f"{time.time()-t0:.1f}"); return 0
            except Exception:
                time.sleep(2)
        print("TIMEOUT"); return 1
    elif cmd == "exec":
        c = qga(); deadline = time.time() + float(a[1])
        pid = c.call({"execute": "guest-exec", "arguments": {"path": a[2], "arg": a[3:], "capture-output": True}})["pid"]
        while time.time() < deadline:
            st = c.call({"execute": "guest-exec-status", "arguments": {"pid": pid}})
            if st["exited"]:
                out = base64.b64decode(st.get("out-data", "")).decode(errors="replace")
                err = base64.b64decode(st.get("err-data", "")).decode(errors="replace")
                print(f"exit={st.get('exitcode')}\n--stdout--\n{out}--stderr--\n{err}", end="")
                return 0 if st.get("exitcode") == 0 else 2
            time.sleep(0.2)
        print("TIMEOUT"); return 1
    elif cmd == "exec-json":
        c = qga(); deadline = time.time() + float(a[1])
        pid = c.call({"execute": "guest-exec", "arguments": {"path": a[2], "arg": a[3:], "capture-output": True}})["pid"]
        while time.time() < deadline:
            st = c.call({"execute": "guest-exec-status", "arguments": {"pid": pid}})
            if st["exited"]:
                print(json.dumps({"exit": st.get("exitcode"), "signal": st.get("signal"),
                                  "out": base64.b64decode(st.get("out-data", "")).decode(errors="replace"),
                                  "err": base64.b64decode(st.get("err-data", "")).decode(errors="replace")}))
                return 0
            time.sleep(0.2)
        print(json.dumps({"exit": None, "out": "", "err": "TIMEOUT"})); return 1
    elif cmd == "put":
        c = qga(); data = open(a[1], "rb").read()
        h = c.call({"execute": "guest-file-open", "arguments": {"path": a[2], "mode": "w"}})
        for i in range(0, len(data), 1 << 20):
            c.call({"execute": "guest-file-write", "arguments": {"handle": h, "buf-b64": base64.b64encode(data[i:i + (1 << 20)]).decode()}})
        c.call({"execute": "guest-file-close", "arguments": {"handle": h}}); print(len(data))
    elif cmd == "get":
        c = qga(); h = c.call({"execute": "guest-file-open", "arguments": {"path": a[1], "mode": "r"}})
        n = 0
        with open(a[2], "wb") as out:
            while True:
                r = c.call({"execute": "guest-file-read", "arguments": {"handle": h, "count": 1 << 20}})
                b = base64.b64decode(r["buf-b64"]); out.write(b); n += len(b)
                if r["eof"]:
                    break
        c.call({"execute": "guest-file-close", "arguments": {"handle": h}}); print(n)
    return 0

if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
