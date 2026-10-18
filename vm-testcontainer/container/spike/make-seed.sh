#!/usr/bin/env bash
# Writes seed/user-data + seed/meta-data for the spike guest (cloud-init NoCloud).
# Optional: SEED_PACKAGES (default "alsa-utils"; empty = no dnf, works offline).
# Optional: GUEST_PROXY=http://10.0.2.2:<port> and EXTRA_CA=<pem file> for sandboxed networks.
# start-vm wrapper builds the ISO with: xorriso -as mkisofs -V cidata -J -r -o /vm/seed.iso seed/user-data seed/meta-data
set -euo pipefail; cd "$(dirname "$0")"; mkdir -p seed
{
cat <<'EOF2'
#cloud-config
chpasswd:
  expire: false
  users:
    - {name: root, password: spike, type: text}
EOF2
if [ -n "${EXTRA_CA:-}" ]; then
  printf 'write_files:\n  - path: /etc/pki/ca-trust/source/anchors/extra.crt\n    encoding: b64\n    content: %s\n' "$(base64 -w0 "$EXTRA_CA")"
fi
cat <<EOF2
runcmd:
  - echo SPIKE_RUNCMD_START > /dev/ttyS0
  - update-ca-trust
EOF2
[ -n "${GUEST_PROXY:-}" ] && printf '  - echo "proxy=%s" >> /etc/dnf/dnf.conf\n' "$GUEST_PROXY"
cat <<'EOF2'
EOF2
[ -n "${SEED_PACKAGES-alsa-utils}" ] && printf '  - dnf -y install %s > /dev/ttyS0 2>&1 || echo DNF_FAILED > /dev/ttyS0\n' "${SEED_PACKAGES-alsa-utils}"
cat <<'EOF2'
  - sed -i 's/^FILTER_RPC_ARGS=.*/FILTER_RPC_ARGS=""/' /etc/sysconfig/qemu-ga
  - semanage permissive -a virt_qemu_ga_t
  - systemctl enable qemu-guest-agent
  - systemctl restart qemu-guest-agent
  - amixer -c 0 sset Master 90% unmute || true
  - amixer -c 0 sset PCM 90% unmute || true
  - echo SPIKE_RUNCMD_DONE > /dev/ttyS0
EOF2
} > seed/user-data
printf 'instance-id: spike-1\nlocal-hostname: spikevm\n' > seed/meta-data
