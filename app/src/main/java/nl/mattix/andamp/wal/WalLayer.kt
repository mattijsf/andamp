// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.ViewConfiguration
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import nl.mattix.andamp.R
import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.ui.menu.MenuBarSection
import nl.mattix.andamp.ui.window.MilkdropSurface
import nl.mattix.andamp.ui.window.PlaylistMenuActions
import nl.mattix.andamp.ui.window.WindowSnap
import nl.mattix.andamp.ui.window.windowScreen
import nl.mattix.andamp.widget.WidgetSignals
import java.util.Locale
import java.util.WeakHashMap

/** The z-index of [WalLayer]: below every classic window, whose z-indexes run up from 0. */
internal const val WAL_LAYER_Z = -2f

/**
 * How long a stack that changed size while the skin animates must hold still before its rect is
 * published, when the animation does not stop: a skin can loop one for as long as it is worn.
 */
internal const val WAL_RECT_SETTLE_MS = 200L

/**
 * How long after the listener's last input the skin is ticked on every display frame, whether its
 * frames want that or not: a drawer a tap opens slides smoothly while the music is stopped.
 */
internal const val WAL_INPUT_FRAMES_MS = 2_000L

private const val NANOS_PER_MS = 1_000_000L

/**
 * For each player state, the layer whose rect `windowRects[MAIN]` holds; read and written on the
 * main thread only. `windowRects` keeps the rect it has when an equal one is put, so the rect alone
 * cannot say which of two surfaces of the same size put it there.
 */
private val mainRectOwners = WeakHashMap<WinampState, PublishedRect>()

/**
 * A worn modern skin as a player surface hands it to its [WalLayer]: the skin, where its buttons'
 * actions go, whether the player is covered, and how the main menu opens over it.
 */
internal class WalLayerParts(
    val surface: WalSurface,
    val actions: WalHostActions,
    /**
     * Whether nobody sees the skin though the screen is on, so it rests: a screen of the app covers
     * it, or a window of the app hides it whole ([hidesModernSkin]). Read in a snapshot.
     */
    val covered: () -> Boolean,
    /** Opens the main menu at an anchor; a menu bar entry opens the part it names. */
    val openMenu: (anchor: MenuAnchor?, section: MenuBarSection?) -> Unit,
    /** What a skin's popup menu is called as one of the app's menus. */
    val menuTitle: String,
    /** Whether the stack keeps to its natural size, as over other apps; see [WalFit.compact]. */
    val compact: Boolean = false,
    /** The visualizer, drawn where the skin shows it ([WalLayout.visRect]) and filling the modifier's bounds. */
    val visualizer: @Composable (Modifier) -> Unit = {},
)

/**
 * [surface] as [vm]'s player surface hands it on, with the ways this surface opens menus and files;
 * it counts as [covered] also while a window of the app hides it whole ([hidesModernSkin]).
 */
@Composable
internal fun rememberWalLayerParts(
    surface: WalSurface,
    vm: WinampViewModel,
    playlistActions: PlaylistMenuActions,
    openMenu: (anchor: MenuAnchor?, section: MenuBarSection?) -> Unit,
    onMinimize: () -> Unit,
    onExit: () -> Unit,
    covered: () -> Boolean,
    compact: Boolean = false,
): WalLayerParts {
    val openMenuNow by rememberUpdatedState(openMenu)
    val minimizeNow by rememberUpdatedState(onMinimize)
    val exitNow by rememberUpdatedState(onExit)
    val playlistNow by rememberUpdatedState(playlistActions)
    val coveredNow by rememberUpdatedState(covered)
    return remember(surface, vm, compact) {
        WalLayerParts(
            surface,
            WalHostActions.of(
                vm,
                openMenu = { section, anchor -> openMenuNow(anchor, section) },
                onMinimize = { minimizeNow() },
                onExit = { exitNow() },
                openFile = { andPlay -> if (andPlay) playlistNow.addFileThenPlay() else playlistNow.addFile() },
            ),
            // a window of the app over the whole skin hides it as well as a screen does
            covered = { coveredNow() || hidesModernSkin(vm.state) },
            openMenu = { anchor, section -> openMenuNow(anchor, section) },
            menuTitle = vm.words.get(R.string.menu_skin_menu),
            compact = compact,
            visualizer = { MilkdropSurface(vm, it) },
        )
    }
}

/**
 * Whether a window of the app hides the whole of a worn modern skin's stack (`windowRects[MAIN]`),
 * so the skin rests as under a covering screen: the fullscreen visualizer, or the Library or the
 * Skin Browser window over all of the stack. Read in a snapshot.
 */
