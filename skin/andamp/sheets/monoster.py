# SPDX-License-Identifier: GPL-3.0-or-later

"""MONOSTER.BMP -- channel-mode indicators. Row y=0 is lit, y=12 is unlit.

State is shown by an indicator bar under the lit label, the way a Material tab
marks its selection. A filled chip does not fit ("stereo" is 24px of ink in a
29px cell), and color alone is weak: in the dark scheme `primary` and the 38%
disabled blend are 2.2:1 apart.
"""

from .. import palette as P, draw
from ._common import sheet

# "stereo" is 30px at the normal 5px pitch and the cell is 29, so both labels
# tighten to a 4px pitch.
SPACING = -1
CELLS = [("stereo", 0, 29), ("mono", 29, 27)]


def build():
    c = sheet("MONOSTER.BMP", P.lcd)
    for label, x, w in CELLS:
        _, _, iw, _ = draw.ink_extents(label, spacing=SPACING)
        bar_x = x + 1 + ((w - 2) - iw) // 2
        for y, ink, lit in ((0, P.accent, True), (12, P.indicator_off, False)):
            draw.text_centered(c, x + 1, y, w - 2, 10, label, ink,
                               spacing=SPACING)
            if lit:
                c.rect(bar_x, y + 9, iw, 2, P.accent)
    return c
