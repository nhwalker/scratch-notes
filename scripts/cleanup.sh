#!/usr/bin/env bash
# Delete topic branches that have already landed on the trunk.
set -euo pipefail
FLOW_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
. "$FLOW_DIR/lib/common.sh"

usage() {
  cat >&2 <<'USAGE'
usage: cleanup.sh [options] [<project>...]

Finds branches matching a pattern that are already contained in their
project's trunk, and deletes them. Local only by default.

Release branches are excluded unless you ask for them by pattern, and a
branch with commits the trunk does not have is never deleted without --force.

options:
  --pattern <glob>  branches to consider (default: <feature-prefix>*,
                    repeatable)
  --remote          also delete the matching branch on the server
  --force           delete branches that have not landed
  --list            only list what would be deleted
  -y, --yes         do not ask
  -n, --dry-run     print what would run
  -v, --debug       verbose
  -h, --help        this text
USAGE
  exit "${1:-2}"
}

do_remote=; force=; list_only=
patterns=(); projects=()
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) usage 0 ;;
    --pattern) [ $# -ge 2 ] || usage; patterns+=("$2"); shift ;;
    --pattern=*) patterns+=("${1#--pattern=}") ;;
    --remote) do_remote=1 ;;
    --force) force=1 ;;
    --list) list_only=1 ;;
    --) shift; while [ $# -gt 0 ]; do projects+=("$1"); shift; done; break ;;
    -*) flow_common_flag "$1" || { flow_err "unknown option: $1"; usage; } ;;
    *) projects+=("$1") ;;
  esac
  shift
done

flow_init
[ ${#patterns[@]} -gt 0 ] || patterns=("${FLOW_FEATURE_PREFIX}*")
flow_load_projects ${projects[@]+"${projects[@]}"}

plan="$FLOW_TMP/plan.tsv"; : > "$plan"
while IFS="$(printf '\t')" read -r rpath pname remote rrev; do
  trunk=$(flow_trunk_of "$rrev")
  base=$(flow_base_ref "$rpath" "$remote" "$trunk" || true)
  [ -n "$base" ] || { flow_warn "$rpath: cannot resolve a base ref, skipping"; continue; }
  # shellcheck disable=SC2046  # one for-each-ref pattern argument per --pattern
  refs=$(git -C "$rpath" for-each-ref --format='%(refname:short)' \
          $(for p in "${patterns[@]}"; do printf 'refs/heads/%s ' "$p"; done) 2>/dev/null || true)
  [ -n "$refs" ] || continue
  cur=$(flow_current_branch "$rpath" || true)
  for b in $refs; do
    landed=no
    if git -C "$rpath" merge-base --is-ancestor "$b" "$base" 2>/dev/null; then
      landed=yes
    elif [ -z "$(git -C "$rpath" cherry "$base" "$b" 2>/dev/null | grep '^+' || true)" ]; then
      landed=yes
    fi
    [ "$landed" = yes ] || [ -n "$force" ] || continue
    printf '%s\t%s\t%s\t%s\t%s\n' "$rpath" "$remote" "$b" "$landed" "$([ "$b" = "$cur" ] && echo current || echo other)" >> "$plan"
  done
done < "$FLOW_PROJECTS"

if [ ! -s "$plan" ]; then
  flow_ok "nothing to clean up"
  exit 0
fi

flow_info "branches to delete:"
while IFS="$(printf '\t')" read -r rpath remote b landed cur; do
  printf '    %-40s %s%s\n' "$rpath" "$b" \
    "$([ "$landed" = no ] && printf ' %s(NOT landed)%s' "$FLOW_C_YEL" "$FLOW_C_OFF")" >&2
done < "$plan"
[ -z "$list_only" ] || exit 0

scope="local branches"
[ -n "$do_remote" ] && scope="local branches, and the branches on the server"
flow_confirm "delete these $scope?" || flow_die "aborted"

flow_summary_init
while IFS="$(printf '\t')" read -r rpath remote b landed cur; do
  if [ "$cur" = current ]; then
    base_ref=$(flow_m_ref "$rpath")
    if [ -z "$base_ref" ] || ! flow_run git -C "$rpath" checkout --quiet --detach "$base_ref"; then
      flow_summary_add fail "$rpath" "$b is checked out and could not be left"
      continue
    fi
  fi
  if ! flow_run git -C "$rpath" branch -D "$b"; then
    flow_summary_add fail "$rpath" "could not delete $b"
    continue
  fi
  msg="$b deleted"
  if [ -n "$do_remote" ]; then
    if [ -n "$(git -C "$rpath" ls-remote --heads "$remote" "refs/heads/$b" 2>/dev/null)" ]; then
      if flow_run git -C "$rpath" push "$remote" --delete "refs/heads/$b"; then
        msg="$msg, removed from $remote"
      else
        msg="$msg, remote delete FAILED"
      fi
    fi
  fi
  flow_summary_add ok "$rpath" "$msg"
done < "$plan"

flow_summary_print "cleanup"
