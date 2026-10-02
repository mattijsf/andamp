# SPDX-License-Identifier: GPL-3.0-or-later

"""TITLEBAR.BMP -- title bars, window buttons, clutter bar, shade mode.

The active title bar is filled with `primary_container`, the idle one with
`tray`. Window buttons are drawn by `_common.win_button`.
"""

from .. import palette as P, draw
from ._common import (sheet, container, win_button, track,
                      R_CHAMFER, R_WINDOW)

BAR_W = 14
WORDMARK = "andamp"

# (sprite y, fill, ink, label)
BARS = [
    (0, P.primary_container, P.on_primary_container, WORDMARK),    # selected
    (15, P.tray, P.ink_dim, WORDMARK),                             # idle
    (57, P.tertiary_container, P.on_tertiary_container, "andamp!"),  # egg sel
    (72, P.tray, P.level, "andamp!"),                              # egg idle
]
SHADE = [(29, P.primary_container, P.on_primary_container),
         (42, P.tray, P.ink_dim)]

def _paint_bar_buttons(c, y, ink, shaded=False):
    """In shade mode the shade button restores the window, so it shows the
    down chevron; everywhere else it collapses and shows the up one."""
    from .. import art as icons
    shade_glyph = icons.GLYPH_EXPAND if shaded else icons.GLYPH_SHADE
    for wx, mask in ((6, icons.GLYPH_OPTIONS), (244, icons.GLYPH_MINIMIZE),
                     (254, shade_glyph), (264, icons.GLYPH_CLOSE)):
        win_button(c, 27 + wx, y + 3, mask, "rest", ink)


def build():
    from .. import art as icons
    c = sheet("TITLEBAR.BMP", P.window)
    # Everything left of x=27 (the ten 9x9 window buttons and the shade
    # position slider) is composited onto a title bar. There is one set of
    # sprites for two bar colors, so their bed is the focused bar's color.
    c.rect(0, 0, 27, 43, P.primary_container)

    for y, fill, ink, label in BARS:
        # clamp=False: this 14px strip is a slice of a 116px window, so its
        # corner is the window's arc, the one REGION.TXT traces. Clamped to its
        # own height the arc would be tighter and sit inside the region's.
        c.rrect(27, y, 275, BAR_W, fill, radius=R_WINDOW, corners=('tl', 'tr'),
                clamp=False)
        c.rframe(27, y, 275, BAR_W, P.hairline, radius=R_WINDOW,
                 corners=('tl', 'tr'), clamp=False)
        draw.text_centered(c, 27, y, 275, BAR_W, label, ink, spacing=1)
        _paint_bar_buttons(c, y, ink)

    # Shade mode: the same bar, with a well for the mini time display and the
    # transport glyphs.
    for y, fill, ink in SHADE:
        # Shade mode is the whole window, so all four corners round, as
        # REGION.TXT's WindowShade silhouette does.
        c.rrect(27, y, 275, BAR_W, fill, radius=R_WINDOW, clamp=False)
        c.rframe(27, y, 275, BAR_W, P.hairline, radius=R_WINDOW, clamp=False)
        # Window-space boxes from main-window.css, offset by the sprite's x=27
        # origin.
        container(c, 27 + 125, y + 2, 29, 10, P.lcd, radius=R_CHAMFER)
        # Transport. There is no sprite for the shade-mode transport (Webamp's
        # CSS is `background: none` on all six), so the glyphs are drawn into
        # this bitmap. Boxes come from main-window.css: previous 169w7,
        # play 176w10, pause 186w9, stop 195w9, next 204w10, eject 215w10. The
        # masks are all seven rows deep, so they share a baseline.
        for gx, mask in ((169, icons.SHADE_PREVIOUS), (179, icons.SHADE_PLAY),
                         (187, icons.SHADE_PAUSE), (196, icons.SHADE_STOP),
                         (206, icons.SHADE_NEXT), (217, icons.SHADE_EJECT)):
            draw.icon(c, 27 + gx, y + 4, len(mask[0]), 7, mask, ink)
        # No well for the position slider: MAIN_SHADE_POSITION_BACKGROUND is
        # its own 17x7 track sprite and covers this area.
        draw.text(c, 27 + 18, y + 4, "andamp", ink)   # clears the options button at x=6
        _paint_bar_buttons(c, y, ink, shaded=True)

    # Window buttons. Options/minimize/close stack their depressed state
    # vertically (y+9); the two shade buttons put theirs beside (x+9).
    for (bx, by), (dx, dy), mask in (
        ((0, 0), (0, 9), icons.GLYPH_OPTIONS),
        ((9, 0), (9, 9), icons.GLYPH_MINIMIZE),
        ((18, 0), (18, 9), icons.GLYPH_CLOSE),
        ((0, 18), (9, 18), icons.GLYPH_SHADE),
        # The *_SELECTED pair is the shade-mode button: it restores, so it
        # takes the down chevron.
        ((0, 27), (9, 27), icons.GLYPH_EXPAND),
    ):
        win_button(c, bx, by, mask, "hover")
        win_button(c, dx, dy, mask, "press")

    # Shade-mode position slider. The 17x7 sprite is the element box (window
    # 226,4), so the groove is drawn inside it with a fill of its own, the same
    # as the mini-time well beside it. The 3x7 thumb fills the 7px element
    # height, so both center on sprite row 3.
    container(c, 0, 36, 17, 7, P.tray_low, radius=R_CHAMFER)
    track(c, 0, 39, 17, 0, P.accent)
    for x, colour in ((17, P.accent), (20, P.accent), (23, P.accent)):
        c.rrect(x, 36, 3, 7, colour, radius=1)

    # Clutter bar: 8x43 strip of five one-letter toggles.
    #
    # Winamp's five cells are 8, 7, 7, 8 and 7 rows tall at y = 3, 11, 18, 25
    # and 33. Centering a 6-row glyph in each would leave gaps of 1, 1, 2 and 1
    # rows, so the offsets below are chosen to make every gap one row. The
    # column then sits 5 rows below the top of the bar and 4 above the bottom.
    letters = [("o", 3, 8, 2), ("a", 11, 7, 1), ("i", 18, 7, 1),
               ("d", 25, 8, 1), ("v", 33, 7, 0)]
    for x, ink in ((304, P.ink_dim), (312, P.hairline)):
        container(c, x, 0, 8, 43, P.tray_low, radius=R_CHAMFER)
        for ch, oy, _oh, dy in letters:
            draw.text_centered(c, x, oy + dy, 8, 6, ch, ink)
    # Lit variants, one per button, at the coordinates Webamp reads them from.
    # The player draws these at the cell, so each glyph keeps the offset inside
    # its sprite that it has in the bar and does not move when it lights.
    for (sx, sy, sw, sh), ch, dy in (((304, 47, 8, 8), "o", 2),
                                     ((312, 55, 8, 7), "a", 1),
                                     ((320, 62, 8, 7), "i", 1),
                                     ((328, 69, 8, 8), "d", 1),
                                     ((336, 77, 8, 7), "v", 0)):
        c.rrect(sx, sy, sw, sh, P.primary, radius=1)
        draw.text_centered(c, sx, sy + dy, sw, 6, ch, P.on_primary)
    return c
