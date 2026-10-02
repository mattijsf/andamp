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
    start: Offset,
) {
    /** Where the crosshair is, in window-local virtual pixels. */
    var focus by mutableStateOf(start)
        private set

    /**
     * Where the finger has asked it to be, unclamped, so that pushing past the edge and
     * coming back lands where it started, and so [move] can measure how far past it is.
     */
    private var wanted = start

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
        wanted = Offset(wanted.x + delta.x / GEARING, wanted.y + delta.y / GEARING)
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
