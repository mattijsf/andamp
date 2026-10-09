// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset

/**
 * One window as it is set up in one [Workspace], as snapshot state; see [WindowArrangement].
 *
 * Each field is its own `mutableStateOf`, so moving one window does not redraw readers of
 * the other fields.
 */
class WindowArrangementState(
    offset: IntOffset? = null,
    size: Int? = null,
    cols: Int? = null,
    shaded: Boolean? = null,
    open: Boolean? = null,
) {
    /** Virtual px from screen center; null means the layout places it. */
    var offset by mutableStateOf(offset)

    /** A count, never pixels: playlist segments, list rows, resize steps. */
    var size by mutableStateOf(size)

    /** Steps wider than the base width; Winamp's own width index. */
    var cols by mutableStateOf(cols)

    /** Whether it was left collapsed to its title bar (window shade). */
    var shaded by mutableStateOf(shaded)

    /** Whether it was left open, for the windows a listener can close. */
    var open by mutableStateOf(open)

    /** This arrangement in the store's terms. */
    fun asMemory() = WindowArrangement(offset?.x, offset?.y, size, shaded, cols, open)

    /**
     * Applies what was remembered. A property the memory does not mention keeps its
     * default, except [size], which is always taken.
     */
    fun restore(memory: WindowArrangement) {
        memory.offset()?.let { offset = it }
        size = memory.size
        memory.cols?.let { cols = it }
        memory.shaded?.let { shaded = it }
        memory.open?.let { open = it }
    }
}

/**
 * One window's placement: an arrangement per [Workspace], of which [workspaceNow] says which
 * one is on screen.
 *
 * The properties read and write the arrangement on screen, so the rest of the app sees one
 * window with one place, size and state, and switching workspaces swaps them under it.
 */
class WindowPlacementState(
    private val arrangements: Map<Workspace, WindowArrangementState>,
    private val workspaceNow: () -> Workspace,
) {
    /** The window as it is set up in [workspace]. */
    fun of(workspace: Workspace): WindowArrangementState = arrangements.getValue(workspace)

    private val current: WindowArrangementState get() = of(workspaceNow())

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

    /** Whether it is collapsed to its title bar (window shade). */
    var shaded: Boolean?
        get() = current.shaded
        set(value) {
            current.shaded = value
        }

    /** Whether it is open, for the windows a listener can close. */
    var open: Boolean?
        get() = current.open
        set(value) {
            current.open = value
        }

    /** This window in the store's terms. */
    fun asMemory() = WindowPlacement(arrangements.mapValues { (_, arrangement) -> arrangement.asMemory() })

    /** Applies what was remembered to every workspace; see [WindowArrangementState.restore]. */
    fun restore(memory: WindowPlacement) {
        arrangements.forEach { (workspace, arrangement) -> arrangement.restore(memory.of(workspace)) }
    }

    companion object {
        /**
         * One entry per window in [WindowStore.WINDOWS], with its defaults, the same in
         * every workspace. A null offset leaves the window to the layout;
         * [WindowStore.LIBRARY] and [WindowStore.SKINS] start at the center, which is what
         * a zero offset means.
         */
        fun forWindows(workspaceNow: () -> Workspace): Map<String, WindowPlacementState> {
            fun window(
                open: Boolean? = null,
                offset: IntOffset? = null,
                shaded: Boolean? = null,
                cols: Int? = null,
            ) = WindowPlacementState(
                Workspace.entries.associateWith {
                    WindowArrangementState(offset = offset, cols = cols, shaded = shaded, open = open)
                },
                workspaceNow,
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
