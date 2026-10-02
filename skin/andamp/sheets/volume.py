# SPDX-License-Identifier: GPL-3.0-or-later

"""VOLUME.BMP and BALANCE.BMP -- 28 stacked 15px frames plus the two handles.

Winamp picks a frame by level, so the level is drawn into the artwork. With
`level_warning` (spot) the active portion switches from `accent` to `level`
above 90%. With `sliders: handle` (dark, light) the handle is the narrow M3
bar; otherwise it is a grip pill. The track is straight: M3 uses the squiggle
for progress indicators, and these are sliders.
"""

from .. import palette as P
from ._common import sheet, container, track, handle, R_PILL

FRAMES = 28
FRAME_H = 15
# With `level_warning` the track turns to `level` (amber in spot) above 90%.
# M3 sliders do not recolor by value, so dark and light leave it off.
HOT = 0.90 if P.STYLE["level_warning"] else 2.0
HANDLE = P.STYLE["sliders"] == "handle"


def _thumbs(c):
    """The thumb is opaque and 14px wide, so it redraws the rail it covers.

    Both stubs are the neutral rail color: the sprite is the same at every
    value, and an active-colored left stub would show a bright bar at volume
    zero."""
    for x, fill in ((15, P.accent), (0, P.pressed_container)):
        if HANDLE:
            c.rect(x, 422, 14, 11, P.tray_low)
            track(c, x, 427, 5, 5, P.edge)
            track(c, x + 9, 427, 5, 5, P.edge)
            handle(c, x + 5, 422, 4, 11, fill)
        else:
            container(c, x, 422, 14, 11, P.tray_low, radius=0)
            container(c, x + 1, 422, 12, 11, fill, radius=R_PILL)
            for dy in (3, 5, 7):
                c.hline(x + 4, 422 + dy, 6, P.on_primary)
    return


def _colour(frac):
    return P.level if frac > HOT else P.accent


def build():
    c = sheet("VOLUME.BMP", P.tray_low)
    # The rail covers every column the thumb can occupy. The input is 65 wide
    # at element x=0 with a 14px thumb, so the thumb sweeps columns 0..64.
    x0, span = 0, 65
    # Frame 0 is empty and frame 27 is full.
    for i in range(FRAMES):
        frac = i / (FRAMES - 1)
        track(c, x0, i * FRAME_H + 6, span, round(span * frac), _colour(frac))
    _thumbs(c)
    return c


def build_balance():
    """All 28 balance frames are drawn alike: a track with a center detent.
    The sheet is read from x=9 for 38px, so the track is centered on x=28."""
    c = sheet("BALANCE.BMP", P.tray_low)
    # Read from sheet x=9 for 38px, and the 14px thumb sweeps element columns
    # 0..37, so the rail spans the full strip. The center falls between two
    # columns, hence the 2px detent.
    left, width = 9, 38
    c_lo = left + width // 2 - 1
    for i in range(FRAMES):
        y = i * FRAME_H + 6
        # No fill. One frame serves both left and right pan, so a fill would
        # grow both ways from the center. The handle's position against the
        # center detent shows the pan.
        track(c, left, y, width, 0, P.accent)
        c.rect(c_lo, y - 2, 2, 5, P.ink_dim)     # center detent
    _thumbs(c)
    return c
