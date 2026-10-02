# SPDX-License-Identifier: GPL-3.0-or-later

"""EQMAIN.BMP -- the 275x315 equalizer sheet.

Band sliders encode their own level: the 28 background frames fill the track
from the 0 dB center line out to the handle, in `accent` for boost and `level`
for cut.
"""

from .. import palette as P, draw
from ._common import (sheet, container, toggle, vtrack, handle, win_button,
                      R_PILL, R_CHAMFER, R_WINDOW)

# --- layout grid -------------------------------------------------------------
# Element boxes from Webamp's DOM: preamp and every band are 14x63 at y=38, the
# bands start at x=78 on an 18px pitch, and the graph is 113x19 at (86, 17).
#
# RAIL_L/RAIL_R hold the card. The slider columns inside it sit 10px from each
# edge (the preamp column opens at x=20, the band column closes at x=254), and
# the card's own margins are 10px inside the 275px window.
RAIL_L, RAIL_R = 10, 264
CARD_Y, CARD_H = 16, 87                 # 16..102, clear of the title bar
BANDS_X = [78 + 18 * i for i in range(10)]
BAND_W, BAND_Y, BAND_H = 14, 38, 63
PREAMP_X = 21
GRAPH = (85, 17, 115, 19)
LABEL_X, LABEL_W = 45, 22
CELL_W, CELL_H = 15, 65

