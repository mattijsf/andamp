# SPDX-License-Identifier: GPL-3.0-or-later

"""The text members of the skin: PLEDIT.TXT, VISCOLOR.TXT, REGION.TXT and
readme.txt.

Webamp falls back to its default skin's values when one of the first three is
missing or malformed, without an error, so verify.py checks them statically.
"""

from . import palette as P
from .tonal import unpack


def _hex(rgb):
    return f"#{rgb:06X}"


def pledit_txt() -> str:
    return (
        "[Text]\n"
        f"Normal={_hex(P.ink)}\n"
        f"Current={_hex(P.current_track)}\n"
        f"NormalBG={_hex(P.playlist_bed)}\n"
        f"SelectedBG={_hex(P.selection)}\n"
        "Font=Arial\n"
        f"mbFG={_hex(P.ink)}\n"
        f"mbBG={_hex(P.playlist_bed)}\n"
    )


def viscolor_txt() -> str:
    """24 lines: background, dot grid, 16 spectrum steps, 5 oscilloscope
    steps, peak dots."""
    entries = (
        [(P.vis_bed, "background")]
        + [(P.hairline, "dot grid")]
        + [(c, f"spectrum {i}") for i, c in enumerate(P.VIS_SPECTRUM)]
        + [(c, f"oscilloscope {i}") for i, c in enumerate(P.VIS_OSC)]
        + [(P.VIS_PEAK, "analyzer peak")]
    )
    assert len(entries) == 24, len(entries)
    lines = []
    for rgb, note in entries:
        r, g, b = unpack(rgb)
        lines.append(f"{r},{g},{b}, // {note}")
    return "\n".join(lines) + "\n"


def _silhouette(w, h):
    """Clockwise from the top-left, following the outside edge, traced from
    the same corner mask the canvas draws. The first point is not repeated:
    Winamp closes the polygon itself."""
    from . import shapes

    return shapes.outline_polygon(w, h, P.STYLE["radius_window"])


def region_txt() -> str:
    """Window silhouettes traced from the corner mask the canvas draws, so the
    window shape and the bitmap corners agree. PointList must stay on one line.

    `[Corners]` is an Andamp extension. Winamp's region format covers the main
    window and the equalizer, each with its shade mode. The playlist and the
    plug-in window resize, so no point list can describe them; this section
    holds the top-left corner, and Andamp mirrors it into the other three at
    the window's size.
    """
    from . import shapes

    sections = [
        ("Normal", [_silhouette(275, 116)]),
        ("WindowShade", [_silhouette(275, 14)]),
        ("Equalizer", [_silhouette(275, 116)]),
        ("EqualizerWS", [_silhouette(275, 14)]),
        ("Corners", [shapes.corner_polygon(P.STYLE["radius_window"])]),
    ]
    out = []
    for name, polys in sections:
        pts = ", ".join(f"{x},{y}" for poly in polys for x, y in poly)
        out.append(f"[{name}]")
        out.append("NumPoints=" + ", ".join(str(len(p)) for p in polys))
        out.append("PointList=" + pts)
        out.append("")
    return "\n".join(out)

# One line per skin for its readme. Written for whoever opens the file, in any
# player: the built-in Dark and Light follow the wallpaper because the app
# rebuilds them from a template, and a .wsz keeps the colors it was built with.
BLURBS = {
    "dark": "The dark one, and what AndAmp wears out of the box. The copy built\n"
            "into AndAmp follows your wallpaper on Android 12 and up; this file\n"
            "keeps the colours you see here.",
    "light": "The light one. The copy built into AndAmp follows your wallpaper on\n"
             "Android 12 and up; this file keeps the colours you see here.",
    "spot": "Green on near-black, with an LCD-style display.",
}


def readme_txt() -> str:
    """readme.txt: the skin's name, author, license and origin.

    The Winamp Skin Museum shows a skin's readme as its description. ASCII
    with CRLF line endings; the other text files use LF.
    """
    theme = P.THEME_NAME
    title = f"AndAmp {theme.capitalize()}"
    lines = [
        title,
        "",
        BLURBS[theme],
        "",
        "By Mattix - https://mattix.nl",
        "",
        "Made for AndAmp, a Winamp 2.8 player for Android: https://mattix.nl/andamp",
        "",
        "Licensed under Creative Commons Attribution 4.0: use it, change it",
        "and share it, as long as you credit Mattix.",
        "https://creativecommons.org/licenses/by/4.0/",
    ]
    return "\r\n".join(lines) + "\r\n"
