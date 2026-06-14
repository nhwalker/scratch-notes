#!/bin/bash
#
# Register a GitLab Runner that drives the podman-gpu-service socket via the
# docker executor. Config is written into a named volume shared with the
# launcher, so registration only needs to run once.
#
# Required env:
#   GITLAB_URL    GitLab instance URL, e.g. https://gitlab.com/
#   RUNNER_TOKEN  Runner authentication token (glrt-...) created in GitLab
#
# Optional env:
#   DEFAULT_IMAGE  default job image (default docker.io/library/alpine:latest)
#   RUNNER_GPU=1   expose the GPU to jobs via CDI (nvidia.com/gpu=all)
#   RUNNER_IMAGE   gitlab-runner image (default docker.io/gitlab/gitlab-runner:latest)
#   CONFIG_VOLUME  config volume name (default gitlab-runner-config)
#
# Extra `gitlab-runner register` flags can be appended as arguments.
#
set -euo pipefail

RUNNER_IMAGE="${RUNNER_IMAGE:-docker.io/gitlab/gitlab-runner:latest}"
CONFIG_VOLUME="${CONFIG_VOLUME:-gitlab-runner-config}"
DEFAULT_IMAGE="${DEFAULT_IMAGE:-docker.io/library/alpine:latest}"
GITLAB_URL="${GITLAB_URL:?set GITLAB_URL, e.g. https://gitlab.com/}"
RUNNER_TOKEN="${RUNNER_TOKEN:?set RUNNER_TOKEN (runner authentication token, glrt-...)}"

register_args=(
  --non-interactive
  --url "${GITLAB_URL}"
  --token "${RUNNER_TOKEN}"
  --executor docker
  --docker-image "${DEFAULT_IMAGE}"
  # Talk to the in-container podman engine instead of a local docker daemon.
  --docker-host "unix:///run/podman/podman.sock"
)

if [ "${RUNNER_GPU:-0}" = "1" ]; then
  # podman maps --device nvidia.com/gpu=all to the CDI spec generated inside
  # the podman-gpu-service container.
  register_args+=(--docker-devices "nvidia.com/gpu=all")
  echo "Registering with GPU access (nvidia.com/gpu=all)"
fi

podman run --rm \
  -v "${CONFIG_VOLUME}:/etc/gitlab-runner" \
  "${RUNNER_IMAGE}" register "${register_args[@]}" "$@"

echo "Registered. Config stored in volume '${CONFIG_VOLUME}'."
echo "Start it with ./start-gitlab-runner.sh (or install-gitlab-runner-systemd.sh)."
