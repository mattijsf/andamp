# SPDX-License-Identifier: GPL-3.0-or-later

"""The 1px icon masks, used by the spot theme.

Diagonals are 45 degrees and curves are hand-plotted. Masks are lists of
equal-length strings; '#' paints, '.' and ' ' skip.
"""


def pad(mask, w, h, ax=0.5, ay=0.5):
    """Center a mask in a w*h box (ax/ay 0=start, 0.5=center, 1=end)."""
    mw, mh = len(mask[0]), len(mask)
    ox = int((w - mw) * ax)
    oy = int((h - mh) * ay)
    out = ["." * w for _ in range(h)]
    for j, row in enumerate(mask):
        y = oy + j
        if 0 <= y < h:
            line = list(out[y])
            for i, ch in enumerate(row):
                x = ox + i
                if 0 <= x < w and ch == "#":
                    line[x] = "#"
            out[y] = "".join(line)
    return out


def overlay(base, top):
    return ["".join("#" if a == "#" or b == "#" else "." for a, b in zip(r1, r2))
            for r1, r2 in zip(base, top)]


def triangle(h, facing="right"):
    """45-degree triangle, height h (odd). Width is (h+1)//2."""
    half = (h - 1) // 2
    widths = [i + 1 for i in range(half + 1)] + [half - i for i in range(half)]
    w = max(widths)
    rows = []
    for wi in widths:
        bar = "#" * wi + "." * (w - wi)
        rows.append(bar if facing == "right" else bar[::-1])
    return rows


def bar(w, h):
    return ["#" * w] * h


def gap(w, h):
    return ["." * w] * h


def hjoin(*masks, space=1):
    h = max(len(m) for m in masks)
    masks = [pad(m, len(m[0]), h) for m in masks]
    rows = []
    for j in range(h):
        rows.append(("." * space).join(m[j] for m in masks))
    return rows


# --- transport ---------------------------------------------------------------
# One optical height for the whole row: 9px, with the solid marks (stop, eject)
# at 7.
PREVIOUS = [
    "##.....#",
    "##....##",
    "##...###",
    "##..####",
    "##.#####",
    "##..####",
    "##...###",
    "##....##",
    "##.....#",
]

PLAY = [
    "#....",
    "##...",
    "###..",
    "####.",
    "#####",
    "####.",
    "###..",
    "##...",
    "#....",
]

PAUSE = [
    ".#....#.",
    "###..###",
    "###..###",
    "###..###",
    "###..###",
    "###..###",
    "###..###",
    "###..###",
    ".#....#.",
]

STOP = [
    ".#####.",
    "#######",
    "#######",
    "#######",
    "#######",
    "#######",
    ".#####.",
]

NEXT = [
    "#.....##",
    "##....##",
    "###...##",
    "####..##",
    "#####.##",
    "####..##",
    "###...##",
    "##....##",
    "#.....##",
]

EJECT = [
    "...#...",
    "..###..",
    ".#####.",
    "#######",
    ".......",
    "#######",
    "#######",
]

# The 9x9 state indicator reuses the full-size marks.
SMALL_PLAY, SMALL_PAUSE, SMALL_STOP = PLAY, PAUSE, STOP

# --- Shade mode -------------------------------------------------------------
# The shade bar gives the transport 7 rows. There is no sprite for these
# (`background: none` in Webamp), so the glyphs are painted into the shade
# bar's background.
SHADE_PREVIOUS = [
    "##....#",
    "##...##",
    "##..###",
    "##.####",
    "##..###",
    "##...##",
    "##....#",
]

SHADE_PLAY = [
    "#...",
    "##..",
    "###.",
    "####",
    "###.",
    "##..",
    "#...",
]

SHADE_PAUSE = [
    ".#...#.",
    "##...##",
    "##...##",
    "##...##",
    "##...##",
    "##...##",
    ".#...#.",
]

SHADE_STOP = STOP
SHADE_NEXT = [
    "#....##",
    "##...##",
    "###..##",
    "####.##",
    "###..##",
    "##...##",
    "#....##",
]

SHADE_EJECT = EJECT


# --- window furniture (5x5 in a 9x9 button) ----------------------------------
GLYPH_CLOSE = ["#...#", ".#.#.", "..#..", ".#.#.", "#...#"]
# Minimize and shade differ as shapes, because content is centered on its ink
# and padding carries no meaning: a bar for minimize, an upward chevron for
# shade, three bars for the options menu.
GLYPH_MINIMIZE = ["#####", "#####"]
GLYPH_SHADE = ["..#..", ".###.", "##.##"]
GLYPH_EXPAND = ["##.##", ".###.", "..#.."]
GLYPH_OPTIONS = ["#####", ".....", "#####", ".....", "#####"]

# Two paths crossing, tails on the left, arrowheads on the right.
SHUFFLE = [
    "..................#..",
    "########.....#######.",
    "#########...#########",
    "........##.##.....##.",
    ".........###......#..",
    "..........#..........",
    ".........###......#..",
    "........##.##.....##.",
    "#########...#########",
    "########.....#######.",
    "..................#..",
]

