// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import nl.mattix.andamp.ui.widget.HIT_SLOP
import nl.mattix.andamp.ui.widget.Widget
import nl.mattix.andamp.ui.widget.distanceSquaredTo
import nl.mattix.andamp.ui.widget.hitTest
import kotlin.math.abs

// What every floating window shares: it is dragged by a handle, it docks against its
// neighbors, and it stays reachable.

/**
 * The screen every window measures itself against, in virtual pixels: how big it is, and the
 * band a handle has to stay inside to remain grabbable.
 *
 * Every window uses the same measurement, because they share a coordinate space: they
 * publish rectangles for each other to dock against, and offsets are relative to the
 * screen's center.
 */
data class WindowScreen(
    val width: Int,
    val height: Int,
    val safeTop: Int,
    val safeBottom: Int,
) {
    /** The top-left corner of a window of [size] whose center-relative offset is [offset]. */
    fun topLeftOf(
        offset: IntOffset,
        size: IntSize,
    ): IntOffset = IntOffset((width - size.width) / 2 + offset.x, (height - size.height) / 2 + offset.y)

    /** The center-relative offset that puts a window of [size] with its top-left corner at [topLeft]. */
    fun offsetOf(
        topLeft: IntOffset,
        size: IntSize,
    ): IntOffset = IntOffset(topLeft.x - (width - size.width) / 2, topLeft.y - (height - size.height) / 2)

    fun offsetOf(rect: IntRect): IntOffset = offsetOf(rect.topLeft, rect.size)

    fun rectOf(
        offset: IntOffset,
        size: IntSize,
    ): IntRect = IntRect(topLeftOf(offset, size), size)

    /** The band a window's handle stays in; the snap offers its edges. */
    val safeArea: IntRect get() = IntRect(0, safeTop, width, safeBottom)
}

/**
 * The screen this window sits on, measured from the container it was given and published to
 * [state].
 *
 * The container must be the whole surface the windows are laid out on: what the system
 * bars take is expressed as [WindowScreen.safeBottom], not by handing this a smaller box.
 */
@androidx.compose.runtime.Composable
internal fun androidx.compose.foundation.layout.BoxWithConstraintsScope.windowScreen(
    scale: Int,
    state: nl.mattix.andamp.state.WinampState,
): WindowScreen {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val bottomInset = surfaceBottomInset(density)
    val screen =
        WindowScreen(
            width = constraints.maxWidth / scale,
            height = constraints.maxHeight / scale,
            safeTop = grabbableTop(scale, density),
            safeBottom = (constraints.maxHeight - bottomInset) / scale,
        )
    androidx.compose.runtime.SideEffect {
        state.screenW = screen.width
        state.screenH = screen.height
        state.safeTop = screen.safeTop
        state.safeBottom = screen.safeBottom
    }
    return screen
}

/** A window traveling with the dragged one: where it is now, and how tall its handle is. */
class CarriedWindow(
    val rect: IntRect,
    val handle: Int,
)

/**
 * Where a window asked to sit at [offset] ends up: docked against whatever is near it, then
 * clamped to stay grabbable.
 *
 * The clamp runs last, because a title bar under the gesture bar cannot be picked up again.
 * The screen the window docks against ends at the safe bottom for the same reason.
 */
