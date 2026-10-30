# opencode-toolbox

A [Toolbx](https://containertoolbx.org/) image based on `registry.access.redhat.com/ubi9/toolbox`
with OpenCode 2 and Plannotator installed **system-wide** (nothing under any `$HOME`).
x86_64 only.

| Component | Source | Location in image |
|---|---|---|
| Node.js + npm | UBI AppStream `nodejs:24` module | `/usr/bin` |
| OpenCode 2 | `npm install -g @opencode/cli` | `/usr/local/bin/opencode` (and `opencode2`) |
| Plannotator OpenCode plugin | `npm install -g --ignore-scripts @plannotator/opencode` | `/usr/local/lib/node_modules/@plannotator/opencode` |
| Plannotator CLI | GitHub release asset `plannotator-linux-x64` | `/usr/local/bin/plannotator` |
| OpenCode system config | `etc-opencode/opencode.json` | `/etc/opencode/opencode.json` |
| `plannotator` skill | copied from the plugin package | `/etc/opencode/skills/plannotator` |

## Build, test, use

```sh
podman build -t opencode-toolbox -f Containerfile .
./test.sh localhost/opencode-toolbox:latest
toolbox create --image localhost/opencode-toolbox:latest opencode
toolbox enter opencode
```

`test.sh` runs the image as an unprivileged user with networking off, starts OpenCode's background
server, and checks that the plugin is active, the four `/plannotator-*` commands are registered, the
skill loads from `/etc/opencode`, and nothing was fetched at runtime.

## How OpenCode 2 finds the system config

- OpenCode 2 reads `/etc/opencode` for every user: `opencode.json` there has the highest
  precedence (users and projects can't override it), and its `commands/`, `skills/`, `agents/`
  and `plugins/` folders are loaded too. No environment variable is needed.
- `opencode.json` enables the plugin by `file://` path, so OpenCode loads the copy in
  `/usr/local/lib/node_modules` instead of downloading it at startup.
- `"update": "disable"`: versions are pinned in the image and `/usr/local` is not writable by users.
- `"websearch": false` and a `policies` rule denying `webfetch:*`: the model gets no web search or
  web fetch tools. Policies in `/etc/opencode` can't be undone by user or project config.
- The plugin registers `/plannotator-review`, `-annotate`, `-last` and `-inbox` itself on
  OpenCode 2, so the command markdown files are not copied. The `plannotator` skill is not
  provided by the plugin, so it is copied.
- OpenCode 2 runs one background server per user (`opencode service status|start|stop|restart`);
  its state lives in that user's `~/.local/share/opencode` and `~/.local/state/opencode`.

## Lockdown environment variables

Set as image `ENV` and in `/etc/profile.d/opencode.sh`. These are read from OpenCode's code
(they aren't in the V2 docs) and can be unset by a user in their own shell.

| Variable | Effect |
|---|---|
| `OPENCODE_DISABLE_MODELS_FETCH=1` | no model-catalog fetch from models.opencode.ai; the catalog built into OpenCode is used |
| `OPENCODE_DISABLE_AUTOUPDATE=1` | no update check |
| `OPENCODE_DISABLE_FREE_MODELS=1` | hides OpenCode's free cloud models (`opencode/*`) |

## Why not Plannotator's `install.sh`

The image does the OpenCode-relevant parts of `scripts/install.sh` explicitly:

1. Download `plannotator-linux-x64` from the GitHub release.
2. Install the `plannotator` skill where OpenCode loads skills.
3. Point OpenCode's `plugins` config at `@plannotator/opencode`.

The plugin's npm `postinstall` (which copies command stubs and the skill into
`~/.config/opencode`) is skipped with `--ignore-scripts`; the Containerfile copies the skill to
`/etc/opencode` instead. The command stubs are not needed on OpenCode 2 (see above).

Not done (they are for other agents or optional extras): Claude Code / Codex / Gemini / Kiro / Pi /
Qwen integrations, the `sem` semantic-diff sidecar, `plannotator install-runtime ...`, and the
checksum / GitHub build-provenance checks on the downloaded binary.

## Updating versions

Change the `ARG`s at the top of the Containerfile.
