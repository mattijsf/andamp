# SPDX-License-Identifier: GPL-3.0-or-later

"""Winamp 2.x classic skin geometry.

Sheet sizes match Nullsoft's base-2.91 skin; sprite rectangles are the ones
Webamp reads (packages/webamp/js/skinSprites.ts). Sheets are drawn against
this table and verify.py checks against it.
"""

SHEETS = {
    "MAIN.BMP":     (275, 116),
    "TITLEBAR.BMP": (344, 87),
    "CBUTTONS.BMP": (136, 36),
    "SHUFREP.BMP":  (92, 85),
    "POSBAR.BMP":   (307, 10),
    "VOLUME.BMP":   (68, 433),
    "BALANCE.BMP":  (68, 433),
    "MONOSTER.BMP": (58, 24),
    "PLAYPAUS.BMP": (42, 9),
    "NUMBERS.BMP":  (99, 13),
    "NUMS_EX.BMP":  (108, 13),
    "TEXT.BMP":     (155, 18),
    "EQMAIN.BMP":   (275, 315),
    "EQ_EX.BMP":    (275, 82),
    "PLEDIT.BMP":   (280, 186),
    "GEN.BMP":      (194, 109),
    "GENEX.BMP":    (130, 75),
    "VIDEO.BMP":    (234, 119),
    "AVS.BMP":      (234, 119),
    "MB.BMP":       (234, 119),
}

SPRITES = {}

SPRITES["MAIN.BMP"] = {"WINDOW_BACKGROUND": (0, 0, 275, 116)}

# CBUTTONS: row y=0 normal, y=18 active. NEXT_ACTIVE is 22 wide upstream, where
# the other cells are 23; drawing it 23 wide bleeds into the eject cell.
SPRITES["CBUTTONS.BMP"] = {
    "PREVIOUS": (0, 0, 23, 18),        "PREVIOUS_ACTIVE": (0, 18, 23, 18),
    "PLAY": (23, 0, 23, 18),           "PLAY_ACTIVE": (23, 18, 23, 18),
    "PAUSE": (46, 0, 23, 18),          "PAUSE_ACTIVE": (46, 18, 23, 18),
    "STOP": (69, 0, 23, 18),           "STOP_ACTIVE": (69, 18, 23, 18),
    "NEXT": (92, 0, 23, 18),           "NEXT_ACTIVE": (92, 18, 22, 18),
    "EJECT": (114, 0, 22, 16),         "EJECT_ACTIVE": (114, 16, 22, 16),
}

SPRITES["TITLEBAR.BMP"] = {
    "TITLE_BAR": (27, 15, 275, 14),
    "TITLE_BAR_SELECTED": (27, 0, 275, 14),
    "EASTER_EGG_TITLE_BAR": (27, 72, 275, 14),
    "EASTER_EGG_TITLE_BAR_SELECTED": (27, 57, 275, 14),
    "OPTIONS_BUTTON": (0, 0, 9, 9),    "OPTIONS_BUTTON_DEPRESSED": (0, 9, 9, 9),
    "MINIMIZE_BUTTON": (9, 0, 9, 9),   "MINIMIZE_BUTTON_DEPRESSED": (9, 9, 9, 9),
    "SHADE_BUTTON": (0, 18, 9, 9),     "SHADE_BUTTON_DEPRESSED": (9, 18, 9, 9),
    "CLOSE_BUTTON": (18, 0, 9, 9),     "CLOSE_BUTTON_DEPRESSED": (18, 9, 9, 9),
    "SHADE_BUTTON_SELECTED": (0, 27, 9, 9),
    "SHADE_BUTTON_SELECTED_DEPRESSED": (9, 27, 9, 9),
    "CLUTTER_BAR_BACKGROUND": (304, 0, 8, 43),
    "CLUTTER_BAR_BACKGROUND_DISABLED": (312, 0, 8, 43),
    "CLUTTER_BAR_BUTTON_O_SELECTED": (304, 47, 8, 8),
    "CLUTTER_BAR_BUTTON_A_SELECTED": (312, 55, 8, 7),
    "CLUTTER_BAR_BUTTON_I_SELECTED": (320, 62, 8, 7),
    "CLUTTER_BAR_BUTTON_D_SELECTED": (328, 69, 8, 8),
    "CLUTTER_BAR_BUTTON_V_SELECTED": (336, 77, 8, 7),
    "SHADE_BACKGROUND": (27, 42, 275, 14),
    "SHADE_BACKGROUND_SELECTED": (27, 29, 275, 14),
    "SHADE_POSITION_BACKGROUND": (0, 36, 17, 7),
    "SHADE_POSITION_THUMB": (20, 36, 3, 7),
    "SHADE_POSITION_THUMB_LEFT": (17, 36, 3, 7),
    "SHADE_POSITION_THUMB_RIGHT": (23, 36, 3, 7),
}

