# SPDX-License-Identifier: GPL-3.0-or-later

"""The skin with its colors factored out.

Every sheet is rebuilt with one sentinel per role in place of a color, so each
pixel records which role drew it. The output is a byte per pixel naming a role,
plus `roles.py`'s table saying where each role comes from.

The role image is identical between `dark` and `light`: the two share STYLE,
and the aliases that differ by scheme differ in what they bind to. Each theme
still gets a template of its own, because the manifest fixes the scheme and
the role table.

Rebinding the role image to a theme's palette reproduces that theme's sheets
and text files byte for byte; `build.py template` checks it. A runtime that
can resolve the table (an M3 ColorScheme lookup, a blend and a tonal
restatement) builds the skin without drawing code of its own.
"""

import json
import zipfile

from . import roles as R
from .tokens import load

FORMAT_VERSION = 1


def _sentinel_palette(module):
    """Give every role a unique small integer and return the order.

    The sheets read colors off `palette`, so replacing the attributes before
    the sheets are imported makes the same drawing code emit indices.
    """
    # Lowercase scalars are the semantic aliases; the uppercase ones are the
    # two single-color entries Winamp reads out of VISCOLOR.TXT by position.
    names = sorted(k for k in dir(module)
                   if not k.startswith("_") and k not in ("HOVER", "PRESSED", "DISABLED")
                   and isinstance(getattr(module, k), int)
                   and not isinstance(getattr(module, k), bool))
    ramps = {k: getattr(module, k) for k in dir(module)
             if k.isupper() and isinstance(getattr(module, k), list)
             and getattr(module, k) and isinstance(getattr(module, k)[0], int)}
    order = []
    for name in names:
        setattr(module, name, len(order))
        order.append(name)
    for name, values in sorted(ramps.items()):
        setattr(module, name, [len(order) + i for i in range(len(values))])
        order.extend(f"{name}[{i}]" for i in range(len(values)))
    # The canvas refuses a color that is not a declared token. Here the colors
    # are role numbers, so the allowed set becomes those.
    from . import canvas
    canvas.ALLOWED = set(range(len(order)))
    return order


def emit(path, canvases, text_files):
    """Write the template zip. Must run in a process whose `palette` has been
    replaced by `_sentinel_palette`, so `canvases` hold indices."""
    from . import palette as P

    _, theme = load()
    m3 = list(theme.ROLES) if hasattr(theme, "ROLES") else list(theme.TONES)
    entries = R.table(P.STYLE, "dark" if P.DARK else "light", m3)

    order = P.__dict__["_TEMPLATE_ROLE_ORDER"]
    index = {name: i for i, name in enumerate(order)}
    # The table is what a runtime resolves; the order is what the pixels index.
    # Every table entry must be in the order.
    unknown = [n for n, _ in entries if n not in index]
    if unknown:
        raise SystemExit(f"template: table names {unknown} which the pixels cannot index")

    blob = bytearray()
    sheets = []
    for name in sorted(canvases):
        c = canvases[name]
        if max(c.px_data) > 255:
            raise SystemExit(f"template: {name} uses role {max(c.px_data)}, over a byte")
        sheets.append({"name": name, "w": c.w, "h": c.h, "offset": len(blob)})
        blob.extend(bytes(c.px_data))

    manifest = {
        "format": FORMAT_VERSION,
        "theme": P.THEME_NAME,
        "scheme": "dark" if P.DARK else "light",
        "roles": [_expr(name, expr, index) for name, expr in entries],
        "role_order": order,
        "sheets": sheets,
        "text": text_files,
        "contrast": [{"name": n, "fg": _role(fg, order), "bg": _role(bg, order),
                      "min": 4.5 if kind == "text" else 3.0}
                     for n, fg, bg, kind in P.PAIRS],
    }
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        _add(z, "manifest.json", json.dumps(manifest, indent=1).encode())
        _add(z, "sheets.idx", bytes(blob))
    return manifest, bytes(blob)


def _expr(name, expr, index):
    if isinstance(expr, R.M3):
        return {"name": name, "kind": "m3", "role": expr.name}
    if isinstance(expr, R.Alias):
        return {"name": name, "kind": "alias", "of": expr.role}
    if isinstance(expr, R.Blend):
        return {"name": name, "kind": "blend", "a": expr.a, "b": expr.b, "t": expr.t}
    if isinstance(expr, R.Tone):
        return {"name": name, "kind": "tone", "of": expr.role, "t": expr.t}
    if isinstance(expr, R.Literal):
        return {"name": name, "kind": "literal", "value": expr.value}
    raise TypeError(expr)


def _role(name, order):
    """Return `name`, or stop the build when a contrast pair names a role the
    order does not have."""
    if name not in order:
        raise SystemExit(f"template: contrast pair names unknown role {name!r}")
    return name


def _add(z, name, data):
    info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    z.writestr(info, data)


def bind(manifest, blob, colours):
    """Rebind a template to real colors: the reference for what the Kotlin
    side does, and what `build.py template` checks the build against."""
    lut = [colours[name] for name in manifest["role_order"]]
    out = {}
    for sheet in manifest["sheets"]:
        n = sheet["w"] * sheet["h"]
        start = sheet["offset"]
        out[sheet["name"]] = [lut[b] for b in blob[start:start + n]]
    return out


# --- generation -------------------------------------------------------------
# Runs as its own process: the sheets read colors off `palette` at import as
# well as at draw time, so the substitution happens before any of them is
# imported.

def _text_with_placeholders(order):
    """The three text files, with every color replaced by the role that put it
    there. PLEDIT.TXT writes #RRGGBB and VISCOLOR.TXT writes r,g,b, so both are
    recoverable from the sentinel; REGION.TXT carries no color."""
    from . import textfiles
    out = {}
    for name, fn in (("PLEDIT.TXT", textfiles.pledit_txt),
                     ("VISCOLOR.TXT", textfiles.viscolor_txt),
                     ("REGION.TXT", textfiles.region_txt)):
        text = fn()
        # The placeholder names the format to write back: `hex` for
        # PLEDIT.TXT's #RRGGBB, `rgb` for VISCOLOR.TXT's r,g,b. Highest role
        # first, so role 1 is not substituted inside role 11.
        for i in range(len(order) - 1, -1, -1):
            text = text.replace("#%06X" % i, "{{%s|hex}}" % order[i])
            text = text.replace("0,0,%d" % i, "{{%s|rgb}}" % order[i])
        out[name] = text
    return out


def _main(out_path):
    import sys
    from . import palette as P

    order = _sentinel_palette(P)
    P.__dict__["_TEMPLATE_ROLE_ORDER"] = order
    if len(order) > 256:
        sys.exit(f"template: {len(order)} roles, over a byte")

    from build import BUILDERS
    canvases = {name: fn() for name, fn in BUILDERS.items()}
    emit(out_path, canvases, _text_with_placeholders(order))
    print(f"template: {len(order)} roles, {len(canvases)} sheets -> {out_path}")


if __name__ == "__main__":
    import sys
    _main(sys.argv[1])
