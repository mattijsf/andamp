# SPDX-License-Identifier: GPL-3.0-or-later

"""PLAYPAUS.BMP -- the 9x9 transport state indicator, plus the 3px work strip.

NOT_WORKING and WORKING are nominally 9x9 at x=36 and x=39 and therefore
overlap and overhang the 42px sheet; Webamp shows only the leading 3 columns
of each, so only x=36..41 is ever painted.
"""

from .. import palette as P, art as icons, draw
from ._common import sheet

STATES = [(0, icons.SMALL_PLAY, P.accent), (9, icons.SMALL_PAUSE, P.level),
          (18, icons.SMALL_STOP, P.ink_dim)]


def build():
    c = sheet("PLAYPAUS.BMP", P.lcd)
    for x, mask, color in STATES:
        draw.icon(c, x, 0, 9, 9, mask, color)
    # Work indicator: idle at x=36 (3px of bed), busy at x=39 (3px lit).
    c.rect(36, 0, 3, 9, P.lcd)
    c.rect(39, 2, 2, 5, P.level)
    return c
