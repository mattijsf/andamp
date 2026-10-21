// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.mattix.andamp.R
import nl.mattix.andamp.core.model.BrowseCapabilities
import nl.mattix.andamp.core.model.LibraryAlbum
import nl.mattix.andamp.core.model.LibraryArtist
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.playback.BrowseSource
import kotlin.coroutines.CoroutineContext

/** A library whose search finds artists and albums as well as tracks, each kind apart. */
interface SectionedSearch {
    suspend fun searchSections(
        query: String,
        limit: Int = BrowseSource.SEARCH_LIMIT,
    ): Sections

    data class Sections(
        val artists: List<LibraryArtist>,
        val albums: List<LibraryAlbum>,
        val tracks: List<Track>,
    )
}

/** A library that can be read again while it is shown; [revision] rises each time it was. */
interface ShelfUpdates {
    val revision: StateFlow<Int>
}

/**
 * The phone's music, from the library's own index ([LibraryIndex]).
 *
 * The phone's media index is read in one pass ([PhoneShelfReader]) and grouped by album artist
 * ([ShelfBuilder]), and every page is answered from the copy. Before it answers, the library
 * checks whether the phone's music changed since: always after the phone said so (a
 * [ContentObserver] on its audio), and otherwise at most once every [CHECK_EVERY_MS]. The check
 * is one small query; only a change reads everything again.
 *
 * Without the permission to read music it answers empty, as the phone's media index would.
 */
