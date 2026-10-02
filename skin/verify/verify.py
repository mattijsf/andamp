# SPDX-License-Identifier: GPL-3.0-or-later

"""Static invariants for the built skin: dimensions, palette, roles, contrast,
sprite states, text files, BMP format, archive layout and geometry. Any failure
fails the build.
"""

import io
import re
import zipfile

from andamp import canvas, palette as P, tonal as T
from spec import sprites as S


class Report:
    def __init__(self):
        self.failures = []
        self.checks = 0

    def ok(self, cond, message):
        self.checks += 1
        if not cond:
            self.failures.append(message)


def check_dimensions(r, canvases):
    for name, c in canvases.items():
        w, h = S.SHEETS[name]
        r.ok((c.w, c.h) == (w, h),
             f"{name}: is {c.w}x{c.h}, spec says {w}x{h}")


def check_palette(r, canvases):
    """Every pixel is a declared token. Anti-aliasing, a gradient or a
    resample would introduce a color that was never declared."""
    for name, c in canvases.items():
        stray = c.colors() - canvas.ALLOWED
        r.ok(not stray,
             f"{name}: {len(stray)} off-palette colours "
             + ", ".join(f"#{v:06X}" for v in sorted(stray)[:4]))


def check_sprite_bounds(r, canvases):
    for sheet, table in S.SPRITES.items():
        if sheet not in canvases:
            continue
        c = canvases[sheet]
        for name in table:
            x, y, w, h = S.effective(sheet, name)
            r.ok(x >= 0 and y >= 0 and x + w <= c.w and y + h <= c.h,
                 f"{sheet}/{name}: {x},{y},{w},{h} outside {c.w}x{c.h}")


def check_contrast(r, _canvases):
    for name, fg_role, bg_role, kind in P.PAIRS:
        need = 4.5 if kind == "text" else 3.0
        fg, bg = P.colour_of(fg_role), P.colour_of(bg_role)
        got = T.contrast(fg, bg)
        r.ok(got >= need,
             f"contrast '{name}': {got:.2f}:1, needs {need}:1 "
             f"({T.hexs(fg)} on {T.hexs(bg)})")


def check_distinct(r, canvases):
    """A pressed or selected state must not be pixel-identical to its normal
    state."""
    for sheet, names in S.DISTINCT:
        if sheet not in canvases:
            continue
        c = canvases[sheet]
        seen = {}
        for name in names:
            x, y, w, h = S.effective(sheet, name)
            key = tuple(c.sub(x, y, w, h).px_data)
            r.ok(key not in seen,
                 f"{sheet}: '{name}' is pixel-identical to '{seen.get(key)}'")
            seen[key] = name


def check_sprites_not_blank(r, canvases):
    """Every named sprite must have at least two colors in it; a flat
    rectangle means the artwork for it was never drawn."""
    exempt = {("PLAYPAUS.BMP", "NOT_WORKING"), ("NUMBERS.BMP", "BLANK"),
              ("NUMS_EX.BMP", "NO_MINUS_SIGN_EX"),
              ("NUMBERS.BMP", "NO_MINUS_SIGN"), ("TEXT.BMP", "CHAR_32"),
              ("EQMAIN.BMP", "GRAPH_LINE_COLORS"),
              ("NUMBERS.BMP", "MINUS_SIGN")}
    for sheet, table in S.SPRITES.items():
        if sheet not in canvases:
            continue
        c = canvases[sheet]
        for name in table:
            if (sheet, name) in exempt:
                continue
            x, y, w, h = S.effective(sheet, name)
            r.ok(len(set(c.sub(x, y, w, h).px_data)) > 1,
                 f"{sheet}/{name}: flat rectangle, nothing drawn")


def check_text_files(r, members):
    pledit = members["PLEDIT.TXT"].decode()
    for key in ("Normal", "Current", "NormalBG", "SelectedBG", "Font"):
        r.ok(re.search(rf"^{key}=", pledit, re.M), f"PLEDIT.TXT: missing {key}")
    for m in re.finditer(r"^\w+=(#[0-9A-Fa-f]{6})$", pledit, re.M):
        r.ok(len(m.group(1)) == 7, f"PLEDIT.TXT: bad colour {m.group(1)}")

    vis = members["VISCOLOR.TXT"].decode().splitlines()
    parsed = [l for l in vis if re.match(r"^\s*(\d+)\s*,?\s*(\d+)\s*,?\s*(\d+)", l)]
    r.ok(len(parsed) == 24, f"VISCOLOR.TXT: {len(parsed)} parseable lines, need 24")

    region = members["REGION.TXT"].decode()
    for section in ("Normal", "WindowShade", "Equalizer", "EqualizerWS"):
        r.ok(f"[{section}]" in region, f"REGION.TXT: missing [{section}]")
    counts = [int(v) for v in re.findall(r"^NumPoints=(.+)$", region, re.M)
              for v in v.split(",")]
    lists = re.findall(r"^PointList=(.+)$", region, re.M)
    r.ok(len(counts) == len(lists), "REGION.TXT: NumPoints/PointList mismatch")
    for n, pts in zip(counts, lists):
        pairs = [p for p in re.split(r"\s*[, ]\s*", pts.strip()) if p]
        r.ok(len(pairs) == n * 2,
             f"REGION.TXT: polygon declares {n} points but lists {len(pairs)//2}")
        r.ok(n >= 3, f"REGION.TXT: polygon with {n} points is degenerate")


