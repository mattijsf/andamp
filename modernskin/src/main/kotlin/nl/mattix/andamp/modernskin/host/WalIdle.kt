// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.modernskin.host

import nl.mattix.andamp.modernskin.skin.Player
import nl.mattix.andamp.modernskin.skin.SkinRuntime

/** What a skin says about resting, as a host reads it each time it publishes a frame ([WalRest]). */
public data class WalIdle(
    /** Whether something moves that wants a frame on every display frame; see [SkinRuntime.framesWanted]. */
    val framesWanted: Boolean,
    /** When, in the runtime's clock ms, something next changes by itself, or null; see [SkinRuntime.nextDueAt]. */
    val nextDueAt: Long?,
    /** Whether the music is stopped, so the skin is woken at the resting rate; see [WalRest]. */
    val stopped: Boolean = false,
) {
    public companion object {
        /** What [runtime] says. */
        public fun of(runtime: SkinRuntime): WalIdle =
            WalIdle(runtime.framesWanted, runtime.nextDueAt(), stopped = runtime.player.status == Player.Status.STOPPED)
    }
}
