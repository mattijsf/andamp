// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset

/**
 * Where one window sits, how big it is and whether it is collapsed, for one way of holding
 * the phone, as snapshot state.
 *
 * Each field is its own `mutableStateOf`, so moving one window does not redraw readers of
 * the other fields.
 *
 * Null means the window does not have that property (the player has no width steps), as
 * in [WindowArrangement].
 */
class WindowArrangementState(
    offset: IntOffset? = null,
    size: Int? = null,
    cols: Int? = null,
    shaded: Boolean? = null,
) {
    /** Virtual px from screen center; null means the layout places it. */
    var offset by mutableStateOf(offset)

    /** A count, never pixels: playlist segments, list rows, resize steps. */
    var size by mutableStateOf(size)

    /** Steps wider than the base width; Winamp's own width index. */
    var cols by mutableStateOf(cols)

    /** Whether it was left collapsed to its title bar (window shade). */
    var shaded by mutableStateOf(shaded)

    /** This arrangement in the store's terms. */
    fun asMemory() = WindowArrangement(offset?.x, offset?.y, size, shaded, cols)

    /**
     * Applies what was remembered. A property the memory does not mention keeps its
     * default, except [size], which is always taken.
     */
    fun restore(memory: WindowArrangement) {
        memory.offset()?.let { offset = it }
        size = memory.size
        memory.cols?.let { cols = it }
        memory.shaded?.let { shaded = it }
    }
}

/**
 * One window's placement: whether it is open, and an arrangement for each way of holding
 * the phone, of which [sidewaysNow] says which one is in use.
 *
 * [offset], [size], [cols] and [shaded] read and write the arrangement in use, so the rest
 * of the app sees one window with one place, and turning the phone swaps the place under
 * it. [open] is the window's own, whichever way the phone is held.
 */
class WindowPlacementState(
    open: Boolean? = null,
    val upright: WindowArrangementState,
    val sideways: WindowArrangementState,
    private val sidewaysNow: () -> Boolean,
) {
    /** Whether it was left open, for the windows a listener can close. */
    var open by mutableStateOf(open)

    private val current: WindowArrangementState get() = if (sidewaysNow()) sideways else upright

    /** Virtual px from screen center; null means the layout places it. */
    var offset: IntOffset?
        get() = current.offset
        set(value) {
            current.offset = value
        }

    /** A count, never pixels: playlist segments, list rows, resize steps. */
    var size: Int?
        get() = current.size
        set(value) {
            current.size = value
        }

    /** Steps wider than the base width; Winamp's own width index. */
    var cols: Int?
        get() = current.cols
        set(value) {
            current.cols = value
        }

    /** Whether it was left collapsed to its title bar (window shade). */
    var shaded: Boolean?
        get() = current.shaded
        set(value) {
            current.shaded = value
        }

    /** This window in the store's terms. */
    fun asMemory() = WindowPlacement(open, upright.asMemory(), sideways.asMemory())

    /** Applies what was remembered to both arrangements; see [WindowArrangementState.restore]. */
    fun restore(memory: WindowPlacement) {
        memory.open?.let { open = it }
        upright.restore(memory.upright)
        sideways.restore(memory.sideways)
    }

    companion object {
        /**
         * One entry per window in [WindowStore.WINDOWS], with its defaults, the same for
         * both ways round. A null offset leaves the window to the layout;
         * [WindowStore.LIBRARY] and [WindowStore.SKINS] start at the center, which is what
         * a zero offset means.
         */
        fun forWindows(sidewaysNow: () -> Boolean): Map<String, WindowPlacementState> {
            fun window(
                open: Boolean? = null,
                offset: IntOffset? = null,
                shaded: Boolean? = null,
                cols: Int? = null,
            ) = WindowPlacementState(
                open,
                upright = WindowArrangementState(offset = offset, cols = cols, shaded = shaded),
                sideways = WindowArrangementState(offset = offset, cols = cols, shaded = shaded),
                sidewaysNow = sidewaysNow,
            )
            return mapOf(
                WindowStore.MAIN to window(shaded = false),
                WindowStore.EQ to window(open = true, shaded = false),
                WindowStore.MILKDROP to window(open = false, cols = 0),
                WindowStore.PLAYLIST to window(open = true, shaded = false, cols = 0),
                WindowStore.LIBRARY to window(offset = IntOffset.Zero, cols = 0),
                WindowStore.SKINS to window(offset = IntOffset.Zero, cols = 0),
            )
        }
    }
}
