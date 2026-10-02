# SPDX-License-Identifier: GPL-3.0-or-later

"""Text and small composite helpers layered over Canvas."""

from . import palette as _P
from .glyphs import FONT_5x6, DIGIT_ORIGIN, GEN_FONT, digits_for

DIGITS = digits_for(_P.STYLE["digits"])


def _glyph(ch):
    return FONT_5x6.get(ch.lower()) or FONT_5x6.get(ch) or FONT_5x6[" "]


def ink_extents(s, spacing=0):
    """Bounding box of the ink a string paints, as (left, top, width, height).

    Every 5px glyph cell carries a 1px right-hand gutter, so centering on
    len*5 would put a label half a pixel left.
    """
    pitch = 5 + spacing
    xs, ys = [], []
    for i, ch in enumerate(s):
        for j, row in enumerate(_glyph(ch)):
            for k, c in enumerate(row):
                if c == "#":
                    xs.append(i * pitch + k)
                    ys.append(j)
    if not xs:
        return 0, 0, 0, 0
    return min(xs), min(ys), max(xs) - min(xs) + 1, max(ys) - min(ys) + 1


def mask_extents(mask):
    xs = [i for r in mask for i, ch in enumerate(r) if ch == "#"]
    ys = [j for j, r in enumerate(mask) for ch in r if ch == "#"]
    if not xs:
        return 0, 0, 0, 0
    return min(xs), min(ys), max(xs) - min(xs) + 1, max(ys) - min(ys) + 1


def text(canvas, x, y, s, color, spacing=0):
    """Draw with the 5x6 face. Unknown characters fall back to space."""
    cx = x
    for ch in s:
        rows = FONT_5x6.get(ch.lower()) or FONT_5x6.get(ch) or FONT_5x6[" "]
        canvas.stamp(cx, y, rows, {"#": color})
        cx += 5 + spacing
    return cx


def text_centered(canvas, x, y, w, h, s, color, spacing=0):
    ix, iy, iw, ih = ink_extents(s, spacing)
    return text(canvas, x + (w - iw) // 2 - ix, y + (h - ih) // 2 - iy,
                s, color, spacing)


def digit(canvas, x, y, d, color):
    """Draw one 7x11 numeral of the theme's digit face inside its 9x13 cell at (x, y)."""
    ox, oy = DIGIT_ORIGIN
    canvas.stamp(x + ox, y + oy, DIGITS[d], {"#": color})


def gen_text(canvas, x, y, s, color, delimiter=None):
    """GEN.BMP variable-width caps. Returns the x after the last glyph."""
    cx = x
    for ch in s.upper():
        rows = GEN_FONT.get(ch)
        if rows is None:
            cx += 4
            continue
        canvas.stamp(cx, y, rows, {"#": color})
        cx += len(rows[0])
        if delimiter is not None:
            canvas.vline(cx, y, len(rows), delimiter)
            cx += 1
    return cx


def icon(canvas, x, y, w, h, mask, color, ax=0.5, ay=0.5, dx=0, dy=0):
    """Center an icon's ink inside a w*h box, ignoring padding baked into the
    mask. dx/dy apply an optical nudge.
    """
    ix, iy, iw, ih = mask_extents(mask)
    canvas.stamp(x + int((w - iw) * ax) - ix + dx,
                 y + int((h - ih) * ay) - iy + dy, mask, {"#": color})
