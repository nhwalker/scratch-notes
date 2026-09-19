#!/usr/bin/env bash
# Close out a release: tag it, then back-merge it into the trunk.
set -euo pipefail
FLOW_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
. "$FLOW_DIR/lib/common.sh"

usage() {
  cat >&2 <<'USAGE'
usage: release-finish.sh [options] <version> [<project>...]

Runs the two steps that close a release, in order:

  1. release-tag.sh <version>        annotated <tag-prefix><version>, pushed
  2. release-backmerge.sh <version>  merge requests carrying the release branch
                                     back to each trunk

The release branch itself is kept. Release branches are how you service a
shipped version; delete them when the version is out of support, with
cleanup.sh --pattern.

options:
  --no-tag          skip the tagging step
  --no-backmerge    skip the back-merge step
  --message <text>  tag message
  --sign            sign the tag
  --label <name>    merge request label (repeatable, back-merge step)
  --assign <user>   assign a user (repeatable, back-merge step)
  -y, --yes         do not ask before pushing
  -n, --dry-run     print what would run
  -v, --debug       verbose
  -h, --help        this text
USAGE
  exit "${1:-2}"
}

version=; do_tag=1; do_bm=1
tagargs=(); bmargs=(); projects=()
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) usage 0 ;;
    --no-tag) do_tag= ;;
    --no-backmerge) do_bm= ;;
    --message|-m) [ $# -ge 2 ] || usage; tagargs+=(--message "$2"); shift ;;
    --sign) tagargs+=(--sign) ;;
    --label) [ $# -ge 2 ] || usage; bmargs+=(--label "$2"); shift ;;
    --assign) [ $# -ge 2 ] || usage; bmargs+=(--assign "$2"); shift ;;
    -y|--yes) FLOW_ASSUME_YES=1; tagargs+=(--yes); bmargs+=(--yes) ;;
    -n|--dry-run) FLOW_DRY_RUN=1; tagargs+=(--dry-run); bmargs+=(--dry-run) ;;
    -v|--verbose|--debug) FLOW_DEBUG=1; tagargs+=(--debug); bmargs+=(--debug) ;;
    --) shift; while [ $# -gt 0 ]; do projects+=("$1"); shift; done; break ;;
    -*) flow_err "unknown option: $1"; usage ;;
    *) if [ -z "$version" ]; then version="$1"; else projects+=("$1"); fi ;;
  esac
  shift
done

[ -n "$version" ] || usage
export FLOW_ASSUME_YES FLOW_DRY_RUN FLOW_DEBUG

rc=0
if [ -n "$do_tag" ]; then
  flow_info "step 1/2: tagging"
  "$FLOW_DIR/release-tag.sh" ${tagargs[@]+"${tagargs[@]}"} "$version" ${projects[@]+"${projects[@]}"} || rc=$?
  [ "$rc" -eq 0 ] || flow_die "tagging failed; fix the projects above before back-merging"
fi
if [ -n "$do_bm" ]; then
  flow_info "step 2/2: back-merging into the trunk"
  "$FLOW_DIR/release-backmerge.sh" ${bmargs[@]+"${bmargs[@]}"} "$version" ${projects[@]+"${projects[@]}"} || rc=$?
fi
exit "$rc"
