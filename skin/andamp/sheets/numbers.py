# SPDX-License-Identifier: GPL-3.0-or-later

"""NUMBERS.BMP and NUMS_EX.BMP -- the time display digits, in the theme's
digit face (seven-segment or geometric).

Both are emitted. NUMS_EX has blank and minus cells of its own. NUMBERS has
none, so Webamp slices MINUS_SIGN out of the "2" glyph at (20, 6, 5, 1), where
both faces have a solid 5px bar.
"""

from .. import palette as P, draw
from ._common import sheet


def _digits(c):
    for d in range(10):
        draw.digit(c, d * 9, 0, str(d), P.accent)


def build():
    c = sheet("NUMBERS.BMP", P.lcd)
    _digits(c)
    return c


def build_ex():
    c = sheet("NUMS_EX.BMP", P.lcd)
    _digits(c)
    # x=90 stays blank; x=99 is the minus, on the rows of the seven-segment
    # middle bar.
    c.rect(99 + 2, 6, 5, 2, P.accent)
    return c
