#!/usr/bin/env bash
# End-to-end re-check of spikes #4/#7 with the consolidated seed (run on the host; needs vmtc-spike:gst image).
# Usage: spike47-verify.sh <base-disk.qcow2>
set -uo pipefail; cd "$(dirname "$0")"; IMG=$1; E=${ENGINE:-docker}
$E rm -f spikevm >/dev/null 2>&1
$E run -d --name spikevm --network host -v "$IMG":/vm/base/disk.img:ro -v "$PWD":/spike vmtc-spike:gst \
  bash -c 'xorriso -as mkisofs -quiet -V cidata -J -r -o /vm/seed.iso /spike/seed/user-data /spike/seed/meta-data && /spike/start-vm.sh' >/dev/null
T0=$(date +%s); X="$E exec spikevm /spike/vmctl.py"
echo "qga_ready_s=$($X wait-qga 900)"
until $E exec spikevm grep -aq SPIKE_RUNCMD_DONE /vm/out/serial.log; do sleep 5; [ $(( $(date +%s)-T0 )) -gt 1800 ] && { echo "RUNCMD TIMEOUT"; break; }; done
echo "runcmd_done_s=$(( $(date +%s)-T0 ))"
$X exec 30 /bin/sh -c 'id -Z; aplay -l | grep ^card; uname -r'
$X hmp 'wavcapture /vm/out/cap.wav snd0' >/dev/null
$X exec 120 /usr/bin/speaker-test -t sine -f 1000 -c 2 -l 1 >/dev/null; $X hmp 'stopcapture 0' >/dev/null
$E cp spikevm:/vm/out/cap.wav out/cap-verify.wav && python3 analyze_wav.py out/cap-verify.wav
$E cp spikevm:/vm/out/serial.log out/serial-verify.log
$E rm -f spikevm >/dev/null
