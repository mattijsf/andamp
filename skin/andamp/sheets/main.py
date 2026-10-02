# SPDX-License-Identifier: GPL-3.0-or-later

"""MAIN.BMP -- the 275x116 window plate.

Almost every element that sits on this plate is an opaque sprite from another
sheet, so this file draws the structure between them: the window edge, the two
display panels, the slider bed and the transport tray. Element boxes come from
Webamp's main-window.css.
"""

from .. import palette as P, draw
from ._common import sheet, container, R_CHAMFER, R_WINDOW

# --- layout grid -------------------------------------------------------------
# Every container edge comes from one of three rails.
#
# RAIL_L aligns with the clutter bar at x=10, the leftmost fixed element.
# RAIL_R leaves one clear column after the stereo indicator, the rightmost,
# whose last column is 267. COL_L opens the right-hand column, shared by the
# info panel and the slider bed beneath it.
#
# The window's margins are 10 left and 5 right, because Winamp's element grid
# runs from x=10 to x=267 inside a 275px window.
RAIL_L, RAIL_R = 10, 269
COL_L = 104
# The left panel cannot reach RAIL_L, because the clutter bar occupies x=10..17
# in that row, so it is centered on its own contents. They span 24..99 (work
# indicator on the left, visualizer below), and the panel gives them 3px either
# side.
PANEL_L, PANEL_R = 21, 102

# Destination boxes (Webamp main-window.css) for the sprites that land in the
# plate's recesses.
TIME = (39, 26, 59, 13)
DIGIT_X = (48, 60, 78, 90)      # absolute x of the four time digits
VIS = (24, 43, 76, 16)
MARQUEE = (111, 24, 154, 6)
KBPS = (111, 43, 15, 6)
KHZ = (156, 43, 10, 6)


# Every container on the plate, as (name, x, y, w, h), for the audit's rail
# check.
def containers():
    return [
        ("left display panel", PANEL_L, 22, PANEL_R - PANEL_L + 1, 40),
        ("right info panel", COL_L, 22, RAIL_R - COL_L + 1, 33),
        ("slider bed", COL_L, 56, RAIL_R - COL_L + 1, 15),
        ("posbar bed", RAIL_L, 71, RAIL_R - RAIL_L + 1, 13),
        ("transport tray", RAIL_L, 85, RAIL_R - RAIL_L + 1, 24),
    ]


# Vertical rhythm. A 1px gutter separates the cards.
#
# Between the info panel's content (ends row 52) and the volume slider (starts
# 57) there are four free rows: two to the panel, one to the gutter, one to the
# slider bed.
#
# The slider bed and the posbar bed touch: rows 70 and 71 are the only ones
# between the volume slider and the position bar, and each bed needs one for
# its own edge.


LEFT_RAILS = {RAIL_L, COL_L, PANEL_L}
RIGHT_RAILS = {RAIL_R, PANEL_R}


def build():
    c = sheet("MAIN.BMP")

    # Window plate.
    c.rframe(0, 0, 275, 116, P.edge, radius=R_WINDOW)

    # Left display panel: transport state, time, visualizer. It cannot reach
    # RAIL_L because the clutter bar occupies x=10..17 in this row.
    container(c, PANEL_L, 22, PANEL_R - PANEL_L + 1, 40, P.lcd,
              radius=R_CHAMFER, edge=P.card_edge)
    # No visualizer well: the player repaints VIS every frame with VISCOLOR
    # line 0 as a hard rectangle, so the bed is the panel color.

    # Time colon. Winamp leaves a 10px hole between the minute and second
    # digits and expects the skin to paint the separator into the plate.
    for cy in (30, 34):
        c.rect(71, cy, 2, 2, P.accent)

    # Right info panel: title marquee, bitrate, sample rate, channel mode.
    container(c, COL_L, 22, RAIL_R - COL_L + 1, 33, P.lcd,
              radius=R_CHAMFER, edge=P.card_edge)
    draw.text(c, KBPS[0] + KBPS[2] + 2, KBPS[1], "kbps", P.ink_dim)
    draw.text(c, KHZ[0] + KHZ[2] + 2, KHZ[1], "khz", P.ink_dim)

    # Slider bed: volume, balance, EQ/PL buttons. It starts at COL_L because
    # the left display panel runs down to y=61.
    container(c, COL_L, 56, RAIL_R - COL_L + 1, 15, P.tray_low,
              radius=R_CHAMFER, edge=P.card_edge)

    # Position bar bed, full width.
    container(c, RAIL_L, 71, RAIL_R - RAIL_L + 1, 13, P.tray_low,
              radius=R_CHAMFER, edge=P.card_edge)

    # Transport tray.
    container(c, RAIL_L, 85, RAIL_R - RAIL_L + 1, 24, P.tray,
              radius=R_CHAMFER, edge=P.card_edge)

    # The "about" hit target at 253,91,13,15 is Winamp's clickable logo. It
    # holds Andamp's mark, a bolt through a broken ring, drawn bare at the full
    # 13px width in `ink_dim`.
    from .. import art as icons
    draw.icon(c, 253, 92, 13, 13, icons.BRAND_MARK, P.ink_dim)

    return c
