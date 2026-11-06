// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import nl.mattix.walkit.Player
import nl.mattix.walkit.SkinRuntime

/** What a skin says about resting, as a host reads it each time it publishes a frame ([WalRest]). */
internal data class WalIdle(
    /** Whether something moves that wants a frame on every display frame; see [SkinRuntime.framesWanted]. */
    val framesWanted: Boolean,
    /** When, in the runtime's clock ms, something next changes by itself, or null; see [SkinRuntime.nextDueAt]. */
    val nextDueAt: Long?,
    /** Whether the music is stopped, so the skin is woken at the resting rate; see [WalRest]. */
    val stopped: Boolean = false,
) {
    companion object {
        /** What [runtime] says. */
        fun of(runtime: SkinRuntime): WalIdle =
            WalIdle(runtime.framesWanted, runtime.nextDueAt(), stopped = runtime.player.status == Player.Status.STOPPED)
    }
}
