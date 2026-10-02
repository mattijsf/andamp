# SPDX-License-Identifier: GPL-3.0-or-later

"""The active theme's color tokens, registered with the canvas.

Every color the skin can paint is defined here. The canvas raises on any other
color at draw time.
"""

from .canvas import allow
from . import tonal as T
from .tokens import load

THEME_NAME, _theme = load()
STYLE = _theme.STYLE
# Light and dark are one design in two schemes. Most aliases below are written
# in terms of M3 roles and need no branch, because the role table flips with
# the scheme. Four branch on DARK: `vis_floor` (a recess is the lowest container
# tone in a dark scheme and the highest in a light one), and `win_hover_fill`,
# `win_hover_ink` and `current_track`, which take the role that stays readable
# in each scheme.
DARK = getattr(_theme, "SCHEME", "dark") == "dark"
SKIN_FILE = _theme.SKIN
WORDMARK = _theme.WORDMARK


def _resolve_seed(spec):
    if isinstance(spec, tuple):
        return T.Palette(spec[0], chroma=spec[1])
    return T.Palette(spec)


if hasattr(_theme, "ROLES"):                       # literal token table
    _roles = {k: T.parse(v) for k, v in _theme.ROLES.items()}
else:                                              # derived from seeds
    _roles = {name: _resolve_seed(seed).tone(tone)
              for name, (seed, tone) in _theme.TONES.items()}

for _name, (_seed, _tone) in _theme.DERIVED.items():
    _roles[_name] = _resolve_seed(_seed).tone(_tone)

globals().update({k: allow(v) for k, v in _roles.items()})

shadow = allow(0x000000)
scrim = allow(0x000000)
black = allow(0x000000)

# --- M3 state layers, baked ---------------------------------------------------
# Classic skins cannot composite at runtime, so hover/pressed layers are
# resolved to fixed colors here (M3: hover 8%, focus/pressed 10%).
HOVER, PRESSED = 0.08, 0.10


def layer(base: int, over: int, opacity: float) -> int:
    return allow(T.blend(base, over, opacity))


pressed_surface = layer(surface_container_high, on_surface, PRESSED)         # noqa: F821
hover_button = layer(surface_container, on_surface, HOVER)                   # noqa: F821
pressed_container = layer(primary, on_primary, PRESSED)                      # noqa: F821
# The window-button state layer: a filled disc under a bare app-bar icon. It
# has to read against the title bar, which is `primary_container` in both
# schemes, so the disc takes the end of the fixed pair that contrasts with it:
# dark where the bar is light, light where the bar is dark.
win_hover_fill = primary_fixed if DARK else on_primary_fixed                 # noqa: F821
# The glyph on that disc. In the light scheme it is `on_primary`, because M3's
# baseline light table gives `primary_fixed` and `primary_container` the same
# value: a `primary_fixed` glyph would be the color of the bar the disc sits on.
win_hover_ink = on_primary_fixed if DARK else on_primary                     # noqa: F821
# M3 disabled content is a 38% blend. Used only for status indicators that
# are not interactive.
DISABLED = 0.38

# --- semantic aliases used by the sheets --------------------------------------
window = surface                        # noqa: F821  the window plate
# Surface layering. With a tonal display (dark, light) the tray is
# `surface_container_low` and the display `surface_container_high`; spot puts
# the tray on `surface_container_high` beside a near-black display.
tray = (surface_container_low if STYLE["display"] == "tonal"          # noqa: F821
        else surface_container_high)                                  # noqa: F821
tray_low = surface_container            # noqa: F821  recessed control beds
edge = outline                          # noqa: F821  meaningful 1px boundaries
hairline = outline_variant              # noqa: F821  decorative dividers only
ink = on_surface                        # noqa: F821  primary text
ink_dim = on_surface_variant            # noqa: F821  secondary text / icons
accent = primary                        # noqa: F821
accent_dim = primary_container          # noqa: F821
level = tertiary                        # noqa: F821

# The display well: a tonal surface container in dark and light,
# `surface_container_lowest` (a near-black LCD) in spot.
lcd = (surface_container_high if STYLE["display"] == "tonal"          # noqa: F821
       else surface_container_lowest)                                 # noqa: F821
