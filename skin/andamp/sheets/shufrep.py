# SPDX-License-Identifier: GPL-3.0-or-later

"""SHUFREP.BMP -- shuffle, repeat, and the EQ / playlist window toggles.

All four are drawn by `_common.toggle`: selected is a bright filled container
with a squarer corner; unselected is an outlined pill in an outlined theme and
a bare glyph in a filled one.
"""

from .. import palette as P, art as icons
from ._common import sheet, toggle

# name -> (x, w, h, icon, label)
BUTTONS = {
    "SHUFFLE": (28, 47, 15, icons.SHUFFLE, None),
    "REPEAT": (0, 28, 15, icons.REPEAT, None),
}
# EQ sits at window x=219 and PL at 242, so the two touch and are drawn as one
# connected button group.
WINDOW_BUTTONS = {
    "EQ": (0, 46, "eq", "left"),
    "PL": (23, 69, "pl", "right"),
}


def build():
    # A sprite is an opaque rectangle: whatever it does not paint ships as
    # sheet fill and lands on the window, so the bed has to be the color the
    # sprite is composited onto. This sheet has two destinations (shuffle and
    # repeat sit on the transport tray at (164,89) and (210,89), EQ and PL on
    # the slider bed at (219,58) and (242,58)), so it has two beds.
    c = sheet("SHUFREP.BMP", P.tray)
    c.rect(0, 60, 92, 25, P.tray_low)
    for _name, (x, w, h, mask, label) in BUTTONS.items():
        for row, (selected, pressed) in enumerate(
            [(False, False), (False, True), (True, False), (True, True)]
        ):
            toggle(c, x + 1, row * 15 + 1, w - 2, h - 2,
                   selected=selected, pressed=pressed, mask=mask, label=label)

    # EQ / PL live on their own 23x12 grid at y=61 and y=73; the depressed
    # column starts at x+46.
    for _name, (x, xd, label, position) in WINDOW_BUTTONS.items():
        for selected in (False, True):
            y = 73 if selected else 61
            # The two segments meet: EQ gives up its right-hand gutter and PL
            # its left-hand one, so on the window (219..241 and 242..264) the
            # group is continuous.
            ox = 1 if position == "left" else 0
            for pressed, bx in ((False, x), (True, xd)):
                toggle(c, bx + ox, y + 1, 22, 10, selected=selected,
                       pressed=pressed, label=label, position=position)
    return c
