#!/usr/bin/env bash
# Cherry-pick trunk commits onto a release branch and open merge requests.
set -euo pipefail
FLOW_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
. "$FLOW_DIR/lib/common.sh"
. "$FLOW_DIR/lib/gitlab.sh"

usage() {
  cat >&2 <<'USAGE'
usage: backport.sh [options] <version> <commit>...

Finds which project each <commit> lives in, cherry-picks the ones belonging to
the same project onto a branch cut from <release-prefix><version>, pushes it
and opens a merge request against the release branch. Commits are picked in
the order you list them.

This is the trunk-based answer to a hotfix: the fix lands on the trunk first
and is carried to the release branch here, so nothing is lost at the next
release.

options:
  --branch <name>   branch to build (default: <backport-prefix><version>)
  --no-fetch        work from refs already fetched
  --label <name>    add a merge request label (repeatable)
  --assign <user>   assign a user (repeatable)
  --no-mr           push the branches, do not ask for merge requests
  -y, --yes         do not ask before pushing
  -n, --dry-run     print what would run
  -v, --debug       verbose
  -h, --help        this text

example:
  backport.sh 24.10 a1b2c3d 9f8e7d6
USAGE
  exit "${1:-2}"
}

version=; bpbranch=; fetch=1; no_mr=
labels=(); assignees=(); commits=()
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) usage 0 ;;
    --branch) [ $# -ge 2 ] || usage; bpbranch="$2"; shift ;;
    --branch=*) bpbranch="${1#--branch=}" ;;
    --no-fetch) fetch= ;;
    --label) [ $# -ge 2 ] || usage; labels+=("$2"); shift ;;
    --label=*) labels+=("${1#--label=}") ;;
    --assign) [ $# -ge 2 ] || usage; assignees+=("$2"); shift ;;
    --assign=*) assignees+=("${1#--assign=}") ;;
    --no-mr) no_mr=1 ;;
    --) shift; while [ $# -gt 0 ]; do commits+=("$1"); shift; done; break ;;
    -*) flow_common_flag "$1" || { flow_err "unknown option: $1"; usage; } ;;
    *) if [ -z "$version" ]; then version="$1"; else commits+=("$1"); fi ;;
  esac
  shift
done

[ -n "$version" ] || usage
[ ${#commits[@]} -gt 0 ] || { flow_err "give at least one commit to back-port"; usage; }
flow_init
flow_validate_name "$version"
relbranch="${FLOW_RELEASE_PREFIX}${version}"
bpbranch="${bpbranch:-${FLOW_BACKPORT_PREFIX}${version}}"

flow_load_projects

# ---- locate each commit ----------------------------------------------------
plan="$FLOW_TMP/plan.tsv"; : > "$plan"
found="$FLOW_TMP/found"; : > "$found"
flow_summary_init
while IFS="$(printf '\t')" read -r rpath pname remote rrev; do
  mine=
  for c in "${commits[@]}"; do
    if sha=$(git -C "$rpath" rev-parse --verify --quiet "$c^{commit}" 2>/dev/null); then
      mine="$mine $sha"
      printf '%s\n' "$c" >> "$found"
    fi
  done
  [ -n "$mine" ] || continue
  if [ -n "$fetch" ]; then
    flow_run git -C "$rpath" fetch --quiet "$remote" \
      "+refs/heads/$relbranch:refs/remotes/$remote/$relbranch" >/dev/null 2>&1 || true
  fi
  if ! flow_remote_branch_exists "$rpath" "$remote" "$relbranch"; then
    flow_summary_add fail "$rpath" "no $remote/$relbranch to back-port onto"
    continue
  fi
  if flow_is_dirty "$rpath"; then
    flow_summary_add fail "$rpath" "uncommitted changes; commit or stash them first"
    continue
  fi
  printf '%s\t%s\t%s\n' "$rpath" "$remote" "${mine# }" >> "$plan"
done < "$FLOW_PROJECTS"

for c in "${commits[@]}"; do
  grep -qxF "$c" "$found" || flow_die "commit '$c' is not in any project of this workspace"
done
[ -s "$plan" ] || flow_no_plan "backport $version" "nothing to back-port"

flow_info "about to back-port onto $relbranch as $bpbranch:"
while IFS="$(printf '\t')" read -r rpath remote shas; do
  printf '    %s\n' "$rpath" >&2
  for s in $shas; do
    printf '      %s %s\n' "$(git -C "$rpath" rev-parse --short "$s")" "$(git -C "$rpath" log -1 --format=%s "$s")" >&2
  done
done < "$plan"
flow_confirm "cherry-pick and push${no_mr:+ (no merge requests)}?" || flow_die "aborted"

while IFS="$(printf '\t')" read -r rpath remote shas; do
  prev=$(flow_current_branch "$rpath" || true)
  # shellcheck disable=SC2086  # $shas is a deliberately split list of ids
  n=$(set -- $shas; echo $#)
  title="Back-port $n commit(s) to $relbranch"
  if [ "$n" = "1" ]; then
    title="$(git -C "$rpath" log -1 --format=%s "$shas") [$relbranch]"
  fi

  if ! flow_run git -C "$rpath" checkout --quiet -B "$bpbranch" "$remote/$relbranch"; then
    flow_summary_add fail "$rpath" "could not create $bpbranch from $remote/$relbranch"
    continue
  fi
  failed=
  if [ -z "${FLOW_DRY_RUN:-}" ]; then
    for s in $shas; do
      if ! git -C "$rpath" cherry-pick -x "$s" >"$FLOW_TMP/pick.log" 2>&1; then
        git -C "$rpath" cherry-pick --abort >/dev/null 2>&1 || true
        sed 's/^/    /' "$FLOW_TMP/pick.log" >&2
        failed="$(git -C "$rpath" rev-parse --short "$s")"
        break
      fi
    done
  else
    for s in $shas; do
      printf '%s[dry-run]%s %s\n' "$FLOW_C_DIM" "$FLOW_C_OFF" \
        "$(flow_quote git -C "$rpath" cherry-pick -x "$s")" >&2
    done
  fi
  if [ -n "$failed" ]; then
    [ -n "$prev" ] && git -C "$rpath" checkout --quiet "$prev" >/dev/null 2>&1 || true
    flow_summary_add fail "$rpath" "$failed does not apply cleanly; back-port it by hand"
    continue
  fi

  remote_sha=$(flow_remote_head "$rpath" "$remote" "$bpbranch" || true)
  flow_mr_reset
  if [ -z "$no_mr" ] && [ -z "$remote_sha" ]; then
    body="Cherry-picked onto $relbranch:"
    for s in $shas; do
      body="$body $(git -C "$rpath" log -1 --format='%h (%s);' "$s")"
    done
    flow_mr_standard "$relbranch" "$title" "$body"
    for l in ${labels[@]+"${labels[@]}"}; do flow_mr_add "merge_request.label=$l"; done
    for a in ${assignees[@]+"${assignees[@]}"}; do flow_mr_add "merge_request.assign=$a"; done
  fi
  if url=$(flow_push "$rpath" "$remote" "$bpbranch" "$remote_sha"); then
    flow_summary_add ok "$rpath" "${url:-pushed $remote/$bpbranch -> $relbranch}"
  else
    flow_summary_add fail "$rpath" "push failed"
  fi
  [ -n "$prev" ] && flow_run git -C "$rpath" checkout --quiet "$prev" >/dev/null 2>&1 || true
done < "$plan"

flow_summary_print "backport $version"
