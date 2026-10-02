# SPDX-License-Identifier: GPL-3.0-or-later

"""GEN.BMP -- the generic window frame Winamp 2.9+ uses for plugin windows.

The titlebar caps carry their own variable-width font: two 7px rows at y=88
(selected) and y=96 (normal), glyphs starting at x=1, each separated by a
delimiter pixel whose color is taken from x=0 of the same row.
"""

from .. import palette as P, draw, art as icons
from ._common import sheet, win_button, frame_piece

DELIMITER = P.error          # never appears inside a glyph
TOP_PIECES = [0, 26, 52, 78, 104, 130]
CLOSE_IN_CORNER = 14         # the close button's x inside the 25px top-right piece

# The piece is 20px tall, the close button sits at y=3 and the title glyphs at
# y=4. The bar is painted 15px tall, so its center is row 7, and the other five
# rows are frame. Must equal pledit.BAR_H.
BAR_H = 15
STRIP_H = 20


def build():
    c = sheet("GEN.BMP", P.window)

    for y, fill, ink in ((0, P.primary_container, P.on_primary_container),
                         (21, P.tray, P.ink_dim)):
        _top(c, y, fill, ink)

    # Bottom and side pieces, outlined in the window's edge color like the
    # playlist's bottom bar. BOTTOM_LEFT at 42 and BOTTOM_RIGHT at 57 each
    # carry one of the window's two bottom corners; the fill between them and
    # the sides above are square.
    frame_piece(c, 0, 42, 125, 14, P.tray, corners=("bl",), skip="rt", edge=P.edge)
    frame_piece(c, 0, 57, 125, 14, P.tray, corners=("br",), skip="lt", edge=P.edge)
    c.rect(127, 72, 25, 14, P.tray)
    c.hline(127, 85, 25, P.edge)
    for x, y, w, h in ((127, 42, 11, 29), (158, 42, 11, 24)):
        c.rect(x, y, w, h, P.window)
        c.vline(x, y, h, P.edge)
    for x, y, w, h in ((139, 42, 8, 29), (170, 42, 8, 24)):
        c.rect(x, y, w, h, P.window)
        c.vline(x + w - 1, y, h, P.edge)

    # the pressed close button
    win_button(c, 148, 42, icons.GLYPH_CLOSE, "press", bg=P.primary_container)

    # Variable-width titlebar caps.
    for y, fill, ink in ((88, P.primary_container, P.on_primary_container),
                         (96, P.tray, P.ink_dim)):
        c.rect(0, y, 194, 7, fill)
        c.vline(0, y, 7, DELIMITER)
        draw.gen_text(c, 1, y, "abcdefghijklmnopqrstuvwxyz", ink,
                      delimiter=DELIMITER)
    return c



def _top(c, y, fill, ink):
    """One title row, drawn like the playlist's.

    Six 25px pieces the player tiles to the window's width: the two corners,
    the caps either side of the title and the two fills. The bar is outlined
    in hairline and rounded at the window's corners by `frame_piece`; the five
    rows below it are frame. Joins between pieces carry no line.
    """
    last = TOP_PIECES[-1]
    frame_piece(c, 0, y, 25, BAR_H, fill, corners=("tl",), skip="rb", edge=P.hairline)
    for x in TOP_PIECES[1:-1]:
        c.rect(x, y, 25, BAR_H, fill)
        c.hline(x, y, 25, P.hairline)
    frame_piece(c, last, y, 25, BAR_H, fill, corners=("tr",), skip="lb", edge=P.hairline)

    # the frame between the bar and the window below it
    for x in TOP_PIECES:
        c.rect(x, y + BAR_H, 25, STRIP_H - BAR_H, P.window)
        c.hline(x, y + BAR_H, 25, P.hairline)
    c.vline(0, y + BAR_H, STRIP_H - BAR_H, P.edge)
    c.vline(last + 24, y + BAR_H, STRIP_H - BAR_H, P.edge)

    # The resting X is drawn into the corner piece: Winamp draws
    # GEN_CLOSE_SELECTED over the art only while the button is held. 14 = 25 -
    # GenWindow.CLOSE_INSET_RIGHT (2) - CLOSE_W (9), and 3 is CLOSE_INSET_TOP;
    # the playlist uses the same offsets (pledit: 153 + 14, y + 3).
    win_button(c, last + CLOSE_IN_CORNER, y + 3, icons.GLYPH_CLOSE, "rest", ink)
