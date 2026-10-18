#!/usr/bin/env bash
# Spike #5 scenario runner (CI). Usage: run-scenario.sh <name> <0666|0660> [containers.conf-file]
#   0666: /dev/kvm world rw (udev rule), podman service started normally
#   0660: /dev/kvm root:kvm 0660, runner user added to kvm; the podman service (and CLI reference runs) are started
#         in a fresh login session (`sudo -iu $USER`) so kvm is a SUPPLEMENTARY group, as for a real logged-in user.
#         (`sg kvm` would make kvm the primary group, which keep-groups does not preserve.)
# Optional containers.conf is installed to ~/.config/containers/containers.conf for the service.
set -uo pipefail
NAME=$1 MODE=$2 CONF=${3:-}
cd "$(dirname "$0")"
if [ "$MODE" = 0666 ]; then
  echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules >/dev/null
  sudo udevadm control --reload-rules && sudo udevadm trigger --name-match=kvm
else
  sudo rm -f /etc/udev/rules.d/99-kvm4all.rules
  sudo chown root:kvm /dev/kvm && sudo chmod 0660 /dev/kvm
  sudo usermod -aG kvm "$USER"
fi
ls -l /dev/kvm
mkdir -p ~/.config/containers; rm -f ~/.config/containers/containers.conf
[ -n "$CONF" ] && cp "$CONF" ~/.config/containers/containers.conf && echo "containers.conf:" && cat ~/.config/containers/containers.conf
SOCK="$XDG_RUNTIME_DIR/podman/podman-$NAME.sock"; mkdir -p "$(dirname "$SOCK")"
# run a command as a fresh login of the same user (supplementary groups re-read from /etc/group)
login() { sudo -iu "$USER" env XDG_RUNTIME_DIR="$XDG_RUNTIME_DIR" IMAGE="$IMAGE" bash -c "$1"; }
if [ "$MODE" = 0666 ]; then podman system service --time=0 "unix://$SOCK" & else echo "service identity: $(login id)"; login "exec podman system service --time=0 unix://$SOCK" & fi
SVC=$!; sleep 3
if [ "$MODE" = 0660 ]; then
  PY="import os; os.open('/dev/kvm', os.O_RDWR); print('OPEN_OK')"
  echo "CLI reference (no group-add): $(login "podman run --rm --device /dev/kvm \$IMAGE python3 -c \"$PY\"" 2>&1 | tail -1)"
  echo "CLI reference (--group-add keep-groups): $(login "podman run --rm --device /dev/kvm --group-add keep-groups \$IMAGE python3 -c \"$PY\"" 2>&1 | tail -1)"
fi
rm -f target/probe-results.txt
# containerDisk probe image: 8 MiB qcow2 in a FROM-scratch image under /disk/
[ -f containerdisk/disk.qcow2 ] || podman run --rm -v "$PWD/containerdisk:/w:Z" "$IMAGE" qemu-img create -q -f qcow2 /w/disk.qcow2 8M
podman build -q -t localhost/vmtc-containerdisk-probe:latest containerdisk >/dev/null
DOCKER_HOST="unix://$SOCK" TESTCONTAINERS_RYUK_DISABLED=true PROBE_EXPECT_KVM_MODE="$MODE" \
  PROBE_KVM_GID="$(getent group kvm | cut -d: -f3)" mvn -B -q test -Dprobe.image="$IMAGE" || echo "MVN_FAILED" >> target/probe-results.txt
echo "image-mount with -Dapi.version=1.48:" >> target/probe-results.txt
DOCKER_HOST="unix://$SOCK" TESTCONTAINERS_RYUK_DISABLED=true mvn -B -q test -Dtest=PodmanKvmProbeTest#imageMount -Dapi.version=1.48 \
  -Dprobe.image="$IMAGE" || echo "MVN_FAILED(api1.48)" >> target/probe-results.txt
{ echo "podman: $(podman --version)  scenario=$NAME mode=$MODE conf=${CONF:-none}"; cat target/probe-results.txt; } > "../../probe-$NAME.txt"
cat "../../probe-$NAME.txt"
kill "$SVC" 2>/dev/null; pkill -f "podman system service --time=0 unix://$SOCK" 2>/dev/null; true
