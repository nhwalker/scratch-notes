"""Config file loading and validation.

The file is a JSON list of rules:

  [
    {
      "match":    {"title": "glob", "instance": "glob", "class": "glob"},
      "geometry": [x, y, width, height],
      "visible":  true,
      "lock":     false
    }
  ]

* match:    at least one of title / instance / class; every field given must
            match (AND). "instance" and "class" are the two halves of WM_CLASS.
* geometry: x, y = top-left of the window-manager FRAME (outer corner);
            width, height = size of the application's client area.
            This is the same convention as the X "-geometry" option.
            Required when visible is true; optional (ignored) when false.
* visible:  default true. false = fully withdrawn (unmapped, no icon).
* lock:     default false. true = ask mwm to remove the move, resize,
            minimize and maximize functions from the window.

Matched windows are always kept at the bottom of the stacking order.
If one window matches several rules, the first rule in the file wins.
Each rule manages at most one window (see Pinner._update_claims).
"""

import json

from .glob import match_tokens, tokenize

MATCH_KEYS = ("title", "instance", "class")
RULE_KEYS = {"match", "geometry", "visible", "lock"}


class ConfigError(ValueError):
    pass


class Rule:
    def __init__(self, index, match, geometry, visible, lock):
        self.index = index              # position in file; also stacking order
        self.match = match              # dict key -> pattern string
        self._toks = {k: tokenize(v) for k, v in match.items()}
        self.geometry = geometry        # (x, y, w, h) or None
        self.visible = visible
        self.lock = lock

    def matches(self, title, instance, wm_class):
        values = {"title": title, "instance": instance, "class": wm_class}
        for key, toks in self._toks.items():
            v = values[key]
            if v is None or not match_tokens(toks, v):
                return False
        return True

    def __repr__(self):
        return "Rule#%d(%s)" % (self.index, self.match)


def _is_int(v):
    return isinstance(v, int) and not isinstance(v, bool)


def parse_rules(data):
    if not isinstance(data, list):
        raise ConfigError("top level must be a JSON list of rules")
    rules = []
    for idx, r in enumerate(data):
        where = "rule %d" % idx
        if not isinstance(r, dict):
            raise ConfigError("%s: must be an object" % where)
        unknown = set(r) - RULE_KEYS
        if unknown:
            raise ConfigError("%s: unknown keys %s" % (where, sorted(unknown)))

        m = r.get("match")
        if not isinstance(m, dict) or not m:
            raise ConfigError("%s: 'match' must be a non-empty object" % where)
        bad = set(m) - set(MATCH_KEYS)
        if bad:
            raise ConfigError("%s: unknown match keys %s (allowed: %s)"
                              % (where, sorted(bad), list(MATCH_KEYS)))
        for k, v in m.items():
            if not isinstance(v, str):
                raise ConfigError("%s: match.%s must be a string" % (where, k))

        visible = r.get("visible", True)
        lock = r.get("lock", False)
        for name, v in (("visible", visible), ("lock", lock)):
            if not isinstance(v, bool):
                raise ConfigError("%s: '%s' must be true or false" % (where, name))

        geom = r.get("geometry")
        if geom is None:
            if visible:
                raise ConfigError("%s: 'geometry' is required when visible" % where)
        else:
            if (not isinstance(geom, list) or len(geom) != 4
                    or not all(_is_int(g) for g in geom)):
                raise ConfigError("%s: 'geometry' must be [x, y, width, height] integers"
                                  % where)
            if geom[2] <= 0 or geom[3] <= 0:
                raise ConfigError("%s: width and height must be > 0" % where)
            geom = tuple(geom)

        rules.append(Rule(idx, dict(m), geom, visible, lock))
    return rules


def load_rules(path):
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
    except json.JSONDecodeError as e:
        raise ConfigError("%s: invalid JSON: %s" % (path, e)) from e
    return parse_rules(data)


def first_match(rules, title, instance, wm_class):
    for r in rules:
        if r.matches(title, instance, wm_class):
            return r
    return None
