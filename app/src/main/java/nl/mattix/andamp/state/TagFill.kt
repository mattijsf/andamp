// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.mattix.andamp.core.model.Track
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * Fills in the tags of rows that are already on the playlist under their filenames.
 *
 * The rows are read from the top down, [READERS] at a time, and the playlist is told as
 * they arrive: the first ones at once, then whatever has been read since, at most once
 * every [PACE_MS]. Every update costs the whole queue (the list on screen, the media session,
 * the stored playlist), so a long folder is paced by the clock and not by its length.
 */
object TagFill {
    /** The shortest time between two updates of the playlist. */
    const val PACE_MS = 100L

    /** How many files are read at a time. */
    const val READERS = 3

    /**
     * Reads every row of [rows] with [read], on [io], and hands the results to [patch] in
     * the caller's context. A row [read] returns null for is left as it is.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun run(
        rows: List<Track>,
        io: CoroutineContext,
        read: suspend (Track) -> Track?,
        patch: (List<Track>) -> Unit,
    ) = coroutineScope {
        val arriving =
            produce(io, capacity = Channel.UNLIMITED) {
                val next = AtomicInteger(0)
                repeat(READERS) {
                    launch {
                        while (true) {
                            val row = rows.getOrNull(next.getAndIncrement()) ?: break
                            read(row)?.let { send(it) }
                        }
                    }
                }
            }
        for (first in arriving) {
            val batch = mutableListOf(first)
            while (true) batch += arriving.tryReceive().getOrNull() ?: break
            patch(batch)
            delay(PACE_MS)
        }
    }
}
