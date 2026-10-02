# SPDX-License-Identifier: GPL-3.0-or-later

"""The palette as a table that code other than Python can read.

`palette.py` builds the values as module attributes for the sheets. This file
states where each value comes from, so a runtime can rebind them to a live
Material You scheme.

`verify` resolves this table and asserts it equals the module attribute of the
same name, entry for entry.

Five kinds:

    M3(name)            a field on Material 3's ColorScheme
    Alias(role)         another entry, resolved earlier in this table
    Blend(a, b, t)      a mix in linear light: M3's state layers at 8% / 10% /
                        38%, and the ramps
    Tone(role, t)       that role's hue and chroma at lightness t, in CIELAB
                        as `tonal.py` computes it
    Literal(hex)

An entry may only refer to entries above it, so one pass resolves the table.
"""

from collections import namedtuple

M3 = namedtuple("M3", "name")
Alias = namedtuple("Alias", "role")
Blend = namedtuple("Blend", "a b t")
Tone = namedtuple("Tone", "role t")
Literal = namedtuple("Literal", "value")

# M3's state-layer opacities.
HOVER, PRESSED, DISABLED = 0.08, 0.10, 0.38


def table(style, scheme, roles):
    """The ordered role table for one theme.

    `style` is the theme's STYLE, `scheme` its "dark"/"light", `roles` the keys
    of its ROLES table. A seeded theme like `spot` derives the same names from
    tones, so both kinds of theme share the names.
    """
    dark = scheme == "dark"
    tonal = style["display"] == "tonal"
    outlined = style.get("outlines", True)
    hero_tertiary = style.get("hero", "primary") == "tertiary"

    t = [(name, M3(name)) for name in roles]

    def add(name, expr):
        t.append((name, expr))

    def pick(name, when_true, when_false, cond):
        add(name, Alias(when_true if cond else when_false))

    # --- semantic aliases ---------------------------------------------------
    add("window", Alias("surface"))
    pick("tray", "surface_container_low", "surface_container_high", tonal)
    add("tray_low", Alias("surface_container"))
    add("edge", Alias("outline"))
    add("hairline", Alias("outline_variant"))
    add("ink", Alias("on_surface"))
    add("ink_dim", Alias("on_surface_variant"))
    add("accent", Alias("primary"))
    add("accent_dim", Alias("primary_container"))
    add("level", Alias("tertiary"))
    pick("lcd", "surface_container_high", "surface_container_lowest", tonal)
    # A recess is the lowest container tone in a dark scheme and the highest in
    # a light one.
    pick("vis_floor", "surface_container_lowest", "surface_container_highest", dark)
    add("vis_bed", Alias("lcd"))
    add("playlist_bed", Alias("surface_container_lowest"))

    # Controls. An outlined theme recesses them and draws a stroke; a filled one
    # pulls the fill to a tone that carries the boundary on its own.
    pick("control_fill", "surface_container_highest", "secondary", outlined)
    pick("control_ink", "ink", "on_secondary", outlined)
    pick("hero_fill", "tertiary", "primary", hero_tertiary)
    pick("hero_ink", "on_tertiary", "on_primary", hero_tertiary)

    # A light scheme takes the other end of M3's fixed primary pair. The hover
    # glyph is the exception: M3's baseline light table gives primary_fixed and
    # primary_container the same value, so a primary_fixed glyph would match
    # the bar its disc sits on.
    pick("win_hover_fill", "primary_fixed", "on_primary_fixed", dark)
    pick("win_hover_ink", "on_primary_fixed", "on_primary", dark)
    pick("current_track", "primary_fixed", "on_primary_fixed", dark)

    pick("toggle_off_ink", "ink_dim", "ink", outlined)
    add("toggle_on_fill", Alias("primary"))
    add("toggle_on_ink", Alias("on_primary"))
    if outlined:
        add("toggle_off_fill", Alias("tray_low"))
        add("toggle_off_edge", Alias("edge"))
        add("control_edge", Alias("edge"))
        add("card_edge", Alias("hairline"))
    # When not outlined those four are None in `palette.py` and absent here.

    # --- baked state layers -------------------------------------------------
    add("pressed_surface", Blend("surface_container_high", "on_surface", PRESSED))
    add("hover_button", Blend("surface_container", "on_surface", HOVER))
    add("pressed_container", Blend("primary", "on_primary", PRESSED))
    add("indicator_off", Blend("lcd", "on_surface_variant", DISABLED))

    # --- the one tonal entry ------------------------------------------------
    # The playlist selection band: 3:1 over the bed while carrying two text
    # colors at 4.5:1. It is a tone of the secondary role's own value, so it
    # follows a live scheme.
    add("selection", Tone("secondary", 41 if dark else 58))

    # --- literals -----------------------------------------------------------
    for name in ("shadow", "scrim", "black"):
        add(name, Literal(0x000000))

    # --- ramps --------------------------------------------------------------
    # Winamp reads these as columns of colors, so each step is its own entry.
    for i, expr in enumerate(_ramp("tertiary", "primary", 16)):
        add(f"VIS_SPECTRUM[{i}]", expr)
    for i, expr in enumerate(_ramp("on_surface", "primary", 5)):
        add(f"VIS_OSC[{i}]", expr)
    for i, expr in enumerate(_ramp("level", "accent", 19)):
        add(f"EQ_GRAPH_LINE[{i}]", expr)
    add("VIS_DOTS", Alias("hairline"))
    add("VIS_PEAK", Alias("on_surface"))
    return t


def _ramp(a, b, n):
    if n == 1:
        return [Alias(a)]
    return [Blend(a, b, i / (n - 1)) for i in range(n)]


def resolve(entries, m3, blend, tone):
    """Bind a table to actual colors.

    `m3(name)` returns the scheme's color for an M3 role, `blend(a, b, t)`
    mixes two resolved colors and `tone(color, t)` restates one at another
    lightness.
    """
    out = {}
    for name, expr in entries:
        if isinstance(expr, M3):
            out[name] = m3(expr.name)
        elif isinstance(expr, Alias):
            out[name] = out[expr.role]
        elif isinstance(expr, Blend):
            out[name] = blend(out[expr.a], out[expr.b], expr.t)
        elif isinstance(expr, Tone):
            out[name] = tone(out[expr.role], expr.t)
        elif isinstance(expr, Literal):
            out[name] = expr.value
        else:
            raise TypeError(f"{name}: unknown expression {expr!r}")
    return out
