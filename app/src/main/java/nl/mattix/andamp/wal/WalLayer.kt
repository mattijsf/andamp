// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.content.Context
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
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import nl.mattix.andamp.BuildConfig
import nl.mattix.andamp.R
import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.ui.window.PlaylistMenuActions
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
    /** Whether a screen of the app covers the player, so the skin rests; read in a snapshot. */
    val covered: () -> Boolean,
    /** Opens the main menu at an anchor, with "Skin menu" first when it is given that entry's action. */
    val openMenu: (onSkinMenu: (() -> Unit)?, anchor: MenuAnchor?) -> Unit,
    /** What a skin's popup menu is called as one of the app's menus. */
    val menuTitle: String,
)

/** [surface] as [vm]'s player surface hands it on, with the ways this surface opens menus and files. */
@Composable
internal fun rememberWalLayerParts(
    surface: WalSurface,
    vm: WinampViewModel,
    playlistActions: PlaylistMenuActions,
    openMenu: (onSkinMenu: (() -> Unit)?, anchor: MenuAnchor?) -> Unit,
    onMinimize: () -> Unit,
    covered: () -> Boolean,
): WalLayerParts {
    val openMenuNow by rememberUpdatedState(openMenu)
    val minimizeNow by rememberUpdatedState(onMinimize)
    val playlistNow by rememberUpdatedState(playlistActions)
    val coveredNow by rememberUpdatedState(covered)
    return remember(surface, vm) {
        WalLayerParts(
            surface,
            WalHostActions.of(
                vm,
                openMenu = { anchor -> openMenuNow(null, anchor) },
                onMinimize = { minimizeNow() },
                openFile = { andPlay -> if (andPlay) playlistNow.addFileThenPlay() else playlistNow.addFile() },
            ),
            covered = { coveredNow() },
            openMenu = { onSkinMenu, anchor -> openMenuNow(onSkinMenu, anchor) },
            menuTitle = vm.words.get(R.string.menu_skin_menu),
        )
    }
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
 * The layer fills the surface, but only the stack takes touches: a touch beside it reaches what is
 * under it, which over the floating player is the app behind.
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
        val fit = WalPlacement.fit(box, LocalDensity.current.density, state.sideways)
        val layout = surface.layout
        val ready = layout?.takeIf { it.fit == fit }?.let { WalPlacement.of(box, it, scale) }
        val last = remember(surface) { mutableStateOf<WalPlacement?>(null) }
        SideEffect { if (ready != null) last.value = ready }
        val fitNow by rememberUpdatedState(fit)
        val readyNow by rememberUpdatedState(ready)
        WalTurn(parts, state, fit = { fitNow }, ready = { readyNow })
        val placed = if (layout == null) null else ready ?: last.value
        if (placed == null) companion() else WalCanvas(parts, state, placed)
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
                        holdTurn(parts, state, context, fit, ready) { rect ->
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
    publish: (IntRect) -> Unit,
) {
    val surface = parts.surface
    var screenOn by mutableStateOf(false)
    launch { WidgetSignals.screenOn(context).collect { screenOn = it } }
    // a covered player and a dark screen show nothing, so the skin's clock rests
    val awake = { screenOn && !parts.covered() }
    launch { snapshotFlow(fit).collect { surface.fit(it) } }
    // the rect goes out as the turn is taken; a drawer sliding open moves it once more, when it stops
    launch {
        var first = true
        snapshotFlow { ready()?.window }.filterNotNull().collectLatest { window ->
            if (!first && surface.layout?.animating == true) stopped(surface)
            first = false
            publish(window)
        }
    }
    launch {
        snapshotFlow(awake).collectLatest { on ->
            while (on) {
                withFrameNanos {}
                surface.requestTick()
            }
        }
    }
    // a change the skin shows, such as the volume or the track, earns it a tick
    launch { snapshotFlow { if (awake()) surface.wakeKey() else null }.filterNotNull().collect { surface.requestTick() } }
    launch { showSkinMenus(surface, state, parts.menuTitle) { parts.actions.pressAt?.invoke() } }
    // a debug build logs each place the stack is drawn at, so a skin's scale reads off a device
    if (BuildConfig.DEBUG) {
        launch {
            snapshotFlow(ready).filterNotNull().collect { Log.d(WalEngine.TIMING_TAG, placedLine(it, fit())) }
        }
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

/** The frame whose draw threw: it is reported once and not drawn again. */
private class FailedFrame {
    var frame = -1
}

/**
 * The stack where [placed] puts it: the skin's latest frame, and the touches on it. A long press
 * opens the main menu at the press, led by "Skin menu" when the engine found a skin menu under the
 * finger. A draw that throws draws nothing more of that frame and is reported once.
 */
@Composable
private fun WalCanvas(
    parts: WalLayerParts,
    state: WinampState,
    placed: WalPlacement,
) {
    val surface = parts.surface
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val doubleTapSlop = remember(context) { ViewConfiguration.get(context).scaledDoubleTapSlop.toFloat() }
    // touches are mapped with the placement of the moment, not the one the gesture began with
    val placedNow by rememberUpdatedState(placed)
    val layer = rememberGraphicsLayer()
    val failed = remember(surface) { FailedFrame() }
    val stack = placed.stack
    Spacer(
        Modifier
            .offset { IntOffset(stack.left, stack.top) }
            .size(with(density) { stack.width.toDp() }, with(density) { stack.height.toDp() })
            .clipToBounds()
            .pointerInput(surface, parts) {
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
                        onLongPress = { _, seq ->
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            // the engine names the press whose object has a skin menu; none named, no entry
                            val skinMenu = if (surface.layout?.menuTarget == seq) ({ surface.rightClick(seq) }) else null
                            parts.openMenu(skinMenu, parts.actions.pressAt?.invoke())
                        },
                    )
                awaitEachGesture { walGesture(touch) { surface.menu != null } }
            }.drawBehind {
                val frame = surface.frames.intValue
                if (frame == failed.frame) return@drawBehind
                runCatching { surface.draw(this, layer) }.onFailure { error ->
                    failed.frame = frame
                    surface.drawFailed(error)
                }
            },
    )
}