# Two opposed arrows.
REPEAT = [
    "................#..",
    "##################.",
    "###################",
    "................##.",
    "................#..",
    "...................",
    "..#................",
    ".##################",
    "###################",
    ".##................",
    "..#................",
]

# --- playlist primitives ------------------------------------------------------
DOC = ["#####..", "#....#.", "#....##", "#.....#", "#.....#",
       "#.....#", "#.....#", "#.....#", "#######"]
FOLDER = ["###......", "#..#.....", "#########", "#.......#", "#.......#",
          "#.......#", "#########"]
LIST3 = ["#########", "#########", ".........", "#########", "#########",
         ".........", "#########", "#########"]
GLOBE = ["..###..", ".#.#.#.", "#..#..#", "#######", "#..#..#",
         ".#.#.#.", "..###.."]
CHECKBOX = ["#########", "#########", "##.....##", "##.....##", "##.....##",
            "##.....##", "##.....##", "#########", "#########"]
# A tick that fits inside the checkbox.
TICK_SM = ["....#", "...#.", "#.#..", ".#..."]
CHECK = [
    "......##",
    ".....##.",
    "##..##..",
    ".####...",
    "..##....",
]
BADGE_PLUS = ["..#..", "..#..", "#####", "..#..", "..#.."]
BADGE_MINUS = [".....", ".....", "#####", ".....", "....."]
BADGE_X = ["#...#", ".#.#.", "..#..", ".#.#.", "#...#"]
BADGE_I = ["..#..", ".....", ".###.", "..#..", ".###."]
BADGE_DOTS = [".....", ".....", "#.#.#", ".....", "....."]
ARROW_DOWN = ["..#..", "..#..", "#####", ".###.", "..#.."]
ARROW_UP = ["..#..", ".###.", "#####", "..#..", "..#.."]
SORT = ["#########", ".........", "#######..", ".........", "#####....",
        ".........", "###......"]
INVERT = ["#########", "#########", "#.......#", "#.......#", "#########",
          "#########", "#########"]

_BADGED = {
    "ADD_FILE": (DOC, BADGE_PLUS),
    "ADD_DIR": (FOLDER, BADGE_PLUS),
    "ADD_URL": (GLOBE, BADGE_PLUS),
    "REMOVE_ALL": (LIST3, BADGE_X),
    "REMOVE_SELECTED": (LIST3, BADGE_MINUS),
    "REMOVE_MISC": (LIST3, BADGE_DOTS),
    "FILE_INFO": (DOC, BADGE_I),
    "SAVE_LIST": (DOC, ARROW_DOWN),
    "LOAD_LIST": (DOC, ARROW_UP),
}

PLAYLIST_ICONS = {name: overlay(pad(base, 15, 11, ax=0.0),
                                pad(badge, 15, 11, ax=1.0, ay=1.0))
                  for name, (base, badge) in _BADGED.items()}
PLAYLIST_ICONS.update({
    "CROP": pad(overlay(pad(LIST3, 11, 9), pad(CHECKBOX, 11, 9, ax=1.0)), 15, 11),
    "INVERT_SELECTION": pad(INVERT, 15, 11),
    "SELECT_ZERO": pad(CHECKBOX, 15, 11),
    "SELECT_ALL": pad(overlay(pad(CHECKBOX, 9, 8), pad(CHECK, 9, 8)), 15, 11),
    "SORT_LIST": pad(SORT, 15, 11),
    "MISC_OPTIONS": pad(overlay(pad(GLYPH_OPTIONS, 9, 7),
                                pad(BADGE_DOTS, 9, 7, ay=1.0)), 15, 11),
    "NEW_LIST": pad(DOC, 15, 11),
})

# Faces for the five menu buttons painted into the playlist's bottom chrome.
# Drawn larger than the popup-item badges because they sit in 22x18 cells.
MENU_ICONS = {
    "ADD": ["...###...", "...###...", "...###...", "#########",
            "#########", "#########", "...###...", "...###...", "...###..."],
    "REMOVE": ["#########", "#########", "#########"],
    # A checkmark drawn as two joined strokes: short down-right, long
    # up-right.
    "SELECT": [
        ".........##",
        "........##.",
        ".......##..",
        "##....##...",
        ".##..##....",
        "..####.....",
        "...##......",
    ],

    "MISC": ["##..##..##", "##..##..##"],
    "LIST": ["###########", "###########", "...........",
             "###########", "###########", "...........",
             "###########", "###########"],
}

# --- brand mark --------------------------------------------------------------
# Andamp's mark: a bolt through a broken ring. It is defined here, outside the
# two icon sets, because every theme draws the same mark. The ring is a 13px
# circle with the two arcs the bolt passes through cut away. 13 wide is what
# the about box gives (Winamp puts it at 13x15).
BRAND_MARK = [
    "....#..##....",
    "...#..##.....",
    "..#...##..#..",
    ".#...##....#.",
    "#....##.....#",
    "#...#####...#",
    "#......##...#",
    "#.....##....#",
    "#.....##....#",
    ".#...##....#.",
    "..#..##...#..",
    "....##...#...",
    "....#..##....",
]