SPRITES["SHUFREP.BMP"] = {
    "SHUFFLE": (28, 0, 47, 15),
    "SHUFFLE_DEPRESSED": (28, 15, 47, 15),
    "SHUFFLE_SELECTED": (28, 30, 47, 15),
    "SHUFFLE_SELECTED_DEPRESSED": (28, 45, 47, 15),
    "REPEAT": (0, 0, 28, 15),
    "REPEAT_DEPRESSED": (0, 15, 28, 15),
    "REPEAT_SELECTED": (0, 30, 28, 15),
    "REPEAT_SELECTED_DEPRESSED": (0, 45, 28, 15),
    "EQ_BUTTON": (0, 61, 23, 12),
    "EQ_BUTTON_SELECTED": (0, 73, 23, 12),
    "EQ_BUTTON_DEPRESSED": (46, 61, 23, 12),
    "EQ_BUTTON_DEPRESSED_SELECTED": (46, 73, 23, 12),
    "PLAYLIST_BUTTON": (23, 61, 23, 12),
    "PLAYLIST_BUTTON_SELECTED": (23, 73, 23, 12),
    "PLAYLIST_BUTTON_DEPRESSED": (69, 61, 23, 12),
    "PLAYLIST_BUTTON_DEPRESSED_SELECTED": (69, 73, 23, 12),
}

SPRITES["POSBAR.BMP"] = {
    "BACKGROUND": (0, 0, 248, 10),
    "THUMB": (248, 0, 29, 10),
    "THUMB_SELECTED": (278, 0, 29, 10),   # note the 1px gap at x=277
}

SPRITES["VOLUME.BMP"] = {
    "BACKGROUND": (0, 0, 68, 420),        # 28 frames x 15px
    "THUMB": (15, 422, 14, 11),
    "THUMB_SELECTED": (0, 422, 14, 11),
}

SPRITES["BALANCE.BMP"] = {
    "BACKGROUND": (9, 0, 38, 420),
    "THUMB": (15, 422, 14, 11),
    "THUMB_ACTIVE": (0, 422, 14, 11),
}

SPRITES["MONOSTER.BMP"] = {
    "STEREO_SELECTED": (0, 0, 29, 12), "STEREO": (0, 12, 29, 12),
    "MONO_SELECTED": (29, 0, 27, 12),  "MONO": (29, 12, 27, 12),
}

SPRITES["PLAYPAUS.BMP"] = {
    "PLAYING": (0, 0, 9, 9), "PAUSED": (9, 0, 9, 9), "STOPPED": (18, 0, 9, 9),
    "NOT_WORKING": (36, 0, 9, 9), "WORKING": (39, 0, 9, 9),  # the two overlap
}

SPRITES["NUMBERS.BMP"] = {f"DIGIT_{d}": (d * 9, 0, 9, 13) for d in range(10)}
SPRITES["NUMBERS.BMP"].update({
    "BLANK": (90, 0, 9, 13),
    "NO_MINUS_SIGN": (9, 6, 5, 1),     # sliver inside the "1"
    "MINUS_SIGN": (20, 6, 5, 1),       # sliver of the "2" crossbar
})

