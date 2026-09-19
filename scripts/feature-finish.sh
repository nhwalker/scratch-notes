#!/usr/bin/env bash
# Retire a feature branch once its merge requests have been merged.
set -euo pipefail
FLOW_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
. "$FLOW_DIR/lib/common.sh"

usage() {
  cat >&2 <<'USAGE'
usage: feature-finish.sh [options] <name> [<project>...]

Syncs the workspace, checks that <feature-prefix><name> has landed on the
trunk of each project that carries it, then deletes the local branch and, if
it is still there, the branch on the server.

"Landed" means one of: the branch is an ancestor of the trunk, or every commit
on it is already upstream by patch id (git cherry). A squash merge rewrites the
commits, so neither test recognises it - such projects are reported as not
merged and need --force.

options:
  --no-sync           skip `repo sync`
  --keep-remote       do not delete the branch on the server
  --force             delete even where the branch has not landed
  -j, --jobs <n>      sync parallelism
  -y, --yes           do not ask before deleting
  -n, --dry-run       print what would happen
  -v, --debug         verbose
  -h, --help          this text
USAGE
  exit "${1:-2}"
}

name=; do_sync=1; keep_remote=; force=; jobs=
projects=()
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) usage 0 ;;
    --no-sync) do_sync= ;;
    --keep-remote) keep_remote=1 ;;
    --force) force=1 ;;
    -j|--jobs) [ $# -ge 2 ] || usage; jobs="$2"; shift ;;
    --jobs=*) jobs="${1#--jobs=}" ;;
    --) shift; while [ $# -gt 0 ]; do projects+=("$1"); shift; done; break ;;
    -*) flow_common_flag "$1" || { flow_err "unknown option: $1"; usage; } ;;
    *) if [ -z "$name" ]; then name="$1"; else projects+=("$1"); fi ;;
  esac
  shift
done

[ -n "$name" ] || usage
flow_init
jobs="${jobs:-$FLOW_JOBS}"
branch="${FLOW_FEATURE_PREFIX}${name}"

flow_load_projects ${projects[@]+"${projects[@]}"}

if [ -n "$do_sync" ]; then
  flow_info "repo sync (-j$jobs $FLOW_SYNC_ARGS)"
  # shellcheck disable=SC2086
  flow_run repo sync -j"$jobs" $FLOW_SYNC_ARGS ${projects[@]+"${projects[@]}"} \
    || flow_warn "repo sync reported errors; continuing with the refs that were fetched"
fi

plan="$FLOW_TMP/plan.tsv"; : > "$plan"
flow_summary_init
while IFS="$(printf '\t')" read -r rpath pname remote rrev; do
  flow_branch_exists "$rpath" "$branch" || { flow_summary_add skip "$rpath" "no $branch"; continue; }
  trunk=$(flow_trunk_of "$rrev")
  if ! base=$(flow_base_ref "$rpath" "$remote" "$trunk"); then
    flow_summary_add fail "$rpath" "cannot resolve a base ref (manifest revision: $rrev)"
    continue
  fi
  merged=no
  if git -C "$rpath" merge-base --is-ancestor "$branch" "$base" 2>/dev/null; then
    merged=yes
  elif [ -z "$(git -C "$rpath" cherry "$base" "$branch" 2>/dev/null | grep '^+' || true)" ]; then
    merged=yes
  fi
  if [ "$merged" = no ] && [ -z "$force" ]; then
    ahead=$(git -C "$rpath" rev-list --count "$base..$branch" 2>/dev/null || echo '?')
    flow_summary_add fail "$rpath" "$ahead commit(s) not on $base; not deleting (--force to override)"
    continue
  fi
  printf '%s\t%s\t%s\t%s\n' "$rpath" "$remote" "$base" "$merged" >> "$plan"
done < "$FLOW_PROJECTS"

if [ ! -s "$plan" ]; then
  flow_no_plan "feature-finish $branch" "nothing to delete"
fi

flow_info "about to delete $branch from:"
while IFS="$(printf '\t')" read -r rpath remote base merged; do
  printf '    %s%s\n' "$rpath" "$([ "$merged" = no ] && printf ' %s(not merged into %s)%s' "$FLOW_C_YEL" "$base" "$FLOW_C_OFF")" >&2
done < "$plan"
scope="local branches"
[ -z "$keep_remote" ] && scope="local branches, and the branch on the server"
flow_confirm "delete $scope?" || flow_die "aborted"

while IFS="$(printf '\t')" read -r rpath remote base merged; do
  msgs=
  cur=$(flow_current_branch "$rpath" || true)
  if [ "$cur" = "$branch" ]; then
    # Leave the project on the manifest revision, the way `repo sync -d` would.
    if ! flow_run git -C "$rpath" checkout --quiet --detach "$base"; then
      flow_summary_add fail "$rpath" "could not move off $branch"
      continue
    fi
  fi
  if flow_run git -C "$rpath" branch -D "$branch"; then
    msgs="local deleted"
  else
    flow_summary_add fail "$rpath" "could not delete local $branch"
    continue
  fi
  if [ -z "$keep_remote" ]; then
    if [ -n "$(git -C "$rpath" ls-remote --heads "$remote" "refs/heads/$branch" 2>/dev/null)" ]; then
      if flow_run git -C "$rpath" push "$remote" --delete "refs/heads/$branch"; then
        msgs="$msgs, remote deleted"
      else
        msgs="$msgs, remote delete FAILED"
      fi
    else
      msgs="$msgs, already gone on $remote"
    fi
  fi
  flow_summary_add ok "$rpath" "$msgs"
done < "$plan"

flow_summary_print "feature-finish $branch"
