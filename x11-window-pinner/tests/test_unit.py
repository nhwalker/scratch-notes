"""Unit tests that need no X server: glob matcher and config validation."""

import json
import os
import unittest

from pinwin.config import ConfigError, first_match, parse_rules
from pinwin.glob import ANY, STAR, glob_match, tokenize

HERE = os.path.dirname(__file__)


class GlobTests(unittest.TestCase):
    def test_shared_vectors(self):
        # Same file is meant to drive the future C implementation's tests.
        with open(os.path.join(HERE, "glob_cases.json"), encoding="utf-8") as f:
            cases = json.load(f)
        self.assertGreater(len(cases), 40)
        for pattern, s, expected in cases:
            with self.subTest(pattern=pattern, s=s):
                self.assertIs(glob_match(pattern, s), expected)

    def test_tokenize(self):
        self.assertEqual(tokenize("a*b"), ["a", STAR, "b"])
        self.assertEqual(tokenize("a**b"), ["a", "*", "b"])
        self.assertEqual(tokenize("??"), ["?"])
        self.assertEqual(tokenize("???"), ["?", ANY])
        self.assertEqual(tokenize("***"), ["*", STAR])

    def test_no_pathological_blowup(self):
        # Iterative algorithm: this must be fast (backtracking recursion is not).
        self.assertFalse(glob_match("*a" * 30 + "b", "a" * 5000))


class ConfigTests(unittest.TestCase):
    def ok(self, data):
        return parse_rules(data)

    def bad(self, data, msg):
        with self.assertRaises(ConfigError) as cm:
            parse_rules(data)
        self.assertIn(msg, str(cm.exception))

    def test_minimal(self):
        (r,) = self.ok([{"match": {"title": "A"}, "geometry": [0, 0, 10, 10]}])
        self.assertTrue(r.visible)
        self.assertFalse(r.lock)
        self.assertEqual(r.geometry, (0, 0, 10, 10))

    def test_hidden_needs_no_geometry(self):
        (r,) = self.ok([{"match": {"class": "X"}, "visible": False}])
        self.assertIsNone(r.geometry)

    def test_errors(self):
        self.bad({}, "top level must be a JSON list")
        self.bad([{"geometry": [0, 0, 1, 1]}], "'match' must be a non-empty")
        self.bad([{"match": {}, "geometry": [0, 0, 1, 1]}], "'match' must be a non-empty")
        self.bad([{"match": {"name": "x"}, "geometry": [0, 0, 1, 1]}], "unknown match keys")
        self.bad([{"match": {"title": 3}, "geometry": [0, 0, 1, 1]}], "must be a string")
        self.bad([{"match": {"title": "x"}}], "'geometry' is required")
        self.bad([{"match": {"title": "x"}, "geometry": [0, 0, 1]}], "[x, y, width, height]")
        self.bad([{"match": {"title": "x"}, "geometry": [0, 0, 0, 5]}], "must be > 0")
        self.bad([{"match": {"title": "x"}, "geometry": [0, 0, 1.5, 5]}], "integers")
        self.bad([{"match": {"title": "x"}, "geometry": [0, 0, True, 5]}], "integers")
        self.bad([{"match": {"title": "x"}, "geometry": [0, 0, 1, 1], "visible": "no"}],
                 "true or false")
        self.bad([{"match": {"title": "x"}, "geometry": [0, 0, 1, 1], "extra": 1}],
                 "unknown keys")

    def test_and_semantics_and_first_rule_wins(self):
        rules = self.ok([
            {"match": {"class": "XTerm", "title": "log*"}, "geometry": [0, 0, 1, 1]},
            {"match": {"class": "XTerm"}, "geometry": [5, 5, 1, 1]},
        ])
        self.assertEqual(first_match(rules, "log: x", "xterm", "XTerm").index, 0)
        self.assertEqual(first_match(rules, "shell", "xterm", "XTerm").index, 1)
        self.assertIsNone(first_match(rules, "log: x", "xterm", "UXTerm"))
        # Missing property never matches a field that is given.
        self.assertIsNone(first_match(rules, None, None, None))

    def test_negative_position_allowed(self):
        self.ok([{"match": {"title": "x"}, "geometry": [-10, -20, 1, 1]}])


class ClaimTests(unittest.TestCase):
    """Pinner._update_claims: one window per rule, sticky claims."""

    def make(self):
        from pinwin.daemon import Pinner
        p = Pinner.__new__(Pinner)      # no X connection needed for this logic
        p.claims, p.warned_extra = {}, set()
        p.rules = parse_rules([{"match": {"title": "A*"}, "geometry": [0, 0, 1, 1]},
                               {"match": {"title": "B*"}, "geometry": [0, 0, 1, 1]}])
        return p

    def test_first_candidate_claimed_extras_warned_once(self):
        p = self.make()
        with self.assertLogs("pinwin", "WARNING") as cm:
            p._update_claims({0: [10, 11], 1: [20]})
            p._update_claims({0: [10, 11], 1: [20]})
        self.assertEqual(p.claims, {0: 10, 1: 20})
        self.assertEqual(len([m for m in cm.output if "0xb" in m]), 1)

    def test_claim_is_sticky_regardless_of_order(self):
        p = self.make()
        p._update_claims({0: [10, 11]})
        p._update_claims({0: [11, 10]})       # stacking order changed
        self.assertEqual(p.claims[0], 10)

    def test_release_when_window_gone_or_unmatched(self):
        p = self.make()
        p._update_claims({0: [10, 11]})
        p._update_claims({0: [11]})           # 10 closed / title changed
        self.assertEqual(p.claims[0], 11)
        p._update_claims({})
        self.assertEqual(p.claims, {})

    def test_extra_warned_again_after_it_reappears(self):
        p = self.make()
        p._update_claims({0: [10, 11]})
        p._update_claims({0: [10]})
        with self.assertLogs("pinwin", "WARNING"):
            p._update_claims({0: [10, 11]})


if __name__ == "__main__":
    unittest.main()
