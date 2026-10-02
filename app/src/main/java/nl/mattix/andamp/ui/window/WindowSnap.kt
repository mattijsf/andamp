// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import kotlin.math.abs

/**
 * Winamp's window docking: a window dragged near another one's edge lands flush against it.
 *
 * Each axis is corrected independently: a horizontal correction needs the two windows to
 * overlap vertically, a vertical one needs them to overlap horizontally, and each axis picks
 * its own nearest candidate, which may come from a different window. The screen's edges
 * attract the same way and are offered last, so a window wins an exact tie.
 *
 * It keeps no state: the caller passes the position the finger asks for on every event, so
 * dragging past the threshold releases the snap.
 */
object WindowSnap {
    /** The snap distance, in virtual px. */
    const val SNAP_DISTANCE = 10

    /**
     * Where a window asked to sit at [target] should land, corrected by less
     * than [distance] px per axis. One coordinate space throughout: virtual
     * px, top-left origin.
     */
    fun snap(
        target: IntRect,
        others: List<IntRect>,
        screen: IntRect,
        distance: Int = SNAP_DISTANCE,
    ): IntOffset {
        val x = Axis(distance)
        val y = Axis(distance)
        others.forEach { other ->
            // docking is offered before alignment, so an exact tie docks
            if (overlaps(target.top, target.bottom, other.top, other.bottom, distance)) {
                x.offer(other.right - target.left) // my left against its right
                x.offer(other.left - target.right) // my right against its left
                x.offer(other.left - target.left) // lefts aligned
                x.offer(other.right - target.right) // rights aligned
            }
            if (overlaps(target.left, target.right, other.left, other.right, distance)) {
                y.offer(other.bottom - target.top)
                y.offer(other.top - target.bottom)
                y.offer(other.top - target.top)
                y.offer(other.bottom - target.bottom)
            }
        }
        // the screen's edges are offered after the windows, so a window wins a tie
        x.offer(screen.left - target.left)
        x.offer(screen.right - target.right)
        y.offer(screen.top - target.top)
        y.offer(screen.bottom - target.bottom)
        return IntOffset(target.left + x.correction, target.top + y.correction)
    }

    /**
     * The snap radius doubles as slack in the perpendicular test, so two
     * windows meeting corner to corner - no true overlap - still dock.
     */
    private fun overlaps(
        aStart: Int,
        aEnd: Int,
        bStart: Int,
        bEnd: Int,
        slack: Int,
    ) = aStart < bEnd + slack && aEnd > bStart - slack

    /** One axis's best correction: nearest wins, and the first offer takes a tie. */
    private class Axis(
        private val distance: Int,
    ) {
        private var best: Int? = null
        val correction: Int get() = best ?: 0

        fun offer(delta: Int) {
            val held = best
            // strictly less: a delta of exactly the snap distance does not snap
            if (abs(delta) < distance && (held == null || abs(delta) < abs(held))) best = delta
        }
    }
}
