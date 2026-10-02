// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import android.graphics.Paint
import android.graphics.Typeface

/**
 * How a row of text sits in a list: the font size, the insets and a baseline centered in the
 * row. The playlist, the skin manager and the media library share these metrics.
 */
object ListText {
    /** GDI's classic 8pt at 96dpi lands around a 10px em. */
    const val FONT_SIZE = 10f

    /** Text starts two px in from the frame. */
    const val LEFT_INSET = 2f

    /** The margin right of the right-aligned column, such as a duration. */
    const val RIGHT_INSET = 3f

    /** The whole list is nudged down from the frame's inner edge. */
    const val TOP_PAD = 3f

    fun paint(typeface: Typeface): Paint =
        Paint().apply {
            isAntiAlias = true
            isSubpixelText = false
            hinting = Paint.HINTING_ON
            textSize = FONT_SIZE
            this.typeface = typeface
        }

    /** The baseline that centers this paint's text in a [rowHeight] row at [rowTop]. */
    fun baseline(
        paint: Paint,
        rowTop: Float,
        rowHeight: Int,
    ): Float {
        val metrics = paint.fontMetrics
        return rowTop + (rowHeight - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
    }

    /**
     * The typeface for the font PLEDIT.TXT names. Courier and mono names get the system's
     * monospace, Times and serif names its serif, and everything else, Arial included, gets
     * [arialLike].
     */
    fun typefaceFor(
        name: String,
        arialLike: Typeface,
    ): Typeface =
        when {
            name.contains("courier", ignoreCase = true) || name.contains("mono", ignoreCase = true) -> Typeface.MONOSPACE
            name.contains("times", ignoreCase = true) || name.contains("serif", ignoreCase = true) -> Typeface.SERIF
            else -> arialLike
        }
}
