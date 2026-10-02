// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

/**
 * Where the pieces of a generic window's title bar go. Geometry only: the pieces have the
 * same sizes in every skin, and [GenWindow] draws them.
 *
 * webamp's gen-window.css lays out seven pieces:
 * ```
 * left(25) | left fill | left end(25) | title plate | right end(25) | right fill | right(25)
 * ```
 * The plate is the plain gap in the rails, sized to the title and its padding; the fills are
 * the rails, and the end caps close a rail where it meets the plate.
 *
 * webamp centers the plate. Winamp 2.8 puts the title at the left, so [layout] gives the left
 * fill no width and the rail runs from the title to the right corner. The left end cap stays,
 * because skins draw the join between the corner and the plate into it.
 */
object GenTitleBar {
    enum class Piece {
        LEFT_CORNER,
        LEFT_FILL,
        LEFT_END,
        PLATE,
        RIGHT_END,
        RIGHT_FILL,
        RIGHT_CORNER,
    }

    data class Segment(
        val piece: Piece,
        val x: Int,
        val width: Int,
    ) {
        val right get() = x + width
    }

    /** Corner and end caps are all 25px wide in every skin's GEN.BMP. */
    const val CAP_W = 25

    // gen-window.css: .gen-top-title { padding: 0 3px 0 4px }
    const val PAD_LEFT = 4
    const val PAD_RIGHT = 3

    fun plateWidth(titleW: Int) = PAD_LEFT + titleW.coerceAtLeast(0) + PAD_RIGHT

    /** The widest title this window can hold: everything the caps and the padding leave. */
    fun maxTitleWidth(windowW: Int) = (windowW - CAP_W * 4 - PAD_LEFT - PAD_RIGHT).coerceAtLeast(0)

    /**
     * [title] cut down to what fits, measured by the caller's font. [layout] clamps the plate
     * but not the text drawn on it, so a long title is truncated here, without an ellipsis.
     */
    fun fit(
        title: String,
        windowW: Int,
        measure: (String) -> Int,
    ): String {
        val max = maxTitleWidth(windowW)
        if (measure(title) <= max) return title
        var length = title.length
        while (length > 0 && measure(title.take(length)) > max) length--
        return title.take(length)
    }

    /**
     * The bar for a [windowW]-wide window holding a [titleW]-wide title, left to right and
     * gapless. A window too narrow for its title still yields ordered, non-negative segments:
     * the caps keep their size and the fill collapses.
     */
    fun layout(
        windowW: Int,
        titleW: Int,
    ): List<Segment> {
        val fixed = CAP_W * 4 // two corners, two end caps
        val plate = plateWidth(titleW).coerceAtMost((windowW - fixed).coerceAtLeast(0))
        val slack = (windowW - fixed - plate).coerceAtLeast(0)
        var x = 0
        return buildList {
            fun put(
                piece: Piece,
                width: Int,
            ) {
                add(Segment(piece, x, width))
                x += width
            }
            put(Piece.LEFT_CORNER, CAP_W)
            // no rail to the left of the title; the end cap stays, because skins draw the
            // join between the corner and the plate into it
            put(Piece.LEFT_FILL, 0)
            put(Piece.LEFT_END, CAP_W)
            put(Piece.PLATE, plate)
            put(Piece.RIGHT_END, CAP_W)
            put(Piece.RIGHT_FILL, slack)
            put(Piece.RIGHT_CORNER, CAP_W)
        }
    }

    /** Where the title's first glyph starts, given the same inputs as [layout]. */
    fun titleX(
        windowW: Int,
        titleW: Int,
    ): Int = layout(windowW, titleW).first { it.piece == Piece.PLATE }.x + PAD_LEFT
}
