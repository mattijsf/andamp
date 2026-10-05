// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.ui.ScaledWindowCanvas
import nl.mattix.andamp.ui.SkinCut
import nl.mattix.andamp.ui.widget.Widget

/**
 * A floating skinned window: its placement, dragging, docking, resizing and raising. The
 * caller draws the window, chrome included, in [draw].
 *
 * With [dragsGroup], dragging it takes the windows docked to it along, as Winamp's player
 * does.
 *
 * With [pinnedAt] the layout holds the window: it is drawn there, a drag does not move it
 * and [onMove] is never called, so the place it floats at is kept as it was. It has no
 * resize grip either, unless it asks to keep it with [pinnedGrip].
 */
@Composable
@Suppress("LongParameterList") // a window: its identity, its size, its place, its contents
fun FloatingSkinWindow(
    id: String,
    state: WinampState,
    scale: Int,
    width: Int,
    height: Int,
    /** Where it sits; null while it has never been moved. */
    offset: IntOffset?,
    /** Where the layout puts it until then. */
    defaultOffset: IntOffset,
    onMove: (IntOffset) -> Unit,
    /** The height of the title bar, the part that has to stay reachable on screen. */
    titleH: Int,
    widgets: List<Widget>,
    /** Whether this window takes its docked neighbors with it (the player does). */
    dragsGroup: Boolean = false,
    /**
     * Receives what the resize grip is asking for, and the window as it was grabbed. A
     * window without one has no grip, like the player and the equalizer.
     */
    onResizeRaw: ((WindowGrab) -> Unit)? = null,
    /**
     * Where the grip is, when it is not the bottom-right corner, as in the playlist's shade
     * bar.
     */
    gripAt: IntRect? = null,
    /** Two taps on the title bar: Winamp's shade gesture. */
    onTitleDoubleTap: (() -> Unit)? = null,
    /** Winamp's right click on the window itself; touch's long press. */
    onLongPress: (() -> Unit)? = null,
    /**
     * The shape the skin cuts this window into (REGION.TXT), or null for a rectangle. The
     * canvas applies it to both the art and the touches.
     */
    cut: SkinCut? = null,
    /** Where the layout holds this window's top-left corner, in virtual pixels, or null while it floats. */
    pinnedAt: IntOffset? = null,
    /** Whether a pinned window keeps its grip; what a drag on it does is then [onResizeRaw]'s to decide. */
    pinnedGrip: Boolean = false,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {},
    draw: DrawScope.() -> Unit,
) {
    val drag = remember { WindowDrag() }
    val resize = remember { WindowResize() }
    val group = remember { GroupDrag() }
    val onMoveNow by rememberUpdatedState(onMove)
    val defaultNow by rememberUpdatedState(defaultOffset)
    val offsetNow by rememberUpdatedState(offset)
    val onResizeNow by rememberUpdatedState(onResizeRaw)
    val onDoubleTapNow by rememberUpdatedState(onTitleDoubleTap)
    val onLongPressNow by rememberUpdatedState(onLongPress)
    val widgetsNow by rememberUpdatedState(widgets)
    val density = LocalDensity.current
    // a fingertip in this window's virtual pixels, however large those are drawn
    val loupe = rememberLoupe(id, state, Loupe.Edge.of(density.density / scale), draw) { widgetsNow }
    val pinned = pinnedAt != null
    val resizable = onResizeRaw != null && (!pinned || pinnedGrip)
    val shadeable = onTitleDoubleTap != null

    fun place() = offsetNow ?: defaultNow

    val chrome =
        remember(id, width, height, titleH, dragsGroup, resizable, shadeable, gripAt, onLongPress != null, pinned) {
            windowChromeWidget(
                id = "$id.chrome",
                bounds = IntRect(0, 0, width, height),
                grip = if (resizable) gripOf(gripAt, width, height) else null,
                drag = drag,
                resize = resize,
                offsetNow = { place() },
                heightNow = { height },
                onMove = { place, _ ->
                    // a pinned window takes the drag and stays where the layout holds it
                    if (!pinned) {
                        onMoveNow(place)
                        // measured from where the drag began, so the carried windows do not
                        // drift from it event by event
                        if (dragsGroup) {
                            group.carry(state, id, IntOffset(place.x - drag.origin.x, place.y - drag.origin.y))
                        }
                    }
                },
                onResize = { grab -> onResizeNow?.invoke(grab) },
                titleH = titleH,
                onTitleDoubleTap = if (shadeable) ({ onDoubleTapNow?.invoke() }) else null,
                siblings = { widgetsNow },
                onLongPress = if (onLongPress != null) ({ onLongPressNow?.invoke() }) else null,
                onEnd = { group.release() },
            )
        }
    val allWidgets = remember(chrome, widgets) { listOf(chrome) + widgets }

    BoxWithConstraints(modifier) {
        val screen = windowScreen(scale, state)
        val screenW = screen.width
        val screenH = screen.height
        val safeBottom = screen.safeBottom
        val safeTop = screen.safeTop
        val settleTo: (IntOffset, Boolean) -> IntOffset = { asked, escaped ->
            // the group is worked out before the first move, because the window must not
            // dock to what it is about to carry
            val carried = if (dragsGroup) group.ensure(state, id) else emptySet()
            val luggage = carried.mapNotNull { member -> state.windowRects[member]?.let { member to it } }
            settle(
                asked,
                width,
                height,
                screenW,
                screenH,
                safeTop,
                safeBottom,
                titleH,
                neighbours = state.neighboursOf(id, carried),
                escaped = escaped,
                // a group docks by its outer edges
                carried = luggage.map { it.second },
                carriedHandles = luggage.map { state.windowHandles[it.first] ?: titleH },
                anchorNow = state.windowRects[id],
            )
        }
        SideEffect {
            state.windowHandles[id] = titleH
            drag.settle = settleTo
            // the window has been composed where it was told to be, so the drag measures
            // from there
            drag.syncToLayout()
        }
        // a pinned window is where the layout says, with nothing to keep in reach
        val bounded =
            pinnedAt?.let { offsetOfTopLeft(it, width, height, screenW, screenH) }
                ?: WindowBounds.clamp(
                    place(),
                    width,
                    height,
                    screenW,
                    screenH,
                    titleH,
                    safeTop = safeTop,
                    safeBottom = safeBottom,
                )
        val rect = rectOf(bounded, width, height, screenW, screenH)
        SideEffect { state.windowRects[id] = rect }
        DisposableEffect(id) {
            onDispose {
                state.windowRects.remove(id)
                state.windowHandles.remove(id)
            }
        }
        Box(
            Modifier
                .offset { devicePlacementOf(bounded, width, height, screenW, screenH, scale) },
        ) {
            ScaledWindowCanvas(
                width,
                height,
                scale,
                state,
                allWidgets,
                cut = cut,
                onPress = { state.raiseWindow(id) },
                // every window offers the magnifier while tap assist is on
                loupe = loupe.takeIf { state.tapAssist },
            ) { draw() }
            // A grip near the right edge of the screen sits in the strip the system watches
            // for the back gesture, so the grip's rectangle is excluded from system
            // gestures. Android allows 200dp of exclusion per edge.
            if (resizable) GripExclusion(gripOf(gripAt, width, height), scale)
            overlay()
        }
    }
}

