// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.mattix.andamp.R
import nl.mattix.andamp.core.model.Track
import kotlin.coroutines.CoroutineContext

/**
 * The lists the library makes itself ([SmartList]), for the sources on show: what arrived on
 * the phone lately, what was played most, played last and not for a while ([PlayLog]), and the
 * bookmarks. A list with nothing in it is left out.
 *
 * A played song or a bookmark belongs to the source that plays it ([SourceForRows]); only those
 * of the sources on show are listed, so the phone's library does not list a server's songs.
 */
class SmartLists(
    private val plays: () -> PlayLog,
    private val phone: () -> IndexedPhoneLibrary,
    private val bookmarks: () -> List<Track>,
    private val words: Words,
    private val io: CoroutineContext = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun of(shown: Set<MusicSource>): List<SmartList> {
        val added = if (MusicSource.LOCAL in shown) phone().recentlyAdded(LIMIT) else emptyList()
        return withContext(io) {
            fun mine(track: Track) = SourceForRows.of(track) in shown
            val log = plays()
            val playsOf = { played: PlayLog.Played -> words.plural(R.plurals.library_count_plays, played.plays) }
            listOfNotNull(
                list(R.string.library_recently_added, added.map { it to null }),
                list(
                    R.string.library_most_played,
                    log.mostPlayed(READ).filter { mine(it.track) }.map { it.track to playsOf(it) },
                ),
                list(
                    R.string.library_recently_played,
                    log.recentlyPlayed(READ).filter { mine(it.track) }.map { it.track to null },
                ),
                list(
                    R.string.library_not_played_lately,
                    log.notLately(clock() - LATELY_MS, READ).filter { mine(it.track) }.map { it.track to playsOf(it) },
                ),
                list(R.string.library_bookmarks, bookmarks().filter(::mine).map { it to null }),
            )
        }
    }

    private fun list(
        name: Int,
        entries: List<Pair<Track, String?>>,
    ) = entries.take(LIMIT).takeIf { it.isNotEmpty() }?.let { SmartList(words.get(name), it) }

    private companion object {
        /** The most entries a list shows. */
        const val LIMIT = 100

        /** How many are read before those of other sources are left out. */
        const val READ = 1000

        /** How long a song is not played before it counts as not played lately: sixty days. */
        const val LATELY_MS = 60L * 24 * 60 * 60 * 1000
    }
}
