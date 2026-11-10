// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import nl.mattix.andamp.skin.Sprite

/** Where the shaded main window keeps its controls, from webamp's main-window.css under `.shade`. */
internal object MainShade {
    /** The transport buttons' hit rectangles; their art is part of the bar. */
    val PREV = Sprite(169, 2, 7, 10)
    val PLAY = Sprite(176, 2, 10, 10)
    val PAUSE = Sprite(186, 2, 9, 10)
    val STOP = Sprite(195, 2, 9, 10)
    val NEXT = Sprite(204, 2, 10, 10)
    val EJECT = Sprite(215, 2, 10, 10)

    const val POSITION_X = 226
    const val POSITION_Y = 4
    const val POSITION_W = 17
    const val THUMB_W = 3

    /** The clock, in the 5x6 TEXT.BMP font. */
    const val TIME_X = 127
    const val TIME_Y = 4
    const val TIME_W = 25 // "00:00" in the 5px font
    const val TIME_H = 6

    /** The strip of visualizer the shaded bar keeps; webamp's `.shade #visualizer`. */
    const val VIS_X = 79
    const val VIS_Y = 5
}