# The band is a div stack, and the handle travels inside a 62px inner track, so
# the travel is 51 (BAND_H - 11 would give 52). Webamp's handle tops are 0, 12,
# 25, 38, 51 for values 100, 75, 50, 25, 0, so the handle center runs from
# local row 5 (+12 dB) through 30 (0 dB) to 56 (-12 dB).
HANDLE_H = 11
TRAVEL = 51
MID_LOCAL = TRAVEL // 2 + HANDLE_H // 2
LABELS = (("+12", BAND_Y + HANDLE_H // 2),
          ("0", BAND_Y + MID_LOCAL),
          ("-12", BAND_Y + TRAVEL + HANDLE_H // 2))

GRAPH_LINE = P.EQ_GRAPH_LINE


def containers():
    """For the geometry audit: the card, and the two slider columns inside it.
    The columns are not filled, but the audit checks that they are inset
    equally."""
    col_x = BANDS_X[0] - 1
    return [
        ("eq card", RAIL_L, CARD_Y, RAIL_R - RAIL_L + 1, CARD_H),
        ("preamp column", PREAMP_X - 1, BAND_Y, BAND_W + 2, BAND_H),
        ("bands column", col_x, BAND_Y,
         BANDS_X[-1] + BAND_W + 1 - col_x, BAND_H),
    ]


# The slider columns are inset from the card's rails by this much on each side.
WELL_INSET = 10


HANDLE = P.STYLE["sliders"] == "handle"


def _band_frame(c, ox, oy, n):
    """One 15x65 band cell. n=0 is full cut, n=27 is full boost. The fill runs
    from the 0 dB line out to the handle, `accent` for boost and `level` for
    cut. The track is straight: M3 uses the squiggle for progress indicators,
    and these are sliders."""
    # Cell, card and handle sprite must all carry the same background, or the
    # seams between them show as tonal steps.
    c.rect(ox, oy, CELL_W, CELL_H, P.tray_low)
    # Scale line first, so the fill lays over it.
    c.hline(ox, oy + MID_LOCAL, CELL_W, P.hairline)
    frac = n / 27.0
    hi = int((1 - frac) * TRAVEL) + HANDLE_H // 2
    # 28 frames cannot straddle 0 dB exactly; the two nearest land a pixel
    # either side of the line. Snap them, so a flat band shows no fill at all.
    if abs(hi - MID_LOCAL) <= 1:
        hi = MID_LOCAL
    colour = P.accent if hi < MID_LOCAL else P.level
    vtrack(c, ox + 7, oy, BAND_H, MID_LOCAL, hi, colour)


def _thumb(c, x, y, fill):
    if HANDLE:
        # Vertical slider, so the M3 handle lies across the track. The rail
        # continues above and below it: the band cell draws its rail at cell
        # column 7 and Webamp insets the 11px handle by 1, so the rail lands
        # at local column 6.
        c.rect(x, y, 11, 11, P.tray_low)
        vtrack(c, x + 6, y, 3, 0, 2, P.edge)
        vtrack(c, x + 6, y + 8, 3, 0, 2, P.edge)
        # 10 wide. Webamp insets the 11px handle by 1, so a full-width bar
        # would span element columns 1..11 (center 6) while the rail is on
        # columns 6..7 (center 6.5). At 10 wide the handle spans 2..11 and the
        # two centers coincide.
        handle(c, x + 1, y + 3, 10, 5, fill)
    else:
        container(c, x, y, 11, 11, P.tray_low, radius=0)
        container(c, x, y, 11, 11, fill, radius=R_PILL)
        c.hline(x + 3, y + 5, 5, P.on_primary)


def build():
    c = sheet("EQMAIN.BMP", P.window)

    # --- window plate (0,0,275,116) -----------------------------------------
    c.rframe(0, 0, 275, 116, P.edge, radius=R_WINDOW)

    # One card for the whole equalizer body. Two stacked containers cannot
    # both be padded here: the graph ends at y=35 and the bands start at 38,
    # which is not enough room for a gap plus two insets.
    container(c, RAIL_L, CARD_Y, RAIL_R - RAIL_L + 1, CARD_H, P.tray_low,
              radius=R_CHAMFER, edge=P.card_edge)

    # No frame on the graph well. The title bar ends at row 13 and the graph
    # element starts at 17: two rows of gap and the card's top edge, with no
    # row left for a frame.
    container(c, *GRAPH, P.vis_floor, radius=R_CHAMFER)
    for gx in range(GRAPH[0] + 3, GRAPH[0] + GRAPH[2] - 2, 6):  # 0 dB reference
        c.px(gx, GRAPH[1] + GRAPH[3] // 2, P.hairline)

    for label, cy in LABELS:
        ix, _, iw, ih = draw.ink_extents(label)
        draw.text(c, LABEL_X + (LABEL_W - iw) // 2 - ix, cy - ih // 2,
                  label, P.ink_dim)

    # The sliders sit directly on the card; the rails and the 0 dB line carry
    # the structure.
    well_x = BANDS_X[0] - 1
    well_w = BANDS_X[-1] + BAND_W + 1 - well_x
    # The 0 dB line continues across the 4px gaps between band cells.
    c.hline(well_x, BAND_Y + MID_LOCAL, well_w, P.hairline)
    c.hline(PREAMP_X - 1, BAND_Y + MID_LOCAL, BAND_W + 2, P.hairline)
    # Resting faces. Webamp sprites only the hover/selected/pressed states of
    # these three, so the unselected face has to live in the window art.
    # ON ends where AUTO begins, so they are one connected button group; the
    # presets button stands alone.
    for lx, lw, label, position in ((14, 26, "on", "left"),
                                    (40, 32, "auto", "right"),
                                    (217, 44, "presets", "only")):
        toggle(c, lx, 18, lw, 12, selected=False, label=label,
               position=position)

    # --- title bars (idle y=149, selected y=134) ----------------------------
    from .. import art as icons
    for y, fill, ink in ((134, P.primary_container, P.on_primary_container),
                         (149, P.tray, P.ink_dim)):
        c.rrect(0, y, 275, 14, fill, radius=R_WINDOW, corners=('tl', 'tr'),
                clamp=False)
        c.rframe(0, y, 275, 14, P.hairline, radius=R_WINDOW,
                 corners=('tl', 'tr'), clamp=False)
        draw.text_centered(c, 0, y, 275, 14, "equalizer", ink, spacing=1)
        # Painted-in window buttons. Webamp also carves
        # EQ_MAXIMIZE_BUTTON_ACTIVE_FALLBACK out of (254,152), so the idle bar
        # carries a 9x9 button face there.
        for bx, mask in ((254, icons.GLYPH_SHADE), (264, icons.GLYPH_CLOSE)):
            win_button(c, bx, y + 3, mask, "rest", ink)
    # Same on the window plate itself, for Winamp, which composites the
    # equalizer background and lays no title bar sprite over it.
    for bx, mask in ((254, icons.GLYPH_SHADE), (264, icons.GLYPH_CLOSE)):
        win_button(c, bx, 3, mask, "rest", P.ink_dim)

    # Sprite beds. These blocks ship as opaque rectangles and land on something
    # other than the window plate: the two close buttons on the equalizer's
    # title bar, the ON/AUTO row and the presets button on the card. Each bed
    # is the color it lands on.
    c.rect(0, 116, 9, 18, P.primary_container)
    c.rect(10, 119, 265, 12, P.tray_low)
    c.rect(224, 164, 44, 24, P.tray_low)

    # --- close button sprites (0,116) and (0,125) ---------------------------
    # (0,116) is the equalizer close button's resting sprite in the classic
    # format; Webamp uses it only on .clicked. It matches the face painted into
    # the selected title bar: same glyph, same `primary_container` ground.
    win_button(c, 0, 116, icons.GLYPH_CLOSE, "rest", P.on_primary_container,
               bg=P.primary_container)
    win_button(c, 0, 125, icons.GLYPH_CLOSE, "press", bg=P.primary_container)

    # --- ON / AUTO toggles --------------------------------------------------
    for label, w, position, cols in (
            ("on", 26, "left", ((10, False, False), (128, False, True),
                                (69, True, False), (187, True, True))),
            ("auto", 32, "right", ((36, False, False), (154, False, True),
                                   (95, True, False), (213, True, True)))):
        for x, selected, pressed in cols:
            toggle(c, x, 119, w, 12, selected=selected, pressed=pressed,
                   label=label, position=position)

    # --- band slider sheet: 14 x 2 grid of 15x65 cells at (13,164) ----------
    for n in range(28):
        _band_frame(c, 13 + (n % 14) * CELL_W, 164 + (n // 14) * CELL_H, n)

    _thumb(c, 0, 164, P.accent)
    _thumb(c, 0, 176, P.pressed_container)

    # --- presets button -----------------------------------------------------
    for y, selected in ((164, False), (176, True)):
        toggle(c, 224, y, 44, 12, selected=selected, label="presets")

    # --- EQ curve display ---------------------------------------------------
    container(c, 0, 294, 113, 19, P.vis_floor, radius=0)
    for gx in range(2, 113, 4):
        c.px(gx, 303, P.hairline)
    for i, colour in enumerate(GRAPH_LINE):
        c.px(115, 294 + i, colour)
    for x in range(0, 113, 4):                  # dashed preamp reference line
        c.hline(x, 314, 2, P.ink_dim)
    return c
