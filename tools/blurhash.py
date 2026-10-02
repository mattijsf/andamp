#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""BlurHash encoder, from the algorithm in github.com/woltapp/blurhash (MIT).

Prints one hash per PNG given on the command line. A hash is a handful of DCT
coefficients: a short string describing where a picture is light and dark.
"""
import math
import struct
import sys
import zlib

B83 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~"


def read_png(path):
    d = open(path, "rb").read()
    pos, idat = 8, b""
    while pos < len(d):
        ln = struct.unpack(">I", d[pos:pos + 4])[0]
        typ = d[pos + 4:pos + 8]
        data = d[pos + 8:pos + 8 + ln]
        pos += 12 + ln
        if typ == b"IHDR":
            w, h, depth, ct = struct.unpack(">IIBB", data[:10])
        elif typ == b"IDAT":
            idat += data
    assert depth == 8, depth
    bpp = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ct]
    raw = zlib.decompress(idat)
    stride = w * bpp
    out, prev, i = [], bytearray(stride), 0
    for _ in range(h):
        f = raw[i]; i += 1
        line = bytearray(raw[i:i + stride]); i += stride
        if f == 1:
            for x in range(bpp, stride):
                line[x] = (line[x] + line[x - bpp]) & 255
        elif f == 2:
            for x in range(stride):
                line[x] = (line[x] + prev[x]) & 255
        elif f == 3:
            for x in range(stride):
                a = line[x - bpp] if x >= bpp else 0
                line[x] = (line[x] + ((a + prev[x]) >> 1)) & 255
        elif f == 4:
            for x in range(stride):
                a = line[x - bpp] if x >= bpp else 0
                b = prev[x]
                c = prev[x - bpp] if x >= bpp else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[x] = (line[x] + pr) & 255
        out.append(bytes(line)); prev = line
    return w, h, bpp, out


def srgb_to_linear(v):
    v /= 255.0
    return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4


def linear_to_srgb(v):
    v = max(0.0, min(1.0, v))
    s = v * 12.92 if v <= 0.0031308 else 1.055 * v ** (1 / 2.4) - 0.055
    return int(s * 255 + 0.5)


def sign_pow(v, e):
    return math.copysign(abs(v) ** e, v)


def b83(value, length):
    return "".join(B83[(value // (83 ** (length - 1 - i))) % 83] for i in range(length))


def encode(path, nx=4, ny=3):
    w, h, bpp, rows = read_png(path)

    def px(x, y):
        o = x * bpp
        r, g, b = rows[y][o], rows[y][o + 1], rows[y][o + 2]
        return srgb_to_linear(r), srgb_to_linear(g), srgb_to_linear(b)

    factors = []
    for j in range(ny):
        for i in range(nx):
            norm = 1.0 if (i == 0 and j == 0) else 2.0
            r = g = b = 0.0
            for y in range(h):
                cy = math.cos(math.pi * j * y / h)
                for x in range(w):
                    base = norm * math.cos(math.pi * i * x / w) * cy
                    pr, pg, pb = px(x, y)
                    r += base * pr; g += base * pg; b += base * pb
            n = w * h
            factors.append((r / n, g / n, b / n))

    dc, ac = factors[0], factors[1:]
    size_flag = (nx - 1) + (ny - 1) * 9
    out = b83(size_flag, 1)
    if ac:
        actual = max(max(abs(v) for v in f) for f in ac)
        quant = max(0, min(82, int(actual * 166 - 0.5)))
        maximum = (quant + 1) / 166
        out += b83(quant, 1)
    else:
        maximum = 1.0
        out += b83(0, 1)
    out += b83((linear_to_srgb(dc[0]) << 16) + (linear_to_srgb(dc[1]) << 8) + linear_to_srgb(dc[2]), 4)
    for f in ac:
        vals = [max(0, min(18, int(sign_pow(v / maximum, 0.5) * 9 + 9.5))) for v in f]
        out += b83(vals[0] * 19 * 19 + vals[1] * 19 + vals[2], 2)
    return out


if __name__ == "__main__":
    for p in sys.argv[1:]:
        print(f"{p.split('/')[-1].split('.')[0]}  {encode(p)}")