SPRITES["NUMS_EX.BMP"] = {f"DIGIT_{d}_EX": (d * 9, 0, 9, 13) for d in range(10)}
SPRITES["NUMS_EX.BMP"].update({
    "NO_MINUS_SIGN_EX": (90, 0, 9, 13),
    "MINUS_SIGN_EX": (99, 0, 9, 13),
})

CHAR_W, CHAR_H = 5, 6
TEXT_ROWS = [
    "abcdefghijklmnopqrstuvwxyz\"@\x00\x00 ",
    "0123456789….:()-'!_+\\/[]^&%,=$#",
    "ÅÖÄ?*",
]
SPRITES["TEXT.BMP"] = {
    f"CHAR_{ord(ch)}": (col * CHAR_W, row * CHAR_H, CHAR_W, CHAR_H)
    for row, line in enumerate(TEXT_ROWS)
    for col, ch in enumerate(line)
    if ch != "\x00"
}

SPRITES["EQMAIN.BMP"] = {
    "WINDOW_BACKGROUND": (0, 0, 275, 116),
    "TITLE_BAR": (0, 149, 275, 14),
    "TITLE_BAR_SELECTED": (0, 134, 275, 14),
    "SLIDER_BACKGROUND": (13, 164, 209, 129),
    "SLIDER_THUMB": (0, 164, 11, 11),
    "SLIDER_THUMB_SELECTED": (0, 176, 11, 11),
    "CLOSE_BUTTON": (0, 116, 9, 9),
    "CLOSE_BUTTON_ACTIVE": (0, 125, 9, 9),
    "MAXIMIZE_BUTTON_ACTIVE_FALLBACK": (254, 152, 9, 9),
    "ON_BUTTON": (10, 119, 26, 12),
    "ON_BUTTON_DEPRESSED": (128, 119, 26, 12),
    "ON_BUTTON_SELECTED": (69, 119, 26, 12),
    "ON_BUTTON_SELECTED_DEPRESSED": (187, 119, 26, 12),
    "AUTO_BUTTON": (36, 119, 32, 12),
    "AUTO_BUTTON_DEPRESSED": (154, 119, 32, 12),
    "AUTO_BUTTON_SELECTED": (95, 119, 32, 12),
    "AUTO_BUTTON_SELECTED_DEPRESSED": (213, 119, 32, 12),
    "GRAPH_BACKGROUND": (0, 294, 113, 19),
    "GRAPH_LINE_COLORS": (115, 294, 1, 19),
    "PRESETS_BUTTON": (224, 164, 44, 12),
    "PRESETS_BUTTON_SELECTED": (224, 176, 44, 12),
    "PREAMP_LINE": (0, 314, 113, 1),
}

SPRITES["EQ_EX.BMP"] = {
    "SHADE_BACKGROUND_SELECTED": (0, 0, 275, 14),
    "SHADE_BACKGROUND": (0, 15, 275, 14),
    "SHADE_VOLUME_SLIDER_LEFT": (1, 30, 3, 7),
    "SHADE_VOLUME_SLIDER_CENTER": (4, 30, 3, 7),
    "SHADE_VOLUME_SLIDER_RIGHT": (7, 30, 3, 7),
    "SHADE_BALANCE_SLIDER_LEFT": (11, 30, 3, 7),
    "SHADE_BALANCE_SLIDER_CENTER": (14, 30, 3, 7),
    "SHADE_BALANCE_SLIDER_RIGHT": (17, 30, 3, 7),
    "MAXIMIZE_BUTTON_ACTIVE": (1, 38, 9, 9),
    "MINIMIZE_BUTTON_ACTIVE": (1, 47, 9, 9),
    "SHADE_CLOSE_BUTTON": (11, 38, 9, 9),
    "SHADE_CLOSE_BUTTON_ACTIVE": (11, 47, 9, 9),
}

