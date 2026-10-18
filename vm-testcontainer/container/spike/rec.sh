#!/usr/bin/env bash
# Spike recorder control (runs in the container).
#   rec.sh start <name> <vnc-port> [fps]   -> /vm/out/<name>.webm, pid in /vm/run/<name>.pid
#   rec.sh stop <name>                      -> SIGINT (EOS), wait up to 10 s, print stop latency
set -uo pipefail
# alive = process exists and is not a zombie (PID 1 in the spike container is QEMU, which never reaps orphans)
alive() { [ -e /proc/$1 ] && ! grep -q "^State:.*Z" /proc/$1/status 2>/dev/null; }
case "$1" in
start) setsid /spike/gst-record.sh "$3" "/vm/out/$2.webm" "${4:-10}" > "/vm/out/$2.log" 2>&1 < /dev/null &
       echo $! > "/vm/run/$2.pid"; echo "started $2 pid $(cat /vm/run/$2.pid)";;
stop)  P=$(cat "/vm/run/$2.pid"); T=$(date +%s%N); kill -INT "$P"
       for i in $(seq 1 ${STOP_WAIT_DS:-100}); do alive "$P" || break; sleep 0.1; done
       if alive "$P"; then echo "$2: STILL RUNNING after $(( ${STOP_WAIT_DS:-100}/10 )) s"; exit 1; fi
       echo "$2: stopped in $(( ($(date +%s%N)-T)/1000000 )) ms"; cat "/vm/out/$2.log";;
esac
