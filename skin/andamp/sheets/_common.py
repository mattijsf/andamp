# SPDX-License-Identifier: GPL-3.0-or-later

"""Shared M3 button/container recipes used across the sheets."""

from ..canvas import Canvas
from .. import palette as P, shapes, draw

# The M3 shape scale, mapped ordinally onto this canvas by the active theme.
# Radii are clamped per box by shapes.cut_offsets, so asking for `full` on a
# 10px control yields a pill and on a 5px one a chamfer.
R_NONE = shapes.NONE
R_CHAMFER = P.STYLE["radius_container"]      # M3 "medium"
R_ROUND = P.STYLE["radius_container"] + 1    # M3 "large"
R_PILL = P.STYLE["radius_button"]            # M3 "full"
R_SELECTED = P.STYLE["radius_selected"]      # M3E selected: squarer than round
R_WINDOW = P.STYLE["radius_window"]          # the radius REGION.TXT traces
# The third shape family. A superellipse quantizes to the pixels of a rounded
# rectangle at this size, so a squircle is a pill with a smaller radius: about
# h/3 against h/2. A theme without `radius_squircle` gets a pill.
R_KEY = P.STYLE.get("radius_squircle", R_PILL)
# Slider and progress stroke weight: 3px in the M3 Expressive themes, 2px
# otherwise.
TRACK = P.STYLE.get("track_thickness", 2)


def container(c, x, y, w, h, fill, radius=R_ROUND, edge=None):
    c.rrect(x, y, w, h, fill, radius=radius)
    if edge is not None:
        c.rframe(x, y, w, h, edge, radius=radius)


def button(c, x, y, w, h, *, fill, ink, mask=None, label=None,
           radius=R_PILL, edge=None, spacing=0):
    """One M3 button: filled container, optional 1px outline, centered content."""
    container(c, x, y, w, h, fill, radius=radius, edge=edge)
    if mask is not None:
        draw.icon(c, x, y, w, h, mask, ink)
    if label is not None:
        draw.text_centered(c, x, y, w, h, label, ink, spacing=spacing)


# M3 Expressive connected button group. Adjacent segments share one silhouette:
# the corners on the outside of the run take the full radius, the ones that
# meet a neighbor take the small one.
GROUPED = P.STYLE.get("button_groups", False)


def group(position, outer=R_PILL, inner=shapes.CHAMFER):
    """position: 'left' | 'middle' | 'right' | 'only'."""
    if position == "only" or not GROUPED:
        return outer
    joined = {"left": ("tr", "br"), "right": ("tl", "bl"),
              "middle": ("tl", "bl", "tr", "br")}[position]
    return {k: (inner if k in joined else outer) for k in shapes.ALL}


def toggle(c, x, y, w, h, *, selected, mask=None, label=None,
           pressed=False, spacing=0, position="only"):
    """M3 Expressive toggle. Selected is a bright filled container with a
    squarer corner. Unselected is an outlined pill in an outlined theme and a
    bare glyph in a filled one (`P.toggle_off_fill` is None)."""
    if selected:
        fill = P.pressed_container if pressed else P.toggle_on_fill
        ink = P.toggle_on_ink
        radius, edge = R_SELECTED, None
    elif P.toggle_off_fill is None:
        # No container when off: draw the content alone. Pressed still needs a
        # state layer, so that one keeps a container.
        if pressed:
            container(c, x, y, w, h, P.pressed_surface, radius=group(position))
        if mask is not None:
            draw.icon(c, x, y, w, h, mask, P.toggle_off_ink)
        if label is not None:
            draw.text_centered(c, x, y, w, h, label, P.toggle_off_ink,
                               spacing=spacing)
        return
    else:
        fill = P.pressed_surface if pressed else P.toggle_off_fill
        ink = P.toggle_off_ink
        radius, edge = group(position), P.toggle_off_edge
    button(c, x, y, w, h, fill=fill, ink=ink, mask=mask, label=label,
           radius=radius, edge=edge, spacing=spacing)


# --- window buttons ----------------------------------------------------------
# Winamp, and Webamp after it, has sprites only for the hover and pressed
# states of a window button; the resting face is part of the window's title bar
# bitmap. Every window draws its buttons through `win_button`.
BARE_BUTTONS = P.STYLE["window_buttons"] == "bare"


