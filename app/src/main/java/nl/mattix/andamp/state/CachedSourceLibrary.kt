// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.mattix.andamp.core.model.BrowseCapabilities
import nl.mattix.andamp.core.model.LibraryAlbum
import nl.mattix.andamp.core.model.LibraryArtist
import nl.mattix.andamp.core.model.LibraryPlaylist
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.playback.BrowseSource
import nl.mattix.andamp.core.playback.SourceUnreachable
import nl.mattix.andamp.state.LibraryIndex.Piece
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/** A library that can say whether its last answer was all the source had. */
interface PartialAnswers {
    /** False when the last answer stopped part way, as a walk over a server can. */
    val whole: Boolean
}

/**
 * A source behind a server, kept in the library's own index ([LibraryIndex]) a piece at a time:
 * its artists, its albums, an artist's albums and an album's tracks.
 *
 * A question the index holds the answer to is answered from it, at once and without the
 * network. The source is asked again behind that answer when the answer is older than
 * [MAX_AGE_MS], or was not asked yet since the app started; a different answer is stored and
 * raises [revision], so the window shows it. A question the index does not hold is asked of the
 * source and stored. When the source cannot be reached a stored answer is still given; without
 * one, [SourceUnreachable] goes up as the browse contract says.
 *
 * What is not the listener's library passes through untouched: the catalog ([findArtists], and
 * the albums and tracks of an artist or album the library does not have), the source's own
 * lists, and search, which falls back to the index only when the source cannot be reached.
 *
 * [GroupedSourceLibrary] adds the folders and genres of a source that gives them.
 */
