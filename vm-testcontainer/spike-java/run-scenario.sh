#!/usr/bin/env bash
# Spike #5 scenario runner (CI). Usage: run-scenario.sh <name> <0666|0660> [containers.conf-file]
#   0666: /dev/kvm world rw (udev rule), podman service started normally
#   0660: /dev/kvm root:kvm 0660, runner user added to kvm, podman service started via `sg kvm`
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
if [ "$MODE" = 0666 ]; then podman system service --time=0 "unix://$SOCK" & else sg kvm -c "podman system service --time=0 unix://$SOCK" & fi
SVC=$!; sleep 3
if [ "$MODE" = 0660 ]; then
  echo "CLI reference (no group-add):"; sg kvm -c "podman run --rm --device /dev/kvm $IMAGE python3 -c \"import os; os.open('/dev/kvm', os.O_RDWR); print('CLI_OPEN_OK')\"" 2>&1 | tail -1
  echo "CLI reference (--group-add keep-groups):"; sg kvm -c "podman run --rm --device /dev/kvm --group-add keep-groups $IMAGE python3 -c \"import os; os.open('/dev/kvm', os.O_RDWR); print('CLI_KEEPGROUPS_OPEN_OK')\"" 2>&1 | tail -1
fi
rm -f target/probe-results.txt
DOCKER_HOST="unix://$SOCK" TESTCONTAINERS_RYUK_DISABLED=true PROBE_EXPECT_KVM_MODE="$MODE" \
  PROBE_KVM_GID="$(getent group kvm | cut -d: -f3)" mvn -B -q test -Dprobe.image="$IMAGE" || echo "MVN_FAILED" >> target/probe-results.txt
{ echo "podman: $(podman --version)  scenario=$NAME mode=$MODE conf=${CONF:-none}"; cat target/probe-results.txt; } > "../../probe-$NAME.txt"
cat "../../probe-$NAME.txt"
kill "$SVC" 2>/dev/null; pkill -f "podman system service --time=0 unix://$SOCK" 2>/dev/null; true