def win_button(c, x, y, mask, state, ink=None, bg=None):
    """state: 'rest' is painted into the bar art, 'hover' and 'press' ship as
    sprites.

    With `window_buttons: bare` (dark, light) the icon is bare at rest and gets
    a filled circular state layer on hover and press, like a Material app bar.
    In the dark scheme no surface tone clears 3:1 against the
    `primary_container` title bar (`surface_container_lowest` is 2.07:1), so
    the state layer is a light one. Otherwise (spot) every state is an outlined
    or filled chip.
    """
    from .. import shapes

    # `bg` is for resting faces that ship as their own sprite. The sprite is
    # opaque, so it carries the bar color behind the glyph.
    if bg is not None:
        c.rect(x, y, 9, 9, bg)

    if BARE_BUTTONS:
        if state == "rest":
            draw.icon(c, x, y, 9, 9, mask, ink or P.ink)
            return
        fill, glyph = ((P.primary, P.on_primary) if state == "press"
                       else (P.win_hover_fill, P.win_hover_ink))
        c.stamp(x, y, shapes.disc(9), {"#": fill})
        draw.icon(c, x, y, 9, 9, mask, glyph)
        return

    if state == "press":
        button(c, x, y, 9, 9, fill=P.primary, ink=P.on_primary,
               mask=mask, radius=R_SELECTED)
    else:
        fill = P.hover_button if state == "hover" else P.tray_low
        button(c, x, y, 9, 9, fill=fill, ink=P.ink,
               mask=mask, radius=R_PILL, edge=P.edge)


def sheet(name, fill=None):
    from spec.sprites import SHEETS
    w, h = SHEETS[name]
    return Canvas(w, h, P.window if fill is None else fill)


# --- sliders -----------------------------------------------------------------

def vtrack(c, cx, y, length, active_from, active_to, colour, *, wavy=False,
           thickness=None, inactive=None, amplitude=1, wavelength=8):
    """A vertical slider rail with an active span between two offsets."""
    import math

    thickness = TRACK if thickness is None else thickness
    inactive = P.edge if inactive is None else inactive
    left = cx - thickness // 2
    # Square ends: a radius-1 corner on a 2px-wide bar removes the end rows.
    c.rect(left, y, thickness, length, inactive)
    lo, hi = sorted((active_from, active_to))
    for i in range(lo, hi + 1):
        dx = (math.floor(amplitude * math.sin(2 * math.pi * i / wavelength) + 0.5)
              if wavy else 0)
        for t in range(thickness):
            c.px(left + dx + t, y + i, colour)


def track(c, x, cy, length, active, colour, *, wavy=False, thickness=None,
          inactive=None, amplitude=1, wavelength=8, taper_in=0, taper_out=0):
    """An M3 slider track: a straight inactive rail after the active portion.
    With `wavy` the active portion is the M3 Expressive squiggle.
    """
    import math

    thickness = TRACK if thickness is None else thickness
    inactive = P.edge if inactive is None else inactive
    top = cy - thickness // 2
    # The inactive rail is drawn only past the active portion, so it does not
    # show through where the wave rides 1px off center.
    if active < length:
        c.rect(x + active, top, length - active, thickness, inactive)
    if active <= 0:
        return
    if wavy:
        # The wave amplitude tapers to zero over `taper_in` and `taper_out`
        # pixels, as M3's WavyProgressIndicatorDefaults.indicatorAmplitude does
        # near the ends. A flat end lets a segment drawn in a thumb sprite meet
        # one drawn in the background whatever the wave phase is there.
        for i in range(active):
            a = amplitude
            if taper_in and i < taper_in:
                a = amplitude * i / taper_in
            if taper_out and i >= active - taper_out:
                a = amplitude * (active - 1 - i) / taper_out
            dy = math.floor(a * math.sin(2 * math.pi * i / wavelength) + 0.5)
            for t in range(thickness):
                c.px(x + i, top + dy + t, colour)
    else:
        c.rect(x, top, active, thickness, colour)


def handle(c, x, y, w, h, colour, radius=2):
    """The M3 Expressive slider handle: a narrow rounded bar, drawn at the size
    asked for."""
    c.rrect(x, y, w, h, colour, radius=radius)


def frame_piece(c, x, y, w, h, fill, corners=(), skip=(), edge=None,
                clamp=False):
    """One piece of a stretchable window frame, outlined everywhere except the
    edges in `skip`, which butt a neighboring piece.

    The playlist and the generic (plug-in) window are both assembled from
    pieces the player tiles to size, and both draw them with this helper.
    """
    c.rrect(x, y, w, h, fill, radius=R_WINDOW, corners=corners, clamp=clamp)
    if edge is None:
        return
    for ox, oy in shapes.outline(w, h, R_WINDOW, corners, clamp=clamp):
        # A pixel on a kept horizontal rule is always drawn, even where the
        # rule ends on a side this piece does not outline, so the rule is
        # continuous across a seam.
        if (oy == 0 and "t" not in skip) or (oy == h - 1 and "b" not in skip):
            c.px(x + ox, y + oy, edge)
            continue
        if ("l" in skip and ox == 0) or ("r" in skip and ox == w - 1):
            continue
        if ("t" in skip and oy == 0) or ("b" in skip and oy == h - 1):
            continue
        c.px(x + ox, y + oy, edge)