def check_archive(r, path_or_bytes):
    """Classic Winamp reads BMP only, may not look inside a folder in the
    archive, and extracts to a case-insensitive filesystem."""
    data = (path_or_bytes if isinstance(path_or_bytes, bytes)
            else path_or_bytes.read_bytes())
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        names = z.namelist()
    for n in names:
        r.ok("/" not in n and "\\" not in n, f"archive: {n} is inside a folder")
        # the readme is not part of the skin format and is exempt from its
        # naming rules
        if n.lower() == "readme.txt":
            continue
        r.ok(n == n.upper(), f"archive: {n} is not uppercase")
        r.ok(n.endswith((".BMP", ".TXT")),
             f"archive: {n} is neither a BMP nor a TXT")
    r.ok(len(names) == len(set(n.upper() for n in names)),
         "archive: case-insensitive filename collision")
    required = {"MAIN.BMP", "TITLEBAR.BMP", "CBUTTONS.BMP", "SHUFREP.BMP",
                "POSBAR.BMP", "VOLUME.BMP", "MONOSTER.BMP", "PLAYPAUS.BMP",
                "NUMBERS.BMP", "TEXT.BMP", "EQMAIN.BMP", "PLEDIT.BMP",
                "PLEDIT.TXT", "VISCOLOR.TXT"}
    missing = required - set(names)
    r.ok(not missing, f"archive: missing {sorted(missing)}")


def check_bmp_format(r, members):
    """24-bit, uncompressed, bottom-up, with a 40-byte header."""
    import struct
    for name, data in members.items():
        if not name.endswith(".BMP"):
            continue
        r.ok(data[:2] == b"BM", f"{name}: not a BMP")
        size, width, height, planes, bits, comp = struct.unpack_from("<IiiHHI",
                                                                    data, 14)
        r.ok(size == 40, f"{name}: header is {size} bytes, expected 40")
        r.ok(bits == 24, f"{name}: {bits}bpp, expected 24")
        r.ok(comp == 0, f"{name}: compression {comp}, expected 0 (BI_RGB)")
        r.ok(height > 0, f"{name}: top-down rows, expected bottom-up")


def check_roles(r, canvases):
    """`roles.py` states where every palette value comes from; `palette.py`
    builds the values. This asserts they agree entry for entry, and that
    nothing is painted that the table does not name: a color computed inside a
    sheet could not be rebound to a live scheme."""
    from andamp import roles as R, tonal as T
    from andamp.tokens import load

    _, theme = load()
    m3 = list(theme.ROLES) if hasattr(theme, "ROLES") else list(theme.TONES)
    entries = R.table(P.STYLE, "dark" if P.DARK else "light", m3)
    resolved = R.resolve(entries,
                         m3=lambda n: getattr(P, n),
                         blend=T.blend,
                         tone=lambda c, t: T.Palette("#%06X" % c).tone(t))
    for name, value in resolved.items():
        if "[" in name:
            base, i = name[:-1].split("[")
            want = getattr(P, base)[int(i)]
        else:
            want = getattr(P, name)
        r.ok(want == value,
             f"role '{name}': table gives #{value:06X}, palette has #{want:06X}")
    painted = set().union(*(c.colors() for c in canvases.values()))
    for colour in sorted(painted - set(resolved.values())):
        r.ok(False, f"painted #{colour:06X} is not in the role table")

def run(canvases, members, archive=None):
    import pathlib
    r = Report()
    check_dimensions(r, canvases)
    check_palette(r, canvases)
    check_roles(r, canvases)
    check_sprite_bounds(r, canvases)
    check_contrast(r, canvases)
    check_distinct(r, canvases)
    check_sprites_not_blank(r, canvases)
    check_text_files(r, members)
    check_bmp_format(r, members)
    check_archive(r, archive or pathlib.Path("dist") / P.SKIN_FILE)

    # Geometry: icons that would be clipped by the box they are drawn into,
    # masks whose ink is lopsided inside their own grid, and any control or
    # glyph whose margins are off by more than the half-pixel that an
    # odd-into-even fit makes unavoidable.
    from verify.audit import (audit_beds, audit_fit, audit_grid, audit_masks,
                              audit_sprites, audit_window_edge)
    geometry = []
    audit_grid(geometry)
    audit_fit(geometry)
    audit_masks(geometry)
    audit_sprites(canvases, geometry)
    audit_beds(canvases, geometry)
    audit_window_edge(geometry)
    r.checks += 1
    for kind, name, detail in geometry:
        r.ok(False, f"geometry {kind.lower()} '{name}': {detail}")

    if r.failures:
        print(f"\nFAILED {len(r.failures)} of {r.checks} checks:")
        for f in r.failures:
            print("  -", f)
        return 1
    print(f"verify: {r.checks} checks passed")
    return 0