internal fun hidesModernSkin(s: WinampState): Boolean {
    if (s.visualizerFullscreen) return true
    val stack = s.windowRects[WindowStore.MAIN] ?: return false

    fun over(
        id: String,
        open: Boolean,
    ): Boolean {
        val window = s.windowRects[id] ?: return false
        return open && window.left <= stack.left && window.top <= stack.top && window.right >= stack.right &&
            window.bottom >= stack.bottom
    }
    return over(WindowStore.LIBRARY, s.libraryOpen) || over(WindowStore.SKINS, s.skinManagerOpen)
}

/**
 * The worn modern skin in a player surface: its stack drawn where the engine laid it out in the
 * screen's band, and touches on it sent to the skin. The surface holding the skin's turn also
 * gives it its box, its clock, its menus and its actions, and publishes `windowRects[MAIN]`.
 *
 * Until the engine has laid the stack out for this box, [companion] shows in its place: the
 * classic skin's main window, so the main menu and the long press work while the skin loads. A
 * layout worked out for another box (the phone was turned) leaves the stack where it was last
 * placed, or the companion when it never was.
 *
 * A drag from any part of the skin but a control moves the stack inside the box ([WalDrag]); the skin
 * keeps where it was moved to, upright and sideways apart ([WalSurface.places]). A stack that has
 * grown past the box's edge from there is held inside it ([WalPlacement.held]).
 *
 * The layer fills the surface, but only the stack takes touches: a touch beside it reaches what is
 * under it, which over the floating player is the app behind. There the touch window follows the
 * stack once the finger lifts, as it follows a classic window.
 */
@Composable
internal fun WalLayer(
    parts: WalLayerParts,
    state: WinampState,
    /** The surface's pixels per virtual pixel of the classic windows. */
    scale: Int,
    modifier: Modifier = Modifier,
    companion: @Composable () -> Unit,
) {
    val surface = parts.surface
    BoxWithConstraints(modifier) {
        val box = WalPlacement.box(constraints.maxWidth, windowScreen(scale, state), scale)
        val fit = WalPlacement.fit(box, LocalDensity.current.density, state.sideways, parts.compact)
        val drag = remember(surface) { WalDrag { sideways, move -> surface.places[sideways] = move } }
        val layout = surface.layout
        val fitted = layout?.takeIf { it.fit == fit }
        // where the engine puts the stack, and where the listener moved it from there
        val home = fitted?.let { WalPlacement.of(box, it, scale) }
        val move = drag.shift ?: surface.places[fit.sideways]
        val shift = if (home == null || move == null) null else WalPlacement.held(move, home.stack, box)
        val ready = if (fitted == null || shift == null) home else WalPlacement.of(box, fitted, scale, shift)
        val last = remember(surface) { mutableStateOf<WalPlacement?>(null) }
        SideEffect { if (ready != null) last.value = ready }
        val fitNow by rememberUpdatedState(fit)
        val readyNow by rememberUpdatedState(ready)
        // as composed, with the placement it goes with
        val draggingNow by rememberUpdatedState(drag.shift != null)
        WalTurn(parts, state, fit = { fitNow }, ready = { readyNow }, dragging = { draggingNow })
        val placed = if (layout == null) null else ready ?: last.value
        // the stack snaps as near as a classic window does
        SideEffect { drag.placedAt(fit, box, home?.stack, placed?.stack?.topLeft, WindowSnap.SNAP_DISTANCE * scale) }
        if (placed == null) {
            companion()
        } else {
            WalCanvas(parts, state, placed, drag)
            fitted?.visRect?.let { WalVisualizer(parts, state, placed, it) }
        }
    }
}

/**
 * The skin's turn on this surface, held while its lifecycle is resumed: the sink, the fit, the
 * clock, the menus and the window rect. The rect is removed when the layer goes, unless another
 * surface has published a rect since; a menu left open is closed and the skin's settings saved
 * when the layer goes or the surface stops.
 */
@Composable
private fun WalTurn(
    parts: WalLayerParts,
    state: WinampState,
    fit: () -> WalFit,
    ready: () -> WalPlacement?,
    dragging: () -> Boolean,
) {
    val surface = parts.surface
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    // the rect this layer put in windowRects last, and the token that says it is this layer's
    val published = remember(surface, state) { PublishedRect() }
    LaunchedEffect(parts, state, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            surface.turn.withLock {
                surface.sink = parts.actions
                try {
                    coroutineScope {
                        holdTurn(parts, state, context, fit, ready, dragging) { rect ->
                            state.windowRects[WindowStore.MAIN] = rect
                            published.rect = rect
                            mainRectOwners[state] = published
                        }
                    }
                } finally {
                    if (surface.sink === parts.actions) surface.sink = null
                }
            }
        }
    }
    DisposableEffect(surface, owner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    surface.dismissMenu()
                    surface.save()
                }
            }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(surface, state) {
        onDispose {
            if (mainRectOwners[state] === published) {
                mainRectOwners.remove(state)
                if (state.windowRects[WindowStore.MAIN] == published.rect) state.windowRects.remove(WindowStore.MAIN)
            }
            surface.dismissMenu()
            surface.save()
        }
    }
}