/**
 * The windows traveling with the one being dragged, worked out once when the drag begins, so
 * a window the group passes on the way is not picked up.
 */
internal class GroupDrag {
    private var members: Set<String>? = null
    private var startedAt: Map<String, IntOffset> = emptyMap()

    /** The windows docked to [anchor], worked out once per gesture. */
    fun ensure(
        state: WinampState,
        anchor: String,
    ): Set<String> =
        members ?: DockGroup
            .of(anchor, state.windowRects)
            .minus(anchor)
            .also { found ->
                members = found
                // where each of them started: carrying by "current plus delta" would round
                // a window's place on every event, and the stack would drift apart
                startedAt = found.mapNotNull { id -> state.offsetOfWindow(id)?.let { id to it } }.toMap()
            }

    /** Moves the carried windows to where they started plus [travelled]. */
    fun carry(
        state: WinampState,
        anchor: String,
        travelled: IntOffset,
    ) = ensure(state, anchor).forEach { id ->
        val from = startedAt[id] ?: return@forEach
        state.placeWindow(id, IntOffset(from.x + travelled.x, from.y + travelled.y))
    }

    /** Ends the gesture; the next one works its group out again. */
    fun release() {
        members = null
        startedAt = emptyMap()
    }
}

/** Where the grip is: the bottom-right corner, unless a window says otherwise. */
private fun gripOf(
    gripAt: IntRect?,
    width: Int,
    height: Int,
) = gripAt ?: IntRect(width - RESIZE_GRIP, height - RESIZE_GRIP, width, height)

/** Excludes the grip's rectangle from the system's edge gestures. */
@Composable
private fun GripExclusion(
    at: IntRect,
    scale: Int,
) {
    val density = LocalDensity.current
    Box(
        Modifier
            .offset { IntOffset(at.left * scale, at.top * scale) }
            .size(
                with(density) { (at.width * scale).toDp() },
                with(density) { (at.height * scale).toDp() },
            ).systemGestureExclusion(),
    )
}

/**
 * The magnifier this window opens, built from its own drawing and widgets.
 */
@Composable
private fun rememberLoupe(
    id: String,
    state: WinampState,
    edge: Loupe.Edge,
    draw: DrawScope.() -> Unit,
    widgets: () -> List<Widget>,
): LoupeGesture {
    val drawNow by rememberUpdatedState(draw)
    val widgetsNow by rememberUpdatedState(widgets)
    val edgeNow by rememberUpdatedState(edge)
    return remember(id, state) {
        LoupeGesture(
            state,
            // a press near a side of the screen is aimed further out than the finger got
            aim = { at ->
                state.windowRects[id]?.let { window ->
                    Offset(Loupe.aimedAcross(window.left + at.x, state.screenW, edgeNow) - window.left, at.y)
                } ?: at
            },
        ) { finger, on ->
            Loupe(
                window = id,
                roam = Loupe.clusterAround(on, widgetsNow()),
                paint = { drawNow() },
                widgets = { widgetsNow() },
                // the finger's room on the screen, which a window against its edge has little of
                reach =
                    state.windowRects[id]?.let { window ->
                        Loupe.Reach.on(
                            Offset(window.left + finger.x, window.top + finger.y),
                            state.screenW,
                            state.screenH,
                            edgeNow,
                        )
                    } ?: Loupe.Reach.UNLIMITED,
                // starts on the center of the control that was pressed
                start =
                    Offset(
                        on.bounds.center.x
                            .toFloat(),
                        on.bounds.center.y
                            .toFloat(),
                    ),
            )
        }
    }
}
