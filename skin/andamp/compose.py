# SPDX-License-Identifier: GPL-3.0-or-later

"""dist/preview/<theme>-window.png: the main window, composited.

`build.write_previews` writes one PNG per sheet. This composites the main
window the way a player does: the MAIN.BMP plate, then every sprite blitted at
its main-window.css destination.

Element boxes come from `sheets.main` and sprite rectangles from
`spec.sprites`, the tables the plate is drawn against. The playback state is a
fixed literal (playing, 3:42 in, shuffle on), so the PNG is reproducible byte
for byte.
"""

from . import palette as P, draw
from .canvas import Canvas
from .sheets import main as M
from spec import sprites as S

# --- the state the shot is taken in -----------------------------------------
TIME = "0342"
TITLE = "andamp - material 3"
KBPS, KHZ = "192", "44"
VOLUME = 78            # percent
BALANCE = 0            # -100..100, 0 = centered
POSITION = 0.40        # fraction of the track elapsed
STEREO = True
SHUFFLE, REPEAT = True, False
EQ_OPEN, PL_OPEN = True, True

# Destinations from main-window.css that `sheets.main` does not define.
DEST = {
    "title_bar": (0, 0),
    "clutter": (10, 22),
    "work": (24, 28),
    "playpaus": (26, 28),
    "mono": (212, 41),
    "stereo": (239, 41),
    "volume": (107, 57, 68, 13),
    "balance": (177, 57, 38, 13),
    "eq": (219, 58),
    "pl": (242, 58),
    "posbar": (16, 72, 248, 10),
    "transport": (16, 88),      # 23px pitch for the first five cells
    "eject": (136, 89),
    "shuffle": (164, 89),
    "repeat": (210, 89),
}


def _blit(dst, src, sheet, name, dx, dy, w=None, h=None):
    sx, sy, sw, sh = S.SPRITES[sheet][name]
    dst.blit(src[sheet], sx, sy, w or sw, h or sh, dx, dy)


def _slider_frame(value, steps=28):
    """Winamp picks the background frame from the value; the sheet stacks the
    frames 15px apart even though the element is only 13px tall."""
    return max(0, min(steps - 1, round(value / 100 * steps) - 1))


def _slider(dst, src, sheet, dest, *, frame, pos):
    x, y, w, h = dest
    sx, sy = S.SPRITES[sheet]["BACKGROUND"][:2]
    dst.blit(src[sheet], sx, sy + frame * 15, w, h, x, y)
    tw, th = S.SPRITES[sheet]["THUMB"][2:]
    _blit(dst, src, sheet, "THUMB", x + round(pos * (w - tw)), y + (h - th) // 2)


def main_window(canvases):
    """Returns a 275x116 Canvas: the plate with every sprite composited on."""
    c = Canvas(275, 116, P.window)
    c.blit(canvases["MAIN.BMP"])

    _blit(c, canvases, "TITLEBAR.BMP", "TITLE_BAR_SELECTED", *DEST["title_bar"])
    _blit(c, canvases, "TITLEBAR.BMP", "CLUTTER_BAR_BACKGROUND", *DEST["clutter"])

    # Transport state. The work indicator's cell is 9px wide in the sheet but
    # only 3px on the window, so it is cropped.
    _blit(c, canvases, "PLAYPAUS.BMP", "NOT_WORKING", *DEST["work"], w=3)
    _blit(c, canvases, "PLAYPAUS.BMP", "PLAYING", *DEST["playpaus"])

    # Time, from NUMS_EX: players prefer it over NUMBERS when a skin has it.
    for digit, x in zip(TIME, M.DIGIT_X):
        _blit(c, canvases, "NUMS_EX.BMP", f"DIGIT_{digit}_EX", x, M.TIME[1])

    draw.text(c, *M.MARQUEE[:2], TITLE, P.ink)
    draw.text(c, *M.KBPS[:2], KBPS, P.ink)
    draw.text(c, *M.KHZ[:2], KHZ, P.ink)

    _blit(c, canvases, "MONOSTER.BMP", "MONO", *DEST["mono"])
    _blit(c, canvases, "MONOSTER.BMP",
          "STEREO_SELECTED" if STEREO else "STEREO", *DEST["stereo"])

    # Volume and balance. Winamp indexes the background frame off the value
    # (volume linearly over 28 frames, balance symmetrically about the center)
    # and slides the thumb over its own travel. The frames sit 15px apart in
    # the sheet and the element shows 13 rows of one.
    _slider(c, canvases, "VOLUME.BMP", DEST["volume"],
            frame=_slider_frame(VOLUME), pos=VOLUME / 100)
    _slider(c, canvases, "BALANCE.BMP", DEST["balance"],
            frame=int(abs(BALANCE) / 100 * 27), pos=(BALANCE + 100) / 200)

    _blit(c, canvases, "SHUFREP.BMP",
          "EQ_BUTTON_SELECTED" if EQ_OPEN else "EQ_BUTTON", *DEST["eq"])
    _blit(c, canvases, "SHUFREP.BMP",
          "PLAYLIST_BUTTON_SELECTED" if PL_OPEN else "PLAYLIST_BUTTON",
          *DEST["pl"])

    x, y, w, h = DEST["posbar"]
    _blit(c, canvases, "POSBAR.BMP", "BACKGROUND", x, y)
    tw = S.SPRITES["POSBAR.BMP"]["THUMB"][2]
    _blit(c, canvases, "POSBAR.BMP", "THUMB", x + round(POSITION * (w - tw)), y)

    tx, ty = DEST["transport"]
    for i, name in enumerate(("PREVIOUS", "PLAY", "PAUSE", "STOP", "NEXT")):
        _blit(c, canvases, "CBUTTONS.BMP", name, tx + i * 23, ty)
    _blit(c, canvases, "CBUTTONS.BMP", "EJECT", *DEST["eject"])

    _blit(c, canvases, "SHUFREP.BMP",
          "SHUFFLE_SELECTED" if SHUFFLE else "SHUFFLE", *DEST["shuffle"])
    _blit(c, canvases, "SHUFREP.BMP",
          "REPEAT_SELECTED" if REPEAT else "REPEAT", *DEST["repeat"])
    return c
