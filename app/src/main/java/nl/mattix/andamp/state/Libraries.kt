// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import nl.mattix.andamp.core.playback.BrowseSource

/**
 * One library per source, for the library window, which chooses the source it shows.
 *
 * Cached per source, so the window can ask on every draw and a source keeps the shelves it
 * fetched while another is shown. A source with no library of its own to give gets the
 * phone's. The phone's is one for the whole process: it follows the phone's audio
 * ([IndexedPhoneLibrary.follow]) from its first use.
 */
object Libraries {
    fun of(
        context: Context,
        source: MusicSource,
    ): BrowseSource {
        if (source == MusicSource.LOCAL) return phone(context)
        val app = context.applicationContext
        return synchronized(this) {
            held.getOrPut(source) {
                PackSources.found.firstOrNull { it.source == source }?.browse(app) ?: phone(app)
            }
        }
    }

    /** The phone's library, over the library's own index. */
    fun phone(context: Context): IndexedPhoneLibrary =
        synchronized(this) {
            phone ?: IndexedPhoneLibrary
                .of(context, LibraryIndex(context))
                .also {
                    it.follow(context.applicationContext)
                    phone = it
                }
        }

    /**
     * Drops one source's cached library, so the next call builds it again (after a sign-in or
     * sign-out). The phone's is kept and checks the phone again before its next answer.
     */
    fun forget(source: MusicSource) {
        synchronized(this) {
            if (source == MusicSource.LOCAL) phone?.markDirty() else held.remove(source)
        }
    }

    private val held = mutableMapOf<MusicSource, BrowseSource>()
    private var phone: IndexedPhoneLibrary? = null
}
