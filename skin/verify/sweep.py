# SPDX-License-Identifier: GPL-3.0-or-later

"""Checks the declared contrast pairs against synthetic wallpaper-like schemes.

`verify` asserts the pairs against three fixed palettes. Under Material You the
palette comes from the listener's wallpaper, so this sweeps hue and chroma.

The sweep is a proxy for Android's generator. Contrast depends on tone, so the
tones are measured from the active theme's ROLES table (each role's L*) and
held fixed; what varies is the hue of the source color and how much chroma
each family carries. Chroma 0 stands in for Monochrome.

    python3 verify/sweep.py [N]            N schemes (default 2000) for the
                                           active theme (ANDAMP_THEME)
    python3 verify/sweep.py [N] --repair   the same, with the repair on

The theme needs a literal ROLES table: dark or light.
"""

import math
import sys

sys.path.insert(0, ".")

from andamp import palette as P, roles as R, tonal as T   # noqa: E402
from andamp.tokens import load                            # noqa: E402

# Which tonal palette each M3 role is drawn from, by the role name's prefix.
FAMILY_BY_PREFIX = [
    ("on_primary_fixed", "primary"), ("primary_fixed", "primary"),
    ("on_primary", "primary"), ("primary", "primary"), ("inverse_primary", "primary"),
    ("on_secondary", "secondary"), ("secondary", "secondary"),
    ("on_tertiary", "tertiary"), ("tertiary", "tertiary"),
    ("on_error", "error"), ("error", "error"),
    ("on_surface_variant", "neutral_variant"), ("surface_variant", "neutral_variant"),
    ("outline", "neutral_variant"),
    ("on_surface", "neutral"), ("surface", "neutral"),
    ("inverse_on_surface", "neutral"), ("inverse_surface", "neutral"),
]

# Chroma per family, as a multiple of the source's.
CHROMA = {"primary": 1.00, "secondary": 0.34, "tertiary": 0.50,
          "neutral": 0.06, "neutral_variant": 0.14, "error": 1.0}
ERROR_HUE = 25.0


def family(role):
    for prefix, fam in FAMILY_BY_PREFIX:
        if role.startswith(prefix):
            return fam
    return "neutral"


def baseline_tones(theme):
    """Each M3 role's lightness (L*), measured from the theme's tokens."""
    out = {}
    for name in theme.ROLES:
        L, _, _ = T.to_lab(getattr(P, name))
        out[name] = L
    return out


def scheme_at(tones, hue, chroma, tertiary_shift=60.0):
    """One synthetic scheme: the baseline tones, restated at this hue and
    chroma."""
    out = {}
    for role, L in tones.items():
        fam = family(role)
        h = ERROR_HUE if fam == "error" else (hue + tertiary_shift) % 360 \
            if fam == "tertiary" else hue
        c = chroma * CHROMA[fam]
        out[role] = T.from_lch(L, c, h)   # from_lch takes degrees
    return out


def resolve(entries, m3):
    return R.resolve(entries, m3=lambda n: m3[n], blend=T.blend,
                     tone=lambda c, t: T.Palette("#%06X" % c).tone(t))


def repair(colours, pairs, budget=40):
    """Move a failing pair's foreground along its own tone axis until it
    passes.

    The foreground moves and the background stays, because a background is
    shared by many pairs. The direction is away from the background's
    lightness, one tone per step, for at most `budget` steps.

    Returns the repaired colors and, per role it touched, the steps taken.
    """
    out = dict(colours)
    touched = {}
    for name, fg_role, bg_role, minimum in pairs:
        if fg_role is None or bg_role is None:
            continue
        bg = out[bg_role]
        L_bg, _, _ = T.to_lab(bg)
        step = 1.0 if L_bg < 50 else -1.0
        L, a, b = T.to_lab(out[fg_role])
        chroma = math.hypot(a, b)
        hue = math.degrees(math.atan2(b, a))
        for n in range(budget):
            if T.contrast(out[fg_role], bg) >= minimum:
                break
            L = max(0.0, min(100.0, L + step))
            out[fg_role] = T.from_lch(L, chroma, hue)
            touched[fg_role] = n + 1
        else:
            touched.setdefault(fg_role, budget)
    return out, touched


def pair_roles(entries):
    """The declared pairs with their thresholds. Exits when a pair names a
    role the table lacks."""
    known = {name for name, _ in entries}
    out = []
    for name, fg, bg, kind in P.PAIRS:
        if fg not in known or bg not in known:
            sys.exit(f"sweep: pair '{name}' names a role the table lacks")
        out.append((name, fg, bg, 4.5 if kind == "text" else 3.0))
    return out


def main(n=2000, do_repair=False):
    theme_name, theme = load()
    if not hasattr(theme, "ROLES"):
        sys.exit("sweep: needs a theme with a literal ROLES table")
    entries = R.table(P.STYLE, "dark" if P.DARK else "light", list(theme.ROLES))
    baseline = resolve(entries, {k: getattr(P, k) for k in theme.ROLES})
    pairs = pair_roles(entries)
    tones = baseline_tones(theme)

    # Chroma from Monochrome (0) up to a saturated wallpaper.
    chromas = [0.0, 4.0, 8.0, 16.0, 24.0, 32.0, 48.0, 64.0, 80.0]
    fails, worst, repaired_runs, unrepairable = {}, {}, 0, {}
    margin, floor = {}, {}
    total = 0
    for i in range(n):
        hue = 360.0 * i / n
        chroma = chromas[i % len(chromas)]
        m3 = scheme_at(tones, hue, chroma)
        colours = resolve(entries, m3)
        if do_repair:
            colours, touched = repair(colours, pairs)
            if touched:
                repaired_runs += 1
        total += 1
        for name, fg, bg, minimum in pairs:
            if fg is None or bg is None:
                continue
            ratio = T.contrast(colours[fg], colours[bg])
            margin[name] = min(margin.get(name, 99.0), ratio / minimum)
            floor[name] = min(floor.get(name, 99.0), ratio)
            if ratio < minimum:
                fails[name] = fails.get(name, 0) + 1
                worst[name] = min(worst.get(name, 99), ratio)
                if do_repair:
                    unrepairable[name] = unrepairable.get(name, 0) + 1

    print(f"sweep: {theme_name}, {total} schemes, {len(pairs)} pairs"
          f"{' , repair on' if do_repair else ''}")
    if do_repair:
        print(f"  schemes needing repair: {repaired_runs} "
              f"({repaired_runs / total:.1%})")
    # The tightest pairs are printed whether or not anything fails.
    tight = sorted(margin, key=lambda k: margin[k])[:6]
    print("  tightest pairs (ratio achieved / ratio required):")
    for name in tight:
        print(f"    {name:26s} {floor[name]:5.2f}:1  = {margin[name]:.2f}x required")
    if not fails:
        print("  every pair holds in every scheme")
        return 0
    print(f"  {len(fails)} pairs fail somewhere:")
    for name in sorted(fails, key=lambda k: -fails[k]):
        print(f"    {name:26s} fails {fails[name]:5d}/{total} "
              f"({fails[name]/total:6.1%})  worst {worst[name]:.2f}:1")
    return 0 if do_repair and not unrepairable else 1


if __name__ == "__main__":
    args = sys.argv[1:]
    sys.exit(main(n=int(next((a for a in args if a.isdigit()), 2000)),
                  do_repair="--repair" in args))
