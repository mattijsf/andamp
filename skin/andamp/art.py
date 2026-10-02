# SPDX-License-Identifier: GPL-3.0-or-later

"""The active theme's icon set.

Sheets import this module, so the theme's `icons` style selects the icon set
for every sheet.
"""

from . import palette as P

if P.STYLE["icons"] == "symbols":
    from .icons_symbols import *          # noqa: F401,F403
    from .icons import (pad, overlay, triangle, bar, gap, hjoin,  # noqa: F401
                        BRAND_MARK)
    from . import icons_symbols as _active
else:
    from .icons import *                  # noqa: F401,F403
    from . import icons as _active

PLAYLIST_ICONS = getattr(_active, "PLAYLIST_ICONS", None)
if PLAYLIST_ICONS is None:
    from .icons import pad, overlay
    _BADGED = {
        "ADD_FILE": (_active.DOC, _active.BADGE_PLUS),
        "ADD_DIR": (_active.FOLDER, _active.BADGE_PLUS),
        "ADD_URL": (_active.GLOBE, _active.BADGE_PLUS),
        "REMOVE_ALL": (_active.LIST3, _active.BADGE_X),
        "REMOVE_SELECTED": (_active.LIST3, _active.BADGE_MINUS),
        "REMOVE_MISC": (_active.LIST3, _active.BADGE_DOTS),
        "FILE_INFO": (_active.DOC, _active.BADGE_I),
        "SAVE_LIST": (_active.DOC, _active.ARROW_DOWN),
        "LOAD_LIST": (_active.DOC, _active.ARROW_UP),
    }
    PLAYLIST_ICONS = {n: overlay(pad(b, 15, 11, ax=0.0),
                                 pad(g, 15, 11, ax=1.0, ay=1.0))
                      for n, (b, g) in _BADGED.items()}
    PLAYLIST_ICONS.update({
        "CROP": pad(overlay(pad(_active.LIST3, 11, 9),
                            pad(_active.CHECKBOX, 11, 9, ax=1.0)), 15, 11),
        "INVERT_SELECTION": pad(_active.INVERT, 15, 11),
        "SELECT_ZERO": pad(_active.CHECKBOX, 15, 11),
        "SELECT_ALL": pad(overlay(pad(_active.CHECKBOX, 9, 9),
                                  pad(_active.TICK_SM, 9, 9)), 15, 11),
        "SORT_LIST": pad(_active.SORT, 15, 11),
        "MISC_OPTIONS": pad(overlay(pad(_active.GLYPH_OPTIONS, 10, 8),
                                    pad(_active.BADGE_DOTS, 10, 8, ay=1.0)), 15, 11),
        "NEW_LIST": pad(_active.DOC, 15, 11),
    })
