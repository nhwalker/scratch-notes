"""Integration tests: real Xvfb + a real WM + the pinwin daemon as a subprocess.

PINWIN_TEST_WM=mwm (default, production) or PINWIN_TEST_WM=mutter (GNOME X11).

Skipped automatically when Xvfb or mwm is not installed. Each test gets a
fresh X server and window manager. Verification uses its own small Xlib code
(not pinwin's), so the daemon is checked from the outside.
"""

import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
# PINWIN_TEST_WM selects the window manager under test: "mwm" (default,
# production target) or "mutter" (GNOME's WM in X11 mode, developer machines).
TEST_WM = os.environ.get("PINWIN_TEST_WM", "mwm")
HAVE_X = all(shutil.which(t) for t in ("Xvfb", TEST_WM)) and \
    (TEST_WM != "mutter" or shutil.which("dbus-run-session"))
HAVE_XDOTOOL = shutil.which("xdotool") is not None

if HAVE_X:
    from Xlib import X, Xatom, display
    from Xlib.protocol import event as xevent


def _x_alive(n):
    import socket
    sk = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        sk.settimeout(0.5)
        sk.connect("/tmp/.X11-unix/X%d" % n)
        return True
    except OSError:
        return False
    finally:
        sk.close()


def _free_display():
    """First display number with no live X server; stale lock/socket files
    (e.g. from a killed Xvfb) are removed. Liveness = something accepts on the
    socket, since a lock file's PID may have been reused."""
    for n in range(50, 200):
        if _x_alive(n):
            continue
        for stale in ("/tmp/.X%d-lock" % n, "/tmp/.X11-unix/X%d" % n):
            try:
                os.unlink(stale)
            except FileNotFoundError:
                pass
        return ":%d" % n
    raise RuntimeError("no free display number")


def wait_until(pred, timeout=5.0, interval=0.05, msg="condition"):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            last = pred()
            if last:
                return last
        except Exception as e:          # window may be mid-reparent
            last = e
        time.sleep(interval)
    raise AssertionError("timed out waiting for %s (last: %r)" % (msg, last))


