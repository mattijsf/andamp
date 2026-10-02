// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.core.player.MixedQueueBackend

/**
 * Keeps the player's lanes in step with the sources this phone has.
 *
 * The player is built once per process, but a pack can be installed while Andamp is open.
 * So the list of sources is followed, as [SourceOps.follow] does for the menu and the
 * library: a source that arrives gets a lane, and one that goes loses it.
 *
 * There is one lane per source, made when the source arrives and removed when it goes. A
 * lane holds the player it built. The same source handed back as a different object
 * (another package answering under the same id) gets a new lane in place of the old one.
 *
 * Main thread only, where the player is driven.
 */
internal class SourceLanes(
    private val player: MixedQueueBackend,
    /** A player for one source's rows, or null while it cannot be built; see [ExtraSource.backend]. */
    private val open: (ExtraSource) -> PlaybackBackend?,
) {
    private class Held(
        val extra: ExtraSource,
        val lane: MixedQueueBackend.Lane,
    )

    private val held = LinkedHashMap<MusicSource, Held>()

    /**
     * Follows [extras] for as long as [scope] runs. It settles once before watching,
     * because the first emission arrives on a later turn of the main thread and play may be
     * pressed before that.
     */
    fun follow(
        scope: CoroutineScope,
        extras: () -> List<ExtraSource>,
    ): Job {
        settle(extras())
        return scope.launch { snapshotFlow { extras() }.collect(::settle) }
    }

    /** Adds a lane for every source that has none, and removes the lane of every one that has gone. */
    fun settle(now: List<ExtraSource>) {
        // if two apps answer for one source, the first one found plays
        val here = now.distinctBy { it.source }.associateBy { it.source }
        held.entries.toList().forEach { (source, was) ->
            if (here[source] !== was.extra) {
                held.remove(source)
                player.removeLane(was.lane)
            }
        }
        here.values.forEach { extra ->
            if (extra.source in held) return@forEach
            val lane = laneOf(extra)
            held[extra.source] = Held(extra, lane)
            player.addLane(lane)
        }
    }

    /**
     * A source's account is signing out: its player is released. The lane stays, because
     * the source is still installed; the next of its rows that is reached builds a player
     * again.
     */
    fun signedOut(source: MusicSource) {
        held[source]?.let { player.forget(it.lane) }
    }

    /** A source's lane claims its rows by their scheme. */
    private fun laneOf(extra: ExtraSource) =
        MixedQueueBackend.Lane(
            claims = { SourceForRows.of(it) == extra.source },
            make = { open(extra) },
        )

    companion object {
        /**
         * The phone's lane: what Media3 opens. It claims rows by scheme, so a row from a
         * source with no lane is not handed to ExoPlayer. [make] returns the host's
         * player, which the host keeps for the life of the process, so letting go of the
         * lane does nothing.
         */
        fun phone(make: () -> PlaybackBackend) =
            MixedQueueBackend.Lane(
                claims = { SourceForRows.of(it) == MusicSource.LOCAL },
                make = make,
                letGo = {},
            )
    }
}