class IndexedPhoneLibrary(
    private val canRead: () -> Boolean,
    private val reader: PhoneShelfReader,
    private val index: LibraryIndex,
    private val words: Words,
    private val io: CoroutineContext = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : BrowseSource,
    SectionedSearch,
    ShelfUpdates,
    TrackHomes,
    EveryTrack,
    FoldersAndGenres {
    override val capabilities = BrowseCapabilities(hasArtists = true, hasAlbums = true, canSearch = true)

    override val available: Boolean get() = canRead()

    private val _revision = MutableStateFlow(0)
    override val revision: StateFlow<Int> = _revision.asStateFlow()

    private val gate = Mutex()

    /** Set when the phone said its music changed, so the next read checks at once. */
    @Volatile
    private var dirty = true

    /** When the phone's music was last checked; see [CHECK_EVERY_MS]. */
    @Volatile
    private var checkedAt = 0L

    /** The phone's music changed, or may have: the next read checks before it answers. */
    fun markDirty() {
        dirty = true
    }

    /**
     * Brings the index level with the phone's music, when it may be behind; see the class
     * KDoc. True when it was read again.
     */
    suspend fun refresh(): Boolean =
        withContext(io) {
            gate.withLock {
                if (!dirty && clock() - checkedAt < CHECK_EVERY_MS) return@withLock false
                val stamp = "$SHELF_VERSION:${reader.stamp()}"
                val behind = stamp != index.stamp(SOURCE)
                if (behind) index.replace(SOURCE, ShelfBuilder.build(reader.read()), stamp)
                dirty = false
                checkedAt = clock()
                if (behind) _revision.value++
                behind
            }
        }

    override suspend fun artists(): List<LibraryArtist> = read(emptyList()) { index.artists(SOURCE).map(::artistOf) }

    override suspend fun albums(artistId: String?): List<LibraryAlbum> =
        read(emptyList()) {
            index.albums(SOURCE, artistId).map(::albumOf)
        }

    override suspend fun tracks(albumId: String): List<Track> = read(emptyList()) { index.tracks(SOURCE, albumId) }

    override suspend fun search(
        query: String,
        limit: Int,
    ): List<Track> = read(emptyList()) { index.search(SOURCE, query, limit).tracks }

    override suspend fun searchSections(
        query: String,
        limit: Int,
    ): SectionedSearch.Sections =
        read(NOTHING_FOUND) {
            val found = index.search(SOURCE, query, limit)
            SectionedSearch.Sections(found.artists.map(::artistOf), found.albums.map(::albumOf), found.tracks)
        }

    override suspend fun albumOf(track: Track): LibraryAlbum? =
        read(null) { index.homeOf(SOURCE, track.id).first?.let(::albumOf) }

    override suspend fun artistOf(track: Track): LibraryArtist? =
        read(null) {
            index.homeOf(SOURCE, track.id).second?.let(::artistOf)
        }

    override suspend fun everyTrack(): List<Track> = read(emptyList()) { index.everyTrack(SOURCE) }

    override suspend fun folders(): List<Pair<String, Int?>> = read(emptyList()) { index.folders(SOURCE) }

    override suspend fun folderTracks(folder: String): List<Track> = read(emptyList()) { index.folderTracks(SOURCE, folder) }

    override suspend fun genres(): List<Pair<String, Int?>> = read(emptyList()) { index.genres(SOURCE) }

    override suspend fun genreTracks(genre: String): List<Track> = read(emptyList()) { index.genreTracks(SOURCE, genre) }

    /** The phone's music by when it arrived, the newest first, at most [limit]. */
    suspend fun recentlyAdded(limit: Int): List<Track> = read(emptyList()) { index.recentlyAdded(SOURCE, limit) }

    /** How many artists, albums and tracks the library window lists; null without the permission. */
    suspend fun counts(): Triple<Int, Int, Int>? = read(null) { index.counts(SOURCE) }

    /**
     * Answers from the index once it is level with the phone; [none] without the permission,
     * and when the permission is taken away during the read.
     */
    private suspend fun <T> read(
        none: T,
        answer: () -> T,
    ): T {
        if (!available) return none
        return withContext(io) {
            try {
                refresh()
                answer()
            } catch (refused: SecurityException) {
                Log.i(TAG, "music access went away mid-read", refused)
                none
            }
        }
    }

    private fun artistOf(artist: Shelf.ShelfArtist) =
        LibraryArtist(artist.id, nameOf(artist.id, artist.name), albumCount = artist.albumCount, trackCount = artist.trackCount)

    private fun albumOf(album: Shelf.ShelfAlbum) =
        LibraryAlbum(
            id = album.id,
            title = album.title.ifEmpty { words.get(R.string.library_unknown_album) },
            artist = nameOf(album.artistId, album.artist),
            year = album.year,
            trackCount = album.trackCount,
        )

    /** An artist's name, with the two artists the files do not name called what they are. */
    private fun nameOf(
        id: String,
        name: String,
    ) = when (id) {
        ShelfBuilder.VARIOUS -> words.get(R.string.library_various_artists)
        ShelfBuilder.UNKNOWN -> words.get(R.string.library_unknown_artist)
        else -> name
    }

    /**
     * Follows the phone's audio for as long as the process runs: a change marks the index
     * dirty and, after the phone has been quiet for [SETTLE_MS], reads it again, so the library
     * is up to date when it is next looked at. A scan of many files is one read, not many. It
     * also checks once as it starts, for music added while the app was closed.
     */
    fun follow(context: Context) {
        val scope = CoroutineScope(SupervisorJob() + io)
        var settling: Job? = null
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    markDirty()
                    settling?.cancel()
                    settling =
                        scope.launch {
                            delay(SETTLE_MS)
                            // only a library that has been opened is kept up to date
                            if (available && index.stamp(SOURCE) != null) runCatching { refresh() }
                        }
                }
            }
        context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
        // music added while the app was closed is read soon after it starts, not when the
        // library is next opened
        observer.onChange(false)
    }

    companion object {
        /** The index's name for the phone's shelf. */
        const val SOURCE = "phone"

        /**
         * The version of how [ShelfBuilder] groups the files. It is part of the stamp, so a
         * change to the grouping reads the phone again.
         */
        private const val SHELF_VERSION = 1

        /** The longest the library answers without checking the phone, when nothing said it changed. */
        const val CHECK_EVERY_MS = 30_000L

        /** How long the phone's audio is quiet before a change is read. */
        const val SETTLE_MS = 3_000L

        private const val TAG = "IndexedPhoneLibrary"

        private val NOTHING_FOUND = SectionedSearch.Sections(emptyList(), emptyList(), emptyList())

        /** The phone's library over the index in [index], reading the phone's media index. */
        fun of(
            context: Context,
            index: LibraryIndex,
        ): IndexedPhoneLibrary {
            val app = context.applicationContext
            val access = MediaStoreAudio(app)
            return IndexedPhoneLibrary(access::hasPermission, PhoneShelfReader(app.contentResolver), index, Words.of(app))
        }
    }
}
