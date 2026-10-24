// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import androidx.compose.runtime.mutableStateOf

/**
 * Where the listener moved a stack away from the place the engine put it at: how far, in device
 * px, and how large the stack was then, in device px.
 */
internal data class WalMove(
    val dx: Int,
    val dy: Int,
    val width: Int,
    val height: Int,
)

/**
 * Where the listener moved a modern skin's stack, upright and sideways apart, as snapshot state;
 * null while it sits where the engine puts it. Read and written on the main thread; each change
 * goes to [keep], which keeps it between runs.
 */
internal class WalPlaces(
    upright: WalMove? = null,
    sideways: WalMove? = null,
    private val keep: (sideways: Boolean, move: WalMove?) -> Unit = { _, _ -> },
) {
    private val moves = listOf(mutableStateOf(upright), mutableStateOf(sideways))

    /** Where the stack was moved to, upright or [sideways]. */
    operator fun get(sideways: Boolean): WalMove? = moves[if (sideways) 1 else 0].value

    /** Moves the stack, upright or [sideways], to [move]; null puts it back where the engine puts it. */
    operator fun set(
        sideways: Boolean,
        move: WalMove?,
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
}