/** What the surface holding the turn does until it lets go; each part runs until the scope is cancelled. */
private fun CoroutineScope.holdTurn(
    parts: WalLayerParts,
    state: WinampState,
    context: Context,
    fit: () -> WalFit,
    ready: () -> WalPlacement?,
    dragging: () -> Boolean,
    publish: (IntRect) -> Unit,
) {
    val surface = parts.surface
    var screenOn by mutableStateOf(false)
    launch { WidgetSignals.screenOn(context).collect { screenOn = it } }
    // a covered player and a dark screen show nothing, so the skin's clock rests
    val awake = { screenOn && !parts.covered() }
    // the skin hears whether it shows, and rests once this surface lets go of the turn
    launch {
        try {
            snapshotFlow(awake).collect { surface.awake = it }
        } finally {
            surface.awake = false
        }
    }
    launch { snapshotFlow(fit).collect { surface.fit(it) } }
    launch { followSkinVisualizer(parts, state) }
    // the rect goes out as the turn is taken; a drawer sliding open moves it once more, when it stops
    launch {
        var first = true
        snapshotFlow { ready()?.window }.filterNotNull().collectLatest { window ->
            if (!first && surface.layout?.animating == true) stopped(surface)
            first = false
            publish(window)
        }
    }
    launch { frameLoop(surface, awake) }
    // a change the skin shows, such as the volume or the track, earns it a tick at once
    launch {
        snapshotFlow { if (awake()) surface.wakeKey() else null }.filterNotNull().collect { surface.requestTick(WalCause.KEY) }
    }
    launch { showSkinMenus(surface, state, parts.menuTitle) { parts.actions.pressAt?.invoke() } }
    // the timing log has each place the stack is drawn at, so a skin's scale reads off a device;
    // a stack the finger drags is logged where it lands
    launch {
        snapshotFlow { ready()?.takeUnless { dragging() } }.filterNotNull().collect {
            if (WalTiming.on) Log.d(WalTiming.TAG, placedLine(it, fit()))
        }
    }
}

/** What the frame loop goes by: whether the skin shows, whether its frames want ticks, and the listener's last input. */
private data class FrameAsk(
    val awake: Boolean,
    val wantsFrames: Boolean,
    val inputAt: Long,
)

/**
 * Asks [surface] for a tick on display frames, as [WalTickPace] paces them, while it shows ([awake])
 * and its frames want them ([WalLayout.wantsFrames]), and for [WAL_INPUT_FRAMES_MS] after each of
 * the listener's inputs ([WalSurface.lastInputAt]), so what a touch sets moving runs smoothly. Else
 * it asks for none: the engine wakes the skin itself when something is due.
 */
private suspend fun frameLoop(
    surface: WalSurface,
    awake: () -> Boolean,
) {
    val pace = WalTickPace()
    var seenInput = surface.lastInputAt
    // the display frame time, in ns, until which the last input keeps the loop running
    var inputUntil = Long.MIN_VALUE
    snapshotFlow { FrameAsk(awake(), surface.layout?.wantsFrames == true, surface.lastInputAt) }.collectLatest { ask ->
        var input = ask.inputAt != seenInput
        seenInput = ask.inputAt
        if (!ask.awake) return@collectLatest
        while (true) {
            val now = withFrameNanos { it }
            if (input) {
                inputUntil = now + WAL_INPUT_FRAMES_MS * NANOS_PER_MS
                input = false
            }
            if (!ask.wantsFrames && now >= inputUntil) break
            if (pace.due(now)) surface.requestTick()
        }
    }
}

/**
 * Paces the frame loop at no more than sixty ticks a second, however fast the display runs. A
 * display frame ticks once it is no more than half a display frame before the next deadline, and
 * each tick moves the deadline on by [periodNanos], so a 90 Hz or 120 Hz display ticks as near
 * sixty times a second as its frames fall, and a 60 Hz one on every frame. After a pause the
 * deadlines start over from the next frame.
 */
internal class WalTickPace(
    private val periodNanos: Long = TICK_PERIOD_NANOS,
) {
    private var next = NONE
    private var lastFrame = NONE

    /** Whether the display frame at [frameNanos] ticks; asked once for every display frame, in order. */
    fun due(frameNanos: Long): Boolean {
        val frame = if (lastFrame == NONE) 0L else (frameNanos - lastFrame).coerceIn(0L, periodNanos)
        lastFrame = frameNanos
        if (next != NONE && frameNanos < next - frame / 2) return false
        next = if (next == NONE || frameNanos - next >= periodNanos) frameNanos + periodNanos else next + periodNanos
        return true
    }

    companion object {
        /** A sixtieth of a second, in ns. */
        const val TICK_PERIOD_NANOS = 16_666_667L

        private const val NONE = Long.MIN_VALUE
    }
}

