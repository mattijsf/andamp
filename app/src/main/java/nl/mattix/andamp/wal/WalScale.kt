// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import androidx.compose.ui.unit.IntSize
import nl.mattix.andamp.modernskin.skin.Edge

/** How large a modern skin is drawn on the phone, and where. */
internal object WalScale {
    /**
     * The most skin pixels the main window or the stack may measure either way. Skins are made for
     * desktop screens, so a size past this is a broken skin, and drawing it whole off screen before
     * it is shrunk would take more memory than a phone has.
     */
    const val MAX_PX = 4096

    /** The smallest scale a skin is shown at: one that fits its box only smaller is not shown. */
    const val MIN_SCALE = 0.25f

    /**
     * Why a skin cannot be shown, or null when it can: its main window of [mainSize] or its stack of
     * [stack] measures past [MAX_PX] either way, or, when the [box] it goes in is known, it fits
     * there only at a scale below [MIN_SCALE] ([baseW] as for [fit]). A box with no width or no
     * height (not laid out yet) counts as unknown.
     */
    fun unfit(
        mainSize: Pair<Int, Int>,
        stack: Pair<Int, Int>,
        baseW: Int,
        box: IntSize?,
    ): String? {
        if (maxOf(mainSize.first, mainSize.second, maxOf(stack.first, stack.second)) > MAX_PX) {
            return "too large: main window ${mainSize.first}x${mainSize.second}, stack ${stack.first}x${stack.second}"
        }
        val scale = box?.takeIf { it.width > 0 && it.height > 0 }?.let { fit(it, mainSize, baseW) } ?: return null
        return if (scale < MIN_SCALE) "too large for ${box.width}x${box.height}: scale $scale" else null
    }

    /** The scale for a main window of [mainSize] in [size]: from its visible width at the start ([baseW]) and its height. */
    fun fit(
        size: IntSize,
        mainSize: Pair<Int, Int>,
        baseW: Int,
    ): Float = of(size, baseW.takeIf { it > 0 } ?: mainSize.first, mainSize.second)

    /** Where a stack [stackW] layout pixels wide starts so it sits centered in [width] at [scale]. */
    fun left(
        width: Float,
        stackW: Int,
        scale: Float,
    ): Float = maxOf(0f, kotlin.math.floor((width - stackW * scale) / 2f))

    /** Where a stack [stackW] layout pixels wide starts so it ends at [width] at [scale]: against the far edge. */
    private fun end(
        width: Float,
        stackW: Int,
        scale: Float,
    ): Float = maxOf(0f, kotlin.math.floor(width - stackW * scale))

    /**
     * Where a stack [shown] layout pixels long starts on an axis [length] long at [scale]: against
     * [edge], or at [otherwise] for none.
     */
    private fun along(
        edge: Edge?,
        length: Float,
        shown: Int,
        scale: Float,
        otherwise: Float,
    ): Float =
        when (edge) {
            Edge.START -> 0f
            Edge.END -> end(length, shown, scale)
            null -> otherwise
        }

    /** A stack's scale in a box, and where its top-left corner sits there, in device px. */
    data class Place(
        val scale: Float,
        val left: Float,
        val top: Float,
    )

    /**
     * Where a stack of [stack] skin px goes in [fit]'s box. Upright, the main window of [mainSize]
     * sets the scale (see [fit], with [baseW]) and the stack hangs from the top, centered by how
     * far right the main window's content reaches at rest ([contentRight]): a window made wider
     * grows to the right, past the box's edge if need be, as a desktop window does. A stack that
     * shows [widest] px across, more than the box holds at that scale (MMD3's drawer slid out, or a
     * docked window its minimum width makes wider than the main one), is shrunk until it fits
     * across, and moved left as far as keeps it inside the box; one taller than the box at that
     * scale is shrunk to the box's height and centered both ways. Sideways, the whole stack fits
     * the box both ways and is centered both ways. The scale keeps room for the whole stack, so a
     * drawer opening inside it leaves the scale as it is; the centering goes by the part of it the
     * windows show ([drawn]), from its top-left corner.
     *
     * On an axis where the skin moved its main window against an edge of the viewport ([edgeX],
     * [edgeY]), as into a corner of the screen, the part the windows show goes against that edge of
     * the box instead, upright and sideways.
     */
    @Suppress("LongParameterList") // the stack's sizes as the engine has them, each needed apart
    fun place(
        fit: WalFit,
        mainSize: Pair<Int, Int>,
        baseW: Int,
        contentRight: Int,
        stack: Pair<Int, Int>,
        drawn: Pair<Int, Int> = stack,
        widest: Int = drawn.first,
        edgeX: Edge? = null,
        edgeY: Edge? = null,
    ): Place {
        val box = IntSize(fit.widthPx, fit.heightPx)
        val (stackW, stackH) = stack
        val (drawnW, drawnH) = drawn
        val width = box.width.toFloat()
        val height = box.height.toFloat()
        if (fit.sideways) {
            val s = of(box, stackW, stackH)
            return Place(
                s,
                along(edgeX, width, drawnW, s, left(width, drawnW, s)),
                along(edgeY, height, drawnH, s, left(height, drawnH, s)),
            )
        }
        val base = fit(box, mainSize, baseW)
        val s = across(box.width, widest, base)

        // a stack shrunk to fit across is held inside the box; one at the main window's scale hangs
        // from where its main window at rest is centered, and a wider one runs on to the right
        fun across(scale: Float) =
            along(
                edgeX,
                width,
                drawnW,
                scale,
                if (s <
                    base
                ) {
                    minOf(left(width, contentRight, scale), end(width, drawnW, scale))
                } else {
                    left(width, contentRight, scale)
                },
            )

        fun down(
            scale: Float,
            otherwise: Float,
        ) = along(edgeY, height, drawnH, scale, otherwise)
        if (stackH <= 0 || stackH * s <= height) return Place(s, across(s), down(s, 0f))
        val shrunk = height / stackH
        return Place(shrunk, across(shrunk), down(shrunk, left(height, drawnH, shrunk)))
    }

    /**
     * [scale], or the scale at which [shown] px across fit [width] when they run past it at
     * [scale]: whole from 2x up, as [of] gives it. A pixel's slack keeps a window sized to the box
     * at a fractional scale from being shrunk by a rounding.
     */
    fun across(
        width: Int,
        shown: Int,
        scale: Float,
    ): Float = if (shown > 0 && shown * scale > width + 1f) minOf(scale, whole(width.toFloat() / shown)) else scale

    /** The scale that fits [lw] x [lh] in [size]: whole numbers from 2x up, so the pixels stay crisp. */
    fun of(
        size: IntSize,
        lw: Int,
        lh: Int,
    ): Float {
        if (lw <= 0 || lh <= 0) return 1f
        return whole(minOf(size.width.toFloat() / lw, size.height.toFloat() / lh))
    }

    /** [s] floored to a whole number from 2x up, so the pixels stay crisp like the classic windows'; below that, [s]. */
    private fun whole(s: Float): Float = if (s >= 2f) kotlin.math.floor(s) else s
}
