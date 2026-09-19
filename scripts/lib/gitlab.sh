#!/usr/bin/env bash
# gitlab.sh - push helpers that drive GitLab merge requests through git push
# options. No GitLab API token and no glab CLI needed: everything travels over
# the same ssh/https push the developer already has.
#
# Reference: GitLab "Push options" documentation. Note that
# merge_request.merge_when_pipeline_succeeds was renamed to
# merge_request.auto_merge in GitLab 17.11; set FLOW_MR_AUTO_MERGE_OPT in
# .flowrc to pick the one your server understands.

[ -n "${FLOW_GITLAB_SOURCED:-}" ] && return 0
FLOW_GITLAB_SOURCED=1

FLOW_MR_OPTS=()

flow_mr_reset() { FLOW_MR_OPTS=(); }

# flow_mr_add OPTION
# git refuses to send a push option containing a newline, so every value is
# folded onto one line here and capped, well short of any command line limit.
# A long description belongs in the merge request itself once it is open.
flow_mr_add() {
  local v
  v=$(printf '%s' "$1" | tr '\n\r\t' '   ' | sed -e 's/  */ /g' -e 's/^ //' -e 's/ $//')
  [ ${#v} -gt 1000 ] && v="${v:0:997}..."
  FLOW_MR_OPTS+=("$v")
}

# flow_mr_standard TARGET TITLE DESCRIPTION
# Fills FLOW_MR_OPTS with the options shared by every script here. Callers add
# labels, assignees, draft and auto-merge on top.
flow_mr_standard() {
  local target="$1" title="$2" desc="$3"
  flow_mr_add "merge_request.create"
  [ -n "$target" ] && flow_mr_add "merge_request.target=$target"
  [ -n "$title" ]  && flow_mr_add "merge_request.title=$title"
  [ -n "$desc" ]   && flow_mr_add "merge_request.description=$desc"
  [ "${FLOW_MR_REMOVE_SOURCE:-1}" = "1" ] && flow_mr_add "merge_request.remove_source_branch"
  return 0
}

# flow_push PATH REMOTE BRANCH [LEASE_SHA]
# Pushes BRANCH with whatever is currently in FLOW_MR_OPTS. When LEASE_SHA is
# given the push is forced with an explicit lease: it succeeds only if the
# server branch is still exactly LEASE_SHA, so a rebased branch can be
# republished without ever clobbering someone else's push.
# Prints any merge request URL GitLab reports on stdout; push output is shown
# only when the push fails.
flow_push() {
  local p="$1" remote="$2" branch="$3" lease="${4:-}"
  local out="$FLOW_TMP/push.$$.log" rc=0 url i
  local -a cmd
  cmd=(git -C "$p" push --set-upstream)
  [ -n "$lease" ] && cmd+=("--force-with-lease=refs/heads/$branch:$lease")
  for i in "${FLOW_MR_OPTS[@]:-}"; do
    [ -n "$i" ] && cmd+=(-o "$i")
  done
  cmd+=("$remote" "refs/heads/$branch:refs/heads/$branch")

  if [ -n "${FLOW_DRY_RUN:-}" ]; then
    printf '%s[dry-run]%s %s\n' "$FLOW_C_DIM" "$FLOW_C_OFF" "$(flow_quote "${cmd[@]}")" >&2
    return 0
  fi

  flow_debug "push: $(flow_quote "${cmd[@]}")"
  "${cmd[@]}" > "$out" 2>&1 || rc=$?
  if [ "$rc" -ne 0 ]; then
    sed 's/^/    /' "$out" >&2
    rm -f "$out"
    return "$rc"
  fi
  url=$(grep -Eo 'https?://[^[:space:]]+merge_requests[^[:space:]]*' "$out" | tail -n 1)
  rm -f "$out"
  [ -n "$url" ] && printf '%s\n' "$url"
  return 0
}

# flow_remote_head PATH REMOTE BRANCH -> sha of BRANCH on the server, empty if
# the branch does not exist there. One network round trip per project.
flow_remote_head() {
  git -C "$1" ls-remote --heads "$2" "refs/heads/$3" 2>/dev/null | awk 'NR==1{print $1}'
}
