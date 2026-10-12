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
    /**
     * What the list is taller than its rows: a window held at a height that is not a whole
     * number of rows leaves this much empty under the last one.
     */
    val slack: Int = 0,
    /** How tall a row of the list is: Winamp's 13 px, or what the listener picked. */
    val rowH: Int = ROW_H,
) {
    fun listWidth(frame: WindowFrame) = width - frame.chromeW

    /**
     * The height of the list area: every visible row plus the top pad the rows are drawn
     * with.
     */
    fun listHeight() = ListText.TOP_PAD.toInt() + visibleRows * rowH + slack

    /** The window's height in [frame]: the list plus the frame's chrome. */
    fun height(frame: WindowFrame) = frame.chromeH + listHeight()

    /** Top of a visible row, pad included. */
    fun rowY(row: Int) = rowY(row, rowH)

    /** Which visible row a window-relative y lands on; the pad belongs to row 0. */
    fun rowAt(
        y: Float,
        listTop: Int,
    ): Int = rowAt(y, listTop, rowH)

    companion object {
        const val WIDTH = 275
        const val ROW_H = 13
        const val DEFAULT_ROWS = 8
        const val MIN_ROWS = 3
        const val REMOVE_W = 30
        const val TITLE = "Skins"

        /** The fewest rows of [rowH] the window is drawn with; see [WindowSizing.minRows]. */
        fun minRows(rowH: Int) = WindowSizing.minRows(MIN_ROWS, ROW_H, rowH)

        /** How many rows of [rowH] fit in [availVirtual] px of window, chrome and pad included. */
        fun rowsThatFit(
            availVirtual: Int,
            frame: WindowFrame,
            rowH: Int = ROW_H,
        ): Int = ((availVirtual - furnitureH(frame)) / rowH).coerceAtLeast(minRows(rowH))

        /** The layout of a window exactly [height] tall and [width] wide; see [WindowSizing.rowsAndSlack]. */
        fun filling(
            height: Int,
            width: Int,
            frame: WindowFrame,
            rowH: Int = ROW_H,
        ): SkinManagerLayout {
            val (rows, slack) = WindowSizing.rowsAndSlack(height, furnitureH(frame), rowH, minRows(rowH))
            return SkinManagerLayout(rows, width, slack, rowH)
        }

        /** The height that is not rows: the frame's chrome and the top pad. */
        fun furnitureH(frame: WindowFrame) = frame.chromeH + ListText.TOP_PAD.toInt()

        /** webamp's WINDOW_RESIZE_SEGMENT_WIDTH. */
        const val WIDTH_STEP = WidthSteps.WIDTH_STEP

        /** How the skin browser widens. */
        val WIDTH_STEPS = WidthSteps(WIDTH, WIDTH_STEP)

        fun widthOfCols(cols: Int) = WIDTH_STEPS.widthOf(cols)

        fun listWidth(frame: WindowFrame) = WIDTH - frame.chromeW

        /** Top of a visible row of [rowH], pad included. */
        fun rowY(
            row: Int,
            rowH: Int = ROW_H,
        ) = ListText.TOP_PAD.toInt() + row * rowH

        /** Which visible row of [rowH] a window-relative y lands on; the pad belongs to row 0. */
        fun rowAt(
            y: Float,
            listTop: Int,
            rowH: Int = ROW_H,
        ): Int = ((y - listTop - ListText.TOP_PAD) / rowH).toInt().coerceAtLeast(0)
    }
}
