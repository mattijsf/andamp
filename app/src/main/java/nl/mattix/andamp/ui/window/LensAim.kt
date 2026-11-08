// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import nl.mattix.andamp.ui.window.Loupe.Companion.ESCAPE
import nl.mattix.andamp.ui.window.Loupe.Companion.GEARING
import nl.mattix.andamp.ui.window.Loupe.Companion.MIN_GEARING

/**
 * The crosshair of a lens: where it is, moved by the finger geared down by [GEARING] and kept in
 * [roam], the cluster the lens opened on. Positions are in the pixels of what the lens shows, a
 * classic window's virtual pixels or a skin's.
 *
 * A finger close to a side of the screen cannot travel far toward it, so in that direction the
 * crosshair is geared down less, by as much as it takes to bring the outermost of [centers] within
 * the room the finger has ([reach]).
 *
 * In a row or a column of controls the crosshair can stay put across it ([fixed]): the lens then
 * only slides along the run, and a finger that drifts sideways does not shake it.
 */
class LensAim(
    start: Offset,
    /** Where the crosshair may go. */
    val roam: IntRect,
    /** The centers of the cluster's controls: what the crosshair has to be able to reach. */
    centers: List<Offset>,
    /** How far the finger can travel each way from where it is. */
    reach: Loupe.Reach = Loupe.Reach.UNLIMITED,
    /** The axis the crosshair stays at [start] on. */
    private val fixed: Fixed = Fixed.NEITHER,
) {
    /** An axis the crosshair does not move on. */
    enum class Fixed { NEITHER, X, Y }

    /** Where the crosshair is. */
    var focus by mutableStateOf(start)
        private set

    private val origin = start

    /** How far the finger has moved since the lens opened. */
    private var travelled = Offset.Zero

    private val left = Side.toward(centers.maxOfOrNull { start.x - it.x } ?: 0f, reach.left)
    private val up = Side.toward(centers.maxOfOrNull { start.y - it.y } ?: 0f, reach.up)
    private val right = Side.toward(centers.maxOfOrNull { it.x - start.x } ?: 0f, reach.right)
    private val down = Side.toward(centers.maxOfOrNull { it.y - start.y } ?: 0f, reach.down)

    /**
     * Moves the crosshair by a finger movement of [delta], geared down. Returns false when the
     * finger pushed it more than [ESCAPE] past [roam]: the lens closes and presses nothing.
     */
    fun move(delta: Offset): Boolean {
        travelled += delta
        // Where the finger has asked the crosshair to be, unclamped. It is worked out from all the
        // finger has moved, so pushing past the edge and coming back lands where it started, and
        // how far past the cluster it is can be measured.
        val wanted =
            Offset(
                origin.x + if (travelled.x >= 0) right.crosshair(travelled.x) else -left.crosshair(-travelled.x),
                origin.y + if (travelled.y >= 0) down.crosshair(travelled.y) else -up.crosshair(-travelled.y),
            )
        val held =
            Offset(
                wanted.x.coerceIn(roam.left.toFloat(), (roam.right - 1).toFloat()),
                wanted.y.coerceIn(roam.top.toFloat(), (roam.bottom - 1).toFloat()),
            )
        if ((wanted - held).getDistance() > ESCAPE) return false
        focus =
            when (fixed) {
                Fixed.NEITHER -> held
                Fixed.X -> Offset(origin.x, held.y)
                Fixed.Y -> Offset(held.x, origin.y)
            }
        return true
    }

    /**
     * One direction the crosshair can go: how far the outermost control is that way ([needed]),
     * and the gearing that gets the crosshair there.
     */
    private class Side(
        private val needed: Float,
        private val gearing: Float,
    ) {
        /**
         * How far the crosshair goes for a finger that went [finger] this way. Past the outermost
         * control it is back to [GEARING], so the lens is no easier to push away.
         */
        fun crosshair(finger: Float): Float {
            val within = needed * gearing
            return if (finger <= within) finger / gearing else needed + (finger - within) / GEARING
        }

        companion object {
            /** The side whose outermost control is [needed] away, for a finger that can travel [room] that way. */
            fun toward(
                needed: Float,
                room: Float,
            ): Side {
                val gearing = if (needed <= 0f) GEARING else (room / needed).coerceIn(MIN_GEARING, GEARING)
                return Side(needed.coerceAtLeast(0f), gearing)
            }
        }
    }
}
