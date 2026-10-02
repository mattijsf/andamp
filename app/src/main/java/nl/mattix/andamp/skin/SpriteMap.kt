// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect

/** A source rectangle inside a skin sprite sheet, in sheet pixels. */
data class Sprite(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
)

/**
 * Sprite source rectangles, transcribed 1:1 from webamp's skinSprites.ts
 * (github.com/captbaritone/webamp, packages/webamp/js/skinSprites.ts).
 * Names match webamp's sprite names so entries can be audited against the source.
 */
object SpriteMap {
    // BALANCE.BMP — when the skin has no BALANCE.BMP, Winamp reuses VOLUME.BMP
    // cropped to 38px; see Skin.balanceUsesVolume.
    val MAIN_BALANCE_BACKGROUND = Sprite(9, 0, 38, 420)
    val MAIN_BALANCE_BACKGROUND_FROM_VOLUME = Sprite(0, 0, 38, 420)
    val MAIN_BALANCE_THUMB = Sprite(15, 422, 14, 11)
    val MAIN_BALANCE_THUMB_ACTIVE = Sprite(0, 422, 14, 11)

    // CBUTTONS.BMP
    val MAIN_PREVIOUS_BUTTON = Sprite(0, 0, 23, 18)
    val MAIN_PREVIOUS_BUTTON_ACTIVE = Sprite(0, 18, 23, 18)
    val MAIN_PLAY_BUTTON = Sprite(23, 0, 23, 18)
    val MAIN_PLAY_BUTTON_ACTIVE = Sprite(23, 18, 23, 18)
    val MAIN_PAUSE_BUTTON = Sprite(46, 0, 23, 18)
    val MAIN_PAUSE_BUTTON_ACTIVE = Sprite(46, 18, 23, 18)
    val MAIN_STOP_BUTTON = Sprite(69, 0, 23, 18)
    val MAIN_STOP_BUTTON_ACTIVE = Sprite(69, 18, 23, 18)
    val MAIN_NEXT_BUTTON = Sprite(92, 0, 22, 18) // webamp declares 23 but renders 22
    val MAIN_NEXT_BUTTON_ACTIVE = Sprite(92, 18, 22, 18)
    val MAIN_EJECT_BUTTON = Sprite(114, 0, 22, 16)
    val MAIN_EJECT_BUTTON_ACTIVE = Sprite(114, 16, 22, 16)

    // MAIN.BMP
    val MAIN_WINDOW_BACKGROUND = Sprite(0, 0, 275, 116)

    // MONOSTER.BMP
    val MAIN_STEREO = Sprite(0, 12, 29, 12)
    val MAIN_STEREO_SELECTED = Sprite(0, 0, 29, 12)
    val MAIN_MONO = Sprite(29, 12, 27, 12)
    val MAIN_MONO_SELECTED = Sprite(29, 0, 27, 12)

    // NUMBERS.BMP (NUMS_EX.BMP shares digit layout; its minus signs are full 9x13 cells)
    val NO_MINUS_SIGN = Sprite(9, 6, 5, 1)
    val MINUS_SIGN = Sprite(20, 6, 5, 1)
    val NO_MINUS_SIGN_EX = Sprite(90, 0, 9, 13)
    val MINUS_SIGN_EX = Sprite(99, 0, 9, 13)

    fun digit(n: Int) = Sprite(n * 9, 0, 9, 13)

    // PLAYPAUS.BMP
    val MAIN_PLAYING_INDICATOR = Sprite(0, 0, 9, 9)
    val MAIN_PAUSED_INDICATOR = Sprite(9, 0, 9, 9)
    val MAIN_STOPPED_INDICATOR = Sprite(18, 0, 9, 9)
    val MAIN_NOT_WORKING_INDICATOR = Sprite(36, 0, 3, 9) // dest box is 3px wide
    val MAIN_WORKING_INDICATOR = Sprite(39, 0, 3, 9)

    // PLEDIT.BMP
    // The shaded playlist: a 14px bar of its own art, made of a left cap, a tiling filler, and a
    // 50px right cap that carries the readout and the buttons.
    val PLAYLIST_SHADE_LEFT = Sprite(72, 42, 25, 14)
    val PLAYLIST_SHADE_FILL = Sprite(72, 57, 25, 14)
    val PLAYLIST_SHADE_RIGHT = Sprite(99, 57, 50, 14)
    val PLAYLIST_SHADE_RIGHT_SELECTED = Sprite(99, 42, 50, 14)

