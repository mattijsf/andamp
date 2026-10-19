// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nl.mattix.andamp.backend.pack.PackBrowse
import nl.mattix.andamp.core.playback.BrowseSource

/**
 * One library per source, for the library window, which chooses the source it shows.
 *
 * Cached per source, so the window can ask on every draw and a source keeps the shelves it
 * fetched while another is shown. A source's library is kept in the library's own index
 * ([CachedSourceLibrary]), and a source with no library of its own to give gets the phone's.
 * The phone's is one for the whole process: it follows the phone's audio
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
                val browse = PackSources.found.firstOrNull { it.source == source }?.browse(app)
                if (browse == null) {
                    phone(app)
                } else {
                    CachedSourceLibrary(keyOf(source), browse, index(app), background, wholeOf = ::wholeOf)
                }
            }
        }
    }

    /** The phone's library, over the library's own index. */
    fun phone(context: Context): IndexedPhoneLibrary =
        synchronized(this) {
            phone ?: IndexedPhoneLibrary
                .of(context, index(context))
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

    /**
     * Drops what the index keeps of [source] as well, when nobody is signed in to it any more:
     * the next account may be somebody else's.
     */
    fun clear(source: MusicSource) {
        forget(source)
        val kept = synchronized(this) { index } ?: return
        if (source != MusicSource.LOCAL) background.launch { kept.clear(keyOf(source)) }
    }

    /** The library's own index, one for the whole process. */
    fun index(context: Context): LibraryIndex =
        synchronized(this) { index ?: LibraryIndex(context.applicationContext).also { index = it } }

    /** A source's name in the index, apart from the phone's. */
    private fun keyOf(source: MusicSource) = "source:${source.id}"

    /** Whether a source's pack walked its last question to the end. */
    private fun wholeOf(browse: BrowseSource) = (browse as? PackBrowse)?.whole?.value ?: true

    /** Where the questions a stored answer asks behind it run, for as long as the process. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val held = mutableMapOf<MusicSource, BrowseSource>()
    private var phone: IndexedPhoneLibrary? = null
    private var index: LibraryIndex? = null
}
