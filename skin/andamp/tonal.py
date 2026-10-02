# SPDX-License-Identifier: GPL-3.0-or-later

"""CIELAB tonal palettes: the M3 tone axis without a dependency.

Material 3 builds every role from a tonal palette: a fixed hue and chroma swept
across a lightness axis called tone. M3's HCT takes hue and chroma from CAM16
and tone from CIELAB L*; this module takes all three from CIELAB. Tone
differences keep M3's contrast guarantees (a delta of 40 or more gives 3:1, 50
or more gives 4.5:1).
"""

import math

_XN, _YN, _ZN = 0.95047, 1.0, 1.08883


def _srgb_to_linear(c: float) -> float:
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def _linear_to_srgb(c: float) -> float:
    return 12.92 * c if c <= 0.0031308 else 1.055 * (c ** (1 / 2.4)) - 0.055


def unpack(rgb: int):
    return ((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF)


def pack(r: int, g: int, b: int) -> int:
    return (r << 16) | (g << 8) | b


def parse(value) -> int:
    if isinstance(value, int):
        return value
    return int(value.lstrip("#"), 16)


def hexs(rgb: int) -> str:
    return f"#{rgb:06X}"


def to_lab(rgb: int):
    r, g, b = (_srgb_to_linear(v / 255.0) for v in unpack(rgb))
    x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / _XN
    y = (0.2126729 * r + 0.7151522 * g + 0.0721750 * b) / _YN
    z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / _ZN

    def f(t):
        return t ** (1 / 3) if t > 216 / 24389 else (24389 / 27 * t + 16) / 116

    fx, fy, fz = f(x), f(y), f(z)
    return (116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))


def _lab_to_rgb_float(L: float, a: float, bb: float):
    fy = (L + 16) / 116
    fx = fy + a / 500
    fz = fy - bb / 200

    def finv(t):
        t3 = t ** 3
        return t3 if t3 > 216 / 24389 else (116 * t - 16) * 27 / 24389

    x, y, z = finv(fx) * _XN, finv(fy) * _YN, finv(fz) * _ZN
    r = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z
    g = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z
    b = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z
    return tuple(_linear_to_srgb(v) for v in (r, g, b))


def _in_gamut(c) -> bool:
    return all(-0.0005 <= v <= 1.0005 for v in c)


def from_lch(L: float, C: float, h_deg: float) -> int:
    """Lab -> sRGB, reducing chroma until the color fits the gamut.

    Binary search over chroma with a fixed iteration count, so the result is
    the same on every run.
    """
    h = math.radians(h_deg)
    lo, hi = 0.0, C
    if _in_gamut(_lab_to_rgb_float(L, C * math.cos(h), C * math.sin(h))):
        lo = C
    else:
        for _ in range(32):
            mid = (lo + hi) / 2
            if _in_gamut(_lab_to_rgb_float(L, mid * math.cos(h), mid * math.sin(h))):
                lo = mid
            else:
                hi = mid
    rgbf = _lab_to_rgb_float(L, lo * math.cos(h), lo * math.sin(h))
    return pack(*(max(0, min(255, int(round(v * 255)))) for v in rgbf))


class Palette:
    """One M3 tonal palette: fixed hue + chroma, indexed by tone 0..100."""

    def __init__(self, seed, chroma=None):
        L, a, b = to_lab(parse(seed))
        self.hue = math.degrees(math.atan2(b, a)) % 360
        self.chroma = math.hypot(a, b) if chroma is None else float(chroma)

    def tone(self, t: float) -> int:
        return from_lch(float(t), self.chroma, self.hue)


def luminance(rgb: int) -> float:
    r, g, b = (_srgb_to_linear(v / 255.0) for v in unpack(rgb))
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast(a: int, b: int) -> float:
    """WCAG 2.x relative contrast ratio."""
    la, lb = luminance(a), luminance(b)
    if la < lb:
        la, lb = lb, la
    return (la + 0.05) / (lb + 0.05)


def blend(a: int, b: int, t: float) -> int:
    """Composite b over a at opacity t, in linear light. Bakes M3 state layers
    and the ramps into fixed palette entries."""
    out = []
    for ca, cb in zip(unpack(a), unpack(b)):
        la = _srgb_to_linear(ca / 255.0)
        lb = _srgb_to_linear(cb / 255.0)
        out.append(max(0, min(255, int(round(_linear_to_srgb(la * (1 - t) + lb * t) * 255)))))
    return pack(*out)