    val PLAYLIST_TOP_TILE = Sprite(127, 21, 25, 20)
    val PLAYLIST_TOP_LEFT_CORNER = Sprite(0, 21, 25, 20)
    val PLAYLIST_TITLE_BAR = Sprite(26, 21, 100, 20)
    val PLAYLIST_TOP_RIGHT_CORNER = Sprite(153, 21, 25, 20)
    val PLAYLIST_TOP_TILE_SELECTED = Sprite(127, 0, 25, 20)
    val PLAYLIST_TOP_LEFT_SELECTED = Sprite(0, 0, 25, 20)
    val PLAYLIST_TITLE_BAR_SELECTED = Sprite(26, 0, 100, 20)
    val PLAYLIST_TOP_RIGHT_CORNER_SELECTED = Sprite(153, 0, 25, 20)
    val PLAYLIST_LEFT_TILE = Sprite(0, 42, 12, 29)
    val PLAYLIST_RIGHT_TILE = Sprite(31, 42, 20, 29)
    val PLAYLIST_BOTTOM_TILE = Sprite(179, 0, 25, 38)
    val PLAYLIST_BOTTOM_LEFT_CORNER = Sprite(0, 72, 125, 38)
    val PLAYLIST_BOTTOM_RIGHT_CORNER = Sprite(126, 72, 150, 38)
    val PLAYLIST_VISUALIZER_BACKGROUND = Sprite(205, 0, 75, 38)
    val PLAYLIST_SCROLL_HANDLE = Sprite(52, 53, 8, 18)
    val PLAYLIST_SCROLL_HANDLE_SELECTED = Sprite(61, 53, 8, 18)
    val PLAYLIST_CLOSE_SELECTED = Sprite(52, 42, 9, 9)
    val PLAYLIST_COLLAPSE_SELECTED = Sprite(62, 42, 9, 9)

    // expanding bottom-bar menus: entries 22x18 (normal / selected at x+23), 3px side bars
    val PLAYLIST_ADD_MENU_BAR = Sprite(48, 111, 3, 54)
    val PLAYLIST_ADD_URL = Sprite(0, 111, 22, 18)
    val PLAYLIST_ADD_URL_SELECTED = Sprite(23, 111, 22, 18)
    val PLAYLIST_ADD_DIR = Sprite(0, 130, 22, 18)
    val PLAYLIST_ADD_DIR_SELECTED = Sprite(23, 130, 22, 18)
    val PLAYLIST_ADD_FILE = Sprite(0, 149, 22, 18)
    val PLAYLIST_ADD_FILE_SELECTED = Sprite(23, 149, 22, 18)
    val PLAYLIST_REMOVE_MENU_BAR = Sprite(100, 111, 3, 72)
    val PLAYLIST_REMOVE_ALL = Sprite(54, 111, 22, 18)
    val PLAYLIST_REMOVE_ALL_SELECTED = Sprite(77, 111, 22, 18)
    val PLAYLIST_CROP = Sprite(54, 130, 22, 18)
    val PLAYLIST_CROP_SELECTED = Sprite(77, 130, 22, 18)
    val PLAYLIST_REMOVE_SELECTED = Sprite(54, 149, 22, 18)
    val PLAYLIST_REMOVE_SELECTED_SELECTED = Sprite(77, 149, 22, 18)
    val PLAYLIST_REMOVE_MISC = Sprite(54, 168, 22, 18)
    val PLAYLIST_REMOVE_MISC_SELECTED = Sprite(77, 168, 22, 18)
    val PLAYLIST_SELECT_MENU_BAR = Sprite(150, 111, 3, 54)
    val PLAYLIST_INVERT_SELECTION = Sprite(104, 111, 22, 18)
    val PLAYLIST_INVERT_SELECTION_SELECTED = Sprite(127, 111, 22, 18)
    val PLAYLIST_SELECT_ZERO = Sprite(104, 130, 22, 18)
    val PLAYLIST_SELECT_ZERO_SELECTED = Sprite(127, 130, 22, 18)
    val PLAYLIST_SELECT_ALL = Sprite(104, 149, 22, 18)
    val PLAYLIST_SELECT_ALL_SELECTED = Sprite(127, 149, 22, 18)
    val PLAYLIST_MISC_MENU_BAR = Sprite(200, 111, 3, 54)
    val PLAYLIST_SORT_LIST = Sprite(154, 111, 22, 18)
    val PLAYLIST_SORT_LIST_SELECTED = Sprite(177, 111, 22, 18)
    val PLAYLIST_FILE_INFO = Sprite(154, 130, 22, 18)
    val PLAYLIST_FILE_INFO_SELECTED = Sprite(177, 130, 22, 18)
    val PLAYLIST_MISC_OPTIONS = Sprite(154, 149, 22, 18)
    val PLAYLIST_MISC_OPTIONS_SELECTED = Sprite(177, 149, 22, 18)
    val PLAYLIST_LIST_BAR = Sprite(250, 111, 3, 54)
    val PLAYLIST_NEW_LIST = Sprite(204, 111, 22, 18)
    val PLAYLIST_NEW_LIST_SELECTED = Sprite(227, 111, 22, 18)
    val PLAYLIST_SAVE_LIST = Sprite(204, 130, 22, 18)
    val PLAYLIST_SAVE_LIST_SELECTED = Sprite(227, 130, 22, 18)
    val PLAYLIST_LOAD_LIST = Sprite(204, 149, 22, 18)
    val PLAYLIST_LOAD_LIST_SELECTED = Sprite(227, 149, 22, 18)

