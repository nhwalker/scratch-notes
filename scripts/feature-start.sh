#!/usr/bin/env bash
# Start a short-lived feature branch across a repo workspace.
set -euo pipefail
FLOW_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
. "$FLOW_DIR/lib/common.sh"

usage() {
  cat >&2 <<'USAGE'
usage: feature-start.sh [options] <name> [--all | <project>...]

Creates <feature-prefix><name> in the given projects, branching from the
revision the manifest pins for each project. Nothing is pushed.

options:
  --all             every project in the workspace
  --from <rev>      branch from <rev> instead of the manifest revision
                    (passed to `repo start --rev`; needs a recent repo)
  -n, --dry-run     print what would run
  -v, --debug       verbose
  -h, --help        this text

examples:
  feature-start.sh login-timeout platform/frameworks/base
  feature-start.sh login-timeout --all
USAGE
  exit "${1:-2}"
}

name=
from=
all=
projects=()
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) usage 0 ;;
    --all) all=1 ;;
    --from) [ $# -ge 2 ] || usage; from="$2"; shift ;;
    --from=*) from="${1#--from=}" ;;
    --) shift; while [ $# -gt 0 ]; do projects+=("$1"); shift; done; break ;;
    -*) flow_common_flag "$1" || { flow_err "unknown option: $1"; usage; } ;;
    *) if [ -z "$name" ]; then name="$1"; else projects+=("$1"); fi ;;
  esac
  shift
done

[ -n "$name" ] || usage
flow_init
flow_validate_name "$name"
branch="${FLOW_FEATURE_PREFIX}${name}"

if [ -z "$all" ] && [ ${#projects[@]} -eq 0 ]; then
  flow_die "name one or more project paths, or pass --all to branch the whole workspace"
fi

cmd=(repo start "$branch")
[ -n "$from" ] && cmd+=(--rev "$from")
if [ -n "$all" ]; then
  cmd+=(--all)
else
  cmd+=("${projects[@]}")
fi

flow_info "creating $branch"
flow_run "${cmd[@]}" || flow_die "repo start failed"
flow_ok "$branch created"
cat >&2 <<EOF

  next: commit your work, then
        $FLOW_DIR/feature-sync.sh $name      # rebase onto the trunk
        $FLOW_DIR/feature-submit.sh $name    # push and open merge requests
EOF