_PL_BUTTONS = {
    "ADD_URL": (0, 111), "ADD_DIR": (0, 130), "ADD_FILE": (0, 149),
    "REMOVE_ALL": (54, 111), "CROP": (54, 130),
    "REMOVE_SELECTED": (54, 149), "REMOVE_MISC": (54, 168),
    "INVERT_SELECTION": (104, 111), "SELECT_ZERO": (104, 130),
    "SELECT_ALL": (104, 149),
    "SORT_LIST": (154, 111), "FILE_INFO": (154, 130), "MISC_OPTIONS": (154, 149),
    "NEW_LIST": (204, 111), "SAVE_LIST": (204, 130), "LOAD_LIST": (204, 149),
}

SPRITES["PLEDIT.BMP"] = {
    "TOP_TILE": (127, 21, 25, 20),
    "TOP_LEFT_CORNER": (0, 21, 25, 20),
    "TITLE_BAR": (26, 21, 100, 20),
    "TOP_RIGHT_CORNER": (153, 21, 25, 20),
    "TOP_TILE_SELECTED": (127, 0, 25, 20),
    "TOP_LEFT_SELECTED": (0, 0, 25, 20),
    "TITLE_BAR_SELECTED": (26, 0, 100, 20),
    "TOP_RIGHT_CORNER_SELECTED": (153, 0, 25, 20),
    "LEFT_TILE": (0, 42, 12, 29),
    "RIGHT_TILE": (31, 42, 20, 29),
    "BOTTOM_TILE": (179, 0, 25, 38),
    "BOTTOM_LEFT_CORNER": (0, 72, 125, 38),
    "BOTTOM_RIGHT_CORNER": (126, 72, 150, 38),
    "VISUALIZER_BACKGROUND": (205, 0, 75, 38),
    "SHADE_BACKGROUND": (72, 57, 25, 14),
    "SHADE_BACKGROUND_LEFT": (72, 42, 25, 14),
    "SHADE_BACKGROUND_RIGHT": (99, 57, 50, 14),
    "SHADE_BACKGROUND_RIGHT_SELECTED": (99, 42, 50, 14),
    "SCROLL_HANDLE": (52, 53, 8, 18),
    "SCROLL_HANDLE_SELECTED": (61, 53, 8, 18),
    "CLOSE_SELECTED": (52, 42, 9, 9),
    "COLLAPSE_SELECTED": (62, 42, 9, 9),
    "EXPAND_SELECTED": (150, 42, 9, 9),
    "ADD_MENU_BAR": (48, 111, 3, 54),
    "REMOVE_MENU_BAR": (100, 111, 3, 72),
    "SELECT_MENU_BAR": (150, 111, 3, 54),
    "MISC_MENU_BAR": (200, 111, 3, 54),
    "LIST_BAR": (250, 111, 3, 54),
}
for _n, (_x, _y) in _PL_BUTTONS.items():
    SPRITES["PLEDIT.BMP"][_n] = (_x, _y, 22, 18)
    SPRITES["PLEDIT.BMP"][_n + "_SELECTED"] = (_x + 23, _y, 22, 18)

