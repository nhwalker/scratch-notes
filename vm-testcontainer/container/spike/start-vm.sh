#!/usr/bin/env bash
# Spike VM launcher (runs in the container). Base disk at /vm/base/disk.img (ro), seed ISO optional at /vm/seed.iso.
set -euo pipefail
mkdir -p /vm/run /vm/out
FMT=$(qemu-img info --output=json /vm/base/disk.img | python3 -c 'import json,sys;print(json.load(sys.stdin)["format"])')
qemu-img create -q -f qcow2 -F "$FMT" -b /vm/base/disk.img /vm/run/overlay.qcow2 20G
if [ -w /dev/kvm ]; then ACCEL="-accel kvm -cpu host"; else ACCEL="-accel tcg -cpu max"; echo "WARN: no /dev/kvm, using TCG"; fi
SEED=(); [ -f /vm/seed.iso ] && SEED=(-drive file=/vm/seed.iso,format=raw,if=none,id=seed,readonly=on -device virtio-blk-pci,drive=seed)
echo "QEMU_STARTING $(date +%s.%N)"
exec /usr/libexec/qemu-kvm -name spikevm -nodefaults -no-user-config -M q35 $ACCEL \
  -smp ${VM_CPUS:-2} -m ${VM_MEM_MB:-2048} \
  -display none -vnc 127.0.0.1:0 -device VGA \
  -qmp tcp:127.0.0.1:4444,server=on,wait=off \
  -chardev socket,id=qga0,host=127.0.0.1,port=4445,server=on,wait=off \
  -device virtio-serial-pci -device virtserialport,chardev=qga0,name=org.qemu.guest_agent.0 \
  -serial file:/vm/out/serial.log \
  -drive file=/vm/run/overlay.qcow2,format=qcow2,if=none,id=disk0 -device virtio-blk-pci,drive=disk0,bootindex=0 \
  "${SEED[@]}" \
  -netdev user,id=net0 -device virtio-net-pci,netdev=net0 \
  -audiodev ${VM_AUDIODEV:-none},id=snd0 -device ich9-intel-hda -device hda-duplex,audiodev=snd0 \
  -device qemu-xhci,id=xhci \
  -device pcie-root-port,id=hp1,chassis=1 -device pcie-root-port,id=hp2,chassis=2
