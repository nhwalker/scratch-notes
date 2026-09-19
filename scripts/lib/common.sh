#!/usr/bin/env bash
# common.sh - shared helpers for the git-flow-over-repo scripts.
#
# This file is sourced, never executed. It targets bash 3.2 so it also works
# with the bash that ships on macOS: no associative arrays, no mapfile, no
# ${var,,}. Only git, repo and coreutils are required.

[ -n "${FLOW_COMMON_SOURCED:-}" ] && return 0
FLOW_COMMON_SOURCED=1

# ---------------------------------------------------------------- output ---

if [ -t 2 ] && [ -z "${NO_COLOR:-}" ]; then
  FLOW_C_RED=$'\033[31m'; FLOW_C_GRN=$'\033[32m'; FLOW_C_YEL=$'\033[33m'
  FLOW_C_BLU=$'\033[34m'; FLOW_C_DIM=$'\033[2m';  FLOW_C_OFF=$'\033[0m'
else
  FLOW_C_RED=; FLOW_C_GRN=; FLOW_C_YEL=; FLOW_C_BLU=; FLOW_C_DIM=; FLOW_C_OFF=
fi

flow_info()  { printf '%s::%s %s\n' "$FLOW_C_BLU" "$FLOW_C_OFF" "$*" >&2; }
flow_ok()    { printf '%s ok%s %s\n' "$FLOW_C_GRN" "$FLOW_C_OFF" "$*" >&2; }
flow_warn()  { printf '%swarn%s %s\n' "$FLOW_C_YEL" "$FLOW_C_OFF" "$*" >&2; }
flow_err()   { printf '%serr %s %s\n' "$FLOW_C_RED" "$FLOW_C_OFF" "$*" >&2; }
flow_die()   { flow_err "$@"; exit 1; }
flow_debug() { [ -n "${FLOW_DEBUG:-}" ] && printf '%sdbg %s %s\n' "$FLOW_C_DIM" "$FLOW_C_OFF" "$*" >&2; return 0; }

# ------------------------------------------------------------ run / shell ---

# flow_quote ARGS...  -> shell-quoted single line
flow_quote() {
  local a out=
  for a in "$@"; do out="$out $(printf '%q' "$a")"; done
  printf '%s' "${out# }"
}

# flow_run CMD...  -> runs CMD unless FLOW_DRY_RUN is set
flow_run() {
  if [ -n "${FLOW_DRY_RUN:-}" ]; then
    printf '%s[dry-run]%s %s\n' "$FLOW_C_DIM" "$FLOW_C_OFF" "$(flow_quote "$@")" >&2
    return 0
  fi
  flow_debug "run: $(flow_quote "$@")"
  "$@"
}

# flow_confirm PROMPT -> 0 to proceed. Honours FLOW_ASSUME_YES / FLOW_DRY_RUN.
flow_confirm() {
  local reply
  [ -n "${FLOW_ASSUME_YES:-}" ] && return 0
  [ -n "${FLOW_DRY_RUN:-}" ] && return 0
  if [ ! -t 0 ]; then
    flow_err "$1"
    flow_die "not running on a terminal; re-run with --yes to confirm non-interactively"
  fi
  printf '%s [y/N] ' "$1" >&2
  read -r reply || reply=
  case "$reply" in y|Y|yes|YES) return 0 ;; *) return 1 ;; esac
}

# ------------------------------------------------------------- workspace ---

# Walk up from $FLOW_WORKSPACE or $PWD looking for a .repo directory.
flow_find_workspace() {
  local d
  d=$(cd "${FLOW_WORKSPACE:-$PWD}" 2>/dev/null && pwd -P) || return 1
  while :; do
    [ -d "$d/.repo" ] && { printf '%s\n' "$d"; return 0; }
    [ "$d" = "/" ] && return 1
    d=$(dirname "$d")
  done
}

