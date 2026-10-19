#!/usr/bin/env bash
# Spike VM launcher (runs in the container). Base disk at /vm/base/disk.img (ro), seed ISO optional at /vm/seed.iso.
set -euo pipefail
mkdir -p /vm/run /vm/out
BASE=${VM_BASE:-/vm/base/disk.img}
FMT=$(qemu-img info --output=json "$BASE" | python3 -c 'import json,sys;print(json.load(sys.stdin)["format"])')
qemu-img create -q -f qcow2 -F "$FMT" -b "$BASE" /vm/run/overlay.qcow2 20G
case "${VM_ACCEL:-auto}" in
  kvm) ACCEL="-accel kvm -cpu host";;
  tcg) ACCEL="-accel tcg -cpu max";;
  *)   if [ -r /dev/kvm ] && [ -w /dev/kvm ]; then ACCEL="-accel kvm -cpu host"; else ACCEL="-accel tcg -cpu max"; echo "WARN: no usable /dev/kvm, using TCG"; fi;;
esac
echo "ACCEL: $ACCEL"
SEED=(); [ -f /vm/seed.iso ] && SEED=(-drive file=/vm/seed.iso,format=raw,if=none,id=seed,readonly=on -device virtio-blk-pci,drive=seed)
# Screens: head 0 = VGA, heads 1..N-1 = secondary-vga; one VNC server per head (port 5900+i)
DISPLAYS=()
for ((i=0; i<${VM_SCREENS:-1}; i++)); do
  [ $i -eq 0 ] && DEV=VGA || DEV=secondary-vga
  DISPLAYS+=(-device $DEV,id=video$i -vnc ${VNC_BIND:-127.0.0.1}:$i,id=vnc$i,display=video$i${VNC_OPTS-,share=force-shared})
done
# Firmware: VM_FIRMWARE=uefi -> OVMF pflash pair (plan §8.4); default BIOS (SeaBIOS)
FW=()
if [ "${VM_FIRMWARE:-bios}" = uefi ]; then
  cp /usr/share/edk2/ovmf/OVMF_VARS.fd /vm/run/OVMF_VARS.fd
  FW=(-drive if=pflash,format=raw,unit=0,readonly=on,file=/usr/share/edk2/ovmf/OVMF_CODE.fd
      -drive if=pflash,format=raw,unit=1,file=/vm/run/OVMF_VARS.fd)
  echo "FIRMWARE: uefi (OVMF_CODE.fd + writable OVMF_VARS.fd copy)"
else
  echo "FIRMWARE: bios"
fi
# Port forwards (spike A): VM_PORT_FORWARDS="22:10022,80:10080" -> slirp hostfwd entries on net0 (guest:container port)
HOSTFWD=""
for pf in ${VM_PORT_FORWARDS//,/ }; do HOSTFWD+=",hostfwd=tcp:0.0.0.0:${pf##*:}-:${pf%%:*}"; done
[ -n "$HOSTFWD" ] && echo "PORT_FORWARDS: $HOSTFWD"
echo "QEMU_STARTING $(date +%s.%N)"
exec /usr/libexec/qemu-kvm -name spikevm -nodefaults -no-user-config -M q35 $ACCEL \
  -smp ${VM_CPUS:-2},maxcpus=${VM_MAXCPUS:-4} -m ${VM_MEM_MB:-2048},slots=4,maxmem=${VM_MAXMEM:-8G} \
  -display none "${DISPLAYS[@]}" "${FW[@]}" \
  -qmp tcp:${QMP_BIND:-127.0.0.1}:4444,server=on,wait=off \
  -chardev socket,id=qga0,host=127.0.0.1,port=4445,server=on,wait=off \
  -device virtio-serial-pci -device virtserialport,chardev=qga0,name=org.qemu.guest_agent.0 \
  -serial file:/vm/out/serial.log \
  -drive file=/vm/run/overlay.qcow2,format=qcow2,if=none,id=disk0 -device virtio-blk-pci,drive=disk0,bootindex=0 \
  "${SEED[@]}" \
  -netdev user,id=net0$HOSTFWD -device virtio-net-pci,netdev=net0 \
  -audiodev ${VM_AUDIODEV:-none},id=snd0 -device ich9-intel-hda -device hda-duplex,audiodev=snd0 \
  -device qemu-xhci,id=xhci \
  -device virtio-scsi-pci,id=scsi0 -device scsi-cd,id=cd0,bus=scsi0.0 \
  -device pcie-root-port,id=hp1,chassis=1 -device pcie-root-port,id=hp2,chassis=2 \
  -device pcie-root-port,id=hp3,chassis=3 -device pcie-root-port,id=hp4,chassis=4
