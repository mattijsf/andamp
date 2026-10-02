# SPDX-License-Identifier: GPL-3.0-or-later

"""PNG encoder for preview images only. A .wsz holds no PNG: classic Winamp
cannot read one.
"""

import struct
import zlib


def _chunk(tag: bytes, data: bytes) -> bytes:
    return (
        struct.pack(">I", len(data))
        + tag
        + data
        + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    )


def encode(width: int, height: int, pixels, scale: int = 1) -> bytes:
    raw = bytearray()
    for y in range(height):
        for _ in range(scale):
            raw.append(0)  # filter: none
            base = y * width
            for x in range(width):
                c = pixels[base + x]
                r, g, b = (c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF
                for _ in range(scale):
                    raw += bytes((r, g, b))
    return (
        b"\x89PNG\r\n\x1a\n"
        + _chunk(b"IHDR", struct.pack(">IIBBBBB", width * scale, height * scale, 8, 2, 0, 0, 0))
        + _chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + _chunk(b"IEND", b"")
    )