flow_load_config() {
  local f="${FLOW_CONFIG:-$FLOW_WS/.flowrc}"
  if [ -f "$f" ]; then
    flow_debug "loading config $f"
    # shellcheck disable=SC1090
    . "$f"
  fi
  FLOW_FEATURE_PREFIX="${FLOW_FEATURE_PREFIX:-feature/}"
  FLOW_RELEASE_PREFIX="${FLOW_RELEASE_PREFIX:-release/}"
  FLOW_BACKPORT_PREFIX="${FLOW_BACKPORT_PREFIX:-backport/}"
  FLOW_BACKMERGE_PREFIX="${FLOW_BACKMERGE_PREFIX:-backmerge/}"
  FLOW_TAG_PREFIX="${FLOW_TAG_PREFIX:-v}"
  FLOW_JOBS="${FLOW_JOBS:-4}"
  FLOW_SYNC_ARGS="${FLOW_SYNC_ARGS:---no-tags}"
  FLOW_MR_REMOVE_SOURCE="${FLOW_MR_REMOVE_SOURCE:-1}"
  # GitLab renamed this push option in 17.11; override for newer servers:
  #   FLOW_MR_AUTO_MERGE_OPT=merge_request.auto_merge
  FLOW_MR_AUTO_MERGE_OPT="${FLOW_MR_AUTO_MERGE_OPT:-merge_request.merge_when_pipeline_succeeds}"
}

flow_cleanup_tmp() { [ -n "${FLOW_TMP:-}" ] && rm -rf "$FLOW_TMP"; }

# flow_init - locate the workspace, load config, make a scratch dir.
flow_init() {
  command -v git >/dev/null 2>&1 || flow_die "git not found on PATH"
  command -v repo >/dev/null 2>&1 || flow_die "the 'repo' tool is not on PATH (https://gerrit.googlesource.com/git-repo)"
  FLOW_WS=$(flow_find_workspace) || flow_die "no .repo directory found above ${FLOW_WORKSPACE:-$PWD}; run inside a repo workspace or set FLOW_WORKSPACE"
  FLOW_START_DIR=$PWD
  cd "$FLOW_WS" || flow_die "cannot cd to $FLOW_WS"
  FLOW_TMP=$(mktemp -d "${TMPDIR:-/tmp}/repoflow.XXXXXX") || flow_die "mktemp failed"
  trap flow_cleanup_tmp EXIT
  flow_load_config
  flow_debug "workspace=$FLOW_WS tmp=$FLOW_TMP"
}

# ---------------------------------------------------------------- projects ---

# flow_load_projects [PROJECT...] - build $FLOW_PROJECTS, a TSV of
#   path <TAB> name <TAB> remote <TAB> manifest-revision
# Project arguments are passed straight through to `repo forall`.
flow_load_projects() {
  FLOW_PROJECTS="$FLOW_TMP/projects.tsv"
  # shellcheck disable=SC2016  # expanded by the shell repo forall starts, not here
  local fmt='printf "%s\t%s\t%s\t%s\n" "$REPO_PATH" "$REPO_PROJECT" "$REPO_REMOTE" "$REPO_RREV"'
  flow_debug "repo forall $* -c <metadata>"
  if ! (cd "$FLOW_WS" && repo forall "$@" -c "$fmt") > "$FLOW_PROJECTS" 2>"$FLOW_TMP/forall.err"; then
    sed 's/^/    /' "$FLOW_TMP/forall.err" >&2
    flow_die "could not enumerate projects with 'repo forall'"
  fi
  [ -s "$FLOW_PROJECTS" ] || flow_die "no projects matched${*:+ for: $*}"
  FLOW_PROJECT_COUNT=$(wc -l < "$FLOW_PROJECTS" | tr -d ' ')
  flow_debug "projects=$FLOW_PROJECT_COUNT"
}

