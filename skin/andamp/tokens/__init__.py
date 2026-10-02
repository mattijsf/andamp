# SPDX-License-Identifier: GPL-3.0-or-later

"""Theme selection.

A theme supplies two things: a ROLES table (M3 color role -> hex) and a STYLE
table (shape, iconography and control-treatment choices). Themes are selected
per build by the ANDAMP_THEME environment variable and resolved once, at
import, so a build process holds one theme.
"""

import importlib
import os

DEFAULT = "dark"
NAMES = ("dark", "light", "spot")


def load(name=None):
    name = name or os.environ.get("ANDAMP_THEME", DEFAULT)
    if name not in NAMES:
        raise SystemExit(f"unknown theme {name!r}; expected one of {NAMES}")
    return name, importlib.import_module(f"{__name__}.{name}")
