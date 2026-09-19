#!/usr/bin/env bash
# Sync the workspace and rebase a feature branch onto each project's trunk.
set -euo pipefail
FLOW_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
. "$FLOW_DIR/lib/common.sh"

usage() {
  cat >&2 <<'USAGE'
usage: feature-sync.sh [options] [<name>] [<project>...]

Runs `repo sync`, then rebases <feature-prefix><name> onto the trunk of every
project that carries the branch. The trunk is the revision the manifest pins
for that project; the manifest itself is never modified.

With no <name>, the branch currently checked out in the project you are
standing in is used.

options:
  --no-sync         skip `repo sync`, rebase against refs already fetched
  --onto <ref>      rebase onto <ref> in every project instead of its trunk
  --keep-conflicts  leave a conflicted rebase in place for you to resolve
                    (default: abort it and report the project)
  -j, --jobs <n>    sync parallelism (default: FLOW_JOBS, 4)
  -n, --dry-run     print what would run
  -v, --debug       verbose
  -h, --help        this text
USAGE
  exit "${1:-2}"
}

name=; onto=; do_sync=1; keep=; jobs=
projects=()
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) usage 0 ;;
    --no-sync) do_sync= ;;
    --keep-conflicts) keep=1 ;;
    --onto) [ $# -ge 2 ] || usage; onto="$2"; shift ;;
    --onto=*) onto="${1#--onto=}" ;;
    -j|--jobs) [ $# -ge 2 ] || usage; jobs="$2"; shift ;;
    --jobs=*) jobs="${1#--jobs=}" ;;
    --) shift; while [ $# -gt 0 ]; do projects+=("$1"); shift; done; break ;;
    -*) flow_common_flag "$1" || { flow_err "unknown option: $1"; usage; } ;;
    *) if [ -z "$name" ]; then name="$1"; else projects+=("$1"); fi ;;
  esac
  shift
done

flow_init
jobs="${jobs:-$FLOW_JOBS}"

if [ -n "$name" ]; then
  branch="${FLOW_FEATURE_PREFIX}${name}"
else
  branch=$(flow_topic_from_cwd) || flow_die "no <name> given and the current directory is not on a branch in a project"
  flow_info "using the branch checked out here: $branch"
fi

# Refuse to rebase over uncommitted work: a rebase would stop halfway and
# leave the workspace in a state that is tedious to unwind across N projects.
flow_load_projects ${projects[@]+"${projects[@]}"}
dirty=0
while IFS="$(printf '\t')" read -r rpath pname remote rrev; do
  if flow_branch_exists "$rpath" "$branch" && flow_is_dirty "$rpath"; then
    flow_err "uncommitted changes in $rpath"
    dirty=$((dirty+1))
  fi
done < "$FLOW_PROJECTS"
[ "$dirty" -eq 0 ] || flow_die "commit or stash the changes above first ($dirty project(s))"

if [ -n "$do_sync" ]; then
  flow_info "repo sync (-j$jobs $FLOW_SYNC_ARGS)"
  # shellcheck disable=SC2086
  flow_run repo sync -j"$jobs" $FLOW_SYNC_ARGS ${projects[@]+"${projects[@]}"} \
    || flow_warn "repo sync reported errors; continuing with the refs that were fetched"
fi

flow_summary_init
while IFS="$(printf '\t')" read -r rpath pname remote rrev; do
  if ! flow_branch_exists "$rpath" "$branch"; then
    flow_summary_add skip "$rpath" "no $branch"
    continue
  fi
  trunk=$(flow_trunk_of "$rrev")
  if [ -n "$onto" ]; then
    base="$onto"
  elif ! base=$(flow_base_ref "$rpath" "$remote" "$trunk"); then
    flow_summary_add fail "$rpath" "cannot resolve a base ref (manifest revision: $rrev)"
    continue
  fi
  if ! git -C "$rpath" rev-parse --verify --quiet "$base^{commit}" >/dev/null; then
    flow_summary_add fail "$rpath" "base ref $base does not exist"
    continue
  fi
  read -r ahead behind <<EOF
$(flow_ahead_behind "$rpath" "$base" "$branch" || echo "0 0")
EOF
  if [ "$behind" = "0" ]; then
    flow_summary_add ok "$rpath" "already on top of $base ($ahead commit(s) ahead)"
    continue
  fi
  if [ -n "${FLOW_DRY_RUN:-}" ]; then
    flow_summary_add ok "$rpath" "would rebase $branch onto $base ($behind behind)"
    continue
  fi
  if git -C "$rpath" rebase "$base" "$branch" >"$FLOW_TMP/rebase.log" 2>&1; then
    flow_summary_add ok "$rpath" "rebased onto $base ($ahead commit(s))"
  else
    if [ -n "$keep" ]; then
      flow_summary_add fail "$rpath" "conflict; rebase left in progress, resolve and 'git rebase --continue'"
    else
      git -C "$rpath" rebase --abort >/dev/null 2>&1 || true
      flow_summary_add fail "$rpath" "conflict rebasing onto $base; rebase aborted"
    fi
    sed 's/^/    /' "$FLOW_TMP/rebase.log" >&2
  fi
done < "$FLOW_PROJECTS"

flow_summary_print "feature-sync $branch"