@Suppress("LongParameterList") // one window, one screen, and the neighbours between them
internal fun settle(
    offset: IntOffset,
    size: IntSize,
    screen: WindowScreen,
    titleH: Int,
    neighbours: List<IntRect>,
    /**
     * Whether the drag has traveled far enough to leave the dock it started in. Until it
     * has, the windows and screen edges it was resting against are not offered as snap
     * targets, so the window can be pulled off them. Other neighbors attract from the
     * first pixel.
     */
    escaped: Boolean = true,
    /** Where this window is now; null before it has been laid out. */
    anchorNow: IntRect? = null,
    /** The windows traveling with this one. */
    carried: List<CarriedWindow> = emptyList(),
): IntOffset {
    val target = screen.rectOf(offset, size)
    val rects = carried.map { it.rect }
    // a group docks by its outer edges
    val whole = if (anchorNow == null) target else DockGroup.bounds(target, anchorNow, rects)
    val here = if (anchorNow == null) null else DockGroup.bounds(anchorNow, anchorNow, rects)
    val candidates = if (escaped || here == null) neighbours else neighbours.filterNot { DockGroup.touching(here, it) }
    // until the drag has escaped, the screen's edges are not offered either
    val snapped = WindowSnap.snap(whole, candidates, screen = screen.safeArea.takeIf { escaped })
    val moved = offset + (snapped - whole.topLeft)
    if (anchorNow == null || carried.isEmpty()) return WindowBounds.clamp(moved, size, screen, titleH)

    // A group snaps as one rectangle, so it clamps as one too: on each axis the travel is
    // limited to what keeps every member where it may be on its own, so no member is
    // clamped away from the others when it is laid out.
    val members = carried.map { it.rect to it.handle } + (anchorNow to titleH)
    val asked = screen.topLeftOf(moved, size) - anchorNow.topLeft
    val travelX =
        WindowBounds.travel(
            asked.x,
            members.map { (rect, _) ->
                WindowBounds.leftRange(rect.width, screen.width).shiftedBy(-rect.left)
            },
        )
    val travelY =
        WindowBounds.travel(
            asked.y,
            members.map { (rect, handle) ->
                WindowBounds.topRange(handle, screen.safeTop, screen.safeBottom).shiftedBy(-rect.top)
            },
        )
    return IntOffset(moved.x + travelX - asked.x, moved.y + travelY - asked.y)
}

/**
 * A window drag in flight: where the finger grabbed, where the window is, and the settle
 * function published by the layout pass.
 *
 * The position is tracked here because several pointer events arrive per frame, and state
 * that refreshes on recomposition would be stale for all but the first. It is held outside
 * the widget list, so it survives the list being rebuilt mid-gesture.
 */
internal class WindowDrag {
    var at: Offset? = null

    /**
     * The placement the pointer's coordinates are measured against: the one the window was
     * last laid out at, which is not the one it has been told to move to. Several pointer
     * events land per frame, and each is relative to a window that has not been re-placed
     * yet, so this advances only in [syncToLayout].
     */
    var live: IntOffset = IntOffset.Zero

    /** What the window was last told; becomes [live] once the layout catches up. */
    var applied: IntOffset? = null

    /** Where the drag started, and whether it has left the snap's pull yet. */
    var origin: IntOffset = IntOffset.Zero
    var escaped: Boolean = false

    var settle: ((offset: IntOffset, snap: Boolean) -> IntOffset)? = null

    /** Called once the window has been placed at [applied], so the drag measures from there. */
    fun syncToLayout() {
        applied?.let { live = it }
        applied = null
    }

    fun end() {
        at = null
        applied = null
        escaped = false
    }
}

/**
 * A grip drag as the window receives it: the size the finger is asking for, and the window's
 * size and offset when the grip was grabbed. The new size comes from the raw ask and the new
 * place from the grab; see [WindowSizing.resize].
 */
data class WindowGrab(
    val rawWidth: Int,
    val rawHeight: Int,
    val atWidth: Int,
    val atHeight: Int,
    val atOffset: IntOffset,
)

/**
 * A resize in flight: where the grip was grabbed, and the window as it was at that moment.
 *
 * The window's place is anchored to the grab: pointer events arrive faster than
 * recompositions, so a correction measured against the live height would be applied more
 * than once within a frame.
 */
internal class WindowResize {
    var grabY: Float? = null
    var grabX: Float = 0f
    var heightAtGrab: Int = 0

    /** The grip resizes both ways at once; a window that cannot widen ignores the width. */
    var widthAtGrab: Int = 0
    var offsetAtGrab: IntOffset = IntOffset.Zero
}

/**
 * The chrome gesture: one widget under the whole window that moves it, or resizes it when
 * the grab landed on the grip.
 *
 * Any pixel that is not a control drags the window. The controls are separate widgets and
 * win the hit test.
 *
 * Move or resize is decided once, from the grab point, because the grip's rectangle depends
 * on the size the resize is changing. The resize is live: the window follows the finger,
 * which works because the canvas captures the widget it hit.
 */
