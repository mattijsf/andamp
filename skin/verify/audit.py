# SPDX-License-Identifier: GPL-3.0-or-later

"""Geometry audit: measures where ink lands in each sprite.

Two levels per sprite. The outer level is the control -- the button container
inside its cell. The inner level is the content -- the glyph or icon inside
that container. Both report left/right and top/bottom margins, so a control
that is centered but whose glyph is not shows up as an inner asymmetry only.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from andamp import palette as P
from spec import sprites as S


def bbox(canvas, x, y, w, h, background):
    xs, ys = [], []
    for j in range(h):
        for i in range(w):
            if canvas.get(x + i, y + j) != background:
                xs.append(i)
                ys.append(j)
    if not xs:
        return None
    return min(xs), min(ys), max(xs), max(ys)


def margins(box, w, h):
    x0, y0, x1, y1 = box
    return x0, w - 1 - x1, y0, h - 1 - y1        # left, right, top, bottom


def mask_bbox(mask):
    xs = [i for r in mask for i, ch in enumerate(r) if ch == "#"]
    ys = [j for j, r in enumerate(mask) for ch in r if ch == "#"]
    if not xs:
        return None
    return min(xs), min(ys), max(xs), max(ys)


def audit_masks(report):
    from andamp import art

    names = [n for n in dir(art)
             if n.isupper() and isinstance(getattr(art, n), list)
             and getattr(art, n) and isinstance(getattr(art, n)[0], str)]
    for n in sorted(names):
        mask = getattr(art, n)
        w, h = len(mask[0]), len(mask)
        if any(len(r) != w for r in mask):
            report.append(("MASK", n, f"ragged rows: widths {sorted({len(r) for r in mask})}"))
            continue
        box = mask_bbox(mask)
        if box is None:
            report.append(("MASK", n, "empty"))
            continue
        l, r, t, b = margins(box, w, h)
        # A symmetric inset is harmless; an asymmetric one shifts the glyph
        # wherever the mask is placed by box rather than by ink.
        if abs(l - r) or abs(t - b):
            report.append(("MASK", n,
                           f"{w}x{h} ink inset L{l} R{r} T{t} B{b} -- lopsided"))


# Sprites whose asymmetry is structural: stretch tiles, stacked frame strips,
# and the minus sign, which aligns to the digits' mid-bar rather than to its
# own cell.
EXPECTED = (
    "GEN.BMP/TOP", "GEN.BMP/BOTTOM", "GEN.BMP/MIDDLE", "GEN.BMP/TEXT",
    "PLEDIT.BMP/TOP_", "PLEDIT.BMP/BOTTOM_", "PLEDIT.BMP/LEFT_TILE",
    "PLEDIT.BMP/RIGHT_TILE", "PLEDIT.BMP/SHADE_BACKGROUND",
    # A bottom-row piece: it carries the window's bottom rule, so its content
    # necessarily touches the bottom edge and cannot be centered in its box.
    "PLEDIT.BMP/VISUALIZER_BACKGROUND",
    "PLEDIT.BMP/TITLE_BAR", "VOLUME.BMP/BACKGROUND", "BALANCE.BMP/BACKGROUND",
    "EQMAIN.BMP/SLIDER_BACKGROUND", "EQMAIN.BMP/TITLE_BAR",
    "TITLEBAR.BMP/TITLE_BAR", "TITLEBAR.BMP/EASTER_EGG",
    "TITLEBAR.BMP/SHADE_BACKGROUND", "NUMBERS.BMP/DIGIT",
    "NUMS_EX.BMP/DIGIT", "NUMS_EX.BMP/MINUS", "EQ_EX.BMP/SHADE_BACKGROUND",
    # Channel indicators: the label sits at the same height whether lit or
    # not. Only the lit sprite carries the indicator bar, which leaves the
    # unlit one's ink high in its cell.
    "MONOSTER.BMP/MONO", "MONOSTER.BMP/STEREO",
    # Connected button groups. EQ/PL and the equalizer's ON/AUTO are segmented
    # controls, so each segment is round on the group's outside and nearly
    # square where it meets its neighbor.
    "SHUFREP.BMP/EQ_BUTTON", "SHUFREP.BMP/PLAYLIST_BUTTON",
    "EQMAIN.BMP/ON_BUTTON", "EQMAIN.BMP/AUTO_BUTTON",
)


def audit_sprites(canvases, report, tolerance=1):
    for sheet, table in S.SPRITES.items():
        if sheet not in canvases:
            continue
        c = canvases[sheet]
        for name in sorted(table):
            if any(f"{sheet}/{name}".startswith(e) for e in EXPECTED):
                continue
            x, y, w, h = S.effective(sheet, name)
            if w < 6 or h < 6:
                continue
            bed = c.get(x, y)
            outer = bbox(c, x, y, w, h, bed)
            if outer is None:
                continue
            ol, orr, ot, ob = margins(outer, w, h)
            if abs(ol - orr) > tolerance or abs(ot - ob) > tolerance:
                report.append(("CONTROL", f"{sheet}/{name}",
                               f"{w}x{h} L{ol} R{orr} T{ot} B{ob}"))
            # Inner: content inside the control, against the control's fill.
            bx, by = x + outer[0], y + outer[1]
            bw, bh = outer[2] - outer[0] + 1, outer[3] - outer[1] + 1
            if bw < 6 or bh < 6:
                continue
            # The inner check assumes a filled container with content on it,
            # and samples the fill just inside the top edge. A toggle drawn
            # without a container has ink there, and the measurement would
            # report the gaps in the glyph as content. A sprite whose sampled
            # fill covers less than half of it is skipped.
            fill = c.get(bx + bw // 2, by + 1)
            interior = [c.get(bx + i, by + j) for j in range(bh) for i in range(bw)]
            if interior.count(fill) * 2 < len(interior):
                continue
            inner = bbox(c, bx + 1, by + 1, bw - 2, bh - 2, fill)
            if inner is None:
                continue
            il, ir, it, ib = margins(inner, bw - 2, bh - 2)
            if abs(il - ir) > tolerance or abs(it - ib) > tolerance:
                report.append(("CONTENT", f"{sheet}/{name}",
                               f"{bw - 2}x{bh - 2} L{il} R{ir} T{it} B{ib}"))


# --- sprite beds -------------------------------------------------------------
# A classic-skin sprite is an opaque rectangle. Whatever it does not paint
# (the pixels outside a rounded button, a disc, a handle) ships as sheet fill
# and lands on the window, so the bed has to be the color the sprite is
# composited onto.
#
# (sheet, sprite, the sheet holding what it lands on, destination x, y).
# Destinations are the Webamp CSS positions; only sprites whose own corner is
# bed rather than content are listed, since the check reads that corner.
BEDS = [
    ("CBUTTONS.BMP", "PREVIOUS", "MAIN.BMP", 16, 88),
    ("CBUTTONS.BMP", "PLAY", "MAIN.BMP", 39, 88),
    ("CBUTTONS.BMP", "EJECT", "MAIN.BMP", 136, 89),
    ("SHUFREP.BMP", "SHUFFLE", "MAIN.BMP", 164, 89),
    ("SHUFREP.BMP", "REPEAT", "MAIN.BMP", 210, 89),
    ("SHUFREP.BMP", "EQ_BUTTON", "MAIN.BMP", 219, 58),
    ("SHUFREP.BMP", "PLAYLIST_BUTTON", "MAIN.BMP", 242, 58),
    ("POSBAR.BMP", "BACKGROUND", "MAIN.BMP", 16, 72),
    ("POSBAR.BMP", "THUMB", "MAIN.BMP", 16, 72),
    ("VOLUME.BMP", "THUMB", "MAIN.BMP", 107, 58),
    ("BALANCE.BMP", "THUMB", "MAIN.BMP", 177, 58),
    ("MONOSTER.BMP", "MONO", "MAIN.BMP", 212, 41),
    ("MONOSTER.BMP", "STEREO", "MAIN.BMP", 239, 41),
    ("PLAYPAUS.BMP", "PLAYING", "MAIN.BMP", 26, 28),
    ("NUMS_EX.BMP", "DIGIT_0_EX", "MAIN.BMP", 48, 26),
    ("TEXT.BMP", "CHAR_97", "MAIN.BMP", 111, 24),
    # Window buttons land on a title bar, whose selected sprite starts at x=27.
    ("TITLEBAR.BMP", "OPTIONS_BUTTON", "TITLEBAR.BMP", 27 + 6, 3),
    ("TITLEBAR.BMP", "MINIMIZE_BUTTON", "TITLEBAR.BMP", 27 + 244, 3),
    ("TITLEBAR.BMP", "SHADE_BUTTON", "TITLEBAR.BMP", 27 + 254, 3),
    ("TITLEBAR.BMP", "CLOSE_BUTTON", "TITLEBAR.BMP", 27 + 264, 3),
    ("TITLEBAR.BMP", "OPTIONS_BUTTON_DEPRESSED", "TITLEBAR.BMP", 27 + 6, 3),
    ("TITLEBAR.BMP", "CLOSE_BUTTON_DEPRESSED", "TITLEBAR.BMP", 27 + 264, 3),
    ("TITLEBAR.BMP", "SHADE_BUTTON_SELECTED", "TITLEBAR.BMP", 27 + 254, 32),
    ("TITLEBAR.BMP", "SHADE_POSITION_BACKGROUND", "TITLEBAR.BMP", 27 + 226, 29 + 4),
    ("EQMAIN.BMP", "ON_BUTTON", "EQMAIN.BMP", 14, 18),
    ("EQMAIN.BMP", "AUTO_BUTTON", "EQMAIN.BMP", 40, 18),
    ("EQMAIN.BMP", "PRESETS_BUTTON", "EQMAIN.BMP", 217, 18),
    ("EQMAIN.BMP", "SLIDER_THUMB", "EQMAIN.BMP", 78, 38),
    # The equalizer close buttons land on its title bar sprite at (0,134).
    ("EQMAIN.BMP", "CLOSE_BUTTON", "EQMAIN.BMP", 264, 134 + 3),
    ("EQMAIN.BMP", "CLOSE_BUTTON_ACTIVE", "EQMAIN.BMP", 264, 134 + 3),
    # Equalizer shade: everything sits on the selected shade bar at (0,0).
    ("EQ_EX.BMP", "SHADE_CLOSE_BUTTON", "EQ_EX.BMP", 264, 3),
    ("EQ_EX.BMP", "SHADE_CLOSE_BUTTON_ACTIVE", "EQ_EX.BMP", 264, 3),
    ("EQ_EX.BMP", "MAXIMIZE_BUTTON_ACTIVE", "EQ_EX.BMP", 254, 3),
    ("EQ_EX.BMP", "MINIMIZE_BUTTON_ACTIVE", "EQ_EX.BMP", 254, 3),
    ("EQ_EX.BMP", "SHADE_VOLUME_SLIDER_LEFT", "EQ_EX.BMP", 61, 4),
    ("EQ_EX.BMP", "SHADE_BALANCE_SLIDER_LEFT", "EQ_EX.BMP", 164, 4),
    # Playlist: title-bar buttons sit in the selected top-right corner piece,
    # the menu grid on the bottom strip, the scroll handle on the right tile.
    ("PLEDIT.BMP", "CLOSE_SELECTED", "PLEDIT.BMP", 153 + 14, 3),
    ("PLEDIT.BMP", "COLLAPSE_SELECTED", "PLEDIT.BMP", 153 + 4, 3),
    ("PLEDIT.BMP", "EXPAND_SELECTED", "PLEDIT.BMP", 153 + 4, 3),
    ("PLEDIT.BMP", "ADD_URL", "PLEDIT.BMP", 14, 80),
    ("PLEDIT.BMP", "SELECT_ALL", "PLEDIT.BMP", 14, 80),
    ("PLEDIT.BMP", "SCROLL_HANDLE", "PLEDIT.BMP", 38, 50),
]


def audit_beds(canvases, report):
    for sheet, sprite, plate, dx, dy in BEDS:
        if sheet not in canvases or plate not in canvases:
            continue
        rect = S.SPRITES[sheet].get(sprite)
        if rect is None:
            report.append(("BED", f"{sheet}/{sprite}", "no such sprite"))
            continue
        bed = canvases[sheet].get(rect[0], rect[1])
        under = canvases[plate].get(dx, dy)
        if bed != under:
            report.append(("BED", f"{sheet}/{sprite}",
                           f"bed #{bed:06X} lands on #{under:06X} "
                           f"at {plate} ({dx},{dy})"))


# --- window buttons against the silhouette -----------------------------------
# REGION.TXT clips the window to the corner arc, and the title-bar buttons sit
# near it. For each glyph row this measures the distance from the glyph's ink
# to the silhouette.
#
# (name, cell x, glyph mask attribute). Cells are the main-window CSS
# positions; all four bars put their buttons at top:3.
BAR_BUTTONS = [("options", 6, "GLYPH_OPTIONS"), ("minimize", 244, "GLYPH_MINIMIZE"),
               ("shade", 254, "GLYPH_SHADE"), ("close", 264, "GLYPH_CLOSE")]
# A glyph row closer to the silhouette than this is reported. The tightest
# clearance per button is kept in MARGINS and not printed.
INK_CLEARANCE = 0


MARGINS = {}


def audit_window_edge(report):
    from andamp import art, shapes, palette as P, draw

    cut = set(shapes.cut_offsets(275, 116, P.STYLE["radius_window"]))
    for name, bx, attr in BAR_BUTTONS:
        mask = getattr(art, attr)
        mw, mh = len(mask[0]), len(mask)
        # draw.icon centers the mask on its ink inside the 9x9 cell.
        ox = bx + (9 - mw) // 2
        oy = 3 + (9 - mh) // 2
        # The cell may poke outside the silhouette: the state layer is a disc,
        # so the corners of the cell are never painted. The ink is measured.
        tightest = 99
        for j, row in enumerate(mask):
            ink = [i for i, ch in enumerate(row) if ch not in (".", " ")]
            if not ink:
                continue
            y = oy + j
            left = min(x for x in range(275) if (x, y) not in cut)
            right = max(x for x in range(275) if (x, y) not in cut)
            gaps = (ox + min(ink) - left, right - (ox + max(ink)))
            tightest = min(tightest, *gaps)
            if min(gaps) < INK_CLEARANCE:
                report.append(("EDGE", f"{name} glyph row {j}",
                               f"clipped by {INK_CLEARANCE - min(gaps)}px"))
        MARGINS[name] = tightest


# (mask attribute, box width, box height, where it is drawn)
ICON_BOXES = [
    ("SMALL_PLAY", 9, 9, "PLAYPAUS state"), ("SMALL_PAUSE", 9, 9, "PLAYPAUS state"),
    ("SMALL_STOP", 9, 9, "PLAYPAUS state"),
    ("PREVIOUS", 21, 16, "CBUTTONS"), ("PAUSE", 21, 16, "CBUTTONS"),
    ("STOP", 21, 16, "CBUTTONS"), ("NEXT", 21, 16, "CBUTTONS"),
    ("EJECT", 20, 14, "CBUTTONS eject"), ("PLAY", 18, 18, "CBUTTONS play disc"),
    ("SHUFFLE", 45, 13, "SHUFREP"), ("REPEAT", 26, 13, "SHUFREP"),
    ("GLYPH_CLOSE", 9, 9, "window button"),
    ("GLYPH_MINIMIZE", 9, 9, "window button"),
    ("GLYPH_SHADE", 9, 9, "window button"),
    ("GLYPH_OPTIONS", 9, 9, "window button"),
]


def audit_fit(report):
    """An icon larger than the box it is stamped into is clipped by Canvas.px
    without an error."""
    from andamp import art

    for name, bw, bh, where in ICON_BOXES:
        mask = getattr(art, name, None)
        if mask is None:
            report.append(("FIT", name, f"missing from the active icon set"))
            continue
        _, _, iw, ih = 0, 0, len(mask[0]), len(mask)
        if iw > bw or ih > bh:
            report.append(("FIT", name,
                           f"{iw}x{ih} does not fit {bw}x{bh} ({where}) "
                           f"-- will be clipped"))


def audit_grid(report):
    """Container edges must land on a declared rail."""
    from andamp.sheets import main, eqmain

    for name, x, y, w, h in main.containers():
        if x not in main.LEFT_RAILS:
            report.append(("GRID", name,
                           f"left edge {x} is not a rail {sorted(main.LEFT_RAILS)}"))
        if x + w - 1 not in main.RIGHT_RAILS:
            report.append(("GRID", name,
                           f"right edge {x + w - 1} is not a rail "
                           f"{sorted(main.RIGHT_RAILS)}"))

    # Equalizer: the card on its rails, and the two slider columns inset equally.
    card, preamp, bands = eqmain.containers()
    if card[1] != eqmain.RAIL_L or card[1] + card[3] - 1 != eqmain.RAIL_R:
        report.append(("GRID", "eq card",
                       f"{card[1]}..{card[1] + card[3] - 1} is not "
                       f"{eqmain.RAIL_L}..{eqmain.RAIL_R}"))
    left_gap = preamp[1] - eqmain.RAIL_L
    right_gap = eqmain.RAIL_R - (bands[1] + bands[3] - 1)
    if left_gap != right_gap or left_gap != eqmain.WELL_INSET:
        report.append(("GRID", "eq slider columns",
                       f"inset left {left_gap}, right {right_gap}, "
                       f"expected {eqmain.WELL_INSET} both"))


def run(canvases):
    report = []
    audit_grid(report)
    audit_fit(report)
    audit_masks(report)
    audit_sprites(canvases, report)
    audit_beds(canvases, report)
    audit_window_edge(report)
    print(f"\n=== geometry audit: {P.THEME_NAME} ===")
    by_kind = {}
    for kind, name, detail in report:
        by_kind.setdefault(kind, []).append((name, detail))
    for kind in ("GRID", "FIT", "MASK", "CONTROL", "CONTENT", "BED", "EDGE"):
        rows = by_kind.get(kind, [])
        print(f"\n{kind}: {len(rows)}")
        for name, detail in rows:
            print(f"  {name:46s} {detail}")
    return report


if __name__ == "__main__":
    from build import BUILDERS
    run({n: fn() for n, fn in BUILDERS.items()})
