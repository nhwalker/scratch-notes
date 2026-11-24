"""X11 enforcement loop.

Design: event-triggered, reconcile-everything.

  X events on root (SubstructureNotify) and on managed clients (PropertyChange)
        |
        v   (debounced ~50 ms)
  reconcile():  scan tree -> match rules -> fix whatever differs
        ^
        |   periodic safety-net reconcile every RESCAN_SECONDS

Every action is a plain ICCCM request on the CLIENT window. mwm holds
SubstructureRedirect on root, so the X server turns these requests into
ConfigureRequest / MapRequest events that mwm handles. Behaviour verified
against mwm 2.3.8 (Ubuntu package) under Xvfb:
  * configure(stack_mode=Below) on a client lowers its frame.
  * configure(x, y, w, h) on a client: x, y = frame origin; w, h = client size.
  * _MOTIF_WM_HINTS changes only take effect when the window is (re)mapped.

Because reconcile only acts where the state is actually wrong, the events our
own requests produce lead to a no-op pass, so the loop converges.
"""

import errno
import logging
import os
import select
import signal
import time
from collections import defaultdict, deque

from Xlib import X, Xatom, Xutil, display, error
from Xlib.protocol import event as xevent

from .config import ConfigError, first_match, load_rules

log = logging.getLogger("pinwin")

# ICCCM WM_STATE values
WITHDRAWN, NORMAL, ICONIC = 0, 1, 3

# _MOTIF_WM_HINTS
MWM_HINTS_FUNCTIONS = 1 << 0
MWM_FUNC_ALL = 1 << 0
MWM_FUNC_RESIZE = 1 << 1
MWM_FUNC_MOVE = 1 << 2
MWM_FUNC_MINIMIZE = 1 << 3
MWM_FUNC_MAXIMIZE = 1 << 4
MWM_FUNC_CLOSE = 1 << 5
MWM_FUNC_BITS = (MWM_FUNC_RESIZE | MWM_FUNC_MOVE | MWM_FUNC_MINIMIZE
                 | MWM_FUNC_MAXIMIZE | MWM_FUNC_CLOSE)
LOCK_REMOVES = MWM_FUNC_RESIZE | MWM_FUNC_MOVE | MWM_FUNC_MINIMIZE | MWM_FUNC_MAXIMIZE

DEBOUNCE_SECONDS = 0.05
RESCAN_SECONDS = 2.0
REMAP_TIMEOUT_SECONDS = 2.0
REQUEST_PATIENCE = 0.5     # max seconds to wait for mwm to act on a request
# Fight detection: more than FIGHT_LIMIT corrections to one window within
# FIGHT_WINDOW seconds -> leave that window alone for FIGHT_BACKOFF seconds.
FIGHT_LIMIT = 15
FIGHT_WINDOW = 10.0
FIGHT_BACKOFF = 10.0

IGNORED_ERRORS = (error.BadWindow, error.BadDrawable, error.BadMatch, error.BadValue)


class Client:
    """Snapshot of one managed top-level client window for a reconcile pass."""

    __slots__ = ("win", "frame", "state", "title", "instance", "wm_class")

    def __init__(self, win, frame, state, title, instance, wm_class):
        self.win, self.frame, self.state = win, frame, state
        self.title, self.instance, self.wm_class = title, instance, wm_class

    @property
    def wid(self):
        return self.win.id


