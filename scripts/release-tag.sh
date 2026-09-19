#!/usr/bin/env bash
# Tag a release across a repo workspace.
set -euo pipefail
FLOW_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
. "$FLOW_DIR/lib/common.sh"

usage() {
  cat >&2 <<'USAGE'
usage: release-tag.sh [options] <version> [<project>...]

Puts an annotated <tag-prefix><version> tag on the head of
<release-prefix><version> in every project that has the branch, and pushes the
tags. Projects without the release branch are skipped.

options:
  --on <ref>        tag <ref> instead of the release branch
  --message <text>  tag message (default: "<tag>")
  --sign            make a signed tag (git tag -s)
  --force           move an existing tag
  --no-push         create the tags locally only
  --no-fetch        do not refresh the release branch first
  -y, --yes         do not ask before pushing
  -n, --dry-run     print what would run
  -v, --debug       verbose
  -h, --help        this text
USAGE
  exit "${1:-2}"
}

version=; on=; message=; sign=; force=; push=1; fetch=1
projects=()
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) usage 0 ;;
    --on) [ $# -ge 2 ] || usage; on="$2"; shift ;;
    --on=*) on="${1#--on=}" ;;
    --message|-m) [ $# -ge 2 ] || usage; message="$2"; shift ;;
    --message=*) message="${1#--message=}" ;;
    --sign) sign=1 ;;
    --force) force=1 ;;
    --no-push) push= ;;
    --no-fetch) fetch= ;;
    --) shift; while [ $# -gt 0 ]; do projects+=("$1"); shift; done; break ;;
    -*) flow_common_flag "$1" || { flow_err "unknown option: $1"; usage; } ;;
    *) if [ -z "$version" ]; then version="$1"; else projects+=("$1"); fi ;;
  esac
  shift
done

[ -n "$version" ] || usage
flow_init
flow_validate_name "$version"
branch="${FLOW_RELEASE_PREFIX}${version}"
tag="${FLOW_TAG_PREFIX}${version}"
message="${message:-$tag}"

flow_load_projects ${projects[@]+"${projects[@]}"}

plan="$FLOW_TMP/plan.tsv"; : > "$plan"
flow_summary_init
while IFS="$(printf '\t')" read -r rpath pname remote rrev; do
  if [ -n "$on" ]; then
    ref="$on"
  elif flow_remote_branch_exists "$rpath" "$remote" "$branch" || flow_branch_exists "$rpath" "$branch"; then
    # Tag what the server has, not a stale local copy.
    [ -n "$fetch" ] && flow_run git -C "$rpath" fetch --quiet "$remote" "refs/heads/$branch" >/dev/null 2>&1 || true
    if flow_remote_branch_exists "$rpath" "$remote" "$branch"; then
      ref="$remote/$branch"
    else
      ref="$branch"
    fi
  else
    flow_summary_add skip "$rpath" "no $branch"
    continue
  fi
  if ! sha=$(git -C "$rpath" rev-parse --verify --quiet "$ref^{commit}"); then
    flow_summary_add fail "$rpath" "$ref does not resolve to a commit"
    continue
  fi
  if git -C "$rpath" rev-parse --verify --quiet "refs/tags/$tag" >/dev/null && [ -z "$force" ]; then
    old=$(git -C "$rpath" rev-parse "refs/tags/$tag^{commit}")
    if [ "$old" = "$sha" ]; then
      flow_summary_add skip "$rpath" "$tag already points at $(git -C "$rpath" rev-parse --short "$sha")"
    else
      flow_summary_add fail "$rpath" "$tag exists and points elsewhere (--force to move it)"
    fi
    continue
  fi
  printf '%s\t%s\t%s\t%s\n' "$rpath" "$remote" "$ref" "$sha" >> "$plan"
done < "$FLOW_PROJECTS"

if [ ! -s "$plan" ]; then
  flow_no_plan "release-tag $tag" "nothing to tag"
fi

flow_info "about to tag $tag:"
while IFS="$(printf '\t')" read -r rpath remote ref sha; do
  printf '    %-40s %s @ %s\n' "$rpath" "$ref" "$(git -C "$rpath" rev-parse --short "$sha")" >&2
done < "$plan"
flow_confirm "create${push:+ and push} $tag in these projects?" || flow_die "aborted"

while IFS="$(printf '\t')" read -r rpath remote ref sha; do
  tagcmd=(git -C "$rpath" tag)
  [ -n "$sign" ] && tagcmd+=(-s) || tagcmd+=(-a)
  [ -n "$force" ] && tagcmd+=(-f)
  tagcmd+=(-m "$message" "$tag" "$sha")
  if ! flow_run "${tagcmd[@]}"; then
    flow_summary_add fail "$rpath" "could not create $tag"
    continue
  fi
  if [ -z "$push" ]; then
    flow_summary_add ok "$rpath" "tagged locally"
    continue
  fi
  pushcmd=(git -C "$rpath" push)
  [ -n "$force" ] && pushcmd+=(--force)
  pushcmd+=("$remote" "refs/tags/$tag")
  if flow_run "${pushcmd[@]}"; then
    flow_summary_add ok "$rpath" "$tag -> $remote"
  else
    flow_summary_add fail "$rpath" "tag push failed"
  fi
done < "$plan"

flow_summary_print "release-tag $tag"
