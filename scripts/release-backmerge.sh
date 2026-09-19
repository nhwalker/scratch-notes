#!/usr/bin/env bash
# Merge a release branch back into the trunk, one merge request per project.
set -euo pipefail
FLOW_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
. "$FLOW_DIR/lib/common.sh"
. "$FLOW_DIR/lib/gitlab.sh"

usage() {
  cat >&2 <<'USAGE'
usage: release-backmerge.sh [options] <version> [<project>...]

For every project whose <release-prefix><version> branch carries commits the
trunk does not, creates <backmerge-prefix><version>, merges the release branch
into it, pushes it and opens a merge request against the trunk. This is how
stabilisation fixes get back to the trunk without anyone cherry-picking by
hand.

Each project must be clean: the merge happens in the project checkout and the
branch that was checked out before is restored afterwards.

options:
  --no-fetch        work from refs already fetched
  --branch <name>   backmerge branch name (default: <backmerge-prefix><version>)
  --label <name>    add a merge request label (repeatable)
  --assign <user>   assign a user (repeatable)
  --no-mr           push the branches, do not ask for merge requests
  -y, --yes         do not ask before pushing
  -n, --dry-run     print what would run
  -v, --debug       verbose
  -h, --help        this text
USAGE
  exit "${1:-2}"
}

version=; fetch=1; bmbranch=; no_mr=
labels=(); assignees=(); projects=()
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) usage 0 ;;
    --no-fetch) fetch= ;;
    --branch) [ $# -ge 2 ] || usage; bmbranch="$2"; shift ;;
    --branch=*) bmbranch="${1#--branch=}" ;;
    --label) [ $# -ge 2 ] || usage; labels+=("$2"); shift ;;
    --label=*) labels+=("${1#--label=}") ;;
    --assign) [ $# -ge 2 ] || usage; assignees+=("$2"); shift ;;
    --assign=*) assignees+=("${1#--assign=}") ;;
    --no-mr) no_mr=1 ;;
    --) shift; while [ $# -gt 0 ]; do projects+=("$1"); shift; done; break ;;
    -*) flow_common_flag "$1" || { flow_err "unknown option: $1"; usage; } ;;
    *) if [ -z "$version" ]; then version="$1"; else projects+=("$1"); fi ;;
  esac
  shift
done

[ -n "$version" ] || usage
flow_init
flow_validate_name "$version"
relbranch="${FLOW_RELEASE_PREFIX}${version}"
bmbranch="${bmbranch:-${FLOW_BACKMERGE_PREFIX}${version}}"

flow_load_projects ${projects[@]+"${projects[@]}"}

plan="$FLOW_TMP/plan.tsv"; : > "$plan"
flow_summary_init
while IFS="$(printf '\t')" read -r rpath pname remote rrev; do
  trunk=$(flow_trunk_of "$rrev")
  if [ -z "$trunk" ]; then
    flow_summary_add skip "$rpath" "pinned at '$rrev', no trunk branch to merge into"
    continue
  fi
  if [ -n "$fetch" ]; then
    flow_run git -C "$rpath" fetch --quiet "$remote" \
      "+refs/heads/$relbranch:refs/remotes/$remote/$relbranch" \
      "+refs/heads/$trunk:refs/remotes/$remote/$trunk" >/dev/null 2>&1 || true
  fi
  if flow_remote_branch_exists "$rpath" "$remote" "$relbranch"; then
    relref="$remote/$relbranch"
  elif flow_branch_exists "$rpath" "$relbranch"; then
    relref="$relbranch"
  else
    flow_summary_add skip "$rpath" "no $relbranch"
    continue
  fi
  if ! flow_remote_branch_exists "$rpath" "$remote" "$trunk"; then
    flow_summary_add fail "$rpath" "no $remote/$trunk to merge into"
    continue
  fi
  pending=$(git -C "$rpath" rev-list --count "$remote/$trunk..$relref" 2>/dev/null || echo 0)
  if [ "$pending" = "0" ]; then
    flow_summary_add skip "$rpath" "$relref already merged into $trunk"
    continue
  fi
  if flow_is_dirty "$rpath"; then
    flow_summary_add fail "$rpath" "uncommitted changes; commit or stash them first"
    continue
  fi
  printf '%s\t%s\t%s\t%s\t%s\n' "$rpath" "$remote" "$trunk" "$relref" "$pending" >> "$plan"
done < "$FLOW_PROJECTS"

if [ ! -s "$plan" ]; then
  flow_no_plan "release-backmerge $relbranch" "nothing to back-merge"
fi

flow_info "about to back-merge $relbranch as $bmbranch:"
while IFS="$(printf '\t')" read -r rpath remote trunk relref pending; do
  printf '    %-40s %s commit(s) -> %s\n' "$rpath" "$pending" "$trunk" >&2
done < "$plan"
flow_confirm "create and push these back-merges${no_mr:+ (no merge requests)}?" || flow_die "aborted"

while IFS="$(printf '\t')" read -r rpath remote trunk relref pending; do
  prev=$(flow_current_branch "$rpath" || true)
  title="Merge $relbranch into $trunk"

  if ! flow_run git -C "$rpath" checkout --quiet -B "$bmbranch" "$remote/$trunk"; then
    flow_summary_add fail "$rpath" "could not create $bmbranch from $remote/$trunk"
    continue
  fi
  if [ -z "${FLOW_DRY_RUN:-}" ]; then
    if ! git -C "$rpath" merge --no-ff --no-edit -m "$title" "$relref" >"$FLOW_TMP/merge.log" 2>&1; then
      git -C "$rpath" merge --abort >/dev/null 2>&1 || true
      [ -n "$prev" ] && git -C "$rpath" checkout --quiet "$prev" >/dev/null 2>&1 || true
      sed 's/^/    /' "$FLOW_TMP/merge.log" >&2
      flow_summary_add fail "$rpath" "merge conflict; resolve by hand on $bmbranch"
      continue
    fi
  else
    printf '%s[dry-run]%s %s\n' "$FLOW_C_DIM" "$FLOW_C_OFF" \
      "$(flow_quote git -C "$rpath" merge --no-ff --no-edit -m "$title" "$relref")" >&2
  fi

  remote_sha=$(flow_remote_head "$rpath" "$remote" "$bmbranch" || true)
  flow_mr_reset
  if [ -z "$no_mr" ] && [ -z "$remote_sha" ]; then
    flow_mr_standard "$trunk" "$title" "Back-merge of $relbranch ($pending commit(s))."
    for l in ${labels[@]+"${labels[@]}"}; do flow_mr_add "merge_request.label=$l"; done
    for a in ${assignees[@]+"${assignees[@]}"}; do flow_mr_add "merge_request.assign=$a"; done
  fi
  if url=$(flow_push "$rpath" "$remote" "$bmbranch" "$remote_sha"); then
    flow_summary_add ok "$rpath" "${url:-pushed $remote/$bmbranch -> $trunk}"
  else
    flow_summary_add fail "$rpath" "push failed"
  fi
  [ -n "$prev" ] && flow_run git -C "$rpath" checkout --quiet "$prev" >/dev/null 2>&1 || true
done < "$plan"

flow_summary_print "release-backmerge $relbranch"
