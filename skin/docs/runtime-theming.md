# Runtime theming

Andamp builds AndAmp Dark and AndAmp Light at run time from Android's dynamic `ColorScheme`, so
the player follows Material You. The geometry is still generated and verified by the Python
build here. This file describes the Python side: the template and how it is checked. The app
side is [`docs/dynamic-skins.md`](../../docs/dynamic-skins.md).

## How it works

`andamp/template.py` rebuilds every sheet with one sentinel per role in place of a color, so
each pixel records which role drew it. The result is a role image: one byte per pixel. Together
with the role table from `andamp/roles.py`, which says where each role's color comes from, it is
the template.

The role image is byte-identical between `dark` and `light`, all 419,442 pixels. The two themes
have the same `STYLE`, and the roles that differ by scheme (the aliases `vis_floor`,
`current_track`, `win_hover_fill` and `win_hover_ink`, and the tone `selection`) differ in what
they bind to and are painted in the same places.

Rebinding the role image to a theme's palette reproduces that theme's `.wsz` members byte for
byte, for all three themes and all twenty sheets. At run time the app resolves the role table
against the live scheme and writes each pixel through the resulting lookup table. No drawing
code is ported to Kotlin.

## Sizes

| | |
|---|---|
| pixels across 20 sheets | 419,442 |
| role indices, raw | 419,442 bytes (one byte per pixel) |
| roles | 110 in `dark` and `light`, 114 in `spot` |
| `andamp-dark-template.zip` | about 14.7 KB |
| `AndAmp Dark.wsz` for comparison | about 21.8 KB |

## What a role resolves to

The 110 roles of `dark` and `light` are of these kinds:

| kind | count | resolves to |
|---|---|---|
| M3 role | 36 | a field on `ColorScheme` |
| alias | 26 | another role; four of them bind differently per scheme |
| blend | 4 | `blend(a, b, t)`, mixed in linear light |
| ramp entry | 40 | a blend between two roles at `i/(n-1)` |
| literal | 3 | `#000000` |
| tone | 1 | `selection`: tone T of a role's hue and chroma |

In the manifest these are five node kinds: `m3`, `alias`, `blend` (ramp entries included),
`tone` and `literal`. `selection` is the only one that needs a tonal restatement. Its seed is
the `secondary` role's own value: `tone(secondary, 41)` in a dark scheme and
`tone(secondary, 58)` in a light one, so it follows the live scheme. The 36 direct roles are
all standard `ColorScheme` fields, the fixed roles and the `surfaceContainer` family among them.

## The template

`build.py template` (and so `build.py all`, for every theme) writes
`dist/andamp-<theme>-template.zip`:

```
manifest.json     format version, theme, scheme, the role table in dependency order,
                  role_order, sheet index, text-file templates, the declared contrast
                  pairs by role name
sheets.idx        concatenated role indices, one byte per pixel
```

and `dist/andamp-<theme>-roles.json`, the same table resolved against the palette that theme
shipped, which the Kotlin tests compare against.

It then asserts the round trip: rebinding the template to the theme's palette must reproduce
that theme's BMPs and text files byte for byte. The check fails when a sheet paints a color that
is not a declared role. A color computed inside a sheet has no name, so nothing downstream could
rebind it; this is why ramps and blends are defined in `palette.py` (`EQ_GRAPH_LINE`,
`VIS_SPECTRUM`). The emitter also refuses a role the pixels cannot index and a contrast pair
naming an unknown role.

## The Kotlin side

The runtime is `nl.mattix.andamp.skin.SkinTemplate` and `Tonal` in `:app`:

```kotlin
val template = SkinTemplate.load(zipStream)
val colors = template.colors(scheme)          // one per role, repair on by default
val sheets = template.bitmaps(colors)         // 20 bitmaps, keyed "MAIN.BMP" etc.
val text = template.textFiles(colors)         // PLEDIT.TXT, VISCOLOR.TXT, REGION.TXT
```

- `colors` walks the role table in order; every expression refers only to roles already
  resolved, so one pass suffices. Which scheme a template binds to is fixed in its manifest, so
  the call takes only the `ColorScheme`.
- `bitmaps` writes each sheet's pixels through the lookup table and makes a `Bitmap` of them.
  No BMP encoder and no zip.
- `Tonal` is a port of `andamp/tonal.py` (CIELAB, where M3 uses HCT), so both sides compute the
  same numbers.

It is tested against the Python output: `SkinTemplateRolesTest` resolves the M3 baseline scheme
and compares with `roles.json`, and `SkinTemplateBitmapsTest` binds the shipped palette and
compares every sheet with the shipped `.wsz`.

## What stays in Python

Everything that decides how the skin looks: the sheets, the shape scale, the icon masks,
`verify`, and `audit` (grid, icon fit, mask balance, sprite margins, sprite beds, window-edge
clearance). The template is an output of that pipeline; `build.py build` still produces the
`.wsz`.

## Contrast under dynamic color

The 34 declared contrast pairs are asserted by `verify` against three fixed palettes. Under
Material You the palette comes from the listener's wallpaper, so those checks do not cover it.

`verify/sweep.py` generates schemes across hue and chroma at the theme's own tones, binds the
role table, and reports the tightest pairs and any that fail:

```bash
python3 verify/sweep.py 1800             # a theme with a literal ROLES table: dark or light
python3 verify/sweep.py 1800 --repair    # the same, with the repair on
```

One run sweeps the active theme (`ANDAMP_THEME`). The sweep is a proxy for Android's scheme
generator: it keeps each role's tone and varies hue and chroma. At 1800 schemes every pair holds
in both themes without repair; the tightest is AndAmp Dark's playlist selection band, at 1.03x
its 3:1. The runtime repairs a failing pair anyway (`colors(scheme, repair = true)`): it moves
the pair's foreground along its own tone axis until the pair passes.
