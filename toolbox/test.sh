#!/usr/bin/env bash
# Smoke test for the image: runs as an unprivileged user with networking off,
# so it proves nothing is fetched at runtime and nothing depends on $HOME.
#   ./test.sh [image]          (uses podman if present, else docker)
set -euo pipefail
image="${1:-localhost/opencode-toolbox:latest}"
engine="$(command -v podman || command -v docker)"

"$engine" run --rm --network none --user 1000:1000 -e HOME=/tmp/home "$image" bash -c '
  set -euo pipefail
  mkdir -p "$HOME/proj" && cd "$HOME/proj"
  echo "opencode    $(opencode --version)"
  echo "$(plannotator --version)"

  # OpenCode 2 serves plugins/commands/skills from its background server.
  opencode service start >/dev/null
  for _ in $(seq 1 30); do
    opencode api get /api/plugin | grep -q "\"id\":\"plannotator\"" && break
    sleep 2
  done
  sleep 2

  opencode api get /api/plugin | grep -q "\"id\":\"plannotator\",\"source\":{\"type\":\"local\""
  opencode api get /api/plugin | grep -o "\"id\":\"plannotator\"[^}]*}[^}]*}[^}]*}" | grep -q "\"status\":\"active\""
  echo "ok  plannotator plugin active (loaded from /usr/local/lib/node_modules)"
  for c in review annotate last inbox; do
    opencode api get /api/command | grep -q "\"name\":\"plannotator-$c\""
  done
  echo "ok  /plannotator-{review,annotate,last,inbox} commands registered"
  opencode api get /api/skill | grep -q "\"path\":\"/etc/opencode/skills/plannotator/SKILL.md\""
  echo "ok  plannotator skill loaded from /etc/opencode"
  test ! -e "$HOME/.cache/opencode/packages" && ! ls "$HOME"/.cache/opencode/npm/@plannotator >/dev/null 2>&1
  echo "ok  plugin was not downloaded at runtime"
'