vis_floor = (surface_container_lowest if DARK                     # noqa: F821
             else surface_container_highest)                        # noqa: F821
# The player fills the visualizer's rect with VISCOLOR line 0 every frame, as a
# hard rectangle, so a rounded well drawn underneath would be painted over. The
# bed is the display panel's color, and the visible shape is the panel's.
vis_bed = lcd                           # noqa: F821

# --- outlined or filled -------------------------------------------------------
# An outlined theme draws a 1px `outline` around a recessed fill. A filled theme
# (`outlines: False`) drops every stroke and uses fills that clear 3:1 over the
# tray, because M3's container tones do not: `primary_container` on `surface`
# is 1.23:1 in the light scheme.
OUTLINED = STYLE.get("outlines", True)

# Card frames. `None` means the container draws no outline at all.
card_edge = hairline if OUTLINED else None                           # noqa: F821

# The transport's ordinary keys.
control_fill = surface_container_highest if OUTLINED else secondary  # noqa: F821
control_ink = ink if OUTLINED else on_secondary                      # noqa: F821
control_edge = edge if OUTLINED else None                            # noqa: F821

# The emphasized control. With `hero: tertiary` it changes hue: every fill
# bright enough to clear 3:1 over the tray has about the same luminance, so
# tone cannot set it apart.
_HERO = STYLE.get("hero", "primary")
hero_fill = tertiary if _HERO == "tertiary" else primary             # noqa: F821
hero_ink = on_tertiary if _HERO == "tertiary" else on_primary        # noqa: F821

# Toggles. M3 nav bars use `secondary_container` for the selected indicator,
# which is about 1.75:1 over `tray_low` in both dark palettes: too little for a
# 23x12px sprite. Selected takes a `primary` fill with `on_primary` content,
# M3's filled icon toggle button, which is about 9.6:1 over that bed.
# `None` means no container: in a filled theme the unselected toggle is a bare
# glyph, like M3's standard icon toggle button. Two bright fills cannot be 3:1
# apart, and a dark unselected fill is not 3:1 over the bed.
toggle_off_fill = tray_low if OUTLINED else None                      # noqa: F821
toggle_off_ink = ink_dim if OUTLINED else ink                         # noqa: F821
toggle_off_edge = edge if OUTLINED else None                          # noqa: F821
toggle_on_fill = primary                # noqa: F821
toggle_on_ink = on_primary              # noqa: F821

# Playlist. PLEDIT.TXT exposes only Normal / Current / NormalBG / SelectedBG.
playlist_bed = surface_container_lowest  # noqa: F821
# The two ends of M3's fixed primary pair: the same hue in both schemes, and
# readable on the playlist bed and the selection band in each.
current_track = primary_fixed if DARK else on_primary_fixed          # noqa: F821


def ramp(a: int, b: int, n: int):
    """n registered steps from a to b, for the visualizer gradient."""
    if n == 1:
        return [allow(a)]
    return [allow(T.blend(a, b, i / (n - 1))) for i in range(n)]


indicator_off = layer(lcd, on_surface_variant, DISABLED)                     # noqa: F821

VIS_SPECTRUM = ramp(tertiary, primary, 16)          # noqa: F821  top -> bottom
# The equalizer's curve, EQ_GRAPH_LINE_COLORS: a 19-step column Winamp reads
# top to bottom. It is defined here because a color computed inside a sheet has
# no role name, so the runtime template could not rebind it.
EQ_GRAPH_LINE = ramp(level, accent, 19)
VIS_OSC = ramp(on_surface, primary, 5)              # noqa: F821
VIS_DOTS = allow(hairline)
VIS_PEAK = allow(on_surface)                        # noqa: F821

