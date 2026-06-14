#!/bin/bash
#
# Entrypoint for the rootful podman-in-podman GPU service container.
#
# 1. If a GPU has been injected by the host's NVIDIA Container Toolkit, generate
#    a CDI spec so nested containers can request it with `--device nvidia.com/gpu=all`.
# 2. Start the Podman API service on a unix socket with no idle timeout.
#
set -euo pipefail

CDI_OUTPUT="${CDI_OUTPUT:-/etc/cdi/nvidia.yaml}"
PODMAN_SOCKET="${PODMAN_SOCKET:-unix:///run/podman/podman.sock}"

gpu_present() {
  # The host's NVIDIA toolkit always injects the device nodes; utilities like
  # nvidia-smi are only present when the host injects them, so key off devices.
  [ -e /dev/nvidiactl ] || ls /dev/nvidia[0-9]* >/dev/null 2>&1
}

setup_cdi() {
  echo "GPU detected; generating CDI spec at ${CDI_OUTPUT}"
  mkdir -p "$(dirname "${CDI_OUTPUT}")"
  if nvidia-ctk cdi generate --output="${CDI_OUTPUT}"; then
    echo "CDI spec written. Nested containers can use: --device nvidia.com/gpu=all"
  else
    echo "WARN: nvidia-ctk cdi generate failed; GPU may be unusable in nested containers" >&2
  fi
}

if [ "${SKIP_CDI:-0}" = "1" ]; then
  echo "SKIP_CDI=1 set; skipping CDI setup."
elif [ "${FORCE_CDI:-0}" = "1" ] || gpu_present; then
  setup_cdi
else
  echo "No GPU detected; skipping CDI setup."
fi

# `--time=0` disables the idle timeout so the service stays up for the host.
echo "Starting podman system service on ${PODMAN_SOCKET}"
exec podman system service --time=0 "${PODMAN_SOCKET}"