@unittest.skipUnless(HAVE_X, "Xvfb and %s required" % TEST_WM)
class MwmIntegration(unittest.TestCase):

    # ------------------------------------------------------------ fixtures

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="pinwin-test-")
        self.procs = []
        self.dpy_name = _free_display()
        self.env = dict(os.environ, DISPLAY=self.dpy_name)
        self.env["PYTHONPATH"] = PROJECT + os.pathsep + self.env.get("PYTHONPATH", "")
        self._spawn(["Xvfb", self.dpy_name, "-screen", "0", "1280x1024x24",
                     "-nolisten", "tcp"])
        wait_until(lambda: os.path.exists("/tmp/.X11-unix/X" + self.dpy_name[1:]),
                   msg="Xvfb socket")
        self.d = wait_until(lambda: display.Display(self.dpy_name), msg="X connection")
        self.root = self.d.screen().root
        self._start_wm()
        self.A_WM_STATE = self.d.intern_atom("WM_STATE")
        self.A_FE = self.d.intern_atom("_NET_FRAME_EXTENTS")
        self.daemon = None

    def _start_wm(self):
        if TEST_WM == "mwm":
            # Opaque move: mwm's default outline move grabs the server, which
            # would freeze every client (including this test) during drags.
            res = os.path.join(self.tmp, "mwm.res")
            with open(res, "w") as f:
                f.write("Mwm*moveOpaque: True\n")
            self._spawn(["mwm"], env=dict(self.env, XENVIRONMENT=res))
            motif = self.d.intern_atom("_MOTIF_WM_INFO")
            wait_until(lambda: self.root.get_full_property(motif, X.AnyPropertyType),
                       msg="mwm startup")
        elif TEST_WM == "mutter":
            xdg = os.path.join(self.tmp, "xdg")
            os.mkdir(xdg, 0o700)
            env = dict(self.env, XDG_RUNTIME_DIR=xdg, LIBGL_ALWAYS_SOFTWARE="1")
            self._spawn(["dbus-run-session", "--", "mutter", "--x11", "--replace",
                         "--sm-disable"], env=env)
            check = self.d.intern_atom("_NET_SUPPORTING_WM_CHECK")
            wait_until(lambda: self.root.get_full_property(check, X.AnyPropertyType),
                       timeout=15, msg="mutter startup")
            time.sleep(1.0)        # let mutter finish managing the screen
        else:
            raise RuntimeError("unknown PINWIN_TEST_WM %r" % TEST_WM)

    def tearDown(self):
        if self.daemon and self.daemon.poll() is None:
            self.daemon.kill()
        if self.daemon:
            self.daemon.wait()
            self.log_f.close()
        xvfb = self.procs[0]           # spawned first in setUp
        for p in reversed(self.procs):
            if p.poll() is None:
                if p is xvfb:
                    p.terminate()      # SIGTERM lets Xvfb remove its lock file
                    try:
                        p.wait(3)
                    except subprocess.TimeoutExpired:
                        p.kill()
                else:
                    p.kill()
            p.wait()
        try:
            self.d.close()
        except Exception:
            pass
        if self.daemon_log_text() and (self._outcome_failed() or os.environ.get("PINWIN_SHOW_LOG")):
            sys.stderr.write("\n--- daemon log ---\n" + self.daemon_log_text())
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _outcome_failed(self):
        res = getattr(self, "_outcome", None)
        if res is None or not hasattr(res, "result"):
            return False
        r = res.result
        return any(t is self for t, _ in getattr(r, "failures", []) + getattr(r, "errors", []))

    def _spawn(self, argv, env=None, **kw):
        p = subprocess.Popen(argv, env=env or self.env, stdout=subprocess.DEVNULL,
                             stderr=subprocess.DEVNULL, **kw)
        self.procs.append(p)
        return p

    def client(self, title, geometry="200x150+100+100", instance="xclient",
               cls="XClient", inc=None):
        argv = [sys.executable, os.path.join(HERE, "xclient.py"), "--title", title,
                "--instance", instance, "--class", cls, "--geometry", geometry]
        if inc:
            argv += ["--inc", str(inc[0]), str(inc[1])]
        p = self._spawn(argv)
        wait_until(lambda: self.find(title) is not None, msg="client %r managed" % title)
        return p

    def write_config(self, rules):
        path = os.path.join(self.tmp, "config.json")
        with open(path, "w") as f:
            json.dump(rules, f)
        return path

    def start_daemon(self, rules):
        self.cfg = self.write_config(rules)
        self.log_path = os.path.join(self.tmp, "daemon.log")
        self.log_f = open(self.log_path, "w")
        self.daemon = subprocess.Popen(
            [sys.executable, "-m", "pinwin", "-v", self.cfg], env=self.env,
            stdout=self.log_f, stderr=subprocess.STDOUT, cwd=PROJECT)
        wait_until(lambda: "started:" in self.daemon_log_text(), msg="daemon start")

    def daemon_log_text(self):
        p = getattr(self, "log_path", None)
        if not p or not os.path.exists(p):
            return ""
        with open(p) as f:
            return f.read()

    # ------------------------------------------------------------ observation

    def _wm_state(self, w):
        p = w.get_full_property(self.A_WM_STATE, self.A_WM_STATE)
        return int(p.value[0]) if p is not None and len(p.value) else None

    def _clients_under(self, w, depth=0):
        st = self._wm_state(w)
        if st is not None:
            return [w]
        if depth > 4:
            return []
        out = []
        for c in w.query_tree().children:
            out += self._clients_under(c, depth + 1)
        return out

    def all_windows(self):
        """[(frame, client, title, wm_state, viewable)] bottom -> top."""
        out = []
        self.d.sync()
        for top in self.root.query_tree().children:
            try:
                a = top.get_attributes()
                if a.override_redirect:
                    continue
                for c in self._clients_under(top):
                    out.append((top, c, c.get_wm_name(), self._wm_state(c),
                                a.map_state == X.IsViewable))
                # Withdrawn clients reparented to root have no WM_STATE.
                if not self._clients_under(top):
                    name = top.get_wm_name()
                    if name:
                        out.append((top, top, name, None, a.map_state == X.IsViewable))
            except Exception:
                continue
        return out

    def find(self, title, managed=True):
        for f, c, t, st, v in self.all_windows():
            if t == title and (not managed or st in (1, 3)):
                return f, c
        return None

    def find_any(self, title):
        for f, c, t, st, v in self.all_windows():
            if t == title:
                return c
        return None

    def stack(self):
        """Titles of viewable managed windows, bottom -> top."""
        return [t for f, c, t, st, v in self.all_windows() if v and st == 1]

    def outer(self, f, c):
        """(x, y, w, h): visible top-left of the decorated window + client size.

        Independent re-implementation of the config's geometry definition:
        _NET_FRAME_EXTENTS if published (Mutter), else frame origin (mwm)."""
        cg = c.get_geometry()
        fe = c.get_full_property(self.A_FE, X.AnyPropertyType)
        if fe is not None and len(fe.value) >= 4:
            t = c.translate_coords(self.root, 0, 0)
            return (-t.x - fe.value[0], -t.y - fe.value[2], cg.width, cg.height)
        fg = f.get_geometry()
        return (fg.x, fg.y, cg.width, cg.height)

    def geom(self, title):
        f, c = self.find(title)
        return self.outer(f, c)

    def is_visible(self, title):
        return any(t == title and v and st == 1 for f, c, t, st, v in self.all_windows())

    def stays(self, pred, seconds=1.0):
        """Assert pred() holds continuously for `seconds`."""
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            if not pred():
                self.fail("condition stopped holding; windows now: %r"
                          % [(t, st, v) for f, c, t, st, v in self.all_windows()])
            time.sleep(0.1)

    def count_log(self, needle):
        return self.daemon_log_text().count(needle)

    # ------------------------------------------------------------ tests

    def test_existing_window_gets_geometry_and_bottom(self):
        self.client("A", "200x150+300+300")
        self.client("B", "200x150+350+350")
        self.client("Pinned", "200x150+500+500")
        # Precondition: Pinned is not already at the bottom. (Initial stacking
        # order of new windows is WM-specific; Mutter does not always stack
        # them in creation order, so arrange it explicitly.)
        wait_until(lambda: len(self.stack()) == 3, msg="all three visible")
        if self.stack()[0] == "Pinned":
            f, c = self.find("Pinned")
            c.configure(stack_mode=X.Above)    # newest window is focused on both WMs
            self.d.sync()
        wait_until(lambda: self.stack()[0] != "Pinned", msg="precondition")
        self.start_daemon([{"match": {"title": "Pinned"}, "geometry": [10, 20, 400, 300]}])
        wait_until(lambda: self.geom("Pinned") == (10, 20, 400, 300), msg="geometry")
        wait_until(lambda: self.stack()[0] == "Pinned", msg="bottom")

    def test_window_that_appears_later(self):
        self.client("A")
        self.start_daemon([{"match": {"title": "Late*"}, "geometry": [50, 60, 320, 240]}])
        self.client("Late window", "100x100+700+700")
        wait_until(lambda: self.geom("Late window") == (50, 60, 320, 240), msg="geometry")
        wait_until(lambda: self.stack()[0] == "Late window", msg="bottom")

    def test_raise_by_other_client_is_undone(self):
        self.client("A")
        self.client("P")
        self.start_daemon([{"match": {"title": "P"}, "geometry": [0, 0, 300, 200]}])
        wait_until(lambda: self.stack() == ["P", "A"], msg="initial pin")
        before = self.count_log("restacking")
        f, c = self.find("P")
        c.configure(stack_mode=X.Above)       # some other client raises it
        self.d.sync()
        wait_until(lambda: self.count_log("restacking") > before, msg="daemon reacts")
        wait_until(lambda: self.stack() == ["P", "A"], msg="re-lowered")

    @unittest.skipUnless(HAVE_XDOTOOL, "xdotool required")
    def test_user_click_raise_is_undone(self):
        self.client("A", "200x150+100+100")
        self.client("P", "200x150+0+0")
        self.start_daemon([{"match": {"title": "P"}, "geometry": [0, 0, 600, 500]}])
        wait_until(lambda: self.stack() == ["P", "A"], msg="initial pin")
        before = self.count_log("restacking")
        # Click P's title bar where A does not cover it. (Observed with mwm
        # defaults: clicking the content area of an already-focused window
        # does not raise it; clicking the title bar does.)
        subprocess.run(["xdotool", "mousemove", "450", "10", "click", "1"],
                       env=self.env, check=True)
        if TEST_WM == "mwm":
            # mwm reliably raises on a title-bar click, so the daemon must act.
            wait_until(lambda: self.count_log("restacking") > before, msg="daemon reacts")
        # Observed on Mutter 46: once the daemon has lowered the window with
        # _NET_RESTACK_WINDOW, a title-bar click focuses it but did not raise
        # it in this setup (cause not determined). Either way the invariant is
        # the same: it ends up, and stays, at the bottom.
        wait_until(lambda: self.stack() == ["P", "A"], msg="at bottom")
        self.stays(lambda: self.stack() == ["P", "A"], 1.0)

    def test_moved_window_is_restored(self):
        self.client("P")
        self.start_daemon([{"match": {"title": "P"}, "geometry": [40, 40, 300, 200]}])
        wait_until(lambda: self.geom("P") == (40, 40, 300, 200), msg="pin")
        f, c = self.find("P")
        before = self.count_log("geometry 0x")
        c.configure(x=600, y=500, width=100, height=80)
        self.d.sync()
        # The daemon usually restores within ~50 ms, so check the log for the
        # correction rather than trying to observe the intermediate state.
        wait_until(lambda: self.count_log("geometry 0x") > before, msg="correction")
        wait_until(lambda: self.geom("P") == (40, 40, 300, 200), msg="restored")

    def test_hidden_window_stays_hidden_and_can_be_shown(self):
        self.client("A")
        self.client("Secret", "150x100+500+500")
        self.start_daemon([{"match": {"title": "Secret"}, "visible": False}])
        wait_until(lambda: not self.is_visible("Secret"), msg="hidden")
        # The application maps itself again -> daemon hides it again.
        before = self.count_log("hiding")
        self.find_any("Secret").map()
        self.d.sync()
        wait_until(lambda: self.count_log("hiding") > before, msg="re-hide")
        wait_until(lambda: not self.is_visible("Secret"), msg="hidden again")
        # Config reload: now visible with a geometry.
        self.write_config([{"match": {"title": "Secret"}, "geometry": [5, 5, 250, 120]}])
        self.daemon.send_signal(signal.SIGHUP)
        wait_until(lambda: self.is_visible("Secret"), msg="shown after reload")
        wait_until(lambda: self.geom("Secret") == (5, 5, 250, 120), msg="geometry after show")
        wait_until(lambda: self.stack()[0] == "Secret", msg="bottom after show")

    def test_hidden_windows_restored_on_exit(self):
        self.client("Secret")
        self.start_daemon([{"match": {"title": "Secret"}, "visible": False}])
        wait_until(lambda: not self.is_visible("Secret"), msg="hidden")
        self.daemon.send_signal(signal.SIGTERM)
        self.assertEqual(self.daemon.wait(5), 0)
        wait_until(lambda: self.is_visible("Secret"), msg="restored")

    @unittest.skipUnless(HAVE_XDOTOOL, "xdotool required")
    def test_iconify_is_undone(self):
        self.client("A")
        self.client("P")
        self.start_daemon([{"match": {"title": "P"}, "geometry": [0, 0, 300, 200]}])
        wait_until(lambda: self.stack() == ["P", "A"], msg="pin")
        f, c = self.find("P")
        subprocess.run(["xdotool", "windowminimize", str(c.id)], env=self.env, check=True)
        wait_until(lambda: "deiconifying" in self.daemon_log_text(), msg="deiconify")
        wait_until(lambda: self.stack() == ["P", "A"], msg="back, at bottom")

    def test_title_change_rematches(self):
        self.client("A")
        self.client("not yet", "200x150+400+400")
        self.start_daemon([{"match": {"title": "Board*"}, "geometry": [1, 2, 210, 160]}])
        time.sleep(0.5)
        self.assertEqual(self.geom("not yet"), (400, 400, 200, 150))
        f, c = self.find("not yet")
        c.set_wm_name("Board: now matching")
        self.d.sync()
        wait_until(lambda: self.geom("Board: now matching") == (1, 2, 210, 160), msg="pin")
        wait_until(lambda: self.stack()[0] == "Board: now matching", msg="bottom")

    def test_class_and_instance_matching(self):
        self.client("same title", "200x150+400+400", instance="alpha", cls="Alpha")
        self.client("same title", "200x150+450+450", instance="beta", cls="Beta")
        self.start_daemon([{"match": {"class": "Beta", "instance": "b*"},
                            "geometry": [0, 0, 222, 111]}])
        def beta_geom():
            for f, c, t, st, v in self.all_windows():
                if c.get_wm_class() == ("beta", "Beta"):
                    return self.outer(f, c)
        wait_until(lambda: beta_geom() == (0, 0, 222, 111), msg="beta pinned")
        for f, c, t, st, v in self.all_windows():
            if c.get_wm_class() == ("alpha", "Alpha"):
                self.assertEqual(self.outer(f, c), (400, 400, 200, 150))

    def test_rule_manages_only_one_window(self):
        first = self.client("Dup", "200x150+400+400")
        self.client("Dup", "200x150+700+400")
        self.client("Free", "100x100+0+600")
        self.start_daemon([{"match": {"title": "Dup"}, "geometry": [0, 0, 300, 200]}])
        wait_until(lambda: "also matches" in self.daemon_log_text(), msg="warning")

        def dup_geoms():
            return sorted(self.outer(f, c)
                          for f, c, t, st, v in self.all_windows() if t == "Dup" and st == 1)
        wait_until(lambda: dup_geoms() == [(0, 0, 300, 200), (700, 400, 200, 150)],
                   msg="one pinned, one untouched")
        wait_until(lambda: self.stack()[0] == "Dup", msg="pinned one at bottom")
        self.stays(lambda: dup_geoms() == [(0, 0, 300, 200), (700, 400, 200, 150)], 1.0)
        self.assertEqual(self.count_log("also matches"), 1, "warning should not repeat")
        # Close the managed window -> the other one is claimed and pinned.
        first.kill()
        wait_until(lambda: dup_geoms() == [(0, 0, 300, 200)], msg="second claimed")

    def test_hiding_rule_hides_only_one_window(self):
        self.client("Dup", "200x150+400+400")
        self.client("Dup", "200x150+700+400")
        self.start_daemon([{"match": {"title": "Dup"}, "visible": False}])

        def visible_dups():
            return sum(1 for f, c, t, st, v in self.all_windows()
                       if t == "Dup" and v and st == 1)
        wait_until(lambda: visible_dups() == 1, msg="exactly one hidden")
        self.stays(lambda: visible_dups() == 1, 1.5)   # claim must not flip

    def test_multiple_pinned_keep_rule_order(self):
        self.client("Free")
        self.client("Second", "100x100+600+0")
        self.client("First", "100x100+700+0")
        self.start_daemon([
            {"match": {"title": "First"}, "geometry": [0, 0, 300, 300]},
            {"match": {"title": "Second"}, "geometry": [100, 100, 300, 300]},
        ])
        wait_until(lambda: self.stack() == ["First", "Second", "Free"], msg="order")
        f, c = self.find("First")
        c.configure(stack_mode=X.Above)
        self.d.sync()
        wait_until(lambda: self.stack() == ["First", "Second", "Free"], msg="order restored")

    def test_size_increments_do_not_cause_a_fight(self):
        # Increments of 7x13 mean 400x300 is unreachable; mwm rounds it.
        self.client("Inc", "140x130+300+300", inc=(7, 13))
        self.start_daemon([{"match": {"title": "Inc"}, "geometry": [10, 10, 400, 300]}])
        def settled():
            x, y, w, h = self.geom("Inc")
            return (x, y) == (10, 10) and abs(w - 400) < 7 and abs(h - 300) < 13
        wait_until(settled, msg="settle within increments")
        time.sleep(0.5)
        n = self.count_log("geometry 0x")
        time.sleep(3)
        self.assertEqual(self.count_log("geometry 0x"), n, "daemon kept re-requesting")
        x, y, w, h = self.geom("Inc")
        self.assertEqual((x, y), (10, 10))
        self.assertLessEqual(abs(w - 400), 7)
        self.assertLessEqual(abs(h - 300), 13)
        # Still enforced afterwards: moving it is undone.
        f, c = self.find("Inc")
        c.configure(x=500, y=500)
        self.d.sync()
        wait_until(lambda: self.geom("Inc")[:2] == (10, 10), msg="restored after settle")

    def test_fight_with_another_client_backs_off(self):
        self.client("P")
        self.start_daemon([{"match": {"title": "P"}, "geometry": [0, 0, 300, 200]}])
        wait_until(lambda: self.geom("P") == (0, 0, 300, 200), msg="pin")
        f, c = self.find("P")
        end = time.monotonic() + 8
        while time.monotonic() < end and "backing off" not in self.daemon_log_text():
            c.configure(x=500, y=500)          # a hostile client keeps moving it
            self.d.sync()
            time.sleep(0.1)
        self.assertIn("backing off", self.daemon_log_text())

    @unittest.skipUnless(HAVE_XDOTOOL, "xdotool required")
    def test_lock_prevents_user_drag(self):
        self.client("Locked", "200x150+100+100")
        self.client("Unlocked", "200x150+500+100")
        self.start_daemon([
            {"match": {"title": "Locked"}, "geometry": [100, 100, 200, 150], "lock": True},
            {"match": {"title": "Unlocked"}, "geometry": [500, 100, 200, 150]},
        ])
        wait_until(lambda: "remapping" in self.daemon_log_text(), msg="lock remap")
        wait_until(lambda: self.find("Locked") and self.geom("Locked") == (100, 100, 200, 150),
                   msg="locked window back")
        time.sleep(0.5)
        # Freeze the daemon so only mwm's behaviour is measured.
        self.daemon.send_signal(signal.SIGSTOP)
        try:
            def drag(title):
                f, c = self.find(title)
                fx, fy = f.get_geometry().x, f.get_geometry().y
                cy = c.translate_coords(self.root, 0, 0)
                tx, ty = fx + 60, (fy + (-cy.y)) // 2 + 2     # middle of title bar
                cmds = [["mousemove", str(tx), str(ty)], ["mousedown", "1"]]
                cmds += [["mousemove", str(tx + 8 * i), str(ty + 6 * i)] for i in range(1, 16)]
                cmds += [["mouseup", "1"]]
                for cmd in cmds:
                    subprocess.run(["xdotool"] + cmd, env=self.env, check=True)
                    time.sleep(0.03)
                time.sleep(0.3)
                return self.geom(title)[:2]
            self.assertNotEqual(drag("Unlocked"), (500, 100), "control window should move")
            self.assertEqual(drag("Locked"), (100, 100), "locked window moved")
        finally:
            self.daemon.send_signal(signal.SIGCONT)


if __name__ == "__main__":
    unittest.main()
