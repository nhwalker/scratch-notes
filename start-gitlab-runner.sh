#!/bin/bash
#
# Launch a GitLab Runner (rootless host podman) that uses the podman-gpu-service
# socket as its docker executor backend.
#
#   ./start-gitlab-runner.sh              # detached
#   ./start-gitlab-runner.sh --foreground # attached + --rm (used by systemd)
#
# Register first with ./register-gitlab-runner.sh. SOCK_DIR must match the value
# used by start-podman-service.sh.
#
set -euo pipefail

RUNNER_IMAGE="${RUNNER_IMAGE:-docker.io/gitlab/gitlab-runner:latest}"
NAME="${NAME:-gitlab-runner}"
CONFIG_VOLUME="${CONFIG_VOLUME:-gitlab-runner-config}"
SOCK_DIR="${SOCK_DIR:-${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/podman-gpu}"

FOREGROUND=0
if [ "${1:-}" = "--foreground" ]; then
  FOREGROUND=1
fi

if [ ! -S "${SOCK_DIR}/podman.sock" ]; then
  echo "WARN: ${SOCK_DIR}/podman.sock not found; is podman-gpu-service running?" >&2
fi

run_args=(
  --name "${NAME}"
  --replace
  -v "${CONFIG_VOLUME}:/etc/gitlab-runner"
  -v "${SOCK_DIR}:/run/podman"
)

if [ "${FOREGROUND}" -eq 1 ]; then
  # gitlab-runner's default command (`run`) stays in the foreground, so systemd
  # (Type=simple) can track it directly.
  exec podman run --rm "${run_args[@]}" "${RUNNER_IMAGE}"
fi

podman run -d "${run_args[@]}" "${RUNNER_IMAGE}"
echo "Started '${NAME}'. Logs: podman logs -f ${NAME}"
