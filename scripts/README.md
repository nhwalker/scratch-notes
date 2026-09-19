# Git flow scripts for a `repo` workspace

Bash scripts for running a trunk-based branching model across a multi-repository
workspace managed by [Google's `repo` tool](https://gerrit.googlesource.com/git-repo),
with code review on GitLab merge requests.

`repo` gives you one workspace made of many independent git repositories. Git
flow is defined for one repository. These scripts bridge the two: one command
does the same branch operation in every project that needs it, skips the ones
that do not, and reports per project what happened.

## The model

```
main (trunk, per project, whatever the manifest pins)
 |
 |-- feature/<name>    short-lived, one merge request per project, rebased
 |
 '-- release/<version> cut from the trunk, stabilised, tagged, back-merged
        ^
        '-- backport/<version>  fixes that landed on the trunk first
```

* The **trunk** is whatever revision the manifest pins for each project. It is
  read per project, so a workspace where projects sit on different branches
  works without any extra configuration.
* **Feature branches** are short-lived and rebased onto the trunk, never merged
  from it. One branch name spans the workspace; only the projects you actually
  changed get pushed and reviewed.
* **Release branches** are cut from the trunk when a version needs to stabilise
  while the trunk keeps moving. Fixes land on the trunk first and are
  back-ported; at the end the release branch is tagged and back-merged so
  nothing is lost.

## Requirements

`bash` (3.2 and newer, so the bash macOS ships with is fine), `git`, the `repo`
tool, and coreutils. No Python, no `glab`, no API token.

## Commands

Run any of them from anywhere inside the workspace. `flow` is a dispatcher; the
individual scripts work standalone too.

| Command | What it does |
| --- | --- |
| `flow status` | branch, distance from the trunk, dirty state, per project |
| `flow sync` | `repo sync` that refuses to run over uncommitted changes, then reports what fell behind |
| `flow feature start <name> --all` | create `feature/<name>` at each project's trunk |
| `flow feature sync <name>` | sync, then rebase the branch onto each trunk |
| `flow feature submit <name>` | push and open a merge request per project with commits |
| `flow feature finish <name>` | once merged: delete the branch locally and on the server |
| `flow release start <version> --all` | cut and publish `release/<version>` |
| `flow release tag <version>` | annotated `v<version>` on each release branch, pushed |
| `flow backport <version> <commit>...` | cherry-pick trunk commits onto the release branch, one merge request per project |
| `flow release backmerge <version>` | merge requests carrying the release branch back to the trunk |
| `flow release finish <version>` | tag, then back-merge |
| `flow cleanup` | delete branches that have already landed on the trunk |

Every command takes `-h`, `--dry-run` and `--yes`. Anything that pushes asks
first unless you pass `--yes`, and refuses to ask when it is not on a terminal.

### A feature, start to finish

```sh
flow feature start login-timeout --all      # or name the projects you expect to touch
# ... work, commit in whichever projects you changed ...
flow feature sync login-timeout             # repo sync + rebase onto the trunk
flow feature submit login-timeout           # push, one merge request per project
# ... review happens, merge requests get merged ...
flow feature finish login-timeout           # delete the branch everywhere
```

Re-running `submit` after a rebase re-pushes with a lease, so the branch is
updated without the risk of clobbering someone else's push. The merge request
creation options are sent only on the first push; pass `--update-mr` to re-send
the title, labels and assignees afterwards.

### A release, start to finish

```sh
flow release start 24.10 --all              # cut and publish release/24.10
# ... a fix lands on the trunk and is needed in the release ...
flow backport 24.10 a1b2c3d                 # cherry-pick it, merge request against release/24.10
flow release finish 24.10                   # tag v24.10, then back-merge to the trunk
```

## Configuration

Optional, in `<workspace>/.flowrc` (or wherever `FLOW_CONFIG` points). It is
sourced as shell, so it is plain `NAME=value` lines. See `flowrc.example`.

| Variable | Default | Meaning |
| --- | --- | --- |
| `FLOW_FEATURE_PREFIX` | `feature/` | feature branch prefix |
| `FLOW_RELEASE_PREFIX` | `release/` | release branch prefix |
| `FLOW_BACKPORT_PREFIX` | `backport/` | back-port branch prefix |
| `FLOW_BACKMERGE_PREFIX` | `backmerge/` | back-merge branch prefix |
| `FLOW_TAG_PREFIX` | `v` | release tag prefix |
| `FLOW_JOBS` | `4` | `repo sync` parallelism |
| `FLOW_SYNC_ARGS` | `--no-tags` | extra `repo sync` arguments |
| `FLOW_MR_REMOVE_SOURCE` | `1` | ask GitLab to delete the source branch on merge |
| `FLOW_MR_AUTO_MERGE_OPT` | `merge_request.merge_when_pipeline_succeeds` | push option behind `--auto-merge` |

## How the merge requests are created

Through [GitLab push options](https://docs.gitlab.com/topics/git/commit/#push-options),
carried on the same `git push` you were doing anyway:

```
git push -o merge_request.create -o merge_request.target=main \
         -o merge_request.title='...' origin feature/x
```

That means no token to provision and no extra CLI to install, and it works with
whatever ssh or https credentials you already push with. Two consequences worth
knowing:

* **Your GitLab must advertise push options.** Self-managed instances need
  `receive.advertisePushOptions` on, which is the default in current versions.
* **A push option cannot contain a newline**, so merge request descriptions are
  folded onto one line and capped. Long descriptions belong in the merge request
  once it is open.
* GitLab 17.11 renamed `merge_request.merge_when_pipeline_succeeds` to
  `merge_request.auto_merge`. `--auto-merge` sends the old name; set
  `FLOW_MR_AUTO_MERGE_OPT` if your server wants the new one.

## What these scripts deliberately do not do

* **They never touch the manifest.** Cutting a release branch does not repoint
  the manifest at it; that is a reviewed change to the manifest repository, made
  when you actually want the workspace to track the release. `release-start.sh`
  reminds you.
* **No Gerrit.** Nothing calls `repo upload`; review is GitLab merge requests.
* **No merging or approving.** The scripts get changes to review and get
  branches tidied up afterwards. A human merges.

## Known limitations

* **Squash merges are not detected.** `feature finish` and `cleanup` consider a
  branch landed when it is an ancestor of the trunk, or when every commit on it
  is upstream by patch id. A squash merge rewrites the commits, so neither test
  sees it and the branch is reported as unmerged; use `--force` once you have
  checked the merge request.
* **Projects pinned to a sha or tag are skipped** for anything that needs a
  branch to target, and say so, since there is no trunk to open a merge request
  against.
* A conflicted rebase is aborted by default rather than left across N projects.
  `feature sync --keep-conflicts` leaves it in place for the projects that need
  hand resolution.
* `cleanup` and `feature finish` leave a project with a detached HEAD if the
  branch they deleted was the one checked out, the same state `repo sync -d`
  leaves behind. `repo start` or `flow feature start` puts you back on a branch.

## Testing

`scripts/tests/smoke.sh` builds a throw-away workspace of two projects with
local bare "servers", stands in for the `repo` tool with
`scripts/tests/fakebin/repo`, and runs the whole lifecycle against it: feature
start through finish, release cut, tag, back-port, back-merge and cleanup. The
bare repositories record the push options they receive, so the merge request
plumbing is checked rather than assumed.

```sh
scripts/tests/smoke.sh          # --keep to leave the workspace behind for poking at
```

It touches nothing outside its own temporary directory and needs no network.
