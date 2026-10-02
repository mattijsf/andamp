# SPDX-License-Identifier: GPL-3.0-or-later

"""Integer pixel canvas.

No primitive here blends, eases, resamples or shades. Every operation writes
one registered palette color into one pixel, so the output has no anti-aliasing
and stays crisp under Winamp's nearest-neighbor integer scaling.

Colors must be registered with allow() before use; painting an unregistered
color raises.
"""

import math

ALLOWED = set()


def allow(color: int) -> int:
    ALLOWED.add(color)
    return color


class Canvas:
    def __init__(self, width: int, height: int, fill: int):
        self.w = width
        self.h = height
        self.px_data = [self._check(fill)] * (width * height)

    @staticmethod
    def _check(color: int) -> int:
        if color not in ALLOWED:
            raise ValueError(
                f"unregistered colour #{color:06X} -- register it in palette.py. "
                "Every drawn colour must be a declared token."
            )
        return color

    # ---- primitives -----------------------------------------------------
    def px(self, x: int, y: int, c: int):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px_data[y * self.w + x] = self._check(c)

    def get(self, x: int, y: int) -> int:
        return self.px_data[y * self.w + x]

    def hline(self, x: int, y: int, w: int, c: int):
        for i in range(w):
            self.px(x + i, y, c)

    def vline(self, x: int, y: int, h: int, c: int):
        for i in range(h):
            self.px(x, y + i, c)

    def rect(self, x: int, y: int, w: int, h: int, c: int):
        for j in range(h):
            self.hline(x, y + j, w, c)

    def frame(self, x: int, y: int, w: int, h: int, c: int):
        self.hline(x, y, w, c)
        self.hline(x, y + h - 1, w, c)
        self.vline(x, y + 1, h - 2, c)
        self.vline(x + w - 1, y + 1, h - 2, c)

    # ---- shapes ---------------------------------------------------------
    def _shape(self, w, h, radius, corners, clamp=True):
        from . import shapes

        cut = set(shapes.cut_offsets(w, h, radius, corners, clamp))

        def inside(i, j):
            return 0 <= i < w and 0 <= j < h and (i, j) not in cut

        return inside

    def rrect(self, x, y, w, h, c, radius=0, corners=None, clamp=True):
        """Filled rectangle with pixel-art rounded corners. Cut corner pixels
        are left untouched, so whatever was underneath shows through."""
        inside = self._shape(w, h, radius, corners, clamp)
        for j in range(h):
            for i in range(w):
                if inside(i, j):
                    self.px(x + i, y + j, c)

    def rframe(self, x, y, w, h, c, radius=0, corners=None, clamp=True):
        """1px outline tracing the same rounded silhouette as rrect()."""
        inside = self._shape(w, h, radius, corners, clamp)
        for j in range(h):
            for i in range(w):
                if not inside(i, j):
                    continue
                if not (inside(i - 1, j) and inside(i + 1, j)
                        and inside(i, j - 1) and inside(i, j + 1)):
                    self.px(x + i, y + j, c)

    def stamp(self, x: int, y: int, rows, palette):
        """Paint a character-grid sprite. '.' or ' ' leaves the pixel alone."""
        for j, row in enumerate(rows):
            for i, ch in enumerate(row):
                if ch in (".", " "):
                    continue
                self.px(x + i, y + j, palette[ch])

    def wave(self, x, y, length, c, amplitude=1, wavelength=10,
             flat_head=0, flat_tail=0):
        """M3 Expressive wavy track: a flat lead-in and tail, and between them
        a sampled sine quantized to whole pixels."""
        for i in range(length):
            if i < flat_head or i >= length - flat_tail:
                dy = 0
            else:
                phase = (i - flat_head) / wavelength
                dy = math.floor(amplitude * math.sin(2 * math.pi * phase) + 0.5)
            self.px(x + i, y + dy, c)

    def blit(self, src: "Canvas", sx=0, sy=0, w=None, h=None, dx=0, dy=0):
        w = src.w if w is None else w
        h = src.h if h is None else h
        for j in range(h):
            for i in range(w):
                self.px(dx + i, dy + j, src.get(sx + i, sy + j))

    def sub(self, x, y, w, h) -> "Canvas":
        out = Canvas.__new__(Canvas)
        out.w, out.h = w, h
        out.px_data = [self.get(x + i, y + j) for j in range(h) for i in range(w)]
        return out

    def colors(self):
        return set(self.px_data)
