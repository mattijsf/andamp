// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.unit.IntOffset

/**
 * One axis of a window's size, held as a count of steps: the pixels the window takes before
 * any steps ([furniture]), the size of a step, and the range the count may take.
 */
data class SizeAxis(
    val furniture: Int,
    val step: Int,
    val min: Int,
    val max: Int,
    val current: Int,
)

/** The result of a grip drag: the window's new counts, and where it sits. */
data class Resized(
    val cols: Int,
    val steps: Int,
    val offset: IntOffset,
    /**
     * The height this makes the window. It is absolute and not a growth: a drag delivers
     * several events per frame, all measured from the size at the grab.
     */
    val height: Int = 0,
)

/**
 * The arithmetic of a resize grip.
 *
 * Windows resize in steps, because the art tiles in steps: the playlist grows by 29px
 * segments, a list window by one 13px row. Sizes are held as counts of steps, which stay
 * valid when a skin change alters the frame's size.
 */
object WindowSizing {
    /** The fraction of a step at which plain rounding moves to the next count. */
    private const val HALF_STEP = 0.5f

    /** How much further, in steps, the finger must go before the current count changes. */
    private const val BOUNDARY_DEADBAND = 0.2f

    /**
     * The count a grip dragged by [travel] px asks for, rounded to the nearest
     * step and kept inside [min]..[max].
     */
    fun stepsFor(
        startCount: Int,
        travel: Float,
        stepPx: Int,
        min: Int,
        max: Int,
    ): Int {
        val steps = startCount + Math.round(travel / stepPx)
        return steps.coerceIn(min, max.coerceAtLeast(min))
    }

    /**
     * The count a window of [rawHeight] px is asking to be, rounded to the nearest step. It
     * takes an absolute height, because a live resize asks again on every event.
     */
    fun stepsForHeight(
        rawHeight: Int,
        furnitureH: Int,
        stepPx: Int,
        min: Int,
        max: Int,
        /**
         * The window's current count. Passing it adds hysteresis, so a finger resting on a
         * step boundary does not flip the window between two sizes. Null rounds plainly.
         */
        current: Int? = null,
    ): Int {
        val exact = (rawHeight - furnitureH).toFloat() / stepPx
        val steps =
            if (current != null && kotlin.math.abs(exact - current) < HALF_STEP + BOUNDARY_DEADBAND) {
                current
            } else {
                Math.round(exact)
            }
        return steps.coerceIn(min, max.coerceAtLeast(min))
    }

    /** How many steps fit between a window's top edge and the bottom it may reach. */
    fun stepsThatFit(
        availableH: Int,
        chromeH: Int,
        stepPx: Int,
        min: Int,
    ): Int = ((availableH - chromeH) / stepPx).coerceAtLeast(min)

    /**
     * The size to remember: null once a window is as big as fits, which restores the
     * fill-the-screen default, so the window is not pinned to a height that only matches the
     * current screen.
     */
    fun rememberedSize(
        count: Int,
        fits: Int,
    ): Int? = count.takeIf { it < fits }

    /**
     * The count to lay out with: what the listener chose, capped at what fits. The cap is
     * not written back, so a keyboard or a smaller screen shrinks a window only while it
     * applies.
     */
    fun effectiveSteps(
        remembered: Int?,
        fits: Int,
        min: Int,
    ): Int = (remembered ?: fits).coerceIn(min.coerceAtMost(fits), fits.coerceAtLeast(min))

    /**
     * The result of a grip drag: both counts quantized, and the offset that keeps the
     * top-left corner where it was at the grab.
     *
     * The place depends on the size, and both are measured from the window as it was when
     * the grip was grabbed ([grab]), not from its live size. A window with no [widthAxis]
     * keeps its width.
     */
    @Suppress("LongParameterList") // two axes, two size functions and the screen they live on
    fun resize(
        grab: WindowGrab,
        screenW: Int,
        screenH: Int,
        heightAxis: SizeAxis,
        heightOf: (steps: Int) -> Int,
        widthAxis: SizeAxis? = null,
        widthOf: (cols: Int) -> Int = { grab.atWidth },
    ): Resized {
        val cols = widthAxis?.let { countFor(grab.rawWidth, it) } ?: 0
        val steps = countFor(grab.rawHeight, heightAxis)
        val newW = if (widthAxis == null) grab.atWidth else widthOf(cols)
        val newH = heightOf(steps)
        return Resized(
            cols = cols,
            steps = steps,
            offset = anchorTopLeft(grab.atOffset, grab.atWidth, newW, grab.atHeight, newH, screenW, screenH),
            height = newH,
        )
    }

    /** The count [axis] asks for at [raw] pixels, hysteresis included. */
    private fun countFor(
        raw: Int,
        axis: SizeAxis,
    ): Int =
        stepsForHeight(
            rawHeight = raw,
            furnitureH = axis.furniture,
            stepPx = axis.step,
            min = axis.min,
            max = axis.max,
            current = axis.current,
        )

    /**
     * The offset that keeps a window's top-left corner still while it changes size in both
     * axes. Offsets are center-relative, so an unchanged offset would grow the window out
     * of its middle.
     */
    fun anchorTopLeft(
        offset: IntOffset,
        oldW: Int,
        newW: Int,
        oldH: Int,
        newH: Int,
        screenW: Int,
        screenH: Int,
    ): IntOffset =
        IntOffset(
            offset.x + (screenW - oldW) / 2 - (screenW - newW) / 2,
            anchorTop(offset, oldH, newH, screenH).y,
        )

    /**
     * The offset that keeps a window's top edge still while its height changes: offsets are
     * measured from the container's center, so a height change alone would move the top
     * edge by half of it.
     */
    fun anchorTop(
        offset: IntOffset,
        oldH: Int,
        newH: Int,
        screenH: Int,
    ): IntOffset = IntOffset(offset.x, offset.y + (screenH - oldH) / 2 - (screenH - newH) / 2)
}
