# SPDX-License-Identifier: GPL-3.0-or-later

"""PLEDIT.BMP -- the playlist editor frame, 280x186.

The window is built from stretchable frame pieces plus a 16-button icon grid
and five 3px menu bars. Its four corners take the window radius, like the main
window's; REGION.TXT's `[Corners]` section tells Andamp where to cut them.
"""

from .. import palette as P, draw, art as icons
from ._common import (sheet, container, button, win_button, frame_piece, R_PILL,
                      R_CHAMFER, R_NONE)
from spec.sprites import _PL_BUTTONS

TITLE = "playlist"

# The top strip is 20px, and Webamp pins the close and shade buttons at
# top: 3px. The bar is painted 15px tall, so its center is row 7, where the
# buttons and the title text are, and the remaining 5px is frame above the
# list. gen.BAR_H is the same.
BAR_H = 15
STRIP_H = 20
PIECES = ((0, 25), (26, 100), (127, 25), (153, 25))


# The outer pieces are rounded by `frame_piece`, each naming only the window
# corner it carries: the top strip's two ends and the bottom bar's. The seams
# between pieces stay square. REGION.TXT's `[Corners]` section lets Andamp cut
# the same corners out of the window.
PIECE_CORNERS = ()


def _top(c, y, fill, ink):
    """One 178px title strip: corner, title block, tile, corner.

    The two outer pieces carry the window's top corners and the 1px frame.
    """
    frame_piece(c, 0, y, 25, BAR_H, fill, corners=("tl",), skip="rb", edge=P.hairline)
    for px_, pw in ((26, 100), (127, 25)):
        c.rect(px_, y, pw, BAR_H, fill)
        c.hline(px_, y, pw, P.hairline)
    frame_piece(c, 153, y, 25, BAR_H, fill, corners=("tr",), skip="lb", edge=P.hairline)

    # Frame between the bar and the list.
    for x, w in PIECES:
        c.rect(x, y + BAR_H, w, STRIP_H - BAR_H, P.window)
        c.hline(x, y + BAR_H, w, P.hairline)
    c.vline(0, y + BAR_H, STRIP_H - BAR_H, P.edge)
    c.vline(177, y + BAR_H, STRIP_H - BAR_H, P.edge)

    draw.text_centered(c, 26, y, 100, BAR_H, TITLE, ink, spacing=1)
    # Resting faces for the close (right:2) and shade (right:12) buttons,
    # placed inside the 25px top-right corner piece.
    for local_x, mask in ((14, icons.GLYPH_CLOSE), (4, icons.GLYPH_SHADE)):
        win_button(c, 153 + local_x, y + 3, mask, "rest", ink)


def _menu_button(c, x, y, mask, selected):
    if selected:
        button(c, x + 1, y + 1, 20, 16, fill=P.primary, ink=P.on_primary,
               mask=mask, radius=R_CHAMFER)
    else:
        button(c, x + 1, y + 1, 20, 16, fill=P.tray_low, ink=P.ink,
               mask=mask, radius=R_PILL, edge=P.edge)