/** [placed] in [fit]'s box, as the timing log says it: the scale, the box and the stack's rect in pixels. */
internal fun placedLine(
    placed: WalPlacement,
    fit: WalFit,
): String {
    val stack = placed.stack
    val way = if (fit.sideways) "sideways" else "upright"
    return String.format(
        Locale.US,
        "placed at %.3fx in %dx%d %s: stack %dx%d at %d,%d",
        placed.skinScale,
        fit.widthPx,
        fit.heightPx,
        way,
        stack.width,
        stack.height,
        stack.left,
        stack.top,
    )
}

/**
 * Returns once [surface] stops animating, or after [WAL_RECT_SETTLE_MS] when it animates on, as a
 * skin looping an animation does.
 */
private suspend fun stopped(surface: WalSurface) {
    withTimeoutOrNull(WAL_RECT_SETTLE_MS) { snapshotFlow { surface.layout?.animating == true }.first { !it } }
}

/** The rect a layer last put in `windowRects`; the object itself is the layer's owner token. */
private class PublishedRect {
    var rect: IntRect? = null
}

/** The frame whose draw threw: it is reported once and not drawn again, unless a switch changes how frames are drawn. */
private class FailedFrame {
    var frame = -1
    var redraws = -1
}

/**
 * The stack where [placed] puts it: the skin's latest frame, and the touches on it. A long press
 * on an object with a skin menu opens that menu, and anywhere else opens the main menu at the
 * press; a drag from one of the skin's move areas moves the stack with [drag]. A draw that throws
 * draws nothing more of that frame and is reported once.
 */
@Composable
private fun WalCanvas(
    parts: WalLayerParts,
    state: WinampState,
    placed: WalPlacement,
    drag: WalDrag,
) {
    val surface = parts.surface
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val doubleTapSlop = remember(context) { ViewConfiguration.get(context).scaledDoubleTapSlop.toFloat() }
    // touches are mapped with the placement of the moment, not the one the gesture began with
    val placedNow by rememberUpdatedState(placed)
    val graphics = LocalGraphicsContext.current
    // nothing is drawn under the stack where the window shows what lies behind it: over other apps,
    // and in the activity from Android 9 on (a translucent window)
    val clearBelow = parts.compact || Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
    val layers =
        remember(surface, graphics, clearBelow) {
            WalLayers(graphics::createGraphicsLayer, graphics::releaseGraphicsLayer, clearBelow)
        }
    DisposableEffect(layers) { onDispose { layers.release() } }
    val failed = remember(surface) { FailedFrame() }
    val stack = placed.stack
    Spacer(
        Modifier
            .offset { IntOffset(stack.left, stack.top) }
            .size(with(density) { stack.width.toDp() }, with(density) { stack.height.toDp() })
            .clipToBounds()
            .pointerInput(surface, parts, drag) {
                val touch =
                    WalTouch(
                        surface,
                        WalTouch.Rules(
                            longPressMs = viewConfiguration.longPressTimeoutMillis,
                            touchSlop = viewConfiguration.touchSlop,
                            doubleTapMs = viewConfiguration.doubleTapTimeoutMillis,
                            doubleTapSlop = doubleTapSlop,
                        ),
                        toSkin = { placedNow.toSkin(it) },
                        onPress = { at ->
                            val point = placedNow.pointAt(at)
                            // measured as the menu opens, from the rect the menus read then
                            parts.actions.pressAt = { placedNow.anchorAt(point, state.windowRects[WindowStore.MAIN]) }
                        },
                        // the skin's menu where the press found one, else the main menu
                        onLongPress = { _, seq ->
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (surface.layout?.menuTarget == seq) {
                                surface.rightClick(seq)
                            } else {
                                parts.openMenu(parts.actions.pressAt?.invoke(), null)
                            }
                        },
                        drag = drag,
                    )
                awaitEachGesture { walGesture(touch) { surface.menu != null } }
            }.drawBehind {
                val frame = surface.frames.intValue
                val redraws = WalRender.redraws.intValue
                if (frame == failed.frame && redraws == failed.redraws) return@drawBehind
                runCatching { surface.draw(this, layers) }.onFailure { error ->
                    failed.frame = frame
                    failed.redraws = redraws
                    // nothing kept from before the failure is trusted
                    layers.drop()
                    surface.drawFailed(error)
                }
            },
    )
}
