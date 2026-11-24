# x11-window-pinner (`pinwin`)

A small daemon that keeps selected X11 windows at a fixed geometry, **always at
the bottom of the stacking order**, optionally hidden or locked against user
move/resize. Built and tested against **mwm 2.3.8** (Motif Window Manager).

The only dependency is `python-xlib` (pure Python). The design deliberately
avoids anything that would be hard to port to the planned C / Weston
implementation (see [Porting notes](#porting-notes-c--weston)).

## Usage

```sh
pip install python-xlib

python3 -m pinwin --check config.json      # validate config
python3 -m pinwin --list                   # show windows: title, instance, class, geometry
python3 -m pinwin -v config.json           # run (-v info log, -vv debug)
kill -HUP <pid>                            # reload config
kill -TERM <pid>                           # stop (re-shows windows it hid)
```

Run from this directory, or put it on `PYTHONPATH`.

## Config

A JSON list of rules ([example](example-config.json)):

```json
[
  { "match": {"title": "Status Board"}, "geometry": [0, 0, 1280, 720], "lock": true },
  { "match": {"class": "XTerm", "title": "log: *"}, "geometry": [0, 720, 640, 280] },
  { "match": {"instance": "scratch*"}, "visible": false }
]
```

| Key | Required | Meaning |
|---|---|---|
| `match` | yes | Any of `title`, `instance`, `class` (glob patterns). **All given fields must match.** `instance`/`class` are the two halves of `WM_CLASS` (`xprop WM_CLASS`, or `--list`). |
| `geometry` | when visible | `[x, y, width, height]`. `x, y` = top-left of the **mwm frame** (outer corner); `width, height` = the **client area**. Same convention as the X `-geometry` option. |
| `visible` | no (default `true`) | `false` = fully withdrawn: unmapped, no icon. |
| `lock` | no (default `false`) | Remove mwm's move, resize, minimize and maximize functions for the window. |

Rules:

* A rule applies to **every** window it matches.
* A window matching several rules uses the **first** one in the file.
* All visible pinned windows sit below all other managed windows; among
  themselves, the earlier rule is lower.
* Title is read from `_NET_WM_NAME` (UTF-8), falling back to `WM_NAME`.
  Title changes are noticed and re-matched.

### Glob syntax

| Pattern | Matches |
|---|---|
| `*` | any sequence, including empty |
| `?` | exactly one character (Unicode code point) |
| `**` | a literal `*` |
| `??` | a literal `?` |
| anything else | itself, case-sensitive |

The whole string must match. Tokens are read left to right, so `***` is a
literal `*` then a wildcard. One consequence: "wildcard `*` immediately
followed by a literal `*`" (and the same for `?`) cannot be written.

## How it works

```
X events (root SubstructureNotify + client PropertyChange)
        │  debounced ~50 ms           + safety-net rescan every 2 s
        ▼
reconcile(): scan tree → match rules → fix only what differs
```

Every action is an ordinary ICCCM request on the **client** window; mwm turns
it into the real change. Since reconcile only acts where state is wrong, the
events our own requests generate lead to a no-op pass, so the loop converges.

| Goal | Mechanism |
|---|---|
| geometry | `XConfigureWindow(client, x, y, w, h)` |
| bottom | `XConfigureWindow(client, stack_mode=Below)` when a pinned frame is not at the bottom |
| hidden | ICCCM withdraw (`unmap` + synthetic `UnmapNotify`); re-hidden if the app remaps itself |
| minimized by user | re-mapped (de-iconified) |
| lock | set `_MOTIF_WM_HINTS` functions, then withdraw + remap (mwm only reads the hint at map time) |

Safety valves:

* **Size hints / gravity**: if mwm doesn't hit the exact geometry because of the
  window's min/max/resize-increment hints, or a non-NorthWest gravity offsets it
  by up to the frame size, the result is accepted instead of fought.
* **Fight guard**: more than 15 corrections to one window within 10 s (e.g. the
  app or another tool keeps moving it) → warning logged, window left alone for 10 s.

## Verified behaviour of mwm 2.3.8

Measured under Xvfb with the Ubuntu `mwm` package. These facts shaped the design:

* No EWMH: `_NET_SUPPORTED` absent; `wmctrl` and `_NET_WM_STATE_BELOW` do nothing.
* `stack_mode=Below` on the client (or the frame) is honoured.
* Position requests place the frame origin; size requests set the client size.
* Nothing keeps a window at the bottom: clicking a window's title bar
  raises it, including a pinned window. (Clicking the content area of an already-focused window did *not*
  raise it with default resources.)
* `_MOTIF_WM_HINTS` changes on a mapped window are ignored until it is remapped.
* mwm's default outline move grabs the X server; every client, including this
  daemon, is frozen until the drag ends. Corrections then apply afterwards.
  `Mwm*moveOpaque: True` avoids the grab.

## Limitations / known behaviour

* Hiding a window or applying a lock causes a brief unmap/remap. A window with
  `visible: false` is visible briefly when it first appears (mwm maps it before
  the daemon can react).
* Setting `lock` back to `false` does not remove the lock from a running window;
  restart the application.
* If the daemon is killed with `SIGKILL` (or crashes), hidden windows stay hidden.
* mwm's icon windows and other non-client windows are ignored for stacking.
* `COMPOUND_TEXT` titles are decoded as Latin-1 (correct for Latin-1 text only).
* Tested on Xvfb + mwm only, not on real hardware or a custom `.mwmrc`.

## Tests

```sh
python3 -m unittest discover -s tests -t .
```

* `tests/test_unit.py`: glob matcher and config validation (no X needed).
* `tests/glob_cases.json`: shared glob test vectors, intended for the C port too.
* `tests/test_integration.py`: starts a private **Xvfb + mwm** per test and
  drives the real daemon (skipped if `Xvfb`/`mwm` are not installed; some tests
  need `xdotool`). Debian/Ubuntu: `apt-get install xvfb mwm xdotool`.

## Porting notes (C / Weston)

* **Matching**: `pinwin/glob.py` is ~40 lines with no recursion; port it
  directly. `?` and literals consume one **code point**, so the C version must
  decode UTF-8 (a few lines). Run `tests/glob_cases.json` against it.
* **Config**: plain JSON with a fixed schema; any small C JSON parser works.
* **Window identity**: for X apps (Xwayland) the same `title`/`instance`/`class`
  fields apply. Native Wayland apps (xdg-shell `title`/`app_id`) are out of
  scope for now.
* **Enforcement**: inside a compositor you own stacking and placement directly,
  so the request/settle/fight-guard machinery here largely disappears.
