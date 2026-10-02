# SPDX-License-Identifier: GPL-3.0-or-later

"""spot: M3 tonal palettes seeded from Winamp's own colors, on a near-black
surface.

primary  = the classic LCD green
tertiary = the classic spectrum-analyzer amber
"""

NAME = "spot"
SCHEME = "dark"
SKIN = "AndAmp Spot.wsz"
WORDMARK = "andamp"

SEED_PRIMARY = "#00E250"
SEED_TERTIARY = "#FF9E1B"
SEED_ERROR = "#B3261E"

# (seed, chroma override, tone) per role.
_P, _S = (SEED_PRIMARY, None), (SEED_PRIMARY, 16)
_T, _E = (SEED_TERTIARY, None), (SEED_ERROR, None)
_N, _NV = (SEED_PRIMARY, 4), (SEED_PRIMARY, 8)

TONES = {
    "primary": (_P, 80), "on_primary": (_P, 20),
    "primary_container": (_P, 30), "on_primary_container": (_P, 90),
    "inverse_primary": (_P, 40),
    "primary_fixed": (_P, 90), "primary_fixed_dim": (_P, 80),
    "on_primary_fixed": (_P, 10), "on_primary_fixed_variant": (_P, 30),
    "secondary": (_S, 80), "on_secondary": (_S, 20),
    "secondary_container": (_S, 30), "on_secondary_container": (_S, 90),
    "tertiary": (_T, 80), "on_tertiary": (_T, 20),
    "tertiary_container": (_T, 30), "on_tertiary_container": (_T, 90),
    "error": (_E, 80), "on_error": (_E, 20),
    "error_container": (_E, 30), "on_error_container": (_E, 90),
    "surface": (_N, 6), "surface_dim": (_N, 6), "surface_bright": (_N, 24),
    "surface_container_lowest": (_N, 4), "surface_container_low": (_N, 10),
    "surface_container": (_N, 12), "surface_container_high": (_N, 17),
    "surface_container_highest": (_N, 22),
    "on_surface": (_N, 90), "inverse_surface": (_N, 90),
    "inverse_on_surface": (_N, 20),
    "surface_variant": (_NV, 30), "on_surface_variant": (_NV, 80),
    "outline": (_NV, 60), "outline_variant": (_NV, 30),
}

DERIVED = {"selection": (("#00E250", 16), 41)}

STYLE = {
    "digits": "seven_segment",
    "icons": "chunky",
    "play": "pill",
    "sliders": "grip",
    "level_warning": True,   # amber track near full scale
    "wavy_progress": False,
    "button_groups": False,  # separate chips, not an M3E segmented control
    "window_buttons": "chip",
    "display": "lcd",
    "radius_window": 2,
    "radius_container": 1,
    "radius_button": 3,
    "radius_selected": 1,
    "wordmark_spacing": 1,
}
