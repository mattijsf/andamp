# SPDX-License-Identifier: GPL-3.0-or-later

"""24-bit uncompressed BMP encoder, standard library only.

Classic Winamp reads BMP and nothing else, so this is the only image format
the skin ships: 24-bit BITMAPINFOHEADER with biCompression=0.
"""

import struct


def encode(width: int, height: int, pixels) -> bytes:
    """pixels: flat sequence of packed 0xRRGGBB ints, row-major, top-left origin."""
    if len(pixels) != width * height:
        raise ValueError(f"expected {width * height} pixels, got {len(pixels)}")
    pad = b"\0" * ((-(width * 3)) % 4)
    rows = []
    for y in range(height - 1, -1, -1):  # BMP scanlines are bottom-up
        base = y * width
        row = bytearray()
        for x in range(width):
            c = pixels[base + x]
            row.append(c & 0xFF)          # B
            row.append((c >> 8) & 0xFF)   # G
            row.append((c >> 16) & 0xFF)  # R
        row += pad
        rows.append(bytes(row))
    body = b"".join(rows)
    offset = 14 + 40
    out = bytearray(b"BM")
    out += struct.pack("<IHHI", offset + len(body), 0, 0, offset)
    out += struct.pack(
        "<IiiHHIIiiII",
        40,            # biSize
        width,
        height,        # positive => bottom-up
        1,             # biPlanes
        24,            # biBitCount
        0,             # biCompression = BI_RGB
        len(body),
        2835, 2835,    # 72 dpi
        0, 0,
    )
    out += body
    return bytes(out)


def decode(data: bytes):
    """Minimal reader for the subset we write. Returns (width, height, pixels)."""
    if data[:2] != b"BM":
        raise ValueError("not a BMP")
    offset = struct.unpack_from("<I", data, 10)[0]
    hdr = struct.unpack_from("<IiiHHI", data, 14)
    _, width, height, _, bits, comp = hdr
    if bits != 24 or comp != 0:
        raise ValueError(f"unsupported BMP variant: {bits}bpp compression={comp}")
    stride = (width * 3 + 3) & ~3
    pixels = [0] * (width * height)
    for y in range(height):
        src = offset + (height - 1 - y) * stride
        dst = y * width
        for x in range(width):
            i = src + x * 3
            pixels[dst + x] = (data[i + 2] << 16) | (data[i + 1] << 8) | data[i]
    return width, height, pixels
