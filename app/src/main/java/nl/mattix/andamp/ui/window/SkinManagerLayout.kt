// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

/**
 * Geometry of the skin manager window, in virtual px. It holds a count of rows, not a
 * height: the window resizes a row at a time, and the frame's height differs between skins.
 */
class SkinManagerLayout(
    val visibleRows: Int = DEFAULT_ROWS,
    /** The window's width: [WIDTH] plus [WIDTH_STEP] a step. */
    val width: Int = WIDTH,
) {
    fun listWidth(frame: WindowFrame) = width - frame.chromeW

    /**
     * The height of the list area: every visible row plus the top pad the rows are drawn
     * with.
     */
    fun listHeight() = ListText.TOP_PAD.toInt() + visibleRows * ROW_H

    /** The window's height in [frame]: the list plus the frame's chrome. */
    fun height(frame: WindowFrame) = frame.chromeH + listHeight()

    companion object {
        const val WIDTH = 275
        const val ROW_H = 13
        const val DEFAULT_ROWS = 8
        const val MIN_ROWS = 3
        const val REMOVE_W = 30
        const val TITLE = "Skins"

        /** How many rows fit in [availVirtual] px of window, chrome and pad included. */
        fun rowsThatFit(
            availVirtual: Int,
            frame: WindowFrame,
        ): Int = ((availVirtual - furnitureH(frame)) / ROW_H).coerceAtLeast(MIN_ROWS)

        /** The height that is not rows: the frame's chrome and the top pad. */
        fun furnitureH(frame: WindowFrame) = frame.chromeH + ListText.TOP_PAD.toInt()

        /** webamp's WINDOW_RESIZE_SEGMENT_WIDTH. */
        const val WIDTH_STEP = 25

        fun widthOfCols(cols: Int) = WIDTH + cols.coerceAtLeast(0) * WIDTH_STEP

        fun listWidth(frame: WindowFrame) = WIDTH - frame.chromeW

        /** Top of a visible row, pad included. */
        fun rowY(row: Int) = ListText.TOP_PAD.toInt() + row * ROW_H

        /** Which visible row a window-relative y lands on; the pad belongs to row 0. */
        fun rowAt(
            y: Float,
            listTop: Int,
        ): Int = ((y - listTop - ListText.TOP_PAD) / ROW_H).toInt().coerceAtLeast(0)
    }
}
