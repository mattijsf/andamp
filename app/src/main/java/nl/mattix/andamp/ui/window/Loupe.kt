// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntRect
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.ui.widget.Widget
import nl.mattix.andamp.ui.widget.hitTest

/**
 * A magnifier over the controls that are too small for a finger, such as the 9x9 title-bar
 * buttons and the clutter bar's letters; [fiddly] says which.
 *
 * Holding one opens a lens above the finger: that part of the window drawn again at [ZOOM]
 * with the same draw block, the control under the crosshair lit, and the finger moving the
 * crosshair geared down by [GEARING]. Release presses what the crosshair is on.
 *
 * A finger close to a side of the screen cannot travel far toward it, so in that direction
 * the crosshair is geared down less, by as much as it takes to bring the outermost control of
 * the cluster under it within the room the finger has ([reach]).
 */
class Loupe(
    /** The window it belongs to, so the layer can find where that window sits. */
    val window: String,
    /**
     * Where the crosshair may go, in window-local virtual pixels: the cluster this was
     * opened on.
     */
    val roam: IntRect,
    /** The window's own drawing, in window-local virtual pixels. */
    val paint: DrawScope.() -> Unit,
    /** What can be pressed, read on each use: a window may rebuild its widgets. */
    val widgets: () -> List<Widget>,
    private val start: Offset,
    /** How far the finger can travel each way from where it is. */
    reach: Reach = Reach.UNLIMITED,
) {
    /** Where the crosshair is, in window-local virtual pixels. */
    var focus by mutableStateOf(start)
        private set

    /** How far the finger has moved since the lens opened, in virtual pixels. */
    private var travelled = Offset.Zero

    private val left: Side
    private val up: Side
    private val right: Side
    private val down: Side

    init {
        // the centers of the cluster's controls: what the crosshair has to be able to reach
        val centers =
            widgets()
                .filter { !it.background && fiddly(it) && roam.contains(it.bounds.center) }
                .map {
                    Offset(
                        it.bounds.center.x
                            .toFloat(),
                        it.bounds.center.y
                            .toFloat(),
                    )
                }
        left = Side.toward(centers.maxOfOrNull { start.x - it.x } ?: 0f, reach.left)
        up = Side.toward(centers.maxOfOrNull { start.y - it.y } ?: 0f, reach.up)
        right = Side.toward(centers.maxOfOrNull { it.x - start.x } ?: 0f, reach.right)
        down = Side.toward(centers.maxOfOrNull { it.y - start.y } ?: 0f, reach.down)
    }

    /** Once canceled, a release presses nothing. */
    private var cancelled = false

    /**
     * The widget it is over, or null between two of them. An inert control is not a target:
     * the crosshair crosses it without lighting it, and a release over it presses nothing.
     */
    val target: Widget? get() =
        hitTest(
            widgets(),
            androidx.compose.ui.unit
                .IntOffset(focus.x.toInt(), focus.y.toInt()),
        )?.takeUnless { it.inert() }

    /**
     * Moves the crosshair by a finger movement of [delta], geared down.
     *
     * Pushed more than [ESCAPE] past the cluster, the lens closes and presses nothing.
     */
    fun move(
        delta: Offset,
        state: WinampState,
    ) {
        travelled += delta
        // Where the finger has asked the crosshair to be, unclamped. It is worked out from
        // all the finger has moved, so pushing past the edge and coming back lands where it
        // started, and how far past the cluster it is can be measured.
        val wanted =
            Offset(
                start.x + if (travelled.x >= 0) right.crosshair(travelled.x) else -left.crosshair(-travelled.x),
                start.y + if (travelled.y >= 0) down.crosshair(travelled.y) else -up.crosshair(-travelled.y),
            )
        val held =
            Offset(
                wanted.x.coerceIn(roam.left.toFloat(), (roam.right - 1).toFloat()),
                wanted.y.coerceIn(roam.top.toFloat(), (roam.bottom - 1).toFloat()),
            )
        if ((wanted - held).getDistance() > ESCAPE) {
            cancelled = true
            state.pressedWidget = null
            state.loupe = null
            return
        }
        focus = held
        aim(state)
    }

    /**
     * Draws whatever the crosshair is on as pressed. Called when the lens opens as well as
     * on every move, because a release without a move presses that control.
     */
    fun aim(state: WinampState) {
        state.pressedWidget = target?.id?.takeIf { it != chromeOf(window) }
    }

    /** Presses what the crosshair is on, if anything, and closes. */
    fun release(state: WinampState) {
        val hit = target?.takeIf { !cancelled && it.id != chromeOf(window) }
        state.pressedWidget = null
        state.loupe = null
        hit ?: return
        hit.down(state, focus)
        hit.up(state, focus)
    }

    /**
     * How a fingertip meets a side of the screen, in virtual pixels. The sizes are a
     * finger's, so how many virtual pixels they are depends on how large those are drawn.
     */
    data class Edge(
        /** How close to where the glass ends the middle of a fingertip gets. */
        val reach: Float,
        /** How far from a side a press is still aimed further out than it landed; see [aimedAcross]. */
        val band: Float,
    ) {
        companion object {
            private const val REACH_DP = 16f
            private const val BAND_DP = 48f

            /** For a surface on which a dp spans [virtualPerDp] virtual pixels. */
            fun of(virtualPerDp: Float) = Edge(REACH_DP * virtualPerDp, BAND_DP * virtualPerDp)
        }
    }

    /**
     * How far a finger can travel each way from where it is and still be where a fingertip
     * gets, in virtual pixels.
     */
    data class Reach(
        val left: Float,
        val up: Float,
        val right: Float,
        val down: Float,
    ) {
        companion object {
            /** A finger with all the room it could ask for. */
            val UNLIMITED =
                Reach(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)

            /** For a finger at [finger] on a screen of [screenW] by [screenH], whose sides it meets as [edge] says. */
            fun on(
                finger: Offset,
                screenW: Int,
                screenH: Int,
                edge: Edge,
            ) = Reach(
                left = finger.x - edge.reach,
                up = finger.y - edge.reach,
                right = screenW - finger.x - edge.reach,
                down = screenH - finger.y - edge.reach,
            )
        }
    }

    /**
     * One direction the crosshair can go: how far the outermost control is that way
     * ([needed]), and the gearing that gets the crosshair there.
     */
    private class Side(
        private val needed: Float,
        private val gearing: Float,
    ) {
        /**
         * How far the crosshair goes for a finger that went [finger] this way. Past the
         * outermost control it is back to [GEARING], so the lens is no easier to push away.
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

    companion object {
        /**
         * A control both of whose sides are this or less, in virtual pixels, gets the lens.
         * The title-bar buttons are 9 px; the transport buttons and the sliders are larger.
         */
        const val SMALL = 12

        /**
         * Whether a hold on [widget] opens the lens: it is [SMALL], or it asks for the lens
         * through [Widget.magnify].
         */
        fun fiddly(widget: Widget) = widget.magnify || (widget.bounds.width <= SMALL && widget.bounds.height <= SMALL)

        /**
         * The run of small controls [target] belongs to: itself, its close neighbors, and
         * theirs, padded by [PAD]. The run can be a row or a column.
         *
         * An inert control is still part of the run: it holds its place, and the crosshair
         * has to cross it to reach what is on the other side.
         */
        fun clusterAround(
            target: Widget,
            widgets: List<Widget>,
        ): IntRect {
            val small = widgets.filter { it.enabled() && fiddly(it) && it !== target }
            val taken = mutableSetOf(target)
            var bounds = target.bounds
            var grew = true
            while (grew) {
                grew = false
                small.forEach { widget ->
                    if (widget in taken || !near(bounds, widget.bounds)) return@forEach
                    taken += widget
                    bounds = bounds.union(widget.bounds)
                    grew = true
                }
            }
            return IntRect(bounds.left - PAD, bounds.top - PAD, bounds.right + PAD, bounds.bottom + PAD)
        }

        /** Whether two rectangles are within [GAP] of each other on both axes. */
        private fun near(
            bounds: IntRect,
            other: IntRect,
        ): Boolean {
            val dx = maxOf(bounds.left - other.right, other.left - bounds.right, 0)
            val dy = maxOf(bounds.top - other.bottom, other.top - bounds.bottom, 0)
            return dx <= GAP && dy <= GAP
        }

        private fun IntRect.union(other: IntRect) =
            IntRect(
                minOf(left, other.left),
                minOf(top, other.top),
                maxOf(right, other.right),
                maxOf(bottom, other.bottom),
            )

        /**
         * How far apart two small controls may be, in virtual pixels, and still be in one
         * cluster.
         */
        private const val GAP = 13

        /** Room around the cluster, so its edges are reachable. */
        private const val PAD = 3

        /**
         * How far past the cluster the crosshair has to be pushed to cancel the lens, in
         * virtual pixels; the finger travels [GEARING] times as far.
         */
        const val ESCAPE = 40f

        /** How much bigger the art is drawn. */
        const val ZOOM = 3f

        /**
         * How much the finger's movement is divided by. Less than [ZOOM], so the hand does
         * not have to travel far between neighboring controls.
         */
        const val GEARING = 1.5f

        /**
         * The least the finger's movement is divided by, toward an edge of the screen it has
         * little room before. Below one the crosshair outruns the finger; much lower and it
         * cannot be held on one control.
         */
        const val MIN_GEARING = 0.4f

        /**
         * Where a press that landed at [x] on a screen [screenW] wide is aimed.
         *
         * The middle of a fingertip cannot get into the last [Edge.reach] before the glass
         * ends, so a control there cannot be pressed where it is drawn. Within [Edge.band]
         * of a side, the stretch a finger can reach stands for all of it: a press as far
         * out as a finger gets is aimed at the edge itself, and one at the band's inner end
         * is aimed where it landed.
         */
        fun aimedAcross(
            x: Float,
            screenW: Int,
            edge: Edge,
        ): Float {
            val fromRight = screenW - x
            return when {
                edge.band <= edge.reach -> x
                fromRight < edge.band && fromRight <= x -> screenW - edge.stretched(fromRight)
                x < edge.band -> edge.stretched(x)
                else -> x
            }
        }

        /** A distance from the side inside the band, with what a finger can reach spread over all of it. */
        private fun Edge.stretched(fromSide: Float) = ((fromSide - reach) * band / (band - reach)).coerceAtLeast(0f)

        /**
         * How long a hold takes to open the lens. A slow tap opens it too, which is
         * harmless: it opens aimed at the pressed control, so the release presses that
         * control either way.
         */
        const val HOLD_MS = 90L

        /**
         * The smallest and largest side of the lens, in virtual pixels. Its size otherwise
         * comes from the cluster it shows.
         */
        const val LENS_MIN = 46f
        const val LENS_MAX = 150f

        /** How far above the finger the lens sits, in virtual pixels. */
        const val LIFT = 34

        /** The id of a window's chrome widget, which the lens never presses. */
        fun chromeOf(window: String) = "$window.chrome"
    }
}
