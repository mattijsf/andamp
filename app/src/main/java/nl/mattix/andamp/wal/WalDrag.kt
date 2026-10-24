// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.round
import kotlin.math.abs

/**
 * The listener moving a modern skin's stack by one of its move areas, once [WalTouch] hands the
 * finger over. The stack follows the finger inside its box and stays wholly in it; once the drag
 * has gone further than the snap distance, the stack snaps to the box's edges and back to the place
 * the engine gives it, as a classic window snaps ([WalPlacement.snap]). When the finger lifts, the
 * place goes to [keep], upright or sideways as the stack was laid out when the drag began: how far
 * the stack was moved from the engine's place, or null when it is back there. A drag the phone is
 * turned during ends where the stack was, and keeps nothing.
 *
 * The finger's positions count from the stack's top-left corner, which moves with the stack, so
 * each one is turned into a point of the surface with the place the stack was last drawn at
 * ([placedAt]).
 */
internal class WalDrag(
    private val keep: (sideways: Boolean, move: IntOffset?) -> Unit,
) : WalTouch.Drag {
    /** How far the stack is moved from the engine's place while the finger drags it, in surface pixels; null between drags. */
    var shift: IntOffset? by mutableStateOf(null)
        private set

    /** The box the stack moves in, its place there unmoved, where it is drawn, and the snap distance in pixels. */
    private class Frame(
        val box: IntRect,
        val home: IntRect,
        val drawn: IntOffset,
        val snapPx: Int,
    )

    private var frame: Frame? = null

    /** The fit the stack is laid out for now, and the one it was laid out for when the drag began. */
    private var fit: WalFit? = null
    private var began: WalFit? = null

    /** Where the finger pressed, as a point of the surface. */
    private var grab = Offset.Zero

    /** How far the stack was moved when the drag began. */
    private var from = IntOffset.Zero

    /** The drag has gone further than the snap distance, so the snap pulls. */
    private var escaped = false

    /**
     * Where the stack was last drawn: [drawn], its top-left corner on the surface; [home], its rect
     * where the engine places it for [fit] in [box]; and how close [snapPx] makes a snap. Null
     * [home] while the stack has no place in the box, which leaves a drag where it is.
     */
    fun placedAt(
        fit: WalFit,
        box: IntRect,
        home: IntRect?,
        drawn: IntOffset?,
        snapPx: Int,
    ) {
        this.fit = fit
        if (shift != null && fit != began) shift = null
        frame = if (home == null || drawn == null) null else Frame(box, home, drawn, snapPx)
    }

    override fun start(
        from: Offset,
        at: Offset,
    ) {
        val f = frame ?: return
        began = fit
        grab = Offset(f.drawn.x + from.x, f.drawn.y + from.y)
        this.from = f.drawn - f.home.topLeft
        escaped = false
        shift = this.from
        move(at)
    }

    override fun move(at: Offset) {
        if (shift == null) return
        val f = frame ?: return
        val finger = Offset(f.drawn.x + at.x, f.drawn.y + at.y)
        val start = f.home.topLeft + from
        val asked = start + (finger - grab).round()
        // a stack resting against an edge is inside the snap's pull, which waits until the drag leaves it
        if (!escaped && maxOf(abs(asked.x - start.x), abs(asked.y - start.y)) > f.snapPx) escaped = true
        val snapped = if (escaped) WalPlacement.snap(asked, f.home, f.box, f.snapPx) else asked
        shift = WalPlacement.clamp(snapped, f.home, f.box) - f.home.topLeft
    }

    override fun end() {
        val moved = shift ?: return
        shift = null
        // a stack that lost its place in the box meanwhile keeps the place it had
        if (frame == null) return
        val sideways = began?.sideways ?: return
        keep(sideways, moved.takeUnless { it == IntOffset.Zero })
    }
}
