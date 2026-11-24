"""Tiny glob matcher shared (by specification) with the future C port.

Pattern syntax
--------------
  *    matches any sequence of characters, including the empty sequence
  ?    matches exactly one character
  **   matches one literal '*'
  ??   matches one literal '?'
  any other character matches itself (case-sensitive)

The pattern must match the WHOLE string.

Tokenizing is strictly left to right, so "***" is a literal '*' followed by
a wildcard '*'. One consequence is that "wildcard star immediately followed by
a literal star" (or "? wildcard followed by a literal ?") cannot be written.

"Character" means one Unicode code point. The C port must therefore decode
UTF-8 when it consumes '?' or a literal, rather than working byte by byte.
tests/glob_cases.json holds the shared test vectors, including non-ASCII ones.
"""

STAR = object()  # wildcard: any sequence
ANY = object()   # wildcard: any single character


def tokenize(pattern):
    """Turn a pattern into a list of STAR, ANY, or 1-character literal strings."""
    toks = []
    i, n = 0, len(pattern)
    while i < n:
        c = pattern[i]
        if c in "*?":
            if i + 1 < n and pattern[i + 1] == c:
                toks.append(c)          # doubled -> literal
                i += 2
                continue
            toks.append(STAR if c == "*" else ANY)
        else:
            toks.append(c)
        i += 1
    return toks


def match_tokens(toks, s):
    """Classic iterative wildcard match with single-star backtracking.

    Runs in O(len(toks) * len(s)) worst case, with no recursion, which keeps
    the C version simple and bounded.
    """
    p = i = 0
    star_p = -1   # index of the most recent STAR token
    star_i = 0    # position in s where that STAR started matching
    while i < len(s):
        if p < len(toks) and toks[p] is STAR:
            star_p, star_i = p, i
            p += 1
        elif p < len(toks) and (toks[p] is ANY or toks[p] == s[i]):
            p += 1
            i += 1
        elif star_p != -1:
            # Let the last STAR absorb one more character and retry.
            star_i += 1
            i = star_i
            p = star_p + 1
        else:
            return False
    while p < len(toks) and toks[p] is STAR:
        p += 1
    return p == len(toks)


def glob_match(pattern, s):
    return match_tokens(tokenize(pattern), s)
