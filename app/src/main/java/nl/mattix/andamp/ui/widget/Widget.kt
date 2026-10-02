// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.widget

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.ui.window.SliderMath

/**
 * An interactive region on a window, in virtual (275-wide) window-relative
 * pixels. The window's pointer dispatcher captures the widget on down and
 * routes the whole gesture to it; positions may leave [bounds] mid-drag.
 */
class Widget(
    val id: String,
    val bounds: IntRect,
    /** Hit-testing predicate — disabled widgets are transparent to touches (used by popup menus). */
    val enabled: () -> Boolean = { true },
    /**
     * Takes the press and does nothing with it. A 9x9 button whose neighbors are one pixel away is
     * well inside [HIT_SLOP] of them, so a switched-off control that stopped hit-testing would hand
     * its pixels to the control beside it. An inert widget shows no pressed art, and the magnifier
     * passes over it (see [nl.mattix.andamp.ui.window.Loupe]).
     */
    val inert: () -> Boolean = { false },
    private val pointer: Pointer = Pointer(),
    private val taps: Taps = Taps(),
    /**
     * Widgets sharing a group hand the gesture over as the finger sweeps
     * across them: Winamp's EQ lets one drag draw a curve across every band.
     */
    val dragGroup: String? = null,
    /**
     * A backdrop, not a control: something large that lies under the real widgets (a window's drag
     * handle covers its own title bar). A background widget yields to any control within
     * [HIT_SLOP], so a 9x9 close button keeps its touch slop.
     */
    val background: Boolean = false,
    /**
     * The part of a backdrop that no neighbor may reach into with hit slop. A window's resize grip
     * lives inside the same rect as its drag handle; without this the buttons along the bottom bar
     * would take the grip's columns.
     */
    val owned: (IntOffset) -> Boolean = { false },
    /**
     * Asks for the magnifier whatever the size. The playlist's bottom-bar menus are 22x18 tiles
     * that expand into a stack of identical tiles, each a different verb (see
     * [nl.mattix.andamp.ui.window.Loupe.fiddly]).
     */
    val magnify: Boolean = false,
) {
    /** The continuous half of a gesture: press, move, release. */
    data class Pointer(
        val onDown: (Offset) -> Unit = {},
        val onDrag: (Offset) -> Unit = {},
        val onUp: (Offset, Boolean) -> Unit = { _, _ -> },
    )

    /**
     * The discrete half: a tap, and touch's stand-in for a right click
     * (Winamp opened context menus that way).
     */
    data class Taps(
        val onTap: () -> Unit = {},
        val onLongPress: (() -> Unit)? = null,
    )

    val hasLongPress: Boolean get() = taps.onLongPress != null

    /** Fires while the finger is still down, like a desktop context menu. */
    fun longPress(state: WinampState) {
        state.pressedWidget = null // the press became a menu, so drop the pressed art
        taps.onLongPress?.invoke()
    }

    fun down(
        state: WinampState,
        pos: Offset,
    ) {
        if (inert()) return
        state.pressedWidget = id
        pointer.onDown(pos)
    }

    fun drag(pos: Offset) = pointer.onDrag(pos)

    fun up(
        state: WinampState,
        pos: Offset,
        fireTap: Boolean = true,
    ) {
        // release tolerance matches the acquisition slop: a tap that needed
        // slop to hit the widget must not need pixel-perfect release
        if (inert()) return
        val inside =
            distanceSquaredTo(
                androidx.compose.ui.unit
                    .IntOffset(pos.x.toInt(), pos.y.toInt()),
            ) <= HIT_SLOP * HIT_SLOP
        state.pressedWidget = null
        // a gesture that became a long press is a menu: the pointer half must not act on the
        // release either, or a row would open its menu and play itself
        pointer.onUp(pos, inside && fireTap)
        if (inside && fireTap) taps.onTap()
    }
}

/** Simple push button: pressed art while held, action on release-inside. */
@Suppress("LongParameterList") // a rectangle is four of them
fun button(
    id: String,
    x: Int,
    y: Int,
    w: Int,
    h: Int,
    onLongPress: (() -> Unit)? = null,
    /** Read live, so a setting that switches the button off needs no rebuild. */
    inert: () -> Boolean = { false },
    /** Asks for the magnifier whatever its size; see [Widget.magnify]. */
    magnify: Boolean = false,
    onTap: () -> Unit,
) = Widget(id, IntRect(x, y, x + w, y + h), inert = inert, taps = Widget.Taps(onTap, onLongPress), magnify = magnify)

/** A horizontal slider mapping pointer x to 0..1 over [travel] px starting at [x0] (thumb-centered). */
fun hSlider(
    id: String,
    x: Int,
    y: Int,
    w: Int,
    h: Int,
    thumbW: Int,
    travelOverride: Int? = null, // e.g. volume: 68px art but 65px effective track
    value: (Offset) -> Float = { it.x },
    onChange: (Float) -> Unit,
    onCommit: (Float) -> Unit = onChange,
): Widget {
    val travel = (travelOverride ?: (w - thumbW)).coerceAtLeast(1)

    fun frac(pos: Offset) = SliderMath.horizontalFraction(value(pos), x, thumbW, travel)
    return Widget(
        id,
        IntRect(x, y, x + w, y + h),
        pointer =
            Widget.Pointer(
                onDown = { onChange(frac(it)) },
                onDrag = { onChange(frac(it)) },
                onUp = { pos, _ -> onCommit(frac(pos)) },
            ),
    )
}

/** A vertical slider mapping pointer y to 1..0 (top = 1) over [travel] px. */
fun vSlider(
    id: String,
    x: Int,
    y: Int,
    w: Int,
    h: Int,
    thumbH: Int,
    dragGroup: String? = null,
    onChange: (Float) -> Unit,
    onCommit: (Float) -> Unit = onChange,
): Widget {
    val travel = (h - thumbH).coerceAtLeast(1)

    fun frac(pos: Offset) = (1f - (pos.y - y - thumbH / 2f) / travel).coerceIn(0f, 1f)
    return Widget(
        id,
        IntRect(x, y, x + w, y + h),
        dragGroup = dragGroup,
        pointer =
            Widget.Pointer(
                onDown = { onChange(frac(it)) },
                onDrag = { onChange(frac(it)) },
                onUp = { pos, _ -> onCommit(frac(pos)) },
            ),
    )
}