# --- declared contrast pairs, asserted by verify ------------------------------
# kind "text" must clear WCAG 4.5:1, kind "ui" must clear 3:1.
#
# Pairs name roles. Several roles can share one value in the M3 baseline
# (`on_primary_container` and `primary_fixed` are both #EADDFF in dark) and
# differ under a live scheme, and the runtime repairs a failing pair by moving
# its foreground role. `colour_of` resolves a name for the checks that run here.
PAIRS = [
    ("marquee text",            "ink",                  "lcd",                  "text"),
    ("time digits",             "accent",               "lcd",                  "text"),
    ("kbps / khz",              "ink_dim",              "lcd",                  "text"),
    ("titlebar text active",    "on_primary_container", "primary_container",    "text"),
    ("titlebar text idle",      "ink_dim",              "tray",                 "text"),
    ("titlebar glyph active",   "on_primary_container", "primary_container",    "ui"),
    ("transport icon",          "control_ink",          "control_fill",         "ui"),
    ("transport icon pressed",  "on_primary",           "primary",              "ui"),
    ("play emphasis fill",      "hero_fill",            "tray",                 "ui"),
    ("play emphasis icon",      "hero_ink",             "hero_fill",            "ui"),
    ("toggle on glyph",         "toggle_on_ink",        "toggle_on_fill",       "ui"),
    ("posbar track",            "edge",                 "tray_low",             "ui"),
    ("posbar thumb",            "accent",               "tray_low",             "ui"),
    ("slider active track",     "accent",               "tray_low",             "ui"),
    # M3 puts the inactive track on `surface_container_highest`, which is
    # 1.33:1 over the bed in the dark schemes. `outline` is M3's role for a
    # 3:1 boundary against a surface, so the rail uses it.
    ("slider inactive track",   "edge",                 "tray_low",             "ui"),
    ("slider handle",           "accent",               "tray_low",             "ui"),
    ("slider level",            "level",                "tray_low",             "ui"),
    ("window button hover",     "win_hover_fill",       "tray",                 "ui"),
    ("window button glyph",     "win_hover_ink",        "win_hover_fill",       "ui"),
    ("mono/stereo label",       "accent",               "lcd",                  "ui"),
    # State is shown by the indicator bar, so the bar is what is asserted. The
    # unlit label is M3 disabled content at 38% and is not held to 3:1.
    ("mono/stereo indicator",   "accent",               "lcd",                  "ui"),
    ("eq band thumb",           "accent",               "tray_low",             "ui"),
    ("eq graph line",           "accent",               "vis_floor",            "ui"),
    ("playlist normal text",    "ink",                  "playlist_bed",         "text"),
    ("playlist current text",   "current_track",        "playlist_bed",         "text"),
    ("playlist text on sel",    "ink",                  "selection",            "text"),
    ("playlist current on sel", "current_track",        "selection",            "text"),
    ("playlist selection band", "selection",            "playlist_bed",         "ui"),
    ("visualiser peak",         "VIS_PEAK",             "vis_bed",              "ui"),
    # Not asserted: display panel against window plate. M3 separates adjacent
    # surface containers by tone, and the panels are not interactive. Outlined
    # themes give them a `hairline` frame.
]

# The control boundary is asserted either way: an outlined theme's stroke
# against the plate and the tray, a filled theme's fill against the tray.
if OUTLINED:
    PAIRS += [
        ("transport outline",       "control_edge",     "tray",                 "ui"),
        ("toggle off glyph",        "toggle_off_ink",   "toggle_off_fill",      "ui"),
        ("toggle off outline",      "toggle_off_edge",  "window",               "ui"),
        ("toggle off outline/tray", "toggle_off_edge",  "tray",                 "ui"),
        ("toggle on vs off fill",   "toggle_on_fill",   "toggle_off_fill",      "ui"),
    ]
else:
    PAIRS += [
        ("transport fill",          "control_fill",     "tray",                 "ui"),
        # The unselected toggle has no container, so its glyph must be visible
        # on both beds a toggle lands on, and so must the selected container.
        ("toggle off glyph/tray",   "toggle_off_ink",   "tray",                 "ui"),
        ("toggle off glyph/bed",    "toggle_off_ink",   "tray_low",             "ui"),
        ("toggle on fill/tray",     "toggle_on_fill",   "tray",                 "ui"),
        ("toggle on fill/bed",      "toggle_on_fill",   "tray_low",             "ui"),
    ]


def colour_of(name: str) -> int:
    """The color a pair's role name stands for in this palette, ramp entries
    (`NAME[i]`) included."""
    if "[" in name:
        base, k = name[:-1].split("[")
        return globals()[base][int(k)]
    return globals()[name]
