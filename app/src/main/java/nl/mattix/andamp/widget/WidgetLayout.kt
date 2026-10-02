// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.widget

import nl.mattix.andamp.ui.window.MAIN_H
import nl.mattix.andamp.ui.window.MAIN_W
import nl.mattix.andamp.ui.window.SHADE_H
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Which player fits the box the launcher gave, and how big to draw it.
 *
 * The main window is fixed sprite art, 275 by 116, and is never stretched (ENGINEERING.md, Pixel
 * rule 2: integer scale in virtual pixels). A box tall enough gets the whole player; a
 * letterbox-shaped one gets windowshade, the same player at 275 by 14.
 *
 * The scale is fractional only when the listener picks Fill ([WidgetSize.FILL]). It stays uniform,
 * so the aspect is kept, but columns then differ by a device pixel in width.
 */
data class WidgetLayout(
    /** Whether windowshade is shown in place of the whole window. */
    val shaded: Boolean,
    /** Device pixels per virtual pixel, never below one. Whole unless Fill is chosen. */
    val factor: Float,
) {
    val virtualHeight: Int get() = if (shaded) SHADE_H else MAIN_H

    val widthPx: Int get() = px(MAIN_W)

    val heightPx: Int get() = px(virtualHeight)

    /**
     * Where a virtual coordinate lands in the bitmap. The picture and the patches laid over it are
     * rasterized separately, so both round here and agree to the pixel.
     */
    fun px(v: Int) = (v * factor).roundToInt()

    /**
     * How wide [size] virtual pixels are when they start at [at]. At a fractional factor a width
     * depends on where it begins; rounding the width on its own would leave seams between neighbors
     * that should touch.
     */
    fun span(
        at: Int,
        size: Int,
    ) = px(at + size) - px(at)

    /** A virtual rectangle in the bitmap's own pixels. */
    fun rect(
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ) = Rect(px(left), px(top), span(left, width), span(top, height))

    /** A rectangle in the bitmap's pixels, shared by whoever draws it and whoever places it. */
    data class Rect(
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
    )

    companion object {
        /**
         * The layout for a box [widthPx] by [heightPx].
         *
         * The whole window is chosen whenever it fits at a factor of one or more. Otherwise
         * windowshade takes the width it can and leaves the rest of the box empty.
         *
         * A box too small for one device pixel per virtual pixel still gets a factor of 1.
         *
         * [fill] takes the exact factor instead of the whole one below it, so the art reaches the
         * edge of the box.
         */
        fun choose(
            widthPx: Int,
            heightPx: Int,
            fill: Boolean = false,
        ): WidgetLayout {
            val full = factorFor(widthPx, heightPx, MAIN_H, fill)
            if (full >= 1f) return WidgetLayout(shaded = false, factor = full)
            return WidgetLayout(shaded = true, factor = factorFor(widthPx, heightPx, SHADE_H, fill).coerceAtLeast(1f))
        }

        private fun factorFor(
            widthPx: Int,
            heightPx: Int,
            virtualHeight: Int,
            fill: Boolean,
        ): Float {
            val exact = minOf(widthPx / MAIN_W.toFloat(), heightPx / virtualHeight.toFloat())
            return if (fill) exact else floor(exact)
        }
    }
}
