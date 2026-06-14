#!/bin/bash
#
# Launch the podman-in-podman GPU service with rootless host Podman and expose
# its API socket back to the host via a bind-mounted directory.
#
#   ./start-podman-service.sh              # detached; prints how to connect
#   ./start-podman-service.sh --foreground # attached + --rm (used by systemd)
#
# Connect from the host with:
#   export CONTAINER_HOST=unix://$SOCK_DIR/podman.sock
#   podman --url "$CONTAINER_HOST" info
#
set -euo pipefail

IMAGE="${IMAGE:-podman-gpu-service:latest}"
NAME="${NAME:-podman-gpu-service}"
SOCK_DIR="${SOCK_DIR:-${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/podman-gpu}"
STORAGE_VOLUME="${STORAGE_VOLUME:-podman-gpu-storage}"

FOREGROUND=0
if [ "${1:-}" = "--foreground" ]; then
  FOREGROUND=1
fi

mkdir -p "${SOCK_DIR}"

# Inject the host GPU via the host's NVIDIA Container Toolkit CDI if available.
gpu_args=()
if [ "${FORCE_GPU:-0}" = "1" ] || command -v nvidia-ctk >/dev/null 2>&1 || [ -e /dev/nvidiactl ]; then
  gpu_args=(--device nvidia.com/gpu=all)
  echo "Host GPU support detected; passing --device nvidia.com/gpu=all"
else
  echo "No host GPU support detected; starting without GPU"
fi

run_args=(
  --name "${NAME}"
  --replace
  --privileged
  --security-opt label=disable
  -v "${SOCK_DIR}:/run/podman"
  -v "${STORAGE_VOLUME}:/var/lib/containers"
  "${gpu_args[@]}"
)

if [ "${FOREGROUND}" -eq 1 ]; then
  # systemd (Type=simple) tracks this process; clean up on exit.
  exec podman run --rm "${run_args[@]}" "${IMAGE}"
fi

podman run -d "${run_args[@]}" "${IMAGE}"

cat <<EOF

Started '${NAME}'. Connect from the host with:

  export CONTAINER_HOST=unix://${SOCK_DIR}/podman.sock
  podman --url "\$CONTAINER_HOST" info

(Docker clients can use DOCKER_HOST=unix://${SOCK_DIR}/podman.sock instead.)
EOF