open class CachedSourceLibrary(
    /** The source's name in the index. */
    protected val key: String,
    private val upstream: BrowseSource,
    protected val index: LibraryIndex,
    /** Where the questions asked behind a stored answer run; lives as long as the process. */
    private val scope: CoroutineScope,
    protected val io: CoroutineContext = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Whether [upstream] answered its last question all the way through. */
    private val wholeOf: (BrowseSource) -> Boolean = { true },
) : BrowseSource,
    SectionedSearch,
    ShelfUpdates,
    PartialAnswers,
    TrackHomes {
    override val capabilities: BrowseCapabilities get() = upstream.capabilities

    override val available: Boolean get() = upstream.available

    private val _revision = MutableStateFlow(0)
    override val revision: StateFlow<Int> = _revision.asStateFlow()

    @Volatile
    final override var whole: Boolean = true
        private set

    /** The pieces asked of the source since the app started; see the class KDoc. */
    private val asked = ConcurrentHashMap.newKeySet<Piece>()

    /** The pieces being asked behind a stored answer right now, so one is not asked twice at once. */
    private val asking = ConcurrentHashMap.newKeySet<Piece>()

    override suspend fun artists(): List<LibraryArtist> =
        cached(
            Piece.ARTISTS,
            read = { index.artists(key).map(::artistOf) },
            ask = { upstream.artists() },
            store = { artists, at -> index.storeArtists(key, artists.map(::shelfArtist), at) },
        )

    override suspend fun albums(artistId: String?): List<LibraryAlbum> {
        if (artistId == null) {
            return cached(
                Piece.ALBUMS,
                read = { index.albums(key).map(::albumOf) },
                ask = { upstream.albums(null) },
                store = { albums, at -> index.storeAlbums(key, albums.map(::shelfAlbum), at) },
            )
        }
        // an artist from the catalog, outside the library, is not kept
        if (!withContext(io) { index.hasArtist(key, artistId) }) return upstream.albums(artistId)
        return cached(
            Piece.artist(artistId),
            read = { index.albums(key, artistId).map(::albumOf) },
            ask = { upstream.albums(artistId) },
            store = { albums, at -> index.storeArtistAlbums(key, artistId, albums.map(::shelfAlbum), at) },
        )
    }

    override suspend fun tracks(albumId: String): List<Track> {
        val album = withContext(io) { index.album(key, albumId) } ?: return upstream.tracks(albumId)
        return cached(
            Piece.album(albumId),
            read = { index.tracks(key, albumId) },
            ask = { upstream.tracks(albumId) },
            store = { tracks, at -> index.storeTracks(key, albumId, album.title, tracks, at) },
        )
    }

    override suspend fun search(
        query: String,
        limit: Int,
    ): List<Track> =
        try {
            upstream.search(query, limit)
        } catch (unreachable: SourceUnreachable) {
            // nothing kept to fall back on is the same as the source not answering
            if (withContext(io) { index.stored(key, Piece.ARTISTS) } == null) throw unreachable
            withContext(io) { index.search(key, query, limit).tracks }
        }

    /**
     * The artists and albums the index keeps that match, with the tracks the source finds, or
     * the tracks the index keeps when the source cannot search or cannot be reached.
     */
    override suspend fun searchSections(
        query: String,
        limit: Int,
    ): SectionedSearch.Sections {
        val kept = withContext(io) { index.search(key, query, limit) }
        val tracks =
            if (!capabilities.canSearch) {
                kept.tracks
            } else {
                try {
                    upstream.search(query, limit)
                } catch (_: SourceUnreachable) {
                    kept.tracks
                }
            }
        return SectionedSearch.Sections(kept.artists.map(::artistOf), kept.albums.map(::albumOf), tracks)
    }

    /** The album the index keeps [track] under, once that album was opened. */
    override suspend fun albumOf(track: Track): LibraryAlbum? =
        withContext(io) { index.homeOf(key, track.id).first?.let(::albumOf) }

    override suspend fun artistOf(track: Track): LibraryArtist? =
        withContext(io) {
            index.homeOf(key, track.id).second?.let(::artistOf)
        }

    override suspend fun playlists(): List<LibraryPlaylist> = upstream.playlists()

    override suspend fun playlistTracks(id: String): List<Track> = upstream.playlistTracks(id)

    override suspend fun findArtists(
        query: String,
        limit: Int,
    ): List<LibraryArtist> = upstream.findArtists(query, limit)

    /**
     * The answer to [piece]: the stored one, with the source asked again behind it when it is
     * due, or the source's, stored. Read back from the index either way, so a page lists in
     * the same order whether it came from the source or from the index.
     *
     * With [first] set to [First.BEHIND], a piece the index does not hold yet is asked behind
     * as well, and answered for now with what the empty index reads; it is shown when it
     * arrives, through [revision]. That is for a piece that takes long to ask and is not the
     * page's to wait for.
     */
    protected suspend fun <T> cached(
        piece: Piece,
        read: () -> T,
        ask: suspend () -> T,
        store: (T, LibraryIndex.Stored) -> Unit,
        first: First = First.WAIT,
    ): T {
        val stored = withContext(io) { index.stored(key, piece) }
        if (stored != null || first == First.BEHIND) {
            val held = withContext(io) { read() }
            if (stored == null) {
                if (asked.add(piece)) askBehind(piece, held, read, ask, store)
                return held
            }
            whole = stored.whole
            val due = clock() - stored.at >= MAX_AGE_MS
            if (asked.add(piece) || due) askBehind(piece, held, read, ask, store)
            return held
        }
        val fresh = ask()
        val at = LibraryIndex.Stored(clock(), wholeOf(upstream))
        asked += piece
        whole = at.whole
        return withContext(io) {
            store(fresh, at)
            read()
        }
    }

    /** Asks the source for [piece] behind the [held] answer; a different answer is stored and shown. */
    private fun <T> askBehind(
        piece: Piece,
        held: T,
        read: () -> T,
        ask: suspend () -> T,
        store: (T, LibraryIndex.Stored) -> Unit,
    ) {
        if (!asking.add(piece)) return
        scope.launch {
            try {
                val fresh = ask()
                val at = LibraryIndex.Stored(clock(), wholeOf(upstream))
                val changed =
                    withContext(io) {
                        store(fresh, at)
                        read() != held
                    }
                if (changed) _revision.value++
            } catch (_: SourceUnreachable) {
                // the stored answer stays; the source is asked again next time
                asked -= piece
            } finally {
                asking -= piece
            }
        }
    }

    private fun artistOf(artist: Shelf.ShelfArtist) =
        LibraryArtist(artist.id, artist.name, albumCount = artist.albumCount.known(), trackCount = artist.trackCount.known())

    private fun albumOf(album: Shelf.ShelfAlbum) =
        LibraryAlbum(album.id, album.title, album.artist, album.year, album.trackCount.known(), album.kind)

    private fun shelfArtist(artist: LibraryArtist) =
        Shelf.ShelfArtist(
            id = artist.id,
            name = artist.name,
            key = ShelfBuilder.fold(artist.name),
            albumCount = artist.albumCount ?: UNKNOWN,
            trackCount = artist.trackCount ?: UNKNOWN,
        )

    private fun shelfAlbum(album: LibraryAlbum) =
        Shelf.ShelfAlbum(
            id = album.id,
            title = album.title,
            key = ShelfBuilder.fold(album.title),
            artistId = "",
            artist = album.artist,
            year = album.year,
            trackCount = album.trackCount ?: UNKNOWN,
            addedAt = 0,
            kind = album.kind,
        )

    /** A count the source gave, or null for the one it did not. */
    private fun Int.known(): Int? = takeIf { it != UNKNOWN }

    /** How a piece the index does not hold yet is answered; see [cached]. */
    protected enum class First {
        /** Asked of the source, and the answer waited for. */
        WAIT,

        /** Asked behind an empty answer. */
        BEHIND,
    }

    companion object {
        /** How old a stored answer may be before the source is asked again behind it. */
        const val MAX_AGE_MS = 60 * 60 * 1000L

        /** A count the source did not give. */
        private const val UNKNOWN = -1
    }
}
