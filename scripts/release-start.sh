#!/usr/bin/env bash
# Cut release branches across a repo workspace.
set -euo pipefail
FLOW_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
. "$FLOW_DIR/lib/common.sh"

usage() {
  cat >&2 <<'USAGE'
usage: release-start.sh [options] <version> [--all | <project>...]

Creates <release-prefix><version> from the trunk of each project and publishes
it, so stabilisation work has somewhere to go while the trunk keeps moving.

The manifest is not touched. Pinning the manifest at the new release branches
is a separate, deliberate step - usually a change to the manifest repository
reviewed like any other.

options:
  --all             every project in the workspace
  --from <rev>      branch from <rev> instead of the manifest revision
  --no-push         create the branches locally only
  -y, --yes         do not ask before pushing
  -n, --dry-run     print what would run
  -v, --debug       verbose
  -h, --help        this text
USAGE
  exit "${1:-2}"
}

version=; from=; all=; push=1
projects=()
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) usage 0 ;;
    --all) all=1 ;;
    --from) [ $# -ge 2 ] || usage; from="$2"; shift ;;
    --from=*) from="${1#--from=}" ;;
    --no-push) push= ;;
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

if [ -z "$all" ] && [ ${#projects[@]} -eq 0 ]; then
  flow_die "name one or more project paths, or pass --all to cut the whole workspace"
fi

flow_load_projects ${projects[@]+"${projects[@]}"}

# Refuse to reuse an existing release branch: re-cutting one silently would
# throw away whatever stabilisation work is already on it.
existing=0
while IFS="$(printf '\t')" read -r rpath pname remote rrev; do
  if flow_branch_exists "$rpath" "$branch"; then
    flow_err "$rpath already has $branch"
    existing=$((existing+1))
  fi
done < "$FLOW_PROJECTS"
[ "$existing" -eq 0 ] || flow_die "$branch already exists in $existing project(s); pick another version or delete them first"

cmd=(repo start "$branch")
[ -n "$from" ] && cmd+=(--rev "$from")
if [ -n "$all" ]; then cmd+=(--all); else cmd+=("${projects[@]}"); fi

flow_info "creating $branch in $FLOW_PROJECT_COUNT project(s)"
flow_run "${cmd[@]}" || flow_die "repo start failed"

if [ -z "$push" ]; then
  flow_ok "$branch created locally (not pushed)"
  exit 0
fi

flow_confirm "publish $branch to the remote of $FLOW_PROJECT_COUNT project(s)?" || flow_die "aborted; branches exist locally"

flow_summary_init
while IFS="$(printf '\t')" read -r rpath pname remote rrev; do
  flow_branch_exists "$rpath" "$branch" || { flow_summary_add skip "$rpath" "no $branch"; continue; }
  if [ -n "$(git -C "$rpath" ls-remote --heads "$remote" "refs/heads/$branch" 2>/dev/null)" ]; then
    flow_summary_add skip "$rpath" "$branch already on $remote"
    continue
  fi
  if flow_run git -C "$rpath" push --set-upstream "$remote" "refs/heads/$branch:refs/heads/$branch"; then
    flow_summary_add ok "$rpath" "published $remote/$branch"
  else
    flow_summary_add fail "$rpath" "push failed"
  fi
done < "$FLOW_PROJECTS"

rc=0
flow_summary_print "release-start $branch" || rc=$?
cat >&2 <<EOF

  reminder: the manifest still points at the trunk. Pin the projects above at
  $branch in the manifest repository when you want the workspace to track the
  release.
EOF
exit "$rc"