    // EQMAIN.BMP
    val EQ_WINDOW_BACKGROUND = Sprite(0, 0, 275, 116)
    val EQ_TITLE_BAR = Sprite(0, 149, 275, 14)
    val EQ_TITLE_BAR_SELECTED = Sprite(0, 134, 275, 14)
    val EQ_SLIDER_THUMB = Sprite(0, 164, 11, 11)
    val EQ_SLIDER_THUMB_SELECTED = Sprite(0, 176, 11, 11)
    val EQ_CLOSE_BUTTON = Sprite(0, 116, 9, 9)
    val EQ_CLOSE_BUTTON_ACTIVE = Sprite(0, 125, 9, 9)

    // The shaded equalizer, from EQ_EX.BMP: its own bar with volume and balance sliders. Each
    // slider's thumb has three arts, chosen by where the value sits (webamp's segment():
    // left/center/right).
    val EQ_SHADE_BACKGROUND_SELECTED = Sprite(0, 0, 275, 14)
    val EQ_SHADE_BACKGROUND = Sprite(0, 15, 275, 14)
    val EQ_SHADE_VOLUME_THUMB_LEFT = Sprite(1, 30, 3, 7)
    val EQ_SHADE_VOLUME_THUMB_CENTER = Sprite(4, 30, 3, 7)
    val EQ_SHADE_VOLUME_THUMB_RIGHT = Sprite(7, 30, 3, 7)
    val EQ_SHADE_BALANCE_THUMB_LEFT = Sprite(11, 30, 3, 7)
    val EQ_SHADE_BALANCE_THUMB_CENTER = Sprite(14, 30, 3, 7)
    val EQ_SHADE_BALANCE_THUMB_RIGHT = Sprite(17, 30, 3, 7)
    val EQ_SHADE_CLOSE_BUTTON = Sprite(11, 38, 9, 9)
    val EQ_SHADE_CLOSE_BUTTON_ACTIVE = Sprite(11, 47, 9, 9)
    val EQ_ON_BUTTON = Sprite(10, 119, 26, 12)
    val EQ_ON_BUTTON_DEPRESSED = Sprite(128, 119, 26, 12)
    val EQ_ON_BUTTON_SELECTED = Sprite(69, 119, 26, 12)
    val EQ_ON_BUTTON_SELECTED_DEPRESSED = Sprite(187, 119, 26, 12)
    val EQ_AUTO_BUTTON = Sprite(36, 119, 32, 12)
    val EQ_AUTO_BUTTON_DEPRESSED = Sprite(154, 119, 32, 12)
    val EQ_AUTO_BUTTON_SELECTED = Sprite(95, 119, 32, 12)
    val EQ_AUTO_BUTTON_SELECTED_DEPRESSED = Sprite(213, 119, 32, 12)
    val EQ_GRAPH_BACKGROUND = Sprite(0, 294, 113, 19)
    val EQ_GRAPH_LINE_COLORS = Sprite(115, 294, 1, 19)
    val EQ_PRESETS_BUTTON = Sprite(224, 164, 44, 12)
    val EQ_PRESETS_BUTTON_SELECTED = Sprite(224, 176, 44, 12)
    val EQ_PREAMP_LINE = Sprite(0, 314, 113, 1)