# flow_trunk_of REV - manifest revision -> branch name, or empty when the
# project is pinned to a sha or a tag (no branch to target).
flow_trunk_of() {
  local rev="$1"
  case "$rev" in
    refs/heads/*) printf '%s\n' "${rev#refs/heads/}"; return 0 ;;
    refs/*)       return 0 ;;   # tag or other non-branch ref
  esac
  # A bare object id means the project is pinned; there is no branch to target.
  if printf '%s' "$rev" | grep -Eq '^[0-9a-fA-F]{40}([0-9a-fA-F]{24})?$'; then
    return 0
  fi
  printf '%s\n' "$rev"
}

flow_git() { local p="$1"; shift; git -C "$p" "$@"; }

flow_branch_exists() {
  git -C "$1" show-ref --verify --quiet "refs/heads/$2"
}

flow_remote_branch_exists() {
  git -C "$1" show-ref --verify --quiet "refs/remotes/$2/$3"
}

# flow_m_ref PATH - the manifest tracking ref repo maintains (e.g. m/main).
flow_m_ref() {
  git -C "$1" for-each-ref --count=1 --format='%(refname:short)' 'refs/remotes/m/' 2>/dev/null
}

# flow_base_ref PATH REMOTE TRUNK - best ref to rebase/compare against.
# Prefers the fetched remote branch, falls back to repo's m/ ref.
flow_base_ref() {
  local p="$1" remote="$2" trunk="$3" m
  if [ -n "$trunk" ] && flow_remote_branch_exists "$p" "$remote" "$trunk"; then
    printf '%s/%s\n' "$remote" "$trunk"; return 0
  fi
  m=$(flow_m_ref "$p")
  [ -n "$m" ] && { printf '%s\n' "$m"; return 0; }
  return 1
}

flow_is_dirty() {
  [ -n "$(git -C "$1" status --porcelain --untracked-files=no 2>/dev/null)" ]
}

# flow_ahead_behind PATH BASE BRANCH -> "<ahead> <behind>"
flow_ahead_behind() {
  local out
  out=$(git -C "$1" rev-list --left-right --count "$2...$3" 2>/dev/null) || return 1
  # left = behind (in base only), right = ahead (in branch only)
  # shellcheck disable=SC2086  # two numbers, split on purpose
  set -- $out
  printf '%s %s\n' "${2:-0}" "${1:-0}"
}

flow_current_branch() {
  git -C "$1" symbolic-ref --quiet --short HEAD 2>/dev/null
}

# Topic branch of the project containing the current directory, if any.
flow_topic_from_cwd() {
  local top
  top=$(cd "$FLOW_START_DIR" 2>/dev/null && git rev-parse --show-toplevel 2>/dev/null) || return 1
  [ -n "$top" ] || return 1
  flow_current_branch "$top"
}

# ----------------------------------------------------------------- summary ---

flow_summary_init() { FLOW_SUMMARY="$FLOW_TMP/summary.tsv"; : > "$FLOW_SUMMARY"; }

# flow_summary_add STATUS PROJECT MESSAGE   (STATUS: ok|skip|fail)
flow_summary_add() {
  printf '%s\t%s\t%s\n' "$1" "$2" "$3" >> "$FLOW_SUMMARY"
}

# flow_summary_print [TITLE] - prints the table, returns 1 if anything failed.
flow_summary_print() {
  local status project message color width=0 nok=0 nskip=0 nfail=0
  [ -s "$FLOW_SUMMARY" ] || { flow_warn "nothing to report"; return 0; }
  while IFS="$(printf '\t')" read -r status project message; do
    [ ${#project} -gt "$width" ] && width=${#project}
  done < "$FLOW_SUMMARY"
  printf '\n%s%s%s\n' "$FLOW_C_DIM" "${1:-summary}" "$FLOW_C_OFF" >&2
  while IFS="$(printf '\t')" read -r status project message; do
    case "$status" in
      ok)   color=$FLOW_C_GRN; nok=$((nok+1)) ;;
      skip) color=$FLOW_C_DIM; nskip=$((nskip+1)) ;;
      *)    color=$FLOW_C_RED; nfail=$((nfail+1)) ;;
    esac
    printf '  %s%-4s%s %-*s  %s\n' "$color" "$status" "$FLOW_C_OFF" "$width" "$project" "$message" >&2
  done < "$FLOW_SUMMARY"
  printf '\n  %d ok, %d skipped, %d failed\n\n' "$nok" "$nskip" "$nfail" >&2
  [ "$nfail" -eq 0 ]
}

# flow_no_plan TITLE MESSAGE - nothing ended up on the work list. Prints the
# summary and exits 0 when that is simply because there was nothing to do, or
# non-zero when projects failed their checks.
flow_no_plan() {
  local rc=0
  flow_summary_print "$1" || rc=$?
  if [ "$rc" -eq 0 ]; then
    flow_info "$2"
    exit 0
  fi
  flow_err "$2"
  exit "$rc"
}

# ------------------------------------------------------------ arg helpers ---

# flow_common_flag ARG - handle flags every script accepts. Returns 0 if
# consumed. Callers handle everything else themselves.
flow_common_flag() {
  case "$1" in
    -n|--dry-run) FLOW_DRY_RUN=1 ;;
    -y|--yes)     FLOW_ASSUME_YES=1 ;;
    -v|--verbose|--debug) FLOW_DEBUG=1 ;;
    *) return 1 ;;
  esac
  return 0
}

flow_validate_name() {
  # shellcheck disable=SC1003  # '\' is a literal backslash in a case pattern
  case "$1" in
    ''|-*|*' '*|*'..'*|*'~'*|*'^'*|*':'*|*'?'*|*'*'*|*'['*|*'\'*)
      flow_die "invalid branch component: '$1'" ;;
  esac
  git check-ref-format --allow-onelevel "$1" >/dev/null 2>&1 \
    || flow_die "invalid branch component: '$1'"
}
