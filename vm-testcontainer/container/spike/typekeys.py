#!/usr/bin/env python3
"""Spike helper: type text on the VM console via QMP send-key (US layout)."""
import sys, time
sys.path.insert(0, "/spike"); import vmctl
SHIFT = {'!':'1','@':'2','#':'3','$':'4','%':'5','^':'6','&':'7','*':'8','(':'9',')':'0','_':'minus','+':'equal',
         '{':'bracket_left','}':'bracket_right','|':'backslash',':':'semicolon','"':'apostrophe','<':'comma','>':'dot','?':'slash','~':'grave_accent'}
PLAIN = {' ':'spc','\n':'ret','-':'minus','=':'equal','[':'bracket_left',']':'bracket_right','\\':'backslash',';':'semicolon',
         "'":'apostrophe',',':'comma','.':'dot','/':'slash','`':'grave_accent'}
def keys(ch):
    if ch.isalpha(): return (['shift'] if ch.isupper() else []) + [ch.lower()]
    if ch.isdigit(): return [ch]
    if ch in PLAIN: return [PLAIN[ch]]
    if ch in SHIFT: return ['shift', SHIFT[ch]]
    raise ValueError(repr(ch))
c = vmctl.qmp()
for ch in sys.argv[1].encode().decode('unicode_escape'):
    c.call({"execute": "send-key", "arguments": {"keys": [{"type": "qcode", "data": k} for k in keys(ch)]}})
    time.sleep(0.03)
