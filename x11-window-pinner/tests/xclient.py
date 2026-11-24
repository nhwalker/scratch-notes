"""Minimal X11 test client: one top-level window with a chosen title, WM_CLASS
and size hints. Runs until killed.

  python3 xclient.py --title T [--instance i] [--class C] [--geometry WxH+X+Y]
                     [--inc W H]
"""

import argparse
import re
import time

from Xlib import X, Xutil, display


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--title", required=True)
    ap.add_argument("--instance", default="xclient")
    ap.add_argument("--class", dest="cls", default="XClient")
    ap.add_argument("--geometry", default="200x150+100+100")
    ap.add_argument("--inc", nargs=2, type=int, help="resize increments W H")
    a = ap.parse_args()
    w, h, x, y = map(int, re.fullmatch(r"(\d+)x(\d+)\+(-?\d+)\+(-?\d+)", a.geometry).groups())

    d = display.Display()
    s = d.screen()
    win = s.root.create_window(x, y, w, h, 1, s.root_depth, X.InputOutput,
                               X.CopyFromParent, background_pixel=s.white_pixel,
                               event_mask=X.StructureNotifyMask)
    win.set_wm_name(a.title)
    win.set_wm_class(a.instance, a.cls)
    hints = {"flags": Xutil.USPosition | Xutil.USSize, "x": x, "y": y,
             "width": w, "height": h}
    if a.inc:
        hints["flags"] |= Xutil.PResizeInc | Xutil.PBaseSize
        hints.update(width_inc=a.inc[0], height_inc=a.inc[1], base_width=0, base_height=0)
    win.set_wm_normal_hints(**hints)
    win.map()
    d.flush()
    while True:
        while d.pending_events():
            d.next_event()
        time.sleep(0.05)


if __name__ == "__main__":
    main()