SPRITES["GEN.BMP"] = {
    "TOP_LEFT_SELECTED": (0, 0, 25, 20),
    "TOP_LEFT_END_SELECTED": (26, 0, 25, 20),
    "TOP_CENTER_FILL_SELECTED": (52, 0, 25, 20),
    "TOP_RIGHT_END_SELECTED": (78, 0, 25, 20),
    "TOP_LEFT_RIGHT_FILL_SELECTED": (104, 0, 25, 20),
    "TOP_RIGHT_SELECTED": (130, 0, 25, 20),
    "TOP_LEFT": (0, 21, 25, 20),
    "TOP_LEFT_END": (26, 21, 25, 20),
    "TOP_CENTER_FILL": (52, 21, 25, 20),
    "TOP_RIGHT_END": (78, 21, 25, 20),
    "TOP_LEFT_RIGHT_FILL": (104, 21, 25, 20),
    "TOP_RIGHT": (130, 21, 25, 20),
    "BOTTOM_LEFT": (0, 42, 125, 14),
    "BOTTOM_RIGHT": (0, 57, 125, 14),
    "BOTTOM_FILL": (127, 72, 25, 14),
    "MIDDLE_LEFT": (127, 42, 11, 29),
    "MIDDLE_LEFT_BOTTOM": (158, 42, 11, 24),
    "MIDDLE_RIGHT": (139, 42, 8, 29),
    "MIDDLE_RIGHT_BOTTOM": (170, 42, 8, 24),
    "CLOSE_SELECTED": (148, 42, 9, 9),
    "TEXT_SELECTED_ROW": (0, 88, 194, 7),
    "TEXT_ROW": (0, 96, 194, 7),
}

SPRITES["GENEX.BMP"] = {
    "BUTTON_LEFT_UNPRESSED": (0, 0, 4, 15),
    "BUTTON_CENTER_UNPRESSED": (4, 0, 39, 15),
    "BUTTON_RIGHT_UNPRESSED": (43, 0, 4, 15),
    "BUTTON_PRESSED": (0, 1, 47, 15),
    "SCROLL_UP_UNPRESSED": (0, 31, 14, 14),
    "SCROLL_DOWN_UNPRESSED": (14, 31, 14, 14),
    "SCROLL_UP_PRESSED": (28, 31, 14, 14),
    "SCROLL_DOWN_PRESSED": (42, 31, 14, 14),
    "SCROLL_LEFT_UNPRESSED": (0, 45, 14, 14),
    "SCROLL_RIGHT_UNPRESSED": (14, 45, 14, 14),
    "SCROLL_LEFT_PRESSED": (28, 45, 14, 14),
    "SCROLL_RIGHT_PRESSED": (42, 45, 14, 14),
    "VERTICAL_SCROLL_HANDLE_UNPRESSED": (56, 31, 14, 28),
    "VERTICAL_SCROLL_HANDLE_PRESSED": (70, 31, 14, 28),
    "HORIZONTAL_SCROLL_HANDLE_UNPRESSED": (84, 31, 28, 14),
    "HORIZONTAL_SCROLL_HANDLE_PRESSED": (84, 45, 28, 14),
}

# GENEX color row: y=0, every other pixel from x=48.
GENEX_COLOR_SLOTS = [
    "item_bg", "item_fg", "window_bg", "button_text", "window_text",
    "divider", "selection", "list_header_bg", "list_header_text",
    "list_header_frame_top", "list_header_frame_middle",
    "list_header_frame_bottom", "list_header_dead", "scrollbar_fg",
    "scrollbar_bg", "scrollbar_fg_pressed", "scrollbar_bg_pressed",
    "scrollbar_dead", "listview_text_hl", "listview_bg_hl",
    "listview_text_sel", "listview_bg_sel",
]

