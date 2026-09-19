#!/usr/bin/env bash
# End-to-end exercise of the flow scripts against throw-away git repositories
# and a stand-in `repo` tool. Nothing here touches a real workspace or a real
# GitLab: the "server" is a bare repository whose pre-receive hook records the
# push options it was sent and prints a merge request URL back, the way GitLab
# does.
#
# usage: scripts/tests/smoke.sh [--keep]
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
S=$(dirname "$HERE")
KEEP=
[ "${1:-}" = "--keep" ] && KEEP=1

TMP=$(mktemp -d "${TMPDIR:-/tmp}/flow-smoke.XXXXXX")
cleanup() { [ -n "$KEEP" ] && { echo "workspace kept at $TMP"; return; }; rm -rf "$TMP"; }
trap cleanup EXIT

export PATH="$HERE/fakebin:$PATH"
export NO_COLOR=1
export GIT_AUTHOR_NAME=Smoke GIT_AUTHOR_EMAIL=smoke@example.invalid
export GIT_COMMITTER_NAME=Smoke GIT_COMMITTER_EMAIL=smoke@example.invalid
export FLOW_ASSUME_YES=1

WS="$TMP/ws"
PASS=0; FAIL=0

say()  { printf '\n\033[1m--- %s\033[0m\n' "$*"; }
ok()   { PASS=$((PASS+1)); printf '  \033[32mpass\033[0m %s\n' "$*"; }
bad()  { FAIL=$((FAIL+1)); printf '  \033[31mFAIL\033[0m %s\n' "$*"; }
# expect_ok DESC CMD...   - CMD must succeed
# expect_no DESC CMD...   - CMD must fail
expect_ok() { local d="$1"; shift; if "$@" >/dev/null 2>&1; then ok "$d"; else bad "$d"; fi; }
expect_no() { local d="$1"; shift; if "$@" >/dev/null 2>&1; then bad "$d"; else ok "$d"; fi; }

# ---------------------------------------------------------------- fixture ---
setup() {
  mkdir -p "$TMP/remotes" "$WS/.repo"
  local p
  for p in app lib; do
    git init --quiet --bare "$TMP/remotes/$p.git"
    git -C "$TMP/remotes/$p.git" config receive.advertisePushOptions true
    git -C "$TMP/remotes/$p.git" symbolic-ref HEAD refs/heads/main
    cat > "$TMP/remotes/$p.git/hooks/pre-receive" <<'HOOK'
#!/usr/bin/env bash
out="$GIT_DIR/push-options.log"
: > "$out"
i=0
while [ "$i" -lt "${GIT_PUSH_OPTION_COUNT:-0}" ]; do
  eval "v=\${GIT_PUSH_OPTION_$i}"
  printf '%s\n' "$v" >> "$out"
  i=$((i+1))
done
[ "${GIT_PUSH_OPTION_COUNT:-0}" -gt 0 ] && \
  echo "  https://gitlab.example.invalid/g/p/-/merge_requests/1"
exit 0
HOOK
    chmod +x "$TMP/remotes/$p.git/hooks/pre-receive"

    git init --quiet -b main "$WS/$p"
    echo "$p" > "$WS/$p/README"
    git -C "$WS/$p" add -A
    git -C "$WS/$p" commit --quiet -m "initial $p"
    git -C "$WS/$p" remote add origin "$TMP/remotes/$p.git"
    git -C "$WS/$p" push --quiet -u origin main 2>/dev/null
    git -C "$WS/$p" update-ref refs/remotes/m/main refs/remotes/origin/main
  done
  # path <TAB> name <TAB> remote <TAB> manifest revision
  printf 'app\tgroup/app\torigin\tmain\nlib\tgroup/lib\torigin\trefs/heads/main\n' \
    > "$WS/.repo/projects.tsv"
}