class Pinner:
    def __init__(self, config_path, display_name=None):
        self.config_path = config_path
        self.rules = load_rules(config_path) if config_path else []
        self.d = display.Display(display_name)
        self.d.set_error_handler(self._on_async_error)
        self.root = self.d.screen().root
        a = self.d.intern_atom
        self.A_WM_STATE = a("WM_STATE")
        self.A_NET_WM_NAME = a("_NET_WM_NAME")
        self.A_UTF8 = a("UTF8_STRING")
        self.A_MOTIF_HINTS = a("_MOTIF_WM_HINTS")

        self.watched = set()       # client wids we selected PropertyChange on
        self.hidden = {}           # wid -> Window we withdrew because visible=false
        self.pending_remap = {}    # wid -> (Window, deadline) withdrawn to re-read hints
        self.geom_state = {}      # wid -> geometry request state, see _enforce_geometry
        self.fights = defaultdict(deque)   # wid -> timestamps of corrections
        self.backoff = {}          # wid -> time until which we leave it alone
        self.seen_configure = set()  # frame ids with a ConfigureNotify since our request
        self.claims = {}           # rule index -> the one wid that rule manages
        self.warned_extra = set()  # (rule index, wid) extras already warned about

        self._reload_requested = False
        self._stop_requested = False

    # ------------------------------------------------------------------ X helpers

    def _on_async_error(self, err, request):
        # Windows can vanish between our query and our request; that is normal.
        if isinstance(err, IGNORED_ERRORS):
            log.debug("ignored async X error: %s", err)
        else:
            log.warning("X error: %s", err)

    def _text_prop(self, win, atom):
        p = win.get_full_property(atom, X.AnyPropertyType)
        if p is None or p.format != 8:
            return None
        v = p.value
        if isinstance(v, str):
            return v
        if p.property_type == self.A_UTF8:
            return v.decode("utf-8", "replace")
        # STRING is ISO-8859-1 by ICCCM. COMPOUND_TEXT is decoded the same way,
        # which is exact for Latin-1 text only (no escape-sequence handling).
        return v.decode("latin-1")

    def _title(self, win):
        t = self._text_prop(win, self.A_NET_WM_NAME)
        return t if t is not None else self._text_prop(win, Xatom.WM_NAME)

    def _wm_class(self, win):
        p = win.get_full_property(Xatom.WM_CLASS, X.AnyPropertyType)
        if p is None or p.format != 8:
            return None, None
        v = p.value if isinstance(p.value, bytes) else p.value.encode("latin-1")
        parts = v.split(b"\0")
        inst = parts[0].decode("latin-1") if len(parts) > 0 else None
        cls = parts[1].decode("latin-1") if len(parts) > 1 else None
        return inst, cls

    def _wm_state(self, win):
        p = win.get_full_property(self.A_WM_STATE, self.A_WM_STATE)
        if p is None or len(p.value) < 1:
            return None
        return int(p.value[0])

    def _find_client(self, win, depth=0):
        """Return (client_window, state) for the first descendant with WM_STATE."""
        st = self._wm_state(win)
        if st is not None:
            return win, st
        if depth >= 4:
            return None, None
        for ch in win.query_tree().children:
            c, st = self._find_client(ch, depth + 1)
            if c is not None:
                return c, st
        return None, None

    def _withdraw(self, win):
        """Equivalent of Xlib's XWithdrawWindow (ICCCM 4.1.4)."""
        win.unmap()
        ev = xevent.UnmapNotify(window=win, event=self.root, from_configure=False)
        self.root.send_event(ev, event_mask=X.SubstructureRedirectMask
                             | X.SubstructureNotifyMask)

    # ------------------------------------------------------------------ scanning

    def scan(self):
        """Return (clients in root stacking order, viewable stacking list).

        clients: list of Client for windows mwm currently manages (WM_STATE
                 Normal or Iconic), ordered bottom -> top by their frames.
        stack:   ids of root children that are viewable and not
                 override-redirect, bottom -> top.
        """
        clients, stack = [], []
        for top in self.root.query_tree().children:
            try:
                attrs = top.get_attributes()
                if attrs.override_redirect:
                    continue
                if attrs.map_state == X.IsViewable:
                    stack.append(top.id)
                win, st = self._find_client(top)
                if win is None or st not in (NORMAL, ICONIC):
                    continue
                inst, cls = self._wm_class(win)
                clients.append(Client(win, top, st, self._title(win), inst, cls))
            except IGNORED_ERRORS:
                continue
        return clients, stack

    # ------------------------------------------------------------------ fight guard

    def _may_act(self, wid, what):
        now = time.monotonic()
        until = self.backoff.get(wid)
        if until is not None:
            if now < until:
                return False
            del self.backoff[wid]
        q = self.fights[wid]
        while q and now - q[0] > FIGHT_WINDOW:
            q.popleft()
        if len(q) >= FIGHT_LIMIT:
            self.backoff[wid] = now + FIGHT_BACKOFF
            q.clear()
            log.warning("window 0x%x: %d corrections in %.0fs (last: %s); something "
                        "is fighting the pin, backing off %.0fs",
                        wid, FIGHT_LIMIT, FIGHT_WINDOW, what, FIGHT_BACKOFF)
            return False
        q.append(now)
        return True

    # ------------------------------------------------------------------ reconcile

    def reconcile(self):
        now = time.monotonic()
        clients, stack = self.scan()
        live = {c.wid for c in clients}

        # Watch new clients for title / WM_STATE changes.
        for c in clients:
            if c.wid not in self.watched:
                c.win.change_attributes(event_mask=X.PropertyChangeMask)
                self.watched.add(c.wid)
        self.watched &= live | set(self.hidden) | set(self.pending_remap)
        self.seen_configure &= {c.frame.id for c in clients}
        known = live | set(self.hidden) | set(self.pending_remap)
        for d in (self.fights, self.backoff, self.geom_state):
            for wid in [w for w in d if w not in known]:
                del d[wid]

        # Finish lock remaps once mwm has withdrawn the window.
        for wid, (win, deadline) in list(self.pending_remap.items()):
            try:
                st = self._wm_state(win)
            except IGNORED_ERRORS:
                del self.pending_remap[wid]
                continue
            if st in (None, WITHDRAWN) or now > deadline:
                log.info("window 0x%x: remapping so mwm reads new hints", wid)
                win.map()
                del self.pending_remap[wid]

        # Candidates per rule, in claim-preference order: managed clients
        # bottom -> top, then windows we have withdrawn ourselves.
        candidates = defaultdict(list)     # rule index -> [wid]
        by_wid = {}                        # wid -> (rule, Client or None)
        for c in clients:
            r = first_match(self.rules, c.title, c.instance, c.wm_class)
            by_wid[c.wid] = (r, c)
            if r is not None:
                candidates[r.index].append(c.wid)
        for wid, win in list(self.hidden.items()) + \
                [(w, v[0]) for w, v in self.pending_remap.items()]:
            if wid in live:
                continue
            try:
                inst, cls = self._wm_class(win)
                r = first_match(self.rules, self._title(win), inst, cls)
            except IGNORED_ERRORS:
                self.hidden.pop(wid, None)
                continue
            by_wid[wid] = (r, None)
            if r is not None:
                candidates[r.index].append(wid)

        self._update_claims(candidates)
        claimed = set(self.claims.values())

        # Withdrawn-by-us windows that are no longer claimed by a hiding rule
        # (rule changed, title changed, or another window holds the claim).
        for wid, win in list(self.hidden.items()):
            if wid in live:
                continue          # app remapped it; handled below as a client
            r, _ = by_wid.get(wid, (None, None))
            if wid not in claimed or r is None or r.visible:
                log.info("window 0x%x: no longer hidden by config, showing", wid)
                try:
                    win.map()
                except IGNORED_ERRORS:
                    pass
                del self.hidden[wid]

        pinned = []   # (rule, client) for visible managed windows
        for c in clients:
            r, _ = by_wid[c.wid]
            if r is None or c.wid not in claimed or c.wid in self.pending_remap:
                continue
            try:
                if self._enforce(c, r):
                    pinned.append((r, c))
            except IGNORED_ERRORS:
                continue

        frames = {c.frame.id for c in clients if c.state == NORMAL}
        self._enforce_stacking(pinned, [w for w in stack if w in frames])
        self.d.sync()

    def _update_claims(self, candidates):
        """Each rule manages at most one window.

        A claim is sticky: the rule keeps its window for as long as that window
        exists and still matches the rule (first-match). Otherwise the rule
        claims its first candidate (bottom-most managed window, else one we
        withdrew). Extra matches are left alone and warned about once.
        """
        for idx in list(self.claims):
            if self.claims[idx] not in candidates.get(idx, ()):
                log.info("rule %d: released window 0x%x", idx, self.claims[idx])
                del self.claims[idx]
        for idx, wids in candidates.items():
            if idx not in self.claims:
                self.claims[idx] = wids[0]
                log.info("rule %d: claimed window 0x%x", idx, wids[0])
            for wid in wids:
                if wid != self.claims[idx] and (idx, wid) not in self.warned_extra:
                    self.warned_extra.add((idx, wid))
                    log.warning("rule %d %s also matches window 0x%x; ignoring it "
                                "(rule already manages 0x%x)",
                                idx, self.rules[idx].match, wid, self.claims[idx])
        all_wids = {w for ws in candidates.values() for w in ws}
        self.warned_extra = {(i, w) for i, w in self.warned_extra
                             if w in all_wids and self.claims.get(i) != w}

    def _enforce(self, c, r):
        """Apply visibility, lock and geometry. Returns True if c stays visible."""
        if not r.visible:
            if self._may_act(c.wid, "hide"):
                log.info("hiding 0x%x %r", c.wid, c.title)
                self._withdraw(c.win)
                self.hidden[c.wid] = c.win
            return False
        self.hidden.pop(c.wid, None)

        if c.state == ICONIC:
            if self._may_act(c.wid, "deiconify"):
                log.info("deiconifying 0x%x %r", c.wid, c.title)
                c.win.map()
            return True

        if r.lock and self._needs_lock(c.win):
            if self._may_act(c.wid, "lock"):
                log.info("locking 0x%x %r (withdraw + remap)", c.wid, c.title)
                self._apply_lock(c.win)
                self._withdraw(c.win)
                self.pending_remap[c.wid] = (c.win, time.monotonic() + REMAP_TIMEOUT_SECONDS)
            return False

        self._enforce_geometry(c, r.geometry)
        return True

    def _needs_lock(self, win):
        p = win.get_full_property(self.A_MOTIF_HINTS, X.AnyPropertyType)
        return (self._allowed_functions(p) & LOCK_REMOVES) != 0

    @staticmethod
    def _allowed_functions(p):
        if p is None or len(p.value) < 2 or not (p.value[0] & MWM_HINTS_FUNCTIONS):
            return MWM_FUNC_BITS
        fn = int(p.value[1])
        if fn & MWM_FUNC_ALL:      # "ALL" means: everything except listed bits
            return MWM_FUNC_BITS & ~fn
        return fn & MWM_FUNC_BITS

    def _apply_lock(self, win):
        p = win.get_full_property(self.A_MOTIF_HINTS, X.AnyPropertyType)
        vals = [int(v) for v in p.value][:5] if p is not None else []
        vals += [0] * (5 - len(vals))
        allowed = self._allowed_functions(p) & ~LOCK_REMOVES
        vals[0] |= MWM_HINTS_FUNCTIONS
        vals[1] = allowed               # explicit list, no ALL bit
        win.change_property(self.A_MOTIF_HINTS, self.A_MOTIF_HINTS, 32, vals)

    def _enforce_geometry(self, c, desired):
        """Request `desired`, tolerating only differences mwm is entitled to.

        mwm may legitimately not give exactly what we ask:
          * size: min/max size and resize-increment hints (WM_NORMAL_HINTS)
          * position: a non-NorthWest window gravity offsets by up to the
            frame decoration size
        Such a result is "explained" and accepted once mwm has responded to our
        request. Any other difference (user or another client moved it) is
        corrected. Per-window state while a request is outstanding:
          (desired, actual_before_request, time_requested)
        """
        fg = c.frame.get_geometry()
        cg = c.win.get_geometry()
        actual = (fg.x, fg.y, cg.width, cg.height)
        if actual == desired:
            self.geom_state.pop(c.wid, None)
            return
        st = self.geom_state.get(c.wid)
        asked = st is not None and st[0] == desired
        if asked and self._explained(c, desired, actual, fg, cg):
            return                      # mwm did what it could; leave it be
        if (asked and actual == st[1] and c.frame.id not in self.seen_configure
                and time.monotonic() - st[2] < REQUEST_PATIENCE):
            return                      # mwm has not processed our request yet
        if not self._may_act(c.wid, "geometry"):
            return
        log.info("geometry 0x%x %r: %s -> %s", c.wid, c.title, actual, desired)
        x, y, w, h = desired
        c.win.configure(x=x, y=y, width=w, height=h)
        self.seen_configure.discard(c.frame.id)
        self.geom_state[c.wid] = (desired, actual, time.monotonic())

    def _explained(self, c, desired, actual, fg, cg):
        try:
            hints = c.win.get_wm_normal_hints()
        except IGNORED_ERRORS:
            hints = None
        flags = hints.flags if hints is not None else 0

        def size_ok(want, got, mn, mx, inc):
            if got == want:
                return True
            if flags & Xutil.PMinSize and mn > 0 and want < mn and got == mn:
                return True
            if flags & Xutil.PMaxSize and mx > 0 and want > mx and got == mx:
                return True
            return bool(flags & Xutil.PResizeInc) and inc > 1 and abs(got - want) < inc

        if hints is not None:
            w_ok = size_ok(desired[2], actual[2], hints.min_width, hints.max_width,
                           hints.width_inc)
            h_ok = size_ok(desired[3], actual[3], hints.min_height, hints.max_height,
                           hints.height_inc)
        else:
            w_ok = desired[2] == actual[2]
            h_ok = desired[3] == actual[3]
        dx, dy = actual[0] - desired[0], actual[1] - desired[1]
        if (dx, dy) == (0, 0):
            pos_ok = True
        else:
            grav = hints.win_gravity if hints is not None and flags & Xutil.PWinGravity \
                else X.NorthWestGravity
            extra_w = fg.width - cg.width
            extra_h = fg.height - cg.height
            pos_ok = grav != X.NorthWestGravity and abs(dx) <= extra_w and abs(dy) <= extra_h
        if w_ok and h_ok and pos_ok:
            log.debug("window 0x%x: %s accepted for %s (size hints / gravity)",
                      c.wid, actual, desired)
            return True
        return False

    def _enforce_stacking(self, pinned, stack):
        """Pinned frames must be the bottom-most client frames, in rule order.

        `stack` holds only viewable frames of managed clients (bottom -> top);
        mwm's own windows (icons etc.) are deliberately not considered.
        """
        pinned.sort(key=lambda rc: (rc[0].index, rc[1].wid))
        want = [c.frame.id for _, c in pinned]
        if not want:
            return
        if stack[:len(want)] == want:
            return
        acting = [c for _, c in pinned if self._may_act(c.wid, "lower")]
        if not acting:
            return
        log.info("restacking: lowering %s", ["0x%x" % c.wid for c in acting])
        # Lower in reverse order so the first rule ends up lowest.
        for c in reversed(acting):
            try:
                c.win.configure(stack_mode=X.Below)
            except IGNORED_ERRORS:
                pass

    # ------------------------------------------------------------------ list mode

    def list_windows(self):
        clients, _ = self.scan()
        rows = []
        for c in clients:
            fg = c.frame.get_geometry()
            cg = c.win.get_geometry()
            r = first_match(self.rules, c.title, c.instance, c.wm_class) if self.rules else None
            rows.append({
                "id": "0x%x" % c.wid, "title": c.title, "instance": c.instance,
                "class": c.wm_class, "state": {NORMAL: "normal", ICONIC: "iconic"}[c.state],
                "geometry": [fg.x, fg.y, cg.width, cg.height],
                "rule": r.index if r else None,
            })
        return rows

    # ------------------------------------------------------------------ main loop

    def reload(self):
        try:
            self.rules = load_rules(self.config_path)
        except (OSError, ConfigError) as e:
            log.error("reload failed, keeping previous config: %s", e)
            return
        self.geom_state.clear()
        self.backoff.clear()
        log.info("config reloaded: %d rules", len(self.rules))

    def restore_hidden(self):
        for wid, win in list(self.hidden.items()):
            try:
                log.info("exit: re-showing hidden window 0x%x", wid)
                win.map()
            except IGNORED_ERRORS:
                pass
        self.hidden.clear()
        try:
            self.d.sync()
        except Exception:
            pass

    def run(self):
        self.root.change_attributes(event_mask=X.SubstructureNotifyMask)
        rfd, wfd = os.pipe()
        os.set_blocking(wfd, False)
        old_wakeup = signal.set_wakeup_fd(wfd)

        def on_hup(_s, _f):
            self._reload_requested = True

        def on_term(_s, _f):
            self._stop_requested = True

        signal.signal(signal.SIGHUP, on_hup)
        signal.signal(signal.SIGTERM, on_term)
        signal.signal(signal.SIGINT, on_term)

        log.info("started: %d rules from %s", len(self.rules), self.config_path)
        xfd = self.d.fileno()
        dirty = True
        next_rescan = 0.0
        try:
            while not self._stop_requested:
                if self._reload_requested:
                    self._reload_requested = False
                    self.reload()
                    dirty = True
                now = time.monotonic()
                if dirty or now >= next_rescan:
                    self.reconcile()
                    dirty = False
                    next_rescan = time.monotonic() + RESCAN_SECONDS
                timeout = max(0.0, next_rescan - time.monotonic())
                if not self.d.pending_events():
                    try:
                        ready, _, _ = select.select([xfd, rfd], [], [], timeout)
                    except InterruptedError:
                        continue
                    if rfd in ready:
                        try:
                            os.read(rfd, 512)
                        except OSError as e:
                            if e.errno != errno.EAGAIN:
                                raise
                # Drain, then debounce: wait briefly for the burst to finish.
                got = self._drain()
                if got:
                    deadline = time.monotonic() + DEBOUNCE_SECONDS
                    while time.monotonic() < deadline:
                        ready, _, _ = select.select([xfd], [], [],
                                                    max(0.0, deadline - time.monotonic()))
                        if ready:
                            self._drain()
                    dirty = True
        finally:
            self.restore_hidden()
            signal.set_wakeup_fd(old_wakeup)
            os.close(rfd)
            os.close(wfd)
        log.info("stopped")

    def _drain(self):
        n = 0
        while self.d.pending_events():
            e = self.d.next_event()
            if e.type == X.ConfigureNotify:
                self.seen_configure.add(e.window.id)
            n += 1
        return n
