#!/usr/bin/env bash
# Spike for the reference-guest pipeline (PLAN §10.3): customize a stock Rocky GenericCloud image once,
# flatten it, package it as a containerDisk, so tests can boot it with NO seed.
# Usage: build-guest.sh <engine> <runner-image> <base.qcow2> <workdir> <containerdisk-tag> [kvm|tcg]
set -euo pipefail
# Env (spikes A/C): GUEST_VARIANT=reference|blackbox (default reference). blackbox removes qemu-guest-agent and disables
#   cloud-init after customization (an agent-less, cloud-init-less guest for black-box tests).
#   SSH_PUBKEY_FILES="a.pub b.pub" installs those keys for root and vmtc. Both variants get: users root/vmtc with
#   password "vmtc", vmtc with NOPASSWD sudo, PermitRootLogin yes, PasswordAuthentication yes.
E=$1 IMG=$2 BASE=$(readlink -f "$3") WORK=$(mkdir -p "$4" && readlink -f "$4") TAG=$5 ACCEL=${6:-kvm}
VARIANT=${GUEST_VARIANT:-reference}
OUT=rocky9-vmtc.qcow2
if [ "$VARIANT" = blackbox ]; then OUT=rocky9-vmtc-blackbox.qcow2; fi
HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$WORK/run" "$WORK/seed"
KEYS=""; for f in ${SSH_PUBKEY_FILES:-}; do KEYS+="      - $(cat "$f")"$'\n'; done
{
cat <<'EOS'
#cloud-config
ssh_pwauth: true
disable_root: false
chpasswd:
  expire: false
  users:
    - {name: root, password: vmtc, type: text}
    - {name: vmtc, password: vmtc, type: text}
users:
  - default
  - name: vmtc
    shell: /bin/bash
    lock_passwd: false
    sudo: ALL=(ALL) NOPASSWD:ALL
EOS
if [ -n "$KEYS" ]; then printf '    ssh_authorized_keys:\n%s' "$KEYS"; fi
cat <<'EOS'
write_files:
  - path: /etc/cloud/cloud.cfg.d/99-vmtc-datasource.cfg
    content: |
      datasource_list: [ NoCloud, None ]
  - path: /etc/ssh/sshd_config.d/10-vmtc.conf
    content: |
      PermitRootLogin yes
      PasswordAuthentication yes
runcmd:
EOS
if [ -n "$KEYS" ]; then
  printf '  - mkdir -p -m 700 /root/.ssh\n'
  for f in ${SSH_PUBKEY_FILES}; do printf '  - echo "%s" >> /root/.ssh/authorized_keys\n' "$(cat "$f")"; done
  printf '  - chmod 600 /root/.ssh/authorized_keys\n'
fi
cat <<'EOS'
  - sh -c "dnf -y install alsa-utils > /var/log/seed-dnf.log 2>&1; echo DNF_RC=\$? > /dev/ttyS0"
  - sed -i 's/^FILTER_RPC_ARGS=.*/FILTER_RPC_ARGS=""/' /etc/sysconfig/qemu-ga
  - semanage permissive -a virt_qemu_ga_t
  - systemctl enable qemu-guest-agent
  - amixer -c 0 sset Master 90% unmute || true
  - amixer -c 0 sset PCM 90% unmute || true
  - alsactl store
EOS
if [ "$VARIANT" = blackbox ]; then
cat <<'EOS'
  - systemctl disable --now qemu-guest-agent
  - sh -c "dnf -y remove qemu-guest-agent > /var/log/seed-dnf-remove.log 2>&1; echo DNF_REMOVE_RC=\$? > /dev/ttyS0"
  - rpm -q qemu-guest-agent > /dev/ttyS0 2>&1 || echo AGENT_REMOVED > /dev/ttyS0
EOS
fi
cat <<'EOS'
  - cloud-init clean --logs
  - truncate -s0 /etc/machine-id
EOS
[ "$VARIANT" = blackbox ] && printf '  - touch /etc/cloud/cloud-init.disabled\n'
cat <<'EOS'
  - echo VMTC_CUSTOMIZE_DONE > /dev/ttyS0
  - poweroff
EOS
} > "$WORK/seed/user-data"
echo "--- seed/user-data ($VARIANT):"; sed 's/^/    /' "$WORK/seed/user-data"
printf 'instance-id: vmtc-customize\nlocal-hostname: vm\n' > "$WORK/seed/meta-data"
DEV=(); [ "$ACCEL" = kvm ] && DEV=(--device /dev/kvm)
T0=$(date +%s)
# 1) customization boot: QEMU exits when the guest powers off, so the container exits too
timeout 1800 "$E" run --rm --init "${DEV[@]}" -e VM_ACCEL="$ACCEL" -e VM_SCREENS=1 \
  -v "$BASE:/vm/base/disk.img:ro" -v "$WORK/run:/vm/run" -v "$WORK/seed:/vm/seedsrc:ro" -v "$HERE:/spike:ro" "$IMG" \
  bash -c 'mkdir -p /vm/out && xorriso -as mkisofs -quiet -V cidata -J -r -o /vm/seed.iso /vm/seedsrc/user-data /vm/seedsrc/meta-data && /spike/start-vm.sh; rc=$?; cp /vm/out/serial.log /vm/run/customize-serial.log; exit $rc'
echo "customize_seconds=$(( $(date +%s) - T0 ))"
grep -a -E "DNF_RC|VMTC_CUSTOMIZE_DONE" "$WORK/run/customize-serial.log" | tr -d '\r'
grep -aq VMTC_CUSTOMIZE_DONE "$WORK/run/customize-serial.log" || { echo "customization did not finish"; exit 1; }
# 2) flatten overlay (needs the same backing-file path, so same mounts)
T1=$(date +%s)
"$E" run --rm -v "$BASE:/vm/base/disk.img:ro" -v "$WORK/run:/vm/run" "$IMG" \
  qemu-img convert -O qcow2 -c /vm/run/overlay.qcow2 "/vm/run/$OUT"
echo "convert_seconds=$(( $(date +%s) - T1 ))"
ls -l "$BASE" "$WORK/run/$OUT"
# 3) containerDisk
printf 'FROM scratch\nADD --chown=107:107 %s /disk/%s\n' "$OUT" "$OUT" > "$WORK/run/Containerfile"
"$E" build -q -f "$WORK/run/Containerfile" -t "$TAG" "$WORK/run" >/dev/null
"$E" image ls "$TAG"
