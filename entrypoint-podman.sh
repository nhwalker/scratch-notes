#!/bin/bash
# Entrypoint for the Java/Gradle CI image.
#
# START_PODMAN (non-empty): fork a Docker-compatible podman API service
# (--time=0, never idle-exits) and export DOCKER_HOST at its unix socket
# so Testcontainers and docker CLIs talk to it.
#
# If nvidia-smi reports a GPU, generate the NVIDIA CDI spec so nested
# containers can target GPUs with --device nvidia.com/gpu=...
#
# This script is also sourced by derived images (e.g. the k3s image) to
# reuse the podman/GPU setup: the final exec only runs when the script
# is executed directly, so a sourcing caller keeps control afterwards.
set -euo pipefail

PODMAN_SOCKET=/run/podman/podman.sock

if [[ -n "${START_PODMAN:-}" ]]; then
    mkdir -p "$(dirname "${PODMAN_SOCKET}")"
    podman system service --time=0 "unix://${PODMAN_SOCKET}" &
    export DOCKER_HOST="unix://${PODMAN_SOCKET}"
    for _ in $(seq 1 50); do
        [[ -S "${PODMAN_SOCKET}" ]] && break
        sleep 0.1
    done
    if [[ ! -S "${PODMAN_SOCKET}" ]]; then
        echo "entrypoint: podman service socket ${PODMAN_SOCKET} did not appear" >&2
    fi
fi

if command -v nvidia-smi >/dev/null 2>&1 && nvidia-smi -L 2>/dev/null | grep -q 'GPU'; then
    mkdir -p /etc/cdi
    nvidia-ctk cdi generate --output=/etc/cdi/nvidia.yaml \
        || echo "entrypoint: nvidia-ctk cdi generate failed; GPU CDI devices unavailable" >&2
fi

# Only hand off to the container command when run as the entrypoint
# itself; when sourced, return so the caller can continue.
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    exec "$@"
fi