@Suppress("LongParameterList") // a window, two gestures and the state behind them
internal fun windowChromeWidget(
    id: String,
    bounds: IntRect,
    grip: IntRect?,
    drag: WindowDrag,
    resize: WindowResize,
    offsetNow: () -> IntOffset,
    heightNow: () -> Int,
    /** Where the window now is, and how far the drag has taken it since the grab. */
    onMove: (place: IntOffset, travelled: IntOffset) -> Unit,
    /** The size the finger is asking for, unquantized, with the grab it is measured from. */
    onResize: (WindowGrab) -> Unit,
    onEnd: () -> Unit = {},
    /**
     * The height of the title bar, where [onTitleDoubleTap] applies: Winamp's shade gesture,
     * two taps on the bar.
     */
    titleH: Int = 0,
    onTitleDoubleTap: (() -> Unit)? = null,
    /**
     * The widgets drawn on this window, so the chrome can claim its title bar without
     * claiming the buttons in it; see [answersInBar].
     */
    siblings: () -> List<Widget> = { emptyList() },
    /**
     * Winamp's right click on the window, as a long press.
     */
    onLongPress: (() -> Unit)? = null,
    /** Called when a drag of the window begins, before its first move. */
    onStart: () -> Unit = {},
): Widget {
    val shade = onTitleDoubleTap?.let { TitleDoubleTap(titleH, it) }
    return Widget(
        id,
        bounds,
        background = true,
        taps = Widget.Taps(onLongPress = onLongPress),
        // the chrome owns the grip and the title bar, except where a button in the bar
        // answers
        owned = { pos ->
            grip?.contains(pos) == true ||
                (pos.y < titleH && siblings().none { it.enabled() && it.answersInBar(pos, titleH) })
        },
        pointer =
            Widget.Pointer(
                onDown = { pos ->
                    if (grip != null && grip.contains(IntOffset(pos.x.toInt(), pos.y.toInt()))) {
                        resize.grabY = pos.y
                        resize.grabX = pos.x
                        resize.heightAtGrab = heightNow()
                        resize.widthAtGrab = bounds.width
                        resize.offsetAtGrab = offsetNow()
                    } else {
                        drag.at = pos
                        drag.live = offsetNow()
                        drag.origin = drag.live
                        drag.applied = null
                        drag.escaped = false
                        onStart()
                    }
                },
                onDrag = { pos ->
                    val grabbedGrip = resize.grabY
                    if (grabbedGrip != null) {
                        // measured against the size at the grab, not the live size
                        onResize(
                            WindowGrab(
                                rawWidth = resize.widthAtGrab + (pos.x - resize.grabX).toInt(),
                                rawHeight = resize.heightAtGrab + (pos.y - grabbedGrip).toInt(),
                                atWidth = resize.widthAtGrab,
                                atHeight = resize.heightAtGrab,
                                atOffset = resize.offsetAtGrab,
                            ),
                        )
                        return@Pointer
                    }
                    val from = drag.at ?: return@Pointer
                    // Positions are window-relative, so the delta is measured from the
                    // grab point: while the window follows the finger it is new travel,
                    // and once the clamp stops the window it is the overshoot, so the
                    // window comes back as soon as the finger does.
                    val target = IntOffset(drag.live.x + (pos.x - from.x).toInt(), drag.live.y + (pos.y - from.y).toInt())
                    // A window resting against something is inside the snap's pull, so
                    // that pull is off until the drag has traveled past SNAP_DISTANCE.
                    if (!drag.escaped && travelled(target, drag.origin) > WindowSnap.SNAP_DISTANCE) drag.escaped = true
                    val next = drag.settle?.invoke(target, drag.escaped) ?: target
                    // the place is absolute: several events land per frame, and a caller
                    // adding a delta to what it last composed would lose all but the first
                    onMove(next, next - drag.origin)
                    drag.applied = next
                },
                onUp = { pos, inside ->
                    val wasResizing = resize.grabY != null
                    resize.grabY = null
                    // A tap needs both the pointer and the window to have stood still:
                    // pointer coordinates are window-relative, so a window keeping up
                    // with the finger shows no pointer travel.
                    val windowTravel = drag.at?.let { travelled(offsetNow(), drag.origin) } ?: 0
                    val still =
                        inside && !wasResizing &&
                            travelledSince(drag.at, pos) <= TAP_SLOP &&
                            windowTravel <= TAP_SLOP
                    drag.end()
                    onEnd()
                    if (still) shade?.tapped(pos.y)
                },
            ),
    )
}

