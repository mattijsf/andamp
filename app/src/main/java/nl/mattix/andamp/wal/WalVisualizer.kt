// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import nl.mattix.andamp.state.WinampState
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The visualizer where the skin shows it, [vis] in skin px from the stack's top-left corner, as
 * [placed] puts the stack: over the skin, taking the taps there as the visualizer window takes
 * them, while a drag on it moves the stack with [drag], as one on the skin around it does. The
 * visualizer window shows it instead while that is open, and nobody sees it while the player is
 * covered.
 */
@Composable
internal fun WalVisualizer(
    parts: WalLayerParts,
    state: WinampState,
    placed: WalPlacement,
    vis: IntRect,
    drag: WalDrag,
) {
    if (state.milkdropOn || parts.covered()) return
    val density = LocalDensity.current
    val k = placed.skinScale
    val left = placed.stack.left + floor(vis.left * k).toInt()
    val top = placed.stack.top + floor(vis.top * k).toInt()
    val right = placed.stack.left + ceil(vis.right * k).toInt()
    val bottom = placed.stack.top + ceil(vis.bottom * k).toInt()
    // the finger's positions as the drag takes them: from the stack's top-left corner
    val inStack = Offset((left - placed.stack.left).toFloat(), (top - placed.stack.top).toFloat())
    val inStackNow by rememberUpdatedState(inStack)
    parts.visualizer(
        Modifier
            .offset { IntOffset(left, top) }
            .pointerInput(drag) {
                detectDragGestures(
                    onDragStart = { at -> drag.start(at + inStackNow, at + inStackNow) },
                    onDrag = { change, _ -> drag.move(change.position + inStackNow) },
                    onDragEnd = { drag.end() },
                    onDragCancel = { drag.end() },
                )
            }.size(with(density) { (right - left).toDp() }, with(density) { (bottom - top).toDp() }),
    )
}

/**
 * Keeps [WinampState.visInSkin] to whether the skin shows the visualizer, until the turn is let go.
 * The skin starting to show it takes it from the visualizer window, which closes; fullscreen asked
 * for there ends with it.
 */
internal suspend fun followSkinVisualizer(
    parts: WalLayerParts,
    state: WinampState,
) {
    try {
        snapshotFlow { parts.surface.layout?.visRect != null }.collect { shown ->
            if (shown && !state.visInSkin && state.milkdropOn) parts.actions.hostAction("TOGGLE", "avs")
            state.visInSkin = shown
            if (!shown && !state.milkdropOn) state.milkdropFullscreen = false
        }
    } finally {
        state.visInSkin = false
        if (!state.milkdropOn) state.milkdropFullscreen = false
    }
}

/**
 * Tells the skin each time the visualizer window closes, until the turn is let go: what the skin
 * moved out into it, with its Detach buttons, goes back into the skin.
 */
internal suspend fun reattachOnClose(
    surface: WalSurface,
    state: WinampState,
) {
    var open = state.milkdropOn
    snapshotFlow { state.milkdropOn }.collect { on ->
        if (open && !on) surface.visualizerWindowClosed()
        open = on
    }
}
