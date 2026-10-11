// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.remote

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect

/** A slider on a lent surface, in the surface's pixels. */
internal class SurfaceSlider(
    val id: String,
    /** The part of the surface it covers. */
    val covers: IntRect,
    /** Where its thumb is now; see [nl.mattix.andamp.ui.widget.Widget.handle]. */
    val handle: () -> Offset,
)

/** Where the sliders of the window on a lent surface are. The screen keeps [at] current. */
internal class Sliders {
    /** The slider a touch at this place on the surface lands on, or null. */
    var at: (x: Float, y: Float) -> SurfaceSlider? = NONE

    companion object {
        /** A surface with no slider on it. */
        val NONE: (Float, Float) -> SurfaceSlider? = { _, _ -> null }
    }
}

/**
 * The slider a drag on a lent surface moves.
 *
 * The owner of the surface reports a tap by its place and a drag by its distance alone, so a
 * drag cannot say what it is on. A slider is therefore held by a tap: the tap that lands on
 * one moves it there, as on a phone, and every drag after that moves it on by the drag's
 * distance, wherever on the surface the finger is. A tap that lands on anything else lets
 * the slider go.
 *
 * A drag is passed on as the touches of a finger of this class's own, through [touch]. It
 * comes down on the slider's thumb, where that is by then, moves as the owner's finger does,
 * and is lifted by [letGo]. So the slider is moved by the code that moves it under a real
 * finger. The finger stays on the part of the surface the slider covers.
 */
internal class HeldSlider(
    private val touch: (Touch, Float, Float) -> Unit,
) {
    /** What the finger does. */
    enum class Touch { DOWN, MOVE, UP }

    private var held: SurfaceSlider? = null
    private var x = 0f
    private var y = 0f

    /** Whether the finger is down. */
    var down = false
        private set

    /** A tap landed on [slider], or on something else when it is null. */
    fun tapped(slider: SurfaceSlider?) {
        letGo()
        held = slider
    }

    /**
     * The owner's finger moved by ([dx], [dy]). Moves the held slider and returns true, or
     * returns false when there is none to move.
     *
     * When the finger comes down, [sliderAt] is asked what is where the slider was. One that
     * is no longer there, because the window moved or another is up, is let go.
     */
    fun dragBy(
        dx: Float,
        dy: Float,
        sliderAt: (Float, Float) -> SurfaceSlider?,
    ): Boolean {
        if (!down && !comeDown(sliderAt)) return false
        val on = held ?: return false
        place(x + dx, y + dy, on.covers)
        touch(Touch.MOVE, x, y)
        return true
    }

    /** Lifts the finger, if it is down. The slider stays held. */
    fun letGo() {
        if (!down) return
        down = false
        touch(Touch.UP, x, y)
    }

    /** Puts the finger down on the thumb of the held slider, as it is now. */
    private fun comeDown(sliderAt: (Float, Float) -> SurfaceSlider?): Boolean {
        val was = held ?: return false
        val middle = was.covers.center
        val now = sliderAt(middle.x.toFloat(), middle.y.toFloat())
        if (now == null || now.id != was.id || now.covers != was.covers) {
            held = null
            return false
        }
        held = now
        val thumb = now.handle()
        place(thumb.x, thumb.y, now.covers)
        down = true
        touch(Touch.DOWN, x, y)
        return true
    }

    private fun place(
        toX: Float,
        toY: Float,
        within: IntRect,
    ) {
        x = toX.coerceIn(within.left.toFloat(), maxOf(within.left, within.right - 1).toFloat())
        y = toY.coerceIn(within.top.toFloat(), maxOf(within.top, within.bottom - 1).toFloat())
    }
}
