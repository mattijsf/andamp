// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import kotlin.math.abs
import kotlin.math.exp

/**
 * What a thrown list does after the finger leaves it.
 *
 * It works in rows, because every list here holds an integer row offset. The speed decays
 * exponentially, and the distance traveled is its integral in closed form, so nothing is
 * stepped and a throw's end can be computed without a clock.
 */
object RowFling {
    /**
     * How fast the finger was going, in rows a second, having moved [rowsMoved] in [overMs].
     * Callers measure the tail of the gesture, not the whole of it.
     */
    fun speedOf(
        rowsMoved: Float,
        overMs: Long,
    ): Float = if (overMs <= 0) 0f else rowsMoved * MILLIS / overMs

    /** Whether a throw is at least [LEAST] rows a second. */
    fun worthCarrying(rowsPerSecond: Float): Boolean = abs(rowsPerSecond) >= LEAST

    /** How far a throw of [rowsPerSecond] has traveled after [ms], in rows. */
    fun travelled(
        rowsPerSecond: Float,
        ms: Long,
    ): Float = rowsPerSecond * SLOWING / MILLIS * (1f - exp(-ms.toFloat() / SLOWING))

    /** The total distance of a throw, in rows. */
    fun distance(rowsPerSecond: Float): Float = rowsPerSecond * SLOWING / MILLIS

    /** The milliseconds until a throw has slowed to [LEAST] rows a second. */
    fun restsAfter(rowsPerSecond: Float): Long {
        if (!worthCarrying(rowsPerSecond)) return 0
        return (SLOWING * kotlin.math.ln(abs(rowsPerSecond) / LEAST)).toLong()
    }

    /**
     * The shortest interval a speed is measured over, in milliseconds: longer than one
     * frame, and short enough that only the end of the gesture counts.
     */
    const val TAIL_MS = 40L

    /** The decay's time constant: the speed falls by a factor of e every this many ms. */
    private const val SLOWING = 320f

    /** The speed, in rows a second, below which a throw is over. */
    private const val LEAST = 1f

    private const val MILLIS = 1000f
}

/**
 * Watches a drag so the list it belongs to knows how hard it was thrown.
 */
class Throw(
    private val rowHeight: Int,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var lastY = 0f
    private var lastAt = 0L
    private var speed = 0f

    fun begin(y: Float) {
        lastY = y
        lastAt = now()
        speed = 0f
    }

    /** Call while the finger moves; the speed is that of the last [RowFling.TAIL_MS] or more. */
    fun moved(y: Float) {
        val at = now()
        val since = at - lastAt
        if (since < RowFling.TAIL_MS) return
        speed = RowFling.speedOf((lastY - y) / rowHeight, since)
        lastY = y
        lastAt = at
    }

    /** The throw's speed in rows a second, or 0 when it is not worth carrying. */
    fun speed(): Float = speed.takeIf { RowFling.worthCarrying(it) } ?: 0f

    fun forget() {
        speed = 0f
    }
}
