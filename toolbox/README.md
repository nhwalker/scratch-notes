# opencode-toolbox

A [Toolbx](https://containertoolbx.org/) image based on `registry.access.redhat.com/ubi9/toolbox`
with OpenCode and Plannotator installed **system-wide** (nothing under any `$HOME`).

| Component | Source | Location in image |
|---|---|---|
| Node.js + npm | UBI AppStream `nodejs:24` module | `/usr/bin` |
| OpenCode | `npm install -g opencode-ai` | `/usr/local/bin/opencode` |
| Plannotator OpenCode plugin | `npm install -g --ignore-scripts @plannotator/opencode` | `/usr/local/lib/node_modules/@plannotator/opencode` |
| Plannotator CLI | GitHub release asset, sha256 pinned in the Containerfile | `/usr/local/bin/plannotator` |
| OpenCode system config | `etc-opencode/opencode.json` | `/etc/opencode/opencode.json` |
| `/plannotator-*` commands, `plannotator` skill | copied from the plugin package | `/etc/opencode/commands`, `/etc/opencode/skills` |

## Build, test, use

```sh
podman build -t opencode-toolbox -f Containerfile .
./test.sh localhost/opencode-toolbox:latest
toolbox create --image localhost/opencode-toolbox:latest opencode
toolbox enter opencode
```

`test.sh` runs the image as an unprivileged user with networking off and checks that the
plugin, commands and skill load from the system locations and that nothing is fetched at runtime.

## How OpenCode finds the system config

- `/etc/opencode/opencode.json` is OpenCode's **managed** config on Linux: always loaded, highest
  precedence. It enables the plugin by `file://` URL so OpenCode does not download it at startup.
- `OPENCODE_CONFIG_DIR=/etc/opencode` (image `ENV` and `/etc/profile.d/opencode.sh`) makes OpenCode
  also load `commands/` and `skills/` from there.
- `autoupdate` is off: versions are pinned in the image and `/usr/local` is not writable by users.

## Why not Plannotator's `install.sh`

The image does the OpenCode-relevant parts of `scripts/install.sh` explicitly:

1. Download `plannotator-linux-{x64,arm64}` from the GitHub release and verify its sha256.
2. Put the `/plannotator-*` command stubs where OpenCode loads commands.
3. Point OpenCode's `plugin` config at `@plannotator/opencode`.

The plugin's npm `postinstall` (which copies the same command stubs and skill into
`~/.config/opencode`) is skipped with `--ignore-scripts`; the Containerfile copies those files to
`/etc/opencode` instead.

Not done (they are for other agents or optional extras): Claude Code / Codex / Gemini / Kiro / Pi /
Qwen integrations, the `sem` semantic-diff sidecar, `plannotator install-runtime ...`, and GitHub
build-provenance attestation checks.

## Updating versions

Change the `ARG`s at the top of the Containerfile. For Plannotator, update the two
`PLANNOTATOR_SHA256_*` values from the release's `plannotator-linux-*.sha256` files.
