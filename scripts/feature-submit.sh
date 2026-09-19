#!/usr/bin/env bash
# Push a feature branch across the workspace and open a GitLab merge request
# per project that has commits.
set -euo pipefail
FLOW_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
. "$FLOW_DIR/lib/common.sh"
. "$FLOW_DIR/lib/gitlab.sh"

usage() {
  cat >&2 <<'USAGE'
usage: feature-submit.sh [options] [<name>] [<project>...]

For every project where <feature-prefix><name> has commits the trunk does not,
pushes the branch and asks GitLab to open a merge request against that
project's trunk. Projects with no commits are skipped, so a workspace-wide
branch produces merge requests only where you actually changed something.

A project whose branch is already on the server is re-pushed with a lease
(safe after a rebase) and, since its merge request already exists, without the
creation options - pass --update-mr to re-send title, labels and friends.

options:
  --title <text>        merge request title (default: the commit subject when
                        there is one commit, else the branch name)
  --description <text>  merge request description
  --description-file <f>
  --target <branch>     target branch (default: each project's trunk)
  --draft               mark the merge request as a draft
  --label <name>        add a label (repeatable)
  --assign <user>       assign a user (repeatable)
  --auto-merge          merge once the pipeline succeeds
  --no-remove-source    keep the source branch after merge
  --update-mr           re-send merge request options for existing branches
  --no-mr               just push, do not touch merge requests
  --no-force            never force-push; fail instead of re-pushing a rebase
  -y, --yes             do not ask before pushing
  -n, --dry-run         print the pushes instead of running them
  -v, --debug           verbose
  -h, --help            this text
USAGE
  exit "${1:-2}"
}

name=; title=; desc=; descfile=; target=; draft=; automerge=
update_mr=; no_mr=; no_force=
labels=(); assignees=(); projects=()
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) usage 0 ;;
    --title) [ $# -ge 2 ] || usage; title="$2"; shift ;;
    --title=*) title="${1#--title=}" ;;
    --description) [ $# -ge 2 ] || usage; desc="$2"; shift ;;
    --description=*) desc="${1#--description=}" ;;
    --description-file) [ $# -ge 2 ] || usage; descfile="$2"; shift ;;
    --description-file=*) descfile="${1#--description-file=}" ;;
    --target) [ $# -ge 2 ] || usage; target="$2"; shift ;;
    --target=*) target="${1#--target=}" ;;
    --draft) draft=1 ;;
    --label) [ $# -ge 2 ] || usage; labels+=("$2"); shift ;;
    --label=*) labels+=("${1#--label=}") ;;
    --assign) [ $# -ge 2 ] || usage; assignees+=("$2"); shift ;;
    --assign=*) assignees+=("${1#--assign=}") ;;
    --auto-merge) automerge=1 ;;
    --no-remove-source) FLOW_MR_REMOVE_SOURCE=0 ;;
    --update-mr) update_mr=1 ;;
    --no-mr) no_mr=1 ;;
    --no-force) no_force=1 ;;
    --) shift; while [ $# -gt 0 ]; do projects+=("$1"); shift; done; break ;;
    -*) flow_common_flag "$1" || { flow_err "unknown option: $1"; usage; } ;;
    *) if [ -z "$name" ]; then name="$1"; else projects+=("$1"); fi ;;
  esac
  shift
done

flow_init
[ -n "${FLOW_MR_REMOVE_SOURCE:-}" ] || FLOW_MR_REMOVE_SOURCE=1

if [ -n "$name" ]; then
  branch="${FLOW_FEATURE_PREFIX}${name}"
else
  branch=$(flow_topic_from_cwd) || flow_die "no <name> given and the current directory is not on a branch in a project"
  flow_info "using the branch checked out here: $branch"
fi
if [ -n "$descfile" ]; then
  [ -f "$descfile" ] || flow_die "no such file: $descfile"
  desc=$(cat "$descfile")
fi

flow_load_projects ${projects[@]+"${projects[@]}"}

