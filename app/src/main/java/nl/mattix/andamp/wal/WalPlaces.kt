// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.IntOffset

/**
 * Where the listener moved a modern skin's stack, upright and sideways apart, as snapshot state:
 * how far from the place the engine puts it, in device px; null while it sits there. Read and
 * written on the main thread; each change goes to [keep], which keeps it between runs.
 */
internal class WalPlaces(
    upright: IntOffset? = null,
    sideways: IntOffset? = null,
    private val keep: (sideways: Boolean, move: IntOffset?) -> Unit = { _, _ -> },
) {
    private val moves = listOf(mutableStateOf(upright), mutableStateOf(sideways))

    /** Where the stack was moved to, upright or [sideways]. */
    operator fun get(sideways: Boolean): IntOffset? = moves[if (sideways) 1 else 0].value

    /** Moves the stack, upright or [sideways], to [move]; null puts it back where the engine puts it. */
    operator fun set(
        sideways: Boolean,
        move: IntOffset?,
    ) {
        val held = moves[if (sideways) 1 else 0]
        if (held.value == move) return
        held.value = move
        keep(sideways, move)
    }

    /** Puts the stack back where the engine puts it, upright and sideways. */
    fun clear() {
        this[false] = null
        this[true] = null
    }

    /** The count of [skinPlaced] last seen. */
    private var placements = 0

    /**
     * The skin has moved its main window against a screen edge [count] times after a pick from one
     * of its menus ([WalLayout.edgePlacements]), counted from its start: a new count puts the stack
     * back where the engine puts it, as a corner picked from CornerAmp's corner menu does.
     */
    fun skinPlaced(count: Int) {
        if (count == placements) return
        placements = count
        clear()
    }
}
