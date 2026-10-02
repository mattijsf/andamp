# SPDX-License-Identifier: GPL-3.0-or-later

"""TEXT.BMP -- the 5x6 bitmap face, 31 columns by 3 rows."""

from .. import palette as P
from ..glyphs import FONT_5x6
from ._common import sheet
from spec.sprites import TEXT_ROWS, CHAR_W, CHAR_H


def build():
    c = sheet("TEXT.BMP", P.lcd)
    for row, line in enumerate(TEXT_ROWS):
        for col, ch in enumerate(line):
            if ch == "\x00":
                continue
            rows = FONT_5x6.get(ch) or FONT_5x6.get(ch.lower()) or FONT_5x6[" "]
            c.stamp(col * CHAR_W, row * CHAR_H, rows, {"#": P.ink})
    return c