    /**
     * EQ slider groove frames: a 14-column x 2-row grid inside EQMAIN.BMP.
     * Frame n (0..27, 0 = -12dB art, 27 = +12dB art), visible size 14x63,
     * horizontal pitch 15, vertical pitch 65. From webamp Band.tsx.
     */
    fun eqSliderFrame(n: Int) = Sprite(13 + (n % 14) * 15, 164 + (n / 14) * 65, 14, 63)

    // POSBAR.BMP
    val MAIN_POSITION_SLIDER_BACKGROUND = Sprite(0, 0, 248, 10)
    val MAIN_POSITION_SLIDER_THUMB = Sprite(248, 0, 29, 10)
    val MAIN_POSITION_SLIDER_THUMB_SELECTED = Sprite(278, 0, 29, 10)

    // SHUFREP.BMP
    val MAIN_SHUFFLE_BUTTON = Sprite(28, 0, 47, 15)
    val MAIN_SHUFFLE_BUTTON_DEPRESSED = Sprite(28, 15, 47, 15)
    val MAIN_SHUFFLE_BUTTON_SELECTED = Sprite(28, 30, 47, 15)
    val MAIN_SHUFFLE_BUTTON_SELECTED_DEPRESSED = Sprite(28, 45, 47, 15)
    val MAIN_REPEAT_BUTTON = Sprite(0, 0, 28, 15)
    val MAIN_REPEAT_BUTTON_DEPRESSED = Sprite(0, 15, 28, 15)
    val MAIN_REPEAT_BUTTON_SELECTED = Sprite(0, 30, 28, 15)
    val MAIN_REPEAT_BUTTON_SELECTED_DEPRESSED = Sprite(0, 45, 28, 15)
    val MAIN_EQ_BUTTON = Sprite(0, 61, 23, 12)
    val MAIN_EQ_BUTTON_SELECTED = Sprite(0, 73, 23, 12)
    val MAIN_EQ_BUTTON_DEPRESSED = Sprite(46, 61, 23, 12)
    val MAIN_EQ_BUTTON_DEPRESSED_SELECTED = Sprite(46, 73, 23, 12)
    val MAIN_PLAYLIST_BUTTON = Sprite(23, 61, 23, 12)
    val MAIN_PLAYLIST_BUTTON_SELECTED = Sprite(23, 73, 23, 12)
    val MAIN_PLAYLIST_BUTTON_DEPRESSED = Sprite(69, 61, 23, 12)
    val MAIN_PLAYLIST_BUTTON_DEPRESSED_SELECTED = Sprite(69, 73, 23, 12)

    // TITLEBAR.BMP
    val MAIN_TITLE_BAR = Sprite(27, 15, 275, 14)
    val MAIN_TITLE_BAR_SELECTED = Sprite(27, 0, 275, 14)
    val MAIN_OPTIONS_BUTTON = Sprite(0, 0, 9, 9)
    val MAIN_OPTIONS_BUTTON_DEPRESSED = Sprite(0, 9, 9, 9)
    val MAIN_MINIMIZE_BUTTON = Sprite(9, 0, 9, 9)
    val MAIN_MINIMIZE_BUTTON_DEPRESSED = Sprite(9, 9, 9, 9)
    val MAIN_SHADE_BUTTON = Sprite(0, 18, 9, 9)
    val MAIN_SHADE_BUTTON_DEPRESSED = Sprite(9, 18, 9, 9)

    // Window shade: the player collapsed to its title bar. The transport buttons are painted into
    // the bar's background, so the widgets over them are hit rects with no sprites of their own.
    val MAIN_SHADE_BACKGROUND = Sprite(27, 42, 275, 14)
    val MAIN_SHADE_BACKGROUND_SELECTED = Sprite(27, 29, 275, 14)
    val MAIN_SHADE_BUTTON_SELECTED = Sprite(0, 27, 9, 9)
    val MAIN_SHADE_BUTTON_SELECTED_DEPRESSED = Sprite(9, 27, 9, 9)
    val MAIN_SHADE_POSITION_BACKGROUND = Sprite(0, 36, 17, 7)
    val MAIN_SHADE_POSITION_THUMB = Sprite(20, 36, 3, 7)
    val MAIN_SHADE_POSITION_THUMB_LEFT = Sprite(17, 36, 3, 7)
    val MAIN_SHADE_POSITION_THUMB_RIGHT = Sprite(23, 36, 3, 7)
    val MAIN_CLOSE_BUTTON = Sprite(18, 0, 9, 9)
    val MAIN_CLOSE_BUTTON_DEPRESSED = Sprite(18, 9, 9, 9)
    val MAIN_CLUTTER_BAR_BACKGROUND = Sprite(304, 0, 8, 43)
    val MAIN_CLUTTER_BAR_BACKGROUND_DISABLED = Sprite(312, 0, 8, 43)

