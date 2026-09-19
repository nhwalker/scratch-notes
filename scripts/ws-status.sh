#!/usr/bin/env bash
# One line per project: branch, distance from the trunk, working tree state.
set -euo pipefail
FLOW_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
. "$FLOW_DIR/lib/common.sh"

usage() {
  cat >&2 <<'USAGE'
usage: ws-status.sh [options] [<project>...]

Prints, for every project in the workspace: the checked-out branch, how far it
is ahead of and behind the trunk the manifest pins, whether the working tree
is dirty, and whether the branch exists on the server.

options:
  --branch <name>   report this branch instead of whatever is checked out,
                    and skip projects that do not have it
  --feature <name>  shorthand for --branch <feature-prefix><name>
  --release <ver>   shorthand for --branch <release-prefix><ver>
  --interesting     only projects that are dirty, ahead, behind or detached
  --no-remote       skip the "is it on the server" column (no network)
  -v, --debug       verbose
  -h, --help        this text
USAGE
  exit "${1:-2}"
}

want=; only_interesting=; check_remote=1
projects=()
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) usage 0 ;;
    --branch) [ $# -ge 2 ] || usage; want="$2"; shift ;;
    --branch=*) want="${1#--branch=}" ;;
    --feature) [ $# -ge 2 ] || usage; want="FEATURE:$2"; shift ;;
    --release) [ $# -ge 2 ] || usage; want="RELEASE:$2"; shift ;;
    --interesting) only_interesting=1 ;;
    --no-remote) check_remote= ;;
    --) shift; while [ $# -gt 0 ]; do projects+=("$1"); shift; done; break ;;
    -*) flow_common_flag "$1" || { flow_err "unknown option: $1"; usage; } ;;
    *) projects+=("$1") ;;
  esac
  shift
done

flow_init
case "$want" in
  FEATURE:*) want="${FLOW_FEATURE_PREFIX}${want#FEATURE:}" ;;
  RELEASE:*) want="${FLOW_RELEASE_PREFIX}${want#RELEASE:}" ;;
esac

flow_load_projects ${projects[@]+"${projects[@]}"}

rows="$FLOW_TMP/rows.tsv"; : > "$rows"
width=7
while IFS="$(printf '\t')" read -r rpath pname remote rrev; do
  branch=
  if [ -n "$want" ]; then
    flow_branch_exists "$rpath" "$want" || continue
    branch="$want"
  else
    branch=$(flow_current_branch "$rpath" || true)
  fi
  trunk=$(flow_trunk_of "$rrev")
  base=$(flow_base_ref "$rpath" "$remote" "$trunk" || true)
  ahead=-; behind=-
  if [ -n "$branch" ] && [ -n "$base" ]; then
    read -r ahead behind <<EOF
$(flow_ahead_behind "$rpath" "$base" "$branch" || echo "- -")
EOF
  fi
  state=clean
  flow_is_dirty "$rpath" && state=dirty
  [ -z "$branch" ] && branch="(detached)"
  onserver=
  if [ -n "$check_remote" ] && [ "$branch" != "(detached)" ]; then
    if [ -n "$(git -C "$rpath" ls-remote --heads "$remote" "refs/heads/$branch" 2>/dev/null)" ]; then
      onserver="on $remote"
    else
      onserver="local only"
    fi
  fi
  if [ -n "$only_interesting" ]; then
    [ "$state" = dirty ] || [ "$branch" = "(detached)" ] \
      || { [ "$ahead" != "0" ] && [ "$ahead" != "-" ]; } \
      || { [ "$behind" != "0" ] && [ "$behind" != "-" ]; } \
      || continue
  fi
  [ ${#rpath} -gt "$width" ] && width=${#rpath}
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$rpath" "$branch" "$ahead" "$behind" "$state" "${trunk:-$rrev}" "$onserver" >> "$rows"
done < "$FLOW_PROJECTS"

if [ ! -s "$rows" ]; then
  flow_warn "no projects to report${want:+ for $want}"
  exit 0
fi

printf '%s%-*s  %-28s %6s %6s  %-5s %-10s %s%s\n' "$FLOW_C_DIM" \
  "$width" "project" "branch" "ahead" "behind" "tree" "on server" "trunk" "$FLOW_C_OFF"
while IFS="$(printf '\t')" read -r rpath branch ahead behind state trunk onserver; do
  c=
  [ "$state" = dirty ] && c=$FLOW_C_YEL
  [ "$branch" = "(detached)" ] && c=$FLOW_C_DIM
  printf '%s%-*s  %-28s %6s %6s  %-5s %-10s %s%s\n' "$c" \
    "$width" "$rpath" "$branch" "$ahead" "$behind" "$state" "$onserver" "$trunk" "$FLOW_C_OFF"
done < "$rows"
