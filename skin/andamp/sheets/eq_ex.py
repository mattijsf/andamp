# SPDX-License-Identifier: GPL-3.0-or-later

"""EQ_EX.BMP -- equalizer shade mode (Winamp 2.9+)."""

from .. import palette as P, draw, art as icons
from ._common import sheet, win_button, track, R_WINDOW


def build():
    c = sheet("EQ_EX.BMP", P.window)
    # The slider handles and the four 9x9 buttons all land on the equalizer
    # shade bar, so their bed is the focused bar's color, as in TITLEBAR.
    c.rect(0, 29, 21, 28, P.primary_container)
    for y, fill, ink in ((0, P.primary_container, P.on_primary_container),
                         (15, P.tray, P.ink_dim)):
        c.rrect(0, y, 275, 14, fill, radius=R_WINDOW, clamp=False)
        c.rframe(0, y, 275, 14, P.hairline, radius=R_WINDOW, clamp=False)
        draw.text(c, 6, y + 4, "equalizer", ink)
        for bx, mask in ((254, icons.GLYPH_EXPAND), (264, icons.GLYPH_CLOSE)):
            win_button(c, bx, y + 3, mask, "rest", ink)
        # The format has no track sprite for these two sliders, only the 3x7
        # handle pieces, so the track is painted into the shade background.
        # Element boxes: volume 61,4 97x6 and balance 164,4 43x6. A 7px thumb
        # in a 6px box at top:4 centers on row 7, so the rail is drawn there.
        track(c, 61, y + 7, 97, 0, P.accent)
        track(c, 164, y + 7, 43, 0, P.accent)

    # These three are the slider handle, one per value region: left of center,
    # centered, right of center.
    for base in (1, 11):
        for k in range(3):
            c.rrect(base + k * 3, 30, 3, 7, P.accent, radius=1)

    # In Webamp the normal equalizer window's shade button resolves to
    # MAXIMIZE_BUTTON_ACTIVE and shade mode resolves to MINIMIZE_BUTTON_ACTIVE,
    # the reverse of what the names suggest. So (1,38) collapses and takes the
    # up chevron, (1,47) restores and takes the down one.
    for x, y, mask, state in ((1, 38, icons.GLYPH_SHADE, "press"),
                              (1, 47, icons.GLYPH_EXPAND, "press"),
                              (11, 47, icons.GLYPH_CLOSE, "press")):
        win_button(c, x, y, mask, state)
    # The resting sprite, like EQ_CLOSE_BUTTON. The _ACTIVE sprites above are
    # pressed states.
    win_button(c, 11, 38, icons.GLYPH_CLOSE, "rest", P.on_primary_container,
               bg=P.primary_container)
    return c
