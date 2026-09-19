#!/usr/bin/env bash
# Safe wrapper around `repo sync` for a workspace with topic branches on it.
set -euo pipefail
FLOW_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
. "$FLOW_DIR/lib/common.sh"

usage() {
  cat >&2 <<'USAGE'
usage: ws-sync.sh [options] [<project>...] [-- <repo sync args>...]

Refuses to sync while any project has uncommitted changes, then runs
`repo sync`, then reports every project whose branch is now behind its trunk.
Anything after -- goes straight to `repo sync`.

options:
  --allow-dirty     sync anyway; repo will skip or complain per project
  -j, --jobs <n>    sync parallelism (default: FLOW_JOBS, 4)
  -n, --dry-run     print what would run
  -v, --debug       verbose
  -h, --help        this text
USAGE
  exit "${1:-2}"
}

allow_dirty=; jobs=
projects=(); extra=()
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) usage 0 ;;
    --allow-dirty) allow_dirty=1 ;;
    -j|--jobs) [ $# -ge 2 ] || usage; jobs="$2"; shift ;;
    --jobs=*) jobs="${1#--jobs=}" ;;
    --) shift; while [ $# -gt 0 ]; do extra+=("$1"); shift; done; break ;;
    -*) flow_common_flag "$1" || { flow_err "unknown option: $1"; usage; } ;;
    *) projects+=("$1") ;;
  esac
  shift
done

flow_init
jobs="${jobs:-$FLOW_JOBS}"
flow_load_projects ${projects[@]+"${projects[@]}"}

if [ -z "$allow_dirty" ]; then
  dirty=0
  while IFS="$(printf '\t')" read -r rpath pname remote rrev; do
    if flow_is_dirty "$rpath"; then
      flow_err "uncommitted changes in $rpath"
      dirty=$((dirty+1))
    fi
  done < "$FLOW_PROJECTS"
  [ "$dirty" -eq 0 ] || flow_die "$dirty project(s) have uncommitted changes; commit them or pass --allow-dirty"
fi

flow_info "repo sync (-j$jobs $FLOW_SYNC_ARGS)"
rc=0
# shellcheck disable=SC2086
flow_run repo sync -j"$jobs" $FLOW_SYNC_ARGS ${extra[@]+"${extra[@]}"} ${projects[@]+"${projects[@]}"} || rc=$?
[ "$rc" -eq 0 ] || flow_warn "repo sync exited $rc; the report below covers what was fetched"

behind_any=0
while IFS="$(printf '\t')" read -r rpath pname remote rrev; do
  branch=$(flow_current_branch "$rpath" || true)
  [ -n "$branch" ] || continue
  trunk=$(flow_trunk_of "$rrev")
  base=$(flow_base_ref "$rpath" "$remote" "$trunk" || true)
  [ -n "$base" ] || continue
  read -r ahead behind <<EOF
$(flow_ahead_behind "$rpath" "$base" "$branch" || echo "0 0")
EOF
  if [ "$behind" != "0" ]; then
    [ "$behind_any" -eq 0 ] && flow_warn "branches behind their trunk after sync:"
    behind_any=$((behind_any+1))
    printf '    %-40s %s is %s behind %s\n' "$rpath" "$branch" "$behind" "$base" >&2
  fi
done < "$FLOW_PROJECTS"

if [ "$behind_any" -gt 0 ]; then
  flow_info "rebase them with: $FLOW_DIR/feature-sync.sh --no-sync <name>"
else
  flow_ok "every checked-out branch is on top of its trunk"
fi
exit "$rc"