# ---- work out what would be pushed before touching the network -------------
plan="$FLOW_TMP/plan.tsv"; : > "$plan"
flow_summary_init
while IFS="$(printf '\t')" read -r rpath pname remote rrev; do
  flow_branch_exists "$rpath" "$branch" || { flow_summary_add skip "$rpath" "no $branch"; continue; }
  if flow_is_dirty "$rpath"; then
    flow_summary_add fail "$rpath" "uncommitted changes; commit them first"
    continue
  fi
  trunk=$(flow_trunk_of "$rrev")
  mr_target="${target:-$trunk}"
  if [ -z "$mr_target" ] && [ -z "$no_mr" ]; then
    flow_summary_add fail "$rpath" "manifest pins this project at '$rrev', which is not a branch; pass --target"
    continue
  fi
  if ! base=$(flow_base_ref "$rpath" "$remote" "$trunk"); then
    flow_summary_add fail "$rpath" "cannot resolve a base ref (manifest revision: $rrev)"
    continue
  fi
  read -r ahead behind <<EOF
$(flow_ahead_behind "$rpath" "$base" "$branch" || echo "0 0")
EOF
  if [ "$ahead" = "0" ]; then
    flow_summary_add skip "$rpath" "no commits beyond $base"
    continue
  fi
  printf '%s\t%s\t%s\t%s\t%s\n' "$rpath" "$remote" "$mr_target" "$ahead" "$behind" >> "$plan"
done < "$FLOW_PROJECTS"

if [ ! -s "$plan" ]; then
  flow_no_plan "feature-submit $branch" "nothing to submit"
fi

flow_info "about to push $branch from:"
while IFS="$(printf '\t')" read -r rpath remote mr_target ahead behind; do
  note=
  [ "$behind" != "0" ] && note=" ${FLOW_C_YEL}($behind behind $mr_target - consider feature-sync.sh first)${FLOW_C_OFF}"
  printf '    %s  %s commit(s) -> %s/%s%s\n' "$rpath" "$ahead" "$remote" "$mr_target" "$note" >&2
done < "$plan"
flow_confirm "push these branches${no_mr:+ (no merge requests)} and open merge requests?" \
  || flow_die "aborted"

# ---- push ------------------------------------------------------------------
while IFS="$(printf '\t')" read -r rpath remote mr_target ahead behind; do
  # Default title/description come from the single commit when there is one.
  t="$title"; d="$desc"
  if [ -z "$t" ]; then
    if [ "$ahead" = "1" ]; then
      t=$(git -C "$rpath" log -1 --format=%s "$branch")
    else
      t="$branch"
    fi
  fi
  if [ -z "$d" ] && [ "$ahead" = "1" ]; then
    d=$(git -C "$rpath" log -1 --format=%b "$branch")
  fi

  remote_sha=$(flow_remote_head "$rpath" "$remote" "$branch" || true)
  lease=
  if [ -n "$remote_sha" ]; then
    if git -C "$rpath" cat-file -e "$remote_sha^{commit}" 2>/dev/null \
       && git -C "$rpath" merge-base --is-ancestor "$remote_sha" "$branch" 2>/dev/null; then
      lease=            # fast-forward, no force needed
    elif [ -n "$no_force" ]; then
      flow_summary_add fail "$rpath" "remote $branch is not a fast-forward and --no-force was given"
      continue
    else
      lease="$remote_sha"
    fi
  fi

  flow_mr_reset
  if [ -z "$no_mr" ]; then
    if [ -z "$remote_sha" ] || [ -n "$update_mr" ]; then
      flow_mr_standard "$mr_target" "$t" "$d"
      [ -n "$draft" ] && flow_mr_add "merge_request.draft"
      [ -n "$automerge" ] && flow_mr_add "$FLOW_MR_AUTO_MERGE_OPT"
      for l in ${labels[@]+"${labels[@]}"}; do flow_mr_add "merge_request.label=$l"; done
      for a in ${assignees[@]+"${assignees[@]}"}; do flow_mr_add "merge_request.assign=$a"; done
    fi
  fi

  if url=$(flow_push "$rpath" "$remote" "$branch" "$lease"); then
    if [ -n "$url" ]; then
      flow_summary_add ok "$rpath" "$url"
    elif [ -n "$remote_sha" ]; then
      flow_summary_add ok "$rpath" "updated ${lease:+(forced) }$remote/$branch"
    else
      flow_summary_add ok "$rpath" "pushed $remote/$branch -> $mr_target"
    fi
  else
    flow_summary_add fail "$rpath" "push failed"
  fi
done < "$plan"

flow_summary_print "feature-submit $branch"
