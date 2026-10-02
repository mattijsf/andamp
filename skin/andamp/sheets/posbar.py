# SPDX-License-Identifier: GPL-3.0-or-later

"""POSBAR.BMP -- the position track and its handle.

With `wavy_progress` (dark, light) the played portion is the M3 Expressive
squiggle: amplitude 1px, wavelength 8px. A classic skin cannot repaint the
track as playback advances, so the handle sprite carries the transition: active
and wavy to its left, inactive rail to its right.
"""

from .. import palette as P
from ._common import sheet, container, track, handle, R_PILL, R_SELECTED

TRACK_W = 248
WAVY = P.STYLE["wavy_progress"]
HANDLE = P.STYLE["sliders"] == "handle"


def _grip_thumb(c, x, fill, ink, radius):
    container(c, x, 0, 29, 10, P.tray_low, radius=0)
    container(c, x + 2, 1, 25, 8, fill, radius=radius)
    for i in (-4, 0, 4):
        c.vline(x + 14 + i, 3, 4, ink)


def _handle_thumb(c, x, fill):
    """The thumb is opaque, so it redraws the rail across its full width and
    clears a 5px window for the handle. The wave phase where the thumb lands
    is unknown at build time, so the wave tapers in from flat."""
    c.rect(x, 0, 29, 10, P.tray_low)
    track(c, x, 5, 12, 12, P.accent, wavy=WAVY, wavelength=8, taper_in=4)
    track(c, x + 17, 5, 12, 0, P.accent)
    handle(c, x + 12, 0, 5, 10, fill)


def build():
    c = sheet("POSBAR.BMP", P.tray_low)

    x0, span = 2, TRACK_W - 4      # full width: the thumb travels to the end
    if WAVY:
        # Straight rail; the squiggle is drawn in the thumb sprite. The thumb
        # is opaque, 29px wide and lands at any pixel, so a wavy background
        # would meet the thumb's wave out of phase.
        #
        # The rail stops 4px short of the sprite's end for the M3 stop
        # indicator: a dot at the end of the track, in the active color.
        track(c, x0, 5, span - 4, 0, P.edge)
        c.rect(x0 + span - 2, 4, 2, 2, P.accent)
    else:
        # Leave room for the stop indicator dot.
        c.wave(x0, 5, span - 4, P.ink_dim, amplitude=1, wavelength=10,
               flat_head=int(span * 0.10), flat_tail=int(span * 0.05))
        c.rect(x0 + span - 2, 4, 2, 2, P.ink_dim)

    for x, fill, radius in ((248, P.accent, R_PILL),
                            (278, P.pressed_container, R_SELECTED)):
        if HANDLE:
            _handle_thumb(c, x, fill)
        else:
            _grip_thumb(c, x, fill, P.on_primary, radius)
    return c
