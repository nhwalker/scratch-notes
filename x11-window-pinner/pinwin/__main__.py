"""Command line entry point.

  python3 -m pinwin CONFIG.json            run the daemon (SIGHUP reloads config)
  python3 -m pinwin --list [CONFIG.json]   print managed windows as JSON and exit
  python3 -m pinwin --check CONFIG.json    validate the config file and exit
"""

import argparse
import json
import logging
import sys

from .config import ConfigError, load_rules


def main(argv=None):
    ap = argparse.ArgumentParser(prog="pinwin", description=(
        "Pin X11 windows (matched by title / WM_CLASS) to a geometry, keep them at "
        "the bottom of the stacking order, and optionally hide or lock them. "
        "Built and tested for mwm."))
    ap.add_argument("config", nargs="?", help="JSON config file")
    ap.add_argument("--display", help="X display (default: $DISPLAY)")
    ap.add_argument("--list", action="store_true",
                    help="print managed windows (title, instance, class, geometry) and exit")
    ap.add_argument("--check", action="store_true", help="validate config and exit")
    ap.add_argument("-v", "--verbose", action="count", default=0)
    a = ap.parse_args(argv)

    logging.basicConfig(
        level=logging.WARNING - 10 * min(a.verbose, 2),
        format="%(asctime)s %(name)s %(levelname)s %(message)s")

    if a.check:
        if not a.config:
            ap.error("--check needs a config file")
        try:
            rules = load_rules(a.config)
        except (OSError, ConfigError) as e:
            print("invalid: %s" % e, file=sys.stderr)
            return 1
        print("ok: %d rules" % len(rules))
        return 0

    if not a.config and not a.list:
        ap.error("a config file is required (or use --list)")

    from .daemon import Pinner   # import Xlib only when needed
    from Xlib import error as xerror

    try:
        if a.list:
            p = Pinner(a.config, a.display)
            print(json.dumps(p.list_windows(), indent=2, ensure_ascii=False))
            return 0
        Pinner(a.config, a.display).run()
        return 0
    except (OSError, ConfigError) as e:
        print("error: %s" % e, file=sys.stderr)
        return 1
    except xerror.DisplayError as e:
        print("error: cannot open display: %s" % e, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