def build():
    c = sheet("PLEDIT.BMP", P.window)
    # Sprite beds: the menu button grid is composited onto the bottom strip and
    # the scroll handle onto the right-hand tile, so each sits on that color.
    c.rect(0, 111, 280, 75, P.tray)
    c.rect(52, 53, 18, 18, P.tray_low)

    _top(c, 0, P.primary_container, P.on_primary_container)
    _top(c, 21, P.tray, P.ink_dim)

    # Side tiles. The left edge is a plain 1px rule; the right carries the
    # 8px scrollbar groove Winamp positions at right:7px.
    c.rect(0, 42, 12, 29, P.window)
    c.vline(0, 42, 29, P.edge)
    c.vline(11, 42, 29, P.hairline)
    c.rect(31, 42, 20, 29, P.window)
    c.vline(50, 42, 29, P.edge)
    c.rect(36, 42, 8, 29, P.tray_low)

    # Bottom strip: tile, then the two corner pieces that carry the controls.
    c.rect(179, 0, 25, 38, P.tray)
    c.hline(179, 37, 25, P.edge)

    # The two bottom corner pieces carry the window's bottom corners, on the
    # same arc as its top ones.
    frame_piece(c, 0, 72, 125, 38, P.tray, corners=("bl",), skip="rt", edge=P.edge)
    frame_piece(c, 126, 72, 150, 38, P.tray, corners=("br",), skip="lt", edge=P.edge)
    # Running-time well and resize grip, positioned from Webamp's element
    # boxes. Webamp puts .playlist-running-time-display at window (132, 88)
    # 90x10, and #playlist-resize-target at window (255, 96) 20x20; the
    # bottom-right piece is drawn at window (W-150, H-38), so
    # sheet_x = window_x + 1 and sheet_y = window_y - 6.
    container(c, 131, 81, 92, 12, P.lcd, radius=R_CHAMFER)
    for i in range(3):
        c.hline(264 + i * 3, 106 - i * 3, 10 - i * 3, P.hairline)

    # The five menu buttons are not sprites: Winamp paints their faces into
    # the bottom chrome and draws the 3px "bar" sprite over them when a menu
    # opens. They sit at bottom:12px, left 14/43/72/101 and right:22px, which
    # inside a 38px strip puts them at y=8..25 (sheet y=80).
    for bx, key in ((14, "ADD"), (43, "REMOVE"), (72, "SELECT"),
                    (101, "MISC"), (126 + 150 - 44, "LIST")):
        button(c, bx, 80, 22, 18, fill=P.surface_container_highest, ink=P.ink,
               mask=icons.MENU_ICONS[key], radius=R_PILL, edge=P.edge)

    # Playlist visualizer well. This piece sits in the bottom row like the
    # tile, so it carries the window's bottom rule.
    c.rect(205, 0, 75, 38, P.tray)
    c.hline(205, 37, 75, P.edge)
    container(c, 207, 4, 71, 30, P.vis_bed, radius=R_NONE, edge=P.card_edge)

    # --- shade mode ---------------------------------------------------------
    # Webamp composites this bar from three pieces: a background tile that
    # repeats across the whole width, a no-repeat left cap, and a no-repeat
    # right cap pinned to the right edge. Only the right cap has a focused
    # variant.
    #
    # Element boxes inside the 50px right cap: the time is right-aligned to
    # end at right:30 (local 20), the resize grip is at right:20 (local 21),
    # the shade button right:12 (local 29) and close right:2 (local 39). The
    # track title runs from left:5, over the left cap and the tile.
    TILE, LEFT = (72, 57), (72, 42)
    RIGHT, RIGHT_SEL = (99, 57), (99, 42)

    def cap(x, y, w, corners, skip):
        """An end of the shade bar, outlined everywhere except the edge that
        butts against the tile. Its corners are square (R_NONE)."""
        from .. import shapes
        c.rect(x, y, w, 14, P.primary_container)
        for ox, oy in shapes.outline(w, 14, R_NONE, corners):
            if ox != skip:
                c.px(x + ox, y + oy, P.hairline)

    # The tile repeats, so it carries only the bar's top and bottom rule.
    c.rect(TILE[0], TILE[1], 25, 14, P.primary_container)
    c.hline(TILE[0], TILE[1], 25, P.hairline)
    c.hline(TILE[0], TILE[1] + 13, 25, P.hairline)
    cap(LEFT[0], LEFT[1], 25, ("tl", "bl"), 24)

    for (rx, ry), ink in ((RIGHT, P.ink_dim),
                          (RIGHT_SEL, P.on_primary_container)):
        cap(rx, ry, 50, ("tr", "br"), 0)
        # No well behind the running time: local 0..20 stays plain bar, so the
        # title field and the time read as one strip. The controls start at
        # local 21.
        # Resize grip. The target runs local 21..29 and the shade button
        # starts at 29, so the grip stops at 26.
        for i in range(2):
            c.hline(rx + 21 + i * 2, ry + 9 - i * 2, 6 - i * 2, ink)
        for local_x, mask in ((39, icons.GLYPH_CLOSE), (29, icons.GLYPH_EXPAND)):
            win_button(c, rx + local_x, ry + 3, mask, "rest", ink)

    # --- scroll handle ------------------------------------------------------
    for x, fill, radius in ((52, P.tray_low, R_PILL), (61, P.accent, R_PILL)):
        container(c, x, 53, 8, 18, fill, radius=radius)
        ink = P.ink if fill is P.tray_low else P.on_primary
        if fill is P.tray_low:
            c.rframe(x, 53, 8, 18, P.edge, radius=radius)
        for dy in (7, 9, 11):
            c.hline(x + 2, 53 + dy, 4, ink)

    # --- small title-bar buttons -------------------------------------------
    # These are the playlist's only extra button sprites, and classic players
    # draw them on press, so they take the pressed treatment the main and
    # equalizer windows use. EXPAND restores from shade mode, so it takes the
    # down chevron against COLLAPSE's up one.
    for x, mask in ((52, icons.GLYPH_CLOSE), (62, icons.GLYPH_SHADE),
                    (150, icons.GLYPH_EXPAND)):
        win_button(c, x, 42, mask, "press", bg=P.primary_container)

    # --- 16-button icon grid ------------------------------------------------
    for name, (x, y) in _PL_BUTTONS.items():
        mask = icons.PLAYLIST_ICONS[name]
        _menu_button(c, x, y, mask, selected=False)
        _menu_button(c, x + 23, y, mask, selected=True)

    # --- 3px menu bars ------------------------------------------------------
    for x, h, key in ((48, 54, "ADD"), (100, 72, "REMOVE"), (150, 54, "SELECT"),
                      (200, 54, "MISC"), (250, 54, "LIST")):
        c.rect(x, 111, 3, h, P.tray)
        c.vline(x, 111, h, P.edge)
    return c
