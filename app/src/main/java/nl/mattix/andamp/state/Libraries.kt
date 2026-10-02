// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import nl.mattix.andamp.core.playback.BrowseSource

/**
 * One library per source, for the library window, which chooses the source it shows.
 *
 * Cached per source, so the window can ask on every draw and a source keeps the shelves it
 * fetched while another is shown. A source with no library of its own to give gets the
 * phone's.
 */
object Libraries {
    fun of(
        context: Context,
        source: MusicSource,
    ): BrowseSource {
        val app = context.applicationContext
        return synchronized(this) {
            held.getOrPut(source) {
                PackSources.found.firstOrNull { it.source == source }?.browse(app) ?: MediaStoreBrowseSource(app)
            }
        }
    }

    /** Drops one source's cached library, so the next call builds it again (after a sign-in or sign-out). */
    fun forget(source: MusicSource) {
        synchronized(this) { held.remove(source) }
    }

    private val held = mutableMapOf<MusicSource, BrowseSource>()
}
