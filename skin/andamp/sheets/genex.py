# SPDX-License-Identifier: GPL-3.0-or-later

"""GENEX.BMP -- media-library chrome plus the palette row Winamp reads from
row y=0, every other pixel starting at x=48.
"""

from .. import palette as P
from ._common import sheet, container, button, R_PILL, R_CHAMFER
from spec.sprites import GENEX_COLOR_SLOTS

COLORS = {
    "item_bg": P.lcd, "item_fg": P.ink, "window_bg": P.window,
    "button_text": P.ink, "window_text": P.ink, "divider": P.hairline,
    "selection": P.selection, "list_header_bg": P.tray,
    "list_header_text": P.ink_dim, "list_header_frame_top": P.edge,
    "list_header_frame_middle": P.hairline, "list_header_frame_bottom": P.edge,
    "list_header_dead": P.tray_low, "scrollbar_fg": P.accent,
    "scrollbar_bg": P.tray_low, "scrollbar_fg_pressed": P.pressed_container,
    "scrollbar_bg_pressed": P.tray, "scrollbar_dead": P.window,
    "listview_text_hl": P.ink, "listview_bg_hl": P.tray,
    "listview_text_sel": P.on_primary, "listview_bg_sel": P.selection,
}

ARROWS = {
    "UP": ["....#....", "...###...", "..#####..", ".#######."],
    "DOWN": [".#######.", "..#####..", "...###...", "....#...."],
    "LEFT": ["...#", "..##", ".###", "####", ".###", "..##", "...#"],
    "RIGHT": ["#...", "##..", "###.", "####", "###.", "##..", "#..."],
}


def build():
    c = sheet("GENEX.BMP", P.window)

    # Button background, sliced left/center/right. The pressed sprite is the
    # same band shifted down one row upstream, so row 15 continues the shape.
    container(c, 0, 0, 47, 16, P.tray_low, radius=R_PILL)
    c.rframe(0, 0, 47, 16, P.edge, radius=R_PILL)

    for x, y, key, pressed in ((0, 31, "UP", False), (14, 31, "DOWN", False),
                               (28, 31, "UP", True), (42, 31, "DOWN", True),
                               (0, 45, "LEFT", False), (14, 45, "RIGHT", False),
                               (28, 45, "LEFT", True), (42, 45, "RIGHT", True)):
        fill, ink = ((P.primary, P.on_primary) if pressed
                     else (P.tray_low, P.ink))
        button(c, x, y, 14, 14, fill=fill, ink=ink, mask=ARROWS[key],
               radius=R_CHAMFER, edge=None if pressed else P.edge)

    for x, y, w, h, pressed in ((56, 31, 14, 28, False), (70, 31, 14, 28, True),
                                (84, 31, 28, 14, False), (84, 45, 28, 14, True)):
        fill = P.pressed_container if pressed else P.accent
        container(c, x, y, w, h, fill, radius=R_PILL)

    # Palette row: one pixel per slot, every other column from x=48.
    for i, slot in enumerate(GENEX_COLOR_SLOTS):
        c.px(48 + i * 2, 0, COLORS[slot])
    return c
