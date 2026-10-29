#!/usr/bin/env bash
# Smoke test for the image: runs as an unprivileged user with networking off,
# so it proves nothing is fetched at runtime and nothing depends on $HOME.
#   ./test.sh [image]          (uses podman if present, else docker)
set -euo pipefail
image="${1:-localhost/opencode-toolbox:latest}"
engine="$(command -v podman || command -v docker)"

"$engine" run --rm --network none --user 1000:1000 -e HOME=/tmp/home "$image" bash -lc '
  set -euo pipefail
  mkdir -p "$HOME" && cd /tmp
  echo "opencode    $(opencode --version)"
  echo "$(plannotator --version)"
  test "$OPENCODE_CONFIG_DIR" = /etc/opencode
  opencode debug skill | grep -q "/etc/opencode/skills/plannotator/SKILL.md"
  echo "ok  plannotator skill loaded from /etc/opencode"
  test "$(opencode debug config | grep -c "\"plannotator-")" -ge 3
  echo "ok  /plannotator-* commands loaded from /etc/opencode"
  opencode debug agent build 2>/dev/null | grep -q "\"submit_plan\""
  echo "ok  plannotator plugin loaded (submit_plan tool registered)"
  test ! -e "$HOME/.cache/opencode/packages"
  echo "ok  plugin was not downloaded at runtime"
'
