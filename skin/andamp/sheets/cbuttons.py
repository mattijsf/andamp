# SPDX-License-Identifier: GPL-3.0-or-later

"""CBUTTONS.BMP -- the transport row as a button group.

Cells are adjacent, so each button is inset 1px inside its cell. Play is the
emphasized member: a filled circle with `play: circle` (dark, light), a filled
pill otherwise. Every pressed state takes a bright fill, and every one but the
circle takes the `R_SELECTED` corner.
"""

from .. import palette as P, art as icons, draw
from .. import shapes
from ._common import sheet, button, R_KEY, R_PILL, R_SELECTED

CELLS = [
    ("PREVIOUS", 0, 23, 18, "PREVIOUS", False),
    ("PLAY", 23, 23, 18, "PLAY", True),
    ("PAUSE", 46, 23, 18, "PAUSE", False),
    ("STOP", 69, 23, 18, "STOP", False),
    ("NEXT", 92, 23, 18, "NEXT", False),
    ("EJECT", 114, 22, 16, "EJECT", False),
]
CIRCLE_PLAY = P.STYLE["play"] == "circle"


def _emphasised(c, x, y, w, h, mask, fill, ink, radius):
    """Given the whole cell. The circle takes all of it, so it stands a pixel
    proud of the keys either side. A pill-styled play button keeps the 1px
    inset."""
    if CIRCLE_PLAY:
        # Diameter = the cell height, so the circle spans every row of the cell.
        d = h
        ox, oy = x + (w - d) // 2, y
        c.stamp(ox, oy, shapes.disc(d), {"#": fill})
        # The play triangle's mass sits left of its bounding box, so it is
        # nudged 1px right.
        draw.icon(c, ox, oy, d, d, mask, ink, dx=1)
    else:
        button(c, x + 1, y + 1, w - 2, h - 2, fill=fill, ink=ink, mask=mask,
               radius=radius)


def build():
    c = sheet("CBUTTONS.BMP", P.tray)
    for name, x, w, h, icon_name, emphasised in CELLS:
        mask = getattr(icons, icon_name)
        active_y = 18 if h == 18 else 16
        # NEXT_ACTIVE is 22px wide upstream, not 23; drawing it 23 wide bleeds
        # into the eject cell.
        aw = 22 if name == "NEXT" else w
        if emphasised:
            _emphasised(c, x, 0, w, h, mask, P.hero_fill, P.hero_ink, R_PILL)
            _emphasised(c, x, active_y, aw, h, mask,
                        P.pressed_container, P.on_primary, R_SELECTED)
        else:
            button(c, x + 1, 1, w - 2, h - 2, fill=P.control_fill,
                   ink=P.control_ink, mask=mask, radius=R_KEY, edge=P.control_edge)
            button(c, x + 1, active_y + 1, aw - 2, h - 2, fill=P.primary,
                   ink=P.on_primary, mask=mask, radius=R_SELECTED)
    return c
