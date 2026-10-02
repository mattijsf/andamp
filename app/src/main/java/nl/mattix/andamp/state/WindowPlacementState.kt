// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset

/**
 * Where one window sits, how big it is, and how it was left, as snapshot state.
 *
 * Each field is its own `mutableStateOf`, so moving one window does not redraw readers of
 * the other fields.
 *
 * Null means the window does not have that property (the player has no width steps, the
 * library is not shadable), as in [WindowPlacement].
 */
class WindowPlacementState(
    offset: IntOffset? = null,
    size: Int? = null,
    cols: Int? = null,
    open: Boolean? = null,
    shaded: Boolean? = null,
) {
    /** Virtual px from screen center; null means the layout places it. */
    var offset by mutableStateOf(offset)

    /** A count, never pixels: playlist segments, list rows, resize steps. */
    var size by mutableStateOf(size)

    /** Steps wider than the base width; Winamp's own width index. */
    var cols by mutableStateOf(cols)

    /** Whether it was left open, for the windows a listener can close. */
    var open by mutableStateOf(open)

    /** Whether it was left collapsed to its title bar (window shade). */
    var shaded by mutableStateOf(shaded)

    /** This window in the store's terms. */
    fun asMemory() = WindowPlacement(offset?.x, offset?.y, size, open, shaded, cols)

    /**
     * Applies what was remembered. A property the memory does not mention keeps its
     * default, except [size], which is always taken.
     */
    fun restore(memory: WindowPlacement) {
        memory.x?.let { x -> memory.y?.let { y -> offset = IntOffset(x, y) } }
        size = memory.size
        memory.cols?.let { cols = it }
        memory.open?.let { open = it }
        memory.shaded?.let { shaded = it }
    }

    companion object {
        /**
         * One entry per window in [WindowStore.WINDOWS], with its defaults. A null offset
         * leaves the window to the layout; [WindowStore.LIBRARY] and [WindowStore.SKINS]
         * start at the center, which is what a zero offset means.
         */
        fun forWindows(): Map<String, WindowPlacementState> =
            mapOf(
                WindowStore.MAIN to WindowPlacementState(shaded = false),
                WindowStore.EQ to WindowPlacementState(open = true, shaded = false),
                WindowStore.MILKDROP to WindowPlacementState(open = false, cols = 0),
                WindowStore.PLAYLIST to WindowPlacementState(open = true, shaded = false, cols = 0),
                WindowStore.LIBRARY to WindowPlacementState(offset = IntOffset.Zero, cols = 0),
                WindowStore.SKINS to WindowPlacementState(offset = IntOffset.Zero, cols = 0),
            )
    }
}
