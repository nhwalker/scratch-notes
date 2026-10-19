#!/usr/bin/env bash
# Spike: measure SIGINT->exit latency for different gst launch variants (runs in container).
try() { name=$1; shift; "$@" > /vm/run/$name.log 2>&1 & P=$!; sleep 5; T=$(date +%s%N); kill -INT $P
  for i in $(seq 1 200); do kill -0 $P 2>/dev/null || break; sleep 0.1; done
  if kill -0 $P 2>/dev/null; then echo "$name: HUNG >20s"; kill -9 $P; else echo "$name: stopped in $(( ($(date +%s%N)-T)/1000000 )) ms"; fi; }
PIPE="rfbsrc host=127.0.0.1 port=5900 view-only=true shared=true incremental=false ! videorate ! video/x-raw,framerate=10/1 ! videoconvert ! vp8enc deadline=1 cpu-used=8 ! webmmux ! filesink"
try A gst-launch-1.0 -e $PIPE location=/vm/out/tA.webm
try B gst-launch-1.0 -q -e $PIPE location=/vm/out/tB.webm
try C /spike/gst-record.sh 5900 /vm/out/tC.webm
try D setsid /spike/gst-record.sh 5900 /vm/out/tD.webm
# E: two concurrent recorders (screen0 + screen1), stop both
two() { /spike/gst-record.sh 5900 /vm/out/tE0.webm > /vm/run/E0.log 2>&1 & P0=$!; /spike/gst-record.sh 5901 /vm/out/tE1.webm > /vm/run/E1.log 2>&1 & P1=$!
  sleep ${1:-5}; T=$(date +%s%N); kill -INT $P0 $P1
  for i in $(seq 1 200); do kill -0 $P0 2>/dev/null || kill -0 $P1 2>/dev/null || break; sleep 0.1; done
  echo "E(${1:-5}s): after $(( ($(date +%s%N)-T)/1000000 )) ms alive: s0=$(kill -0 $P0 2>/dev/null && echo Y || echo n) s1=$(kill -0 $P1 2>/dev/null && echo Y || echo n)"; kill -9 $P0 $P1 2>/dev/null; }
[ "${ONLY_E:-}" ] && { two 5; two 12; }