    // Winamp's clutter bar: the letters down the left edge, each lit while it is held. Every lit
    // letter has its own place on the sheet and its own height.
    val MAIN_CLUTTER_BAR_BUTTON_O_SELECTED = Sprite(304, 47, 8, 8)
    val MAIN_CLUTTER_BAR_BUTTON_A_SELECTED = Sprite(312, 55, 8, 7)
    val MAIN_CLUTTER_BAR_BUTTON_I_SELECTED = Sprite(320, 62, 8, 7)
    val MAIN_CLUTTER_BAR_BUTTON_D_SELECTED = Sprite(328, 69, 8, 8)
    val MAIN_CLUTTER_BAR_BUTTON_V_SELECTED = Sprite(336, 77, 8, 7)

    // VOLUME.BMP
    val MAIN_VOLUME_BACKGROUND = Sprite(0, 0, 68, 420)
    val MAIN_VOLUME_THUMB = Sprite(15, 422, 14, 11)
    val MAIN_VOLUME_THUMB_SELECTED = Sprite(0, 422, 14, 11)

    /** 28 volume/balance background frames, 15px vertical pitch, visible height 13. */
    fun volumeFrameY(frame: Int) = frame * 15

    // Generic window frame (GEN.BMP) — Winamp's chrome for its extra windows;
    // webamp uses the same for its Milkdrop window. Coordinates from
    // webamp's skinSprites.ts GEN block.
    val GEN_TOP_LEFT_SELECTED = Sprite(0, 0, 25, 20)
    val GEN_TOP_LEFT_END_SELECTED = Sprite(26, 0, 25, 20)
    val GEN_TOP_CENTER_FILL_SELECTED = Sprite(52, 0, 25, 20)
    val GEN_TOP_RIGHT_END_SELECTED = Sprite(78, 0, 25, 20)
    val GEN_TOP_LEFT_RIGHT_FILL_SELECTED = Sprite(104, 0, 25, 20)
    val GEN_TOP_RIGHT_SELECTED = Sprite(130, 0, 25, 20)
    val GEN_TOP_LEFT = Sprite(0, 21, 25, 20)
    val GEN_TOP_LEFT_END = Sprite(26, 21, 25, 20)
    val GEN_TOP_CENTER_FILL = Sprite(52, 21, 25, 20)
    val GEN_TOP_RIGHT_END = Sprite(78, 21, 25, 20)
    val GEN_TOP_LEFT_RIGHT_FILL = Sprite(104, 21, 25, 20)
    val GEN_TOP_RIGHT = Sprite(130, 21, 25, 20)
    val GEN_BOTTOM_LEFT = Sprite(0, 42, 125, 14)
    val GEN_BOTTOM_RIGHT = Sprite(0, 57, 125, 14)
    val GEN_BOTTOM_FILL = Sprite(127, 72, 25, 14)
    val GEN_MIDDLE_LEFT = Sprite(127, 42, 11, 29)
    val GEN_MIDDLE_LEFT_BOTTOM = Sprite(158, 42, 11, 24)
    val GEN_MIDDLE_RIGHT = Sprite(139, 42, 8, 29)
    val GEN_MIDDLE_RIGHT_BOTTOM = Sprite(170, 42, 8, 24)
    val GEN_CLOSE_SELECTED = Sprite(148, 42, 9, 9)

