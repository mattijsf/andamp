# SPDX-License-Identifier: GPL-3.0-or-later

"""Material 3 baseline light scheme: the light counterpart of `dark.py`.

Literal values from Google's generated ColorScheme token export (seed
#6750A4). STYLE is identical to dark's: the two themes differ only in color.

In a light scheme the fills that clear 3:1 over the tray are the dark ones, so
the controls are dark squircles on a near-white plate.
"""

NAME = "light"
SKIN = "AndAmp Light.wsz"
SCHEME = "light"
WORDMARK = "andamp"

ROLES = {
    "primary": "#6750A4", "on_primary": "#FFFFFF",
    "primary_container": "#EADDFF", "on_primary_container": "#21005D",
    "inverse_primary": "#D0BCFF",
    "primary_fixed": "#EADDFF", "primary_fixed_dim": "#D0BCFF",
    "on_primary_fixed": "#21005D", "on_primary_fixed_variant": "#4F378B",
    "secondary": "#625B71", "on_secondary": "#FFFFFF",
    "secondary_container": "#E8DEF8", "on_secondary_container": "#1D192B",
    "tertiary": "#7D5260", "on_tertiary": "#FFFFFF",
    "tertiary_container": "#FFD8E4", "on_tertiary_container": "#31111D",
    "error": "#B3261E", "on_error": "#FFFFFF",
    "error_container": "#F9DEDC", "on_error_container": "#410E0B",
    "surface": "#FEF7FF", "on_surface": "#1D1B20",
    "surface_variant": "#E7E0EC", "on_surface_variant": "#49454F",
    "surface_dim": "#DED8E1", "surface_bright": "#FEF7FF",
    "surface_container_lowest": "#FFFFFF", "surface_container_low": "#F7F2FA",
    "surface_container": "#F3EDF7", "surface_container_high": "#ECE6F0",
    "surface_container_highest": "#E6E0E9",
    "outline": "#79747E", "outline_variant": "#CAC4D0",
    "inverse_surface": "#322F35", "inverse_on_surface": "#F5EFF7",
}

# The selection band must clear 3:1 over a white bed and carry two dark text
# colors at 4.5:1. Secondary tones 55 to 61 satisfy all three; tone 58 gives
# 3.39:1 over the bed, 5.03:1 for body text and 5.06:1 for the current track.
#
# `current_track` resolves to `on_primary_fixed` in a light scheme because
# `primary` does not reach 4.5:1 on the band.
DERIVED = {"selection": ("#625B71", 58)}

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
