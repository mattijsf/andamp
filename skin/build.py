#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Build the active theme's .wsz from source.

    python3 build.py            build the active theme
    python3 build.py preview    also write per-sheet PNG previews
    python3 build.py verify     build, write previews, then assert every static
                                invariant
    python3 build.py check      rebuild and diff against the recorded digests
    python3 build.py template   emit the runtime template and check that it
                                rebinds to this build byte for byte
    python3 build.py all        run `verify` and `template` for every theme,
                                each in its own process (a theme is resolved
                                once, at import)

The theme is chosen by ANDAMP_THEME: "dark" (the default), "light" or "spot".

No third-party packages. The drawing layer cannot anti-alias and the zip is
written with fixed timestamps, so two runs produce identical bytes.
"""

import hashlib
import os
import pathlib
import sys

from andamp import bmp, compose, png, palette as P, textfiles, package
from andamp.sheets import (main, titlebar, cbuttons, shufrep, posbar, volume,
                           monoster, playpaus, numbers, text, eqmain, eq_ex,
                           pledit, gen, genex, frames)

ROOT = pathlib.Path(__file__).parent
DIST = ROOT / "dist"
SKIN_NAME = P.SKIN_FILE

BUILDERS = {
    "MAIN.BMP": main.build,
    "TITLEBAR.BMP": titlebar.build,
    "CBUTTONS.BMP": cbuttons.build,
    "SHUFREP.BMP": shufrep.build,
    "POSBAR.BMP": posbar.build,
    "VOLUME.BMP": volume.build,
    "BALANCE.BMP": volume.build_balance,
    "MONOSTER.BMP": monoster.build,
    "PLAYPAUS.BMP": playpaus.build,
    "NUMBERS.BMP": numbers.build,
    "NUMS_EX.BMP": numbers.build_ex,
    "TEXT.BMP": text.build,
    "EQMAIN.BMP": eqmain.build,
    "EQ_EX.BMP": eq_ex.build,
    "PLEDIT.BMP": pledit.build,
    "GEN.BMP": gen.build,
    "GENEX.BMP": genex.build,
    "VIDEO.BMP": frames.build_video,
    "AVS.BMP": frames.build_avs,
    "MB.BMP": frames.build_mb,
}

TEXT_FILES = {
    "PLEDIT.TXT": textfiles.pledit_txt,
    "VISCOLOR.TXT": textfiles.viscolor_txt,
    "REGION.TXT": textfiles.region_txt,
    # Not part of the skin format: the Winamp Skin Museum shows it as the
    # skin's description, and it carries the license.
    "readme.txt": textfiles.readme_txt,
}


def render():
    """Returns (canvases, members) -- members is what goes in the zip."""
    canvases = {name: fn() for name, fn in BUILDERS.items()}
    members = {name: bmp.encode(c.w, c.h, c.px_data)
               for name, c in canvases.items()}
    for name, fn in TEXT_FILES.items():
        members[name] = fn().encode("ascii")
    return canvases, members


def write_previews(canvases):
    out = DIST / "preview" / P.THEME_NAME
    out.mkdir(parents=True, exist_ok=True)
    for name, c in canvases.items():
        scale = 3 if c.w <= 160 else 2
        (out / f"{name.split('.')[0].lower()}.png").write_bytes(
            png.encode(c.w, c.h, c.px_data, scale=scale))
    # The main window, composited from the sheets.
    hero = compose.main_window(canvases)
    (DIST / "preview" / f"{P.THEME_NAME}-window.png").write_bytes(
        png.encode(hero.w, hero.h, hero.px_data, scale=4))


def build(previews=False):
    DIST.mkdir(exist_ok=True)
    canvases, members = render()
    path = DIST / SKIN_NAME
    package.write(path, members)
    digests = {n: hashlib.sha256(d).hexdigest() for n, d in members.items()}
    digests[SKIN_NAME] = hashlib.sha256(path.read_bytes()).hexdigest()
    (DIST / f"CHECKSUMS-{P.THEME_NAME}.txt").write_text(
        "".join(f"{digests[n]}  {n}\n" for n in sorted(digests)))
    if previews:
        write_previews(canvases)
    print(f"{P.THEME_NAME:9s} {path.name:22s} {path.stat().st_size:>7,} bytes  "
          f"{len(members)} members")
    return canvases, members


def template():
    """Emit the runtime template, then check that it reproduces this build.

    The emitter runs in its own process: the sheets read colors off `palette`
    at import as well as at draw time, so the role sentinels must be in place
    before any sheet is imported. Rebinding the template to the real palette
    must give BMPs and text files byte-identical to the ones built here.
    """
    import json
    import subprocess
    import zipfile
    from andamp import template as T

    canvases, members = render()
    path = DIST / f"andamp-{P.THEME_NAME}-template.zip"
    DIST.mkdir(exist_ok=True)
    subprocess.run([sys.executable, "-m", "andamp.template", str(path)],
                   check=True, capture_output=True)

    with zipfile.ZipFile(path) as z:
        manifest = json.loads(z.read("manifest.json"))
        blob = z.read("sheets.idx")

    colours = {}
    for name in manifest["role_order"]:
        if "[" in name:
            base, i = name[:-1].split("[")
            colours[name] = getattr(P, base)[int(i)]
        else:
            colours[name] = getattr(P, name)

    bad = [name for name, px in T.bind(manifest, blob, colours).items()
           if bmp.encode(canvases[name].w, canvases[name].h, px) != members[name]]
    if bad:
        print("TEMPLATE DIFFERS:", ", ".join(sorted(bad)))
        return 1

    for name, text in manifest["text"].items():
        rebound = text
        for role, value in colours.items():
            rebound = rebound.replace("{{%s|hex}}" % role, "#%06X" % value)
            rebound = rebound.replace("{{%s|rgb}}" % role,
                                      "%d,%d,%d" % ((value >> 16) & 255,
                                                    (value >> 8) & 255, value & 255))
        if rebound.encode("ascii") != members[name]:
            print(f"TEMPLATE DIFFERS: {name}")
            return 1

    # The role table resolved against this build's palette. The app's
    # SkinTemplateRolesTest compares its own resolution with this file.
    golden = DIST / f"andamp-{P.THEME_NAME}-roles.json"
    golden.write_text(json.dumps(
        {name: "#%06X" % value for name, value in colours.items()}, indent=1) + "\n")

    print(f"template: {len(manifest['role_order'])} roles, "
          f"{len(manifest['sheets'])} sheets, {path.stat().st_size:,} bytes; "
          f"rebinds to this build exactly")
    return 0


def check():
    _, members = render()
    expected = {}
    for line in (DIST / f"CHECKSUMS-{P.THEME_NAME}.txt").read_text().splitlines():
        digest, name = line.split("  ")
        expected[name] = digest
    bad = [n for n, d in members.items()
           if hashlib.sha256(d).hexdigest() != expected.get(n)]
    zip_digest = hashlib.sha256((DIST / SKIN_NAME).read_bytes()).hexdigest()
    if zip_digest != expected.get(SKIN_NAME):
        bad.append(SKIN_NAME)
    if bad:
        print("REBUILD DIFFERS:", ", ".join(bad))
        return 1
    print(f"reproducible: {len(members) + 1} artefacts byte-identical")
    return 0


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "build"
    if cmd == "build":
        build()
    elif cmd == "preview":
        build(previews=True)
    elif cmd == "check":
        sys.exit(check())
    elif cmd == "template":
        sys.exit(template())
    elif cmd == "all":
        import subprocess
        from andamp.tokens import NAMES
        rc = 0
        for theme in NAMES:
            env = {**os.environ, "ANDAMP_THEME": theme}
            rc |= subprocess.call([sys.executable, __file__, "verify"], env=env)
            # The template is generated from the same sheets, so it is rebuilt
            # with them.
            rc |= subprocess.call([sys.executable, __file__, "template"], env=env)
        sys.exit(rc)
    elif cmd == "verify":
        canvases, members = build(previews=True)
        from verify.verify import run
        sys.exit(run(canvases, members))
    else:
        sys.exit(f"unknown command: {cmd}")
