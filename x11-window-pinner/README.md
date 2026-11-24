# x11-window-pinner (`pinwin`)

A small daemon that keeps selected X11 windows at a fixed geometry, **always at
the bottom of the stacking order**, optionally hidden or locked against user
move/resize.

| Window manager | Role | Tested version |
|---|---|---|
| **mwm** (Motif) | production target | 2.3.8 |
| **Mutter** in X11 mode (GNOME on Xorg) | developer machines | 46.2 |

The same binary and the same config work on both; WM differences are detected
at runtime from what the WM advertises (`_NET_SUPPORTED`), never from its name.

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
| `geometry` | when visible | `[x, y, width, height]`. `x, y` = **visible top-left corner of the decorated window** (title bar / border included); `width, height` = the **client area** (decorations excluded). Same convention as the X `-geometry` option, and measured identical on mwm and Mutter. |
| `visible` | no (default `true`) | `false` = fully withdrawn: unmapped, no icon. |
| `lock` | no (default `false`) | Remove the WM's move, resize, minimize and maximize functions for the window (`_MOTIF_WM_HINTS`; honoured by mwm and Mutter). |

Rules:

* A rule manages **one** window. If it matches several, it claims the
  bottom-most one (stacking order at the time) and logs a warning once for each
  extra, which is left untouched. The claim is sticky: it holds, even while
  the window is hidden, until that window closes or stops matching; then the
  next matching window is claimed.
* A window matching several rules uses the **first** one in the file. If that
  rule has already claimed another window, this one is left alone (it does
  not fall through to later rules).
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

Every action is an ordinary request on the **client** window; the WM turns it
into the real change. Since reconcile only acts where state is wrong, the
events our own requests generate lead to a no-op pass, so the loop converges.

| Goal | mwm (no EWMH) | Mutter (EWMH advertised) |
|---|---|---|
| measure position | frame window origin | client position − `_NET_FRAME_EXTENTS` (Mutter's frame window also holds invisible resize margins) |
| geometry | `XConfigureWindow(client, x, y, w, h)` | same |
| bottom | `XConfigureWindow(client, stack_mode=Below)` | `_NET_RESTACK_WINDOW` message (Below, source = pager) |
| hidden | ICCCM withdraw (`unmap` + synthetic `UnmapNotify`); re-hidden if the app remaps itself | same |
| minimized by user | re-map | re-map + `_NET_ACTIVE_WINDOW` message |
| lock | set `_MOTIF_WM_HINTS` functions, then withdraw + remap | same |

Each right-hand column entry is used only if the WM advertises the
corresponding atom; mwm advertises none, so it always takes the left column.

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

## Verified behaviour of Mutter 46.2 (X11 mode)

Measured under Xvfb with the Ubuntu `mutter` package (`mutter --x11`):

* EWMH supported, including `_NET_FRAME_EXTENTS`, `_NET_RESTACK_WINDOW`,
  `_NET_ACTIVE_WINDOW`, `_NET_WM_STATE_BELOW`.
* The frame window is larger than what you see (invisible resize margins), so
  its origin is *not* the visible corner; `_NET_FRAME_EXTENTS` gives the visible
  decoration (e.g. `[0, 0, 37, 0]`: 37 px title bar). With that correction,
  requests place the visible corner exactly where asked, as on mwm.
* `XConfigureWindow(stack_mode=Below)` from another client is honoured **only
  for the focused window** and silently ignored otherwise. `_NET_RESTACK_WINDOW`
  works for any window.
* `XMapWindow` does not un-minimize; `_NET_ACTIVE_WINDOW` does.
* A minimized window's frame stays mapped (WM_STATE = Iconic).
* Off-screen positions are clamped (e.g. `-50,-50` became `0,0`); mwm honours them.
* A title-bar click on the pinned window focused it but did not raise it once
  the daemon had lowered it (cause not determined); on mwm it raises.

## Limitations / known behaviour

* Hiding a window or applying a lock causes a brief unmap/remap. A window with
  `visible: false` is visible briefly when it first appears (mwm maps it before
  the daemon can react).
* Setting `lock` back to `false` does not remove the lock from a running window;
  restart the application.
* If the daemon is killed with `SIGKILL` (or crashes), hidden windows stay hidden.
* mwm's icon windows and other non-client windows are ignored for stacking.
* `COMPOUND_TEXT` titles are decoded as Latin-1 (correct for Latin-1 text only).
* Tested on Xvfb only (mwm with default resources; Mutter without
  gnome-shell). A full GNOME session runs Mutter inside gnome-shell, whose
  extensions or settings could change behaviour; not tested.
* **GNOME/Mutter differences that can make placement differ from mwm:**
  * Positions Mutter won't allow (off-screen, under the top bar in a real
    GNOME session) are clamped; the daemon logs repeated corrections, then the
    fight guard backs off. Keep configured geometry inside the usable screen.
  * Un-minimizing on Mutter also focuses the window (side effect of
    `_NET_ACTIVE_WINDOW`).
  * Apps that draw their own title bar (GTK "header bar" / client-side
    decorations) are **not handled or tested**: their client window includes an
    invisible shadow (`_GTK_FRAME_EXTENTS`), so under Mutter position and size
    would include that shadow. Under mwm the same app may or may not use
    client-side decorations. Check `--list` output on both if this matters.

## Tests

```sh
python3 -m unittest discover -s tests -t .
```

* `tests/test_unit.py`: glob matcher and config validation (no X needed).
* `tests/glob_cases.json`: shared glob test vectors, intended for the C port too.
* `tests/test_integration.py`: starts a private **Xvfb + WM** per test and
  drives the real daemon (skipped if the tools are not installed; some tests
  need `xdotool`).
  * mwm (default): `apt-get install xvfb mwm xdotool`
  * Mutter: `apt-get install xvfb mutter dbus-x11 xdotool`, then
    `PINWIN_TEST_WM=mutter python3 -m unittest discover -s tests -t .`

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