    // Thin bottom bar for floating windows on skins with no GEN.BMP. Winamp has no such window, so
    // webamp has no names for these: they are the top border line and the outer edge of
    // PLEDIT.BMP's bottom bar, plus the resize grip in its bottom-right corner. One pixel wide,
    // stretched across the window: rows 72..78 sit above every button in the bar and rows 103..109
    // below them, but the bar shades horizontally, so a wider tile would repeat that shading as a
    // seam. x=13 clears the ADD/SUB art that starts at the bar's left edge.
    val PLAYLIST_BOTTOM_BORDER_TOP = Sprite(13, 72, 1, 7)
    val PLAYLIST_BOTTOM_BORDER_EDGE = Sprite(13, 103, 1, 7)
    val PLAYLIST_RESIZE_GRIP = Sprite(257, 93, 13, 11)
}

/**
 * Destination positions on the three windows (virtual 275-wide coordinates),
 * transcribed from webamp's main-window.css / equalizer-window.css /
 * playlist-window.css.
 */
object Dest {
    // Main window
    val TITLE_BAR = IntOffset(0, 0)
    val OPTIONS_BUTTON = IntOffset(6, 3)
    val MINIMIZE_BUTTON = IntOffset(244, 3)
    val SHADE_BUTTON = IntOffset(254, 3)
    val CLOSE_BUTTON = IntOffset(264, 3)
    val CLUTTER_BAR = IntOffset(10, 22)

    /**
     * Where each clutter-bar letter sits inside the bar, from webamp's
     * main-window.css: 7px tall apiece except O and D, which are 8.
     */
    val CLUTTER_O = IntRect(10, 25, 18, 33)
    val CLUTTER_A = IntRect(10, 33, 18, 40)
    val CLUTTER_I = IntRect(10, 40, 18, 47)
    val CLUTTER_D = IntRect(10, 47, 18, 55)
    val CLUTTER_V = IntRect(10, 55, 18, 62)
    val PLAY_PAUSE_INDICATOR = IntOffset(26, 28)
    val WORK_INDICATOR = IntOffset(24, 28)
    val TIME_MINUS = IntOffset(38, 32) // 5x1 NUMBERS variant
    val TIME_MINUS_EX = IntOffset(38, 26) // 9x13 NUMS_EX variant
    val TIME_DIGIT_X = intArrayOf(48, 60, 78, 90) // mm ss at y=26
    const val TIME_DIGIT_Y = 26
    val VISUALIZER = IntOffset(24, 43)
    const val VIS_W = 76
    const val VIS_H = 16
    val MARQUEE = IntOffset(111, 27) // css box y=24 + 3px padding
    const val MARQUEE_W = 154
    val KBPS = IntOffset(111, 43) // 15x6
    val KHZ = IntOffset(156, 43) // 10x6
    val MONO = IntOffset(212, 41)
    val STEREO = IntOffset(239, 41)
    val VOLUME = IntOffset(107, 57) // 68x13; thumb rides at y+1, travel 0..51
    val BALANCE = IntOffset(177, 57) // 38x13; thumb travel 0..24
    val EQ_TOGGLE = IntOffset(219, 58)
    val PL_TOGGLE = IntOffset(242, 58)
    val POSBAR = IntOffset(16, 72) // 248x10; thumb travel 0..219
    val PREV = IntOffset(16, 88)
    val PLAY = IntOffset(39, 88)
    val PAUSE = IntOffset(62, 88)
    val STOP = IntOffset(85, 88)
    val NEXT = IntOffset(108, 88)
    val EJECT = IntOffset(136, 89)
    val SHUFFLE = IntOffset(164, 89)
    val REPEAT = IntOffset(210, 89)

    // Equalizer window
    val EQ_ON = IntOffset(14, 18)
    val EQ_AUTO = IntOffset(40, 18)
    val EQ_PRESETS = IntOffset(217, 18)
    val EQ_GRAPH = IntOffset(86, 17) // 113x19
    const val EQ_SLIDER_Y = 38 // all sliders; box 14x63, thumb travel 0..51
    const val EQ_PREAMP_X = 21

    fun eqBandX(i: Int) = 78 + i * 18 // bands 0..9

    // Playlist window; the close and shade x positions are for a 275-wide window
    const val PL_TOP_H = 20
    const val PL_BOTTOM_H = 38
    const val PL_LEFT_W = 12
    const val PL_RIGHT_W = 20
    const val PL_ROW_H = 13
    const val PL_TILE_STEP = 29
    val PL_CLOSE = IntOffset(264, 3) // right:2 -> 275-2-9
    val PL_SHADE = IntOffset(254, 3) // right:12 -> 275-12-9
}