# Commit on the server side, as if another team had landed something.
upstream_commit() {  # project branch message
  local p="$1" b="$2" m="$3" c="$TMP/clone.$$"
  rm -rf "$c"
  git clone --quiet --branch "$b" "$TMP/remotes/$p.git" "$c" 2>/dev/null
  echo "$m" >> "$c/NOTES"
  git -C "$c" add -A
  git -C "$c" commit --quiet -m "$m"
  git -C "$c" push --quiet origin "$b" 2>/dev/null
  git -C "$c" rev-parse HEAD
  rm -rf "$c"
}

opts() { cat "$TMP/remotes/$1.git/push-options.log" 2>/dev/null; }
has_opt() { opts "$1" | grep -qxF "$2"; }
remote_has_branch() { [ -n "$(git -C "$TMP/remotes/$1.git" for-each-ref "refs/heads/$2")" ]; }

setup
cd "$WS" || exit 1

say "status on a fresh workspace"
expect_ok "ws-status.sh runs" "$S/ws-status.sh"

say "feature start"
expect_ok "flow feature start --all" "$S/flow" feature start login --all
expect_ok "app has feature/login" git -C app rev-parse --verify --quiet feature/login
expect_ok "lib has feature/login" git -C lib rev-parse --verify --quiet feature/login

echo "timeout=30" >> app/README
git -C app commit --quiet -am "Raise the login timeout" -m "Slow links were kicked."

say "feature submit"
expect_ok "dry run succeeds" "$S/flow" feature submit login --dry-run
expect_no "dry run pushed nothing" remote_has_branch app feature/login
expect_ok "submit" "$S/flow" feature submit login --label backend
expect_ok "app branch is on the server" remote_has_branch app feature/login
expect_no "lib skipped, it has no commits" remote_has_branch lib feature/login
expect_ok "merge_request.create sent" has_opt app merge_request.create
expect_ok "merge request targets the trunk" has_opt app merge_request.target=main
expect_ok "label sent" has_opt app merge_request.label=backend
expect_ok "title taken from the commit" has_opt app "merge_request.title=Raise the login timeout"

say "trunk moves, feature sync rebases"
upstream_commit app main "Other team work" >/dev/null
expect_ok "feature sync" "$S/flow" feature sync login
expect_ok "feature/login sits on the new trunk" git -C app merge-base --is-ancestor origin/main feature/login

say "re-submit after a rebase"
expect_ok "leased force-push" "$S/flow" feature submit login
expect_ok "server matches the rebased branch" \
  test "$(git -C app rev-parse feature/login)" = "$(git -C "$TMP/remotes/app.git" rev-parse refs/heads/feature/login)"

say "workspace sync"
expect_ok "flow sync" "$S/flow" sync
echo dirt >> lib/README
expect_no "sync refuses to run over uncommitted changes" "$S/flow" sync
git -C lib checkout --quiet -- README

say "feature finish"
git -C app checkout --quiet feature/login
expect_no "refuses to delete before the merge request lands" "$S/flow" feature finish login
c="$TMP/merge"; git clone --quiet "$TMP/remotes/app.git" "$c" 2>/dev/null
git -C "$c" merge --quiet --no-ff --no-edit -m "Merge feature/login" origin/feature/login
git -C "$c" push --quiet origin main 2>/dev/null; rm -rf "$c"
expect_ok "finish after the merge lands" "$S/flow" feature finish login
expect_no "local branch deleted" git -C app rev-parse --verify --quiet feature/login
expect_no "server branch deleted" remote_has_branch app feature/login

say "cleanup"
"$S/flow" feature start stale --all >/dev/null 2>&1
echo extra >> app/README
git -C app commit --quiet -am "work that never landed"
expect_ok "cleanup --list" "$S/flow" cleanup --list
expect_ok "cleanup" "$S/flow" cleanup
expect_no "branch that landed is deleted" git -C lib rev-parse --verify --quiet feature/stale
expect_ok "branch with unlanded commits is kept" git -C app rev-parse --verify --quiet feature/stale

printf '\n%s passed, %s failed\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
