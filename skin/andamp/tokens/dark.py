# SPDX-License-Identifier: GPL-3.0-or-later

"""Material 3 baseline dark scheme, drawn with M3 Expressive's shapes.

The colors are the literal values from Google's generated ColorScheme token
export (seed #6750A4).

How the skin is built from them:

Fills in place of strokes. A control is a filled container and tone carries
the boundary: no card frame, no button outline. M3's container tones sit too
close to their surface for that (`primary_container` on `surface` is 1.99:1
here and 1.23:1 in the light scheme; a control boundary needs 3:1), so the
fills are roles that clear 3:1 over the tray: `secondary` for the transport
keys, `primary` for a selected toggle.

Three shape families. A pill for chips, a squircle for the transport keys, a
circle for the play button. At 1px resolution a superellipse quantizes to the
pixels of a rounded rectangle, so the family is a corner radius: h/2 for a
pill, about h/3 for a squircle (`radius_squircle`).

The play button in another hue. Every fill bright enough to clear 3:1 over the
tray has about the same luminance, so tone cannot set the play button apart. It
is the only button filled with `tertiary`, and the only circle.

No container until a toggle is on. Two bright fills cannot be 3:1 apart, and a
dark unselected fill is not 3:1 over the bed, so the unselected state is a bare
glyph, like M3's icon toggle button.

`spot` uses the outlined treatment (`outlines` defaults to True).
"""

NAME = "dark"
SCHEME = "dark"
SKIN = "AndAmp Dark.wsz"
WORDMARK = "andamp"

ROLES = {
    "primary": "#D0BCFF", "on_primary": "#381E72",
    "primary_container": "#4F378B", "on_primary_container": "#EADDFF",
    "inverse_primary": "#6750A4",
    "primary_fixed": "#EADDFF", "primary_fixed_dim": "#D0BCFF",
    "on_primary_fixed": "#21005D", "on_primary_fixed_variant": "#4F378B",
    "secondary": "#CCC2DC", "on_secondary": "#332D41",
    "secondary_container": "#4A4458", "on_secondary_container": "#E8DEF8",
    "tertiary": "#EFB8C8", "on_tertiary": "#492532",
    "tertiary_container": "#633B48", "on_tertiary_container": "#FFD8E4",
    "error": "#F2B8B5", "on_error": "#601410",
    "error_container": "#8C1D18", "on_error_container": "#F9DEDC",
    "surface": "#141218", "on_surface": "#E6E0E9",
    "surface_variant": "#49454F", "on_surface_variant": "#CAC4D0",
    "surface_dim": "#141218", "surface_bright": "#3B383E",
    "surface_container_lowest": "#0F0D13", "surface_container_low": "#1D1B20",
    "surface_container": "#211F26", "surface_container_high": "#2B2930",
    "surface_container_highest": "#36343B",
    "outline": "#938F99", "outline_variant": "#49454F",
    "inverse_surface": "#E6E0E9", "inverse_on_surface": "#322F35",
}

# The selection band has to clear 3:1 over the playlist bed and carry both text
# colors at 4.5:1. M3's `secondary_container` is 2.08:1 over the bed. Secondary
# tones 40 to 42 satisfy all three, with the current track at `primary_fixed`
# (`primary` falls short of 4.5:1 on the band).
DERIVED = {"selection": ("#CCC2DC", 41)}

STYLE = {
    "digits": "geometric",
    "icons": "symbols",
    "play": "circle",
    "sliders": "handle",
    "level_warning": False,
    "wavy_progress": True,
    "button_groups": True,
    "window_buttons": "bare",
    "display": "tonal",
    # The filled treatment.
    "outlines": False,          # tone carries every boundary
    "hero": "tertiary",         # the emphasized control changes hue, not tone
    "radius_window": 11,    # the close glyph keeps 3 clear columns from the arc
    "radius_container": 11,     # the shorter beds clamp to pills at this radius
    "radius_button": 32,        # pill
    "radius_squircle": 5,       # straight sides, large corner
    "radius_selected": 3,       # selected morphs pill -> squircle
    "track_thickness": 3,       # M3E draws progress as a chunky stroke
    "wordmark_spacing": 1,
}