/**
 * Whether this widget, and not the chrome, answers for [pos] in a title bar [titleH] tall.
 *
 * A button that starts inside the bar answers for its hit slop as well as its art. A control
 * below the bar answers only for its own bounds, so its slop does not reach up into the
 * handle.
 */
private fun Widget.answersInBar(
    pos: IntOffset,
    titleH: Int,
): Boolean = bounds.contains(pos) || (bounds.top < titleH && distanceSquaredTo(pos) <= HIT_SLOP * HIT_SLOP)

/**
 * Winamp's shade gesture: two taps on the title bar within [DOUBLE_TAP_MS].
 */
private class TitleDoubleTap(
    private val titleH: Int,
    private val onFire: () -> Unit,
) {
    // null, not 0: uptime is small just after boot, and a first tap would read as a second
    private var lastAt: Long? = null

    /** A tap that ended at [y] in the window without moving. */
    fun tapped(y: Float) {
        if (y < 0 || y >= titleH) return
        // the monotonic clock input events are timed against, not the wall clock
        val now = SystemClock.uptimeMillis()
        val second = lastAt?.let { now - it < DOUBLE_TAP_MS } == true
        lastAt = if (second) null else now
        if (second) onFire()
    }
}

/** How far a gesture wandered from where it started; 0 when it never started one. */
private fun travelledSince(
    from: Offset?,
    to: Offset,
): Int =
    from?.let {
        travelled(IntOffset(to.x.toInt(), to.y.toInt()), IntOffset(it.x.toInt(), it.y.toInt()))
    } ?: 0

/** The longest time between the two taps of a double tap. */
private const val DOUBLE_TAP_MS = 400L

/** Travel beyond this many virtual pixels makes a gesture a drag, not a tap. */
private const val TAP_SLOP = 4

/** How far a drag has taken a window from where it was grabbed. */
private fun travelled(
    target: IntOffset,
    origin: IntOffset,
) = maxOf(abs(target.x - origin.x), abs(target.y - origin.y))

/** The side of the square in the bottom-right corner that the resize grip answers to. */
internal const val RESIZE_GRIP = 20

/**
 * The magnifier as one window offers it to the canvas: open it, move it, press what it is
 * on. [make] builds the [Loupe] from the window's own drawing and widgets, and the one open
 * lens is held in the state.
 *
 * [aim] says where a press that landed somewhere in the window is aimed, which near a side
 * of the screen is further out than the finger got; see [Loupe.aimedAcross].
 */
class LoupeGesture(
    private val state: nl.mattix.andamp.state.WinampState,
    private val aim: (Offset) -> Offset = { it },
    private val make: (Offset, Widget) -> Loupe,
) {
    /**
     * How far a press at [touched] is moved to where it was aimed, or nothing.
     *
     * Only a small control ([Loupe.fiddly]) takes a press that was aimed at it from beside
     * it: a slider or a window's handle near the edge is pressed where the finger is. The
     * aim is a fingertip's, so only a finger's press is asked about here.
     */
    fun shiftFor(
        widgets: List<Widget>,
        touched: Offset,
    ): Offset {
        val aimed = aim(touched)
        val reached = hitTest(widgets, IntOffset(aimed.x.toInt(), aimed.y.toInt())) ?: return Offset.Zero
        return if (!reached.background && Loupe.fiddly(reached)) aimed - touched else Offset.Zero
    }

    /**
     * [at] is where the finger is, in the window's virtual pixels, and [on] the control the
     * press landed on: the lens opens over that one.
     */
    fun open(
        at: Offset,
        on: Widget,
    ) {
        val lens = make(at, on)
        state.loupe = lens
        // it opens already aimed at the pressed control
        lens.aim(state)
    }

    fun move(delta: Offset) {
        state.loupe?.move(delta, state)
    }

    fun release() {
        state.loupe?.release(state)
    }
}