# Sprite groups whose members must differ from each other in pixels.
DISTINCT = [
    ("CBUTTONS.BMP", ["PREVIOUS", "PREVIOUS_ACTIVE"]),
    ("CBUTTONS.BMP", ["PLAY", "PLAY_ACTIVE"]),
    ("CBUTTONS.BMP", ["PAUSE", "PAUSE_ACTIVE"]),
    ("CBUTTONS.BMP", ["STOP", "STOP_ACTIVE"]),
    ("CBUTTONS.BMP", ["EJECT", "EJECT_ACTIVE"]),
    ("CBUTTONS.BMP", ["PREVIOUS", "PLAY", "PAUSE", "STOP", "NEXT"]),
    ("SHUFREP.BMP", ["SHUFFLE", "SHUFFLE_DEPRESSED", "SHUFFLE_SELECTED",
                     "SHUFFLE_SELECTED_DEPRESSED"]),
    ("SHUFREP.BMP", ["REPEAT", "REPEAT_DEPRESSED", "REPEAT_SELECTED",
                     "REPEAT_SELECTED_DEPRESSED"]),
    ("SHUFREP.BMP", ["EQ_BUTTON", "EQ_BUTTON_SELECTED", "EQ_BUTTON_DEPRESSED",
                     "EQ_BUTTON_DEPRESSED_SELECTED"]),
    ("SHUFREP.BMP", ["PLAYLIST_BUTTON", "PLAYLIST_BUTTON_SELECTED",
                     "PLAYLIST_BUTTON_DEPRESSED",
                     "PLAYLIST_BUTTON_DEPRESSED_SELECTED"]),
    ("SHUFREP.BMP", ["EQ_BUTTON", "PLAYLIST_BUTTON"]),
    ("TITLEBAR.BMP", ["TITLE_BAR", "TITLE_BAR_SELECTED"]),
    ("TITLEBAR.BMP", ["OPTIONS_BUTTON", "MINIMIZE_BUTTON", "SHADE_BUTTON",
                      "CLOSE_BUTTON"]),
    ("TITLEBAR.BMP", ["CLOSE_BUTTON", "CLOSE_BUTTON_DEPRESSED"]),
    ("TITLEBAR.BMP", ["SHADE_BACKGROUND", "SHADE_BACKGROUND_SELECTED"]),
    ("POSBAR.BMP", ["THUMB", "THUMB_SELECTED"]),
    ("VOLUME.BMP", ["THUMB", "THUMB_SELECTED"]),
    ("BALANCE.BMP", ["THUMB", "THUMB_ACTIVE"]),
    ("MONOSTER.BMP", ["MONO", "MONO_SELECTED"]),
    ("MONOSTER.BMP", ["STEREO", "STEREO_SELECTED"]),
    ("PLAYPAUS.BMP", ["PLAYING", "PAUSED", "STOPPED"]),
    ("NUMBERS.BMP", [f"DIGIT_{d}" for d in range(10)]),
    ("NUMS_EX.BMP", [f"DIGIT_{d}_EX" for d in range(10)]),
    ("EQMAIN.BMP", ["ON_BUTTON", "ON_BUTTON_SELECTED", "ON_BUTTON_DEPRESSED"]),
    ("EQMAIN.BMP", ["AUTO_BUTTON", "AUTO_BUTTON_SELECTED",
                    "AUTO_BUTTON_DEPRESSED"]),
    ("EQMAIN.BMP", ["SLIDER_THUMB", "SLIDER_THUMB_SELECTED"]),
    ("EQMAIN.BMP", ["TITLE_BAR", "TITLE_BAR_SELECTED"]),
    ("EQMAIN.BMP", ["PRESETS_BUTTON", "PRESETS_BUTTON_SELECTED"]),
    ("EQ_EX.BMP", ["SHADE_BACKGROUND", "SHADE_BACKGROUND_SELECTED"]),
    ("PLEDIT.BMP", ["TITLE_BAR", "TITLE_BAR_SELECTED"]),
    ("PLEDIT.BMP", ["SCROLL_HANDLE", "SCROLL_HANDLE_SELECTED"]),
    ("PLEDIT.BMP", list(_PL_BUTTONS)),
    ("GEN.BMP", ["TOP_LEFT", "TOP_LEFT_SELECTED"]),
]

# Sprites whose nominal rectangle overhangs its sheet. Webamp positions these
# by CSS background-position inside a smaller box, so only the leading columns
# are visible. Value = visible size.
CLIPPED = {
    ("PLAYPAUS.BMP", "NOT_WORKING"): (3, 9),
    ("PLAYPAUS.BMP", "WORKING"): (3, 9),
}


def effective(sheet, name):
    """Sprite rect clamped to what is visible."""
    x, y, w, h = SPRITES[sheet][name]
    if (sheet, name) in CLIPPED:
        w, h = CLIPPED[(sheet, name)]
    return x, y, w, h
