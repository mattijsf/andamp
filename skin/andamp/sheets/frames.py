# SPDX-License-Identifier: GPL-3.0-or-later

"""VIDEO.BMP, AVS.BMP and MB.BMP -- 234x119 auxiliary window frames.

Winamp's sprite maps for these three are not part of the Webamp table, so they
are drawn as plain tonal frames in the theme's colors.
"""

from .. import palette as P, draw, art as icons
from ._common import sheet, container, button, R_CHAMFER, R_PILL, R_NONE, R_WINDOW


def build(name, title):
    c = sheet(name, P.window)
    c.rframe(0, 0, 234, 119, P.edge, radius=R_WINDOW)
    c.rrect(1, 1, 232, 14, P.tray, radius=R_WINDOW, corners=("tl", "tr"))
    c.hline(1, 15, 232, P.hairline)
    draw.text(c, 6, 5, title, P.ink_dim)
    button(c, 222, 4, 9, 9, fill=P.tray_low, ink=P.ink,
           mask=icons.GLYPH_CLOSE, radius=R_PILL, edge=P.edge)
    container(c, 4, 19, 226, 82, P.vis_floor, radius=R_NONE, edge=P.card_edge)
    container(c, 4, 104, 226, 11, P.tray_low, radius=R_CHAMFER)
    return c


def build_video():
    return build("VIDEO.BMP", "video")


def build_avs():
    return build("AVS.BMP", "visualization")


def build_mb():
    return build("MB.BMP", "browser")
