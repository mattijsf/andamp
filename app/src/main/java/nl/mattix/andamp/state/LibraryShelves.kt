// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import nl.mattix.andamp.R
import nl.mattix.andamp.core.model.AlbumKind
import nl.mattix.andamp.core.model.LibraryAlbum
import nl.mattix.andamp.core.model.LibraryArtist
import nl.mattix.andamp.core.model.LibraryPlaylist
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.durationSec
import nl.mattix.andamp.core.playback.BrowseSource
import nl.mattix.andamp.state.LibraryOps.Category
import nl.mattix.andamp.state.LibraryOps.Row
import kotlin.coroutines.CoroutineContext

/**
 * The media library's pages, made from what a source answers and from the lists and stations
 * this app keeps: the tabs' roots, an artist's records, a record's tracks, search results.
 *
 * It makes pages and does not move between them. A row that opens a level hands [drill] the
 * fetch of the page it opens into, and [LibraryOps] decides where that page lands.
 *
 * A page made from a question the source stopped answering part way says so
 * ([LibraryPage.incomplete]), as [wholeOf] tells.
 */
@Suppress("TooManyFunctions") // one per kind of page
internal class LibraryShelves(
    private val words: Words,
    private val lists: PlaylistLibrary,
    private val stations: StationStore,
    private val io: CoroutineContext,
    /** Told the rows of a saved list once they are read; see [LibraryOps]. */
    private val onListRead: suspend (List<Track>) -> Unit,
    /** Whether the source answered its last question all the way through. */
    private val wholeOf: (BrowseSource) -> Boolean,
    /** Told, as a tab's root asks the source, whether the library needs a permission first. */
    private val access: (needed: Boolean) -> Unit,
    /** Opens a level: the header it lands under, and the fetch of its page. */
    private val drill: (String, suspend () -> LibraryPage) -> Unit,
    /** The lists the library makes itself for the library on show; see [SmartList]. */
    private val smartLists: suspend (BrowseSource) -> List<SmartList> = { emptyList() },
    /** The order the ALBUMS page is in; see [AlbumOrder]. */
    private val order: () -> AlbumOrder = { AlbumOrder.TITLE },
    /** Writes a row of each song in the listener's [RowFormat]. */
    private val songs: SongWords = SongWords({ RowFormat.DEFAULT }) { emptyMap() },
) {
    private fun label(category: Category): String = words.get(category.label)

    /** The header of the search, and of a search the source could not answer. */
    val searchHeader: String get() = words.get(R.string.library_search_header)

    /** The page for an empty or too-short query: the FIND tab's when [finding], the library's search's otherwise. */
    fun prompt(finding: Boolean) =
        if (finding) {
            LibraryPage(label(Category.FIND), "", emptyList(), empty = words.get(R.string.library_type_an_artist))
        } else {
            LibraryPage(searchHeader, "", emptyList(), empty = words.get(R.string.library_type_to_search))
        }

    /** A search's hits, as a page of tracks. */
    suspend fun results(hits: List<Track>): LibraryPage {
        val capped = hits.size >= BrowseSource.SEARCH_LIMIT
        val count =
            if (capped) {
                words.get(R.string.library_hits_capped, BrowseSource.SEARCH_LIMIT)
            } else {
                count(hits.size, R.plurals.library_count_hits)
            }
        val time = if (hits.isEmpty() || capped) "" else " · ${clock(hits.sumOf { it.durationSec })}"
        return LibraryPage(
            header = searchHeader,
            status = count + time,
            rows = songRows(hits),
            tracks = hits,
            empty = words.get(R.string.library_no_matches),
            restyled = { results(hits) },
        )
    }

    /** A row for each of [tracks]: its song in the row format, numbered from one when [numbered], and its length. */
    private suspend fun songRows(
        tracks: List<Track>,
        numbered: Boolean = false,
    ): List<Row> =
        songs.of(tracks).mapIndexed { at, song ->
            Row(if (numbered) "${at + 1}. $song" else song, detail = clock(tracks[at].durationSec))
        }

    /**
     * What a search found in a library that finds artists and albums too ([SectionedSearch]):
     * a section of each kind that found anything, under a heading with its count. An artist or
     * an album opens as from its tab; the tracks play as a page of their own.
     */
    suspend fun sections(
        src: BrowseSource,
        found: SectionedSearch.Sections,
    ): LibraryPage {
        val rows = mutableListOf<Row>()
        val open = mutableListOf<(() -> Unit)?>()
        val tracksOf = mutableListOf<suspend () -> List<Track>>()
        val headings = mutableSetOf<Int>()

        fun heading(text: String) {
            headings += rows.size
            rows += Row(text)
            open += null
            tracksOf += { emptyList() }
        }
        if (found.artists.isNotEmpty()) {
            heading(count(found.artists.size, R.plurals.library_count_artists))
            for (artist in found.artists) {
                rows += Row(artist.name, detail = artist.albumCount?.let { count(it, R.plurals.library_count_albums) } ?: "")
                open += { drill(artist.name) { albumsPage(src, artist) } }
                tracksOf += { gather(src.albums(artist.id).map { album -> { src.tracks(album.id) } }) }
            }
        }
        if (found.albums.isNotEmpty()) {
            heading(count(found.albums.size, R.plurals.library_count_albums))
            for (album in found.albums) {
                rows += Row(album.title, detail = album.artist, marks = marksOf(src, album.id))
                open += { drill(album.title) { tracksPage(src, album) } }
                tracksOf += { src.tracks(album.id) }
            }
        }
        if (found.tracks.isNotEmpty()) heading(count(found.tracks.size, R.plurals.library_count_tracks))
        val firstTrack = rows.size
        rows += songRows(found.tracks)
        val hits = found.artists.size + found.albums.size + found.tracks.size
        return LibraryPage(
            header = searchHeader,
            status = count(hits, R.plurals.library_count_hits),
            rows = rows,
            open = open,
            tracksOf = tracksOf,
            tracks = found.tracks,
            firstTrack = firstTrack,
            headings = headings,
            empty = words.get(R.string.library_no_matches),
            restyled = { sections(src, found) },
        )
    }

    /** [albums] in [order]: as the source listed them by title, or by artist, or by year with the undated last. */
    private fun ordered(
        albums: List<LibraryAlbum>,
        order: AlbumOrder,
    ): List<LibraryAlbum> =
        when (order) {
            AlbumOrder.TITLE -> albums
            AlbumOrder.ARTIST -> albums.sortedWith(compareBy({ ShelfBuilder.fold(it.artist) }, { ShelfBuilder.fold(it.title) }))
            AlbumOrder.YEAR -> albums.sortedWith(compareBy({ it.year ?: Int.MAX_VALUE }, { ShelfBuilder.fold(it.title) }))
        }

    /** The letter [name] files under for the readout: its first letter, or # for a digit. */
    private fun initial(name: String): String {
        val first = name.firstOrNull { it.isLetterOrDigit() } ?: return ""
        return if (first.isDigit()) "#" else first.uppercase()
    }

    /** [album]'s page of tracks, as a row for it opens it. */
    suspend fun albumPage(
        src: BrowseSource,
        album: LibraryAlbum,
    ): LibraryPage = tracksPage(src, album)

    /** [artist]'s page of records, as a row for them opens it. */
    suspend fun artistPage(
        src: BrowseSource,
        artist: LibraryArtist,
    ): LibraryPage = albumsPage(src, artist)

    /** The root page of the tab [target], over [src]. */
    suspend fun root(
        target: Category,
        src: BrowseSource,
    ): LibraryPage =
        when (target) {
            Category.ARTISTS -> artistsRootPage(src)

            Category.ALBUMS -> albumsRootPage(src)

            Category.LISTS -> listsRootPage(src)

            Category.RADIO -> radio()

            // the FIND tab has no root page other than its prompt
            Category.FIND -> prompt(finding = true)
        }

    private suspend fun artistsRootPage(src: BrowseSource): LibraryPage {
        if (!src.available) {
            access(true)
            return LibraryPage(label(Category.ARTISTS), count(0, R.plurals.library_count_artists), emptyList())
        }
        access(false)
        val artists = src.artists()
        // checked again: access can be revoked while the read runs, and the empty answer
        // then means no permission
        if (!src.available) {
            access(true)
            return LibraryPage(label(Category.ARTISTS), count(0, R.plurals.library_count_artists), emptyList())
        }
        // a long shelf says "1 ALBUM" a thousand times; each count is worded once
        val worded = HashMap<Int, String>()
        val page =
            artists(src, label(Category.ARTISTS), artists, incomplete = src.partial()) { artist ->
                artist.albumCount?.let { n -> worded.getOrPut(n) { count(n, R.plurals.library_count_albums) } } ?: ""
            }
        return if (src is EveryTrack && artists.isNotEmpty()) withEverything(src, page, artists.size) else page
    }

    /**
     * [page] with a first row that stands for every track of the library, as Winamp's artist
     * pane starts with All: it opens them all, and plays or enqueues them all.
     */
    private fun withEverything(
        src: EveryTrack,
        page: LibraryPage,
        artists: Int,
    ): LibraryPage {
        val all = words.get(R.string.library_all_header)
        return LibraryPage(
            header = page.header,
            status = page.status,
            rows = listOf(Row(words.get(R.string.library_all, count(artists, R.plurals.library_count_artists)))) + page.rows,
            open = listOf<(() -> Unit)?>({ drill(all) { everyTrackPage(all, src.everyTrack()) } }) + page.open,
            tracksOf = listOf<suspend () -> List<Track>>({ src.everyTrack() }) + page.tracksOf,
            empty = page.empty,
            incomplete = page.incomplete,
        )
    }

    private suspend fun everyTrackPage(
        header: String,
        tracks: List<Track>,
    ) = trackListPage(header, tracks, empty = words.get(R.string.library_no_tracks), byArtist = true)

    /** Whether [this] stopped part way through the question it last answered; see [wholeOf]. */
    private fun BrowseSource.partial() = !wholeOf(this)

    /**
     * Artists as a page, for the listener's own shelf and for the FIND tab's results
     * alike: each row opens into the artist's records, and stands for every track under
     * them.
     */
    @Suppress("LongParameterList") // the page's parts, and how its rows are worded
    fun artists(
        src: BrowseSource,
        header: String,
        artists: List<LibraryArtist>,
        empty: String? = null,
        incomplete: Boolean = false,
        detail: (LibraryArtist) -> String = { "" },
    ) = LibraryPage(
        header = header,
        status = count(artists.size, R.plurals.library_count_artists),
        rows = artists.map { Row(it.name, detail = detail(it)) },
        open = artists.map { artist -> { drill(artist.name) { albumsPage(src, artist) } } },
        tracksOf = artists.map { artist -> { gather(src.albums(artist.id).map { album -> { src.tracks(album.id) } }) } },
        empty = empty,
        incomplete = incomplete,
    )

    private suspend fun albumsRootPage(src: BrowseSource): LibraryPage {
        if (!src.available) {
            access(true)
            return LibraryPage(label(Category.ALBUMS), count(0, R.plurals.library_count_albums), emptyList())
        }
        access(false)
        val order = order()
        val albums = ordered(src.albums(null), order)
        // checked again, as for the artists' root
        if (!src.available) {
            access(true)
            return LibraryPage(label(Category.ALBUMS), count(0, R.plurals.library_count_albums), emptyList())
        }
        return LibraryPage(
            header = label(Category.ALBUMS),
            incomplete = src.partial(),
            status = count(albums.size, R.plurals.library_count_albums),
            // the artist as the detail, since two albums may share a title; by year, the year
            rows =
                albums.map {
                    val detail = if (order == AlbumOrder.YEAR) it.year?.toString() ?: "" else it.artist
                    Row(it.title, detail = detail, marks = marksOf(src, it.id))
                },
            readout =
                when (order) {
                    AlbumOrder.TITLE -> emptyList()
                    AlbumOrder.ARTIST -> albums.map { initial(it.artist) }
                    AlbumOrder.YEAR -> albums.map { it.year?.toString() ?: "" }
                },
            open = albums.map { album -> { drill(album.title) { tracksPage(src, album) } } },
            tracksOf = albums.map { album -> { src.tracks(album.id) } },
        )
    }

    /**
     * The LISTS tab's root, for the source the window is showing
     * ([LibrarySources.showing]). A source that keeps its own playlists shows those; one
     * that does not shows the lists this app has saved as files. The two are never mixed.
     */
    private suspend fun listsRootPage(src: BrowseSource): LibraryPage {
        val lists =
            when {
                // every source together: the lists this app saved, then each source's own
                src is AllLibrary && src.capabilities.hasPlaylists -> together(savedLists(), sourceListsPage(src))

                src.capabilities.hasPlaylists -> sourceListsPage(src)

                else -> savedLists()
            }
        return withSmartLists(src, smartLists(src), withFoldersAndGenres(src, lists))
    }

    /**
     * FOLDERS and GENRES at the top of [lists], for a library that knows them
     * ([FoldersAndGenres]): each opens a page of its folders or genres, and each of those its
     * tracks. A view with nothing in it is left out.
     */
    private suspend fun withFoldersAndGenres(
        src: BrowseSource,
        lists: LibraryPage,
    ): LibraryPage {
        if (src !is FoldersAndGenres) return lists
        val views =
            listOfNotNull(
                src.folders().takeIf { it.isNotEmpty() }?.let { folders ->
                    View(words.get(R.string.library_folders), folders, src::folderTracks) { it.trimEnd('/') }
                },
                src.genres().takeIf { it.isNotEmpty() }?.let { genres ->
                    View(words.get(R.string.library_genres), genres, src::genreTracks) { it }
                },
            )
        if (views.isEmpty()) return lists
        // every source together: folders and genres one source alone gives carry its mark
        val mark = (src as? SourceMarks)?.let { marks -> marks.foldersAndGenresFrom()?.let(marks::markOfSource) }
        return LibraryPage(
            header = lists.header,
            status = count(views.size + lists.rows.size, R.plurals.library_count_lists),
            rows = views.map { Row(it.name, detail = it.groups.size.toString(), marks = listOfNotNull(mark)) } + lists.rows,
            open = views.map<View, (() -> Unit)?> { view -> { drill(view.name) { groupsPage(view) } } } + lists.open,
            tracksOf = views.map<View, suspend () -> List<Track>> { { emptyList() } } + lists.tracksOf,
            saved = views.map { null } + lists.saved,
            empty = lists.empty,
            incomplete = lists.incomplete,
        )
    }

    /** FOLDERS or GENRES: its [groups] with their counts, the tracks of each, and how a group's name reads. */
    private class View(
        val name: String,
        val groups: List<Pair<String, Int?>>,
        val tracksOf: suspend (String) -> List<Track>,
        val shown: (String) -> String,
    )

    /** A view's folders or genres, each opening as a page of its tracks. */
    private fun groupsPage(view: View) =
        LibraryPage(
            header = view.name,
            status = count(view.groups.size, R.plurals.library_count_lists),
            rows =
                view.groups.map { (group, n) ->
                    Row(view.shown(group), detail = n?.let { words.get(R.string.library_track_count_short, it) } ?: "")
                },
            open =
                view.groups.map { (group, _) ->
                    {
                        drill(view.shown(group)) {
                            trackListPage(
                                view.shown(group),
                                view.tracksOf(group),
                                words.get(R.string.library_no_tracks),
                                byArtist = true,
                            )
                        }
                    }
                },
            tracksOf = view.groups.map { (group, _) -> { view.tracksOf(group) } },
            empty = words.get(R.string.library_no_lists),
        )

    /**
     * The lists the library makes itself, at the top of [lists], the way Winamp's library
     * tree has its views above the playlists. Each opens as a page of tracks.
     */
    private fun withSmartLists(
        src: BrowseSource,
        smart: List<SmartList>,
        lists: LibraryPage,
    ): LibraryPage {
        if (smart.isEmpty()) return lists
        // every source together: a list whose songs all come from one source carries its mark
        val marks = src as? SourceMarks
        return LibraryPage(
            header = lists.header,
            status = count(smart.size + lists.rows.size, R.plurals.library_count_lists),
            rows =
                smart.map {
                    Row(
                        it.name,
                        detail = words.get(R.string.library_track_count_short, it.entries.size),
                        marks = listOfNotNull(it.from?.let { from -> marks?.markOfSource(from) }),
                    )
                } + lists.rows,
            open = smart.map { list -> { drill(list.name) { smartPage(list) } } } + lists.open,
            tracksOf =
                smart.map<SmartList, suspend () -> List<Track>> { list -> { list.entries.map { it.first } } } + lists.tracksOf,
            saved = smart.map { null } + lists.saved,
            empty = lists.empty,
            incomplete = lists.incomplete,
        )
    }

    /** A list the library made, as a page of tracks, each with what its entry says on the right. */
    private suspend fun smartPage(list: SmartList): LibraryPage {
        val tracks = list.entries.map { it.first }
        val time = if (tracks.isEmpty()) "" else " · ${clock(tracks.sumOf { it.durationSec })}"
        return LibraryPage(
            header = list.name,
            status = count(tracks.size, R.plurals.library_count_tracks) + time,
            rows =
                songRows(tracks, numbered = true).mapIndexed { at, row ->
                    list.entries[at].second?.let { row.copy(detail = it) } ?: row
                },
            tracks = tracks,
            empty = words.get(R.string.library_no_tracks),
            restyled = { smartPage(list) },
        )
    }

    /** The saved lists and the sources' lists as one page, the saved ones first, as [LibraryPage.saved] wants. */
    private fun together(
        saved: LibraryPage,
        theirs: LibraryPage,
    ) = LibraryPage(
        header = label(Category.LISTS),
        status = count(saved.rows.size + theirs.rows.size, R.plurals.library_count_lists),
        rows = saved.rows + theirs.rows,
        open = saved.open + theirs.open,
        tracksOf = saved.tracksOf + theirs.tracksOf,
        saved = saved.saved,
        empty = saved.empty,
        incomplete = theirs.incomplete,
    )

    /** The marks of the sources that have the album, when every source is shown together ([SourceMarks]). */
    private fun marksOf(
        src: BrowseSource,
        albumId: String,
    ): List<SourceMark> = (src as? SourceMarks)?.marksOf(albumId).orEmpty()

    private suspend fun sourceListsPage(src: BrowseSource): LibraryPage {
        val their = src.playlists()
        return LibraryPage(
            header = label(Category.LISTS),
            incomplete = src.partial(),
            status = count(their.size, R.plurals.library_count_lists),
            rows =
                their.map {
                    Row(
                        it.name,
                        detail =
                            it.trackCount?.let { n -> words.get(R.string.library_track_count_short, n) } ?: "",
                        // every source together: whose list it is
                        marks = listOfNotNull((src as? SourceMarks)?.markOfList(it.id)),
                    )
                },
            open = their.map { list -> { drill(list.name) { sourceListPage(src, list) } } },
            tracksOf = their.map { list -> { src.playlistTracks(list.id) } },
            empty = words.get(R.string.library_no_lists),
        )
    }

    private suspend fun sourceListPage(
        src: BrowseSource,
        list: LibraryPlaylist,
    ): LibraryPage {
        val tracks = src.playlistTracks(list.id)
        return trackListPage(
            list.name,
            tracks,
            empty = words.get(R.string.library_no_tracks),
            incomplete = src.partial(),
            byArtist = true,
        )
    }

    /** The lists this app has saved as files, as LISTS shows them for a source without lists of its own. */
    suspend fun savedLists(): LibraryPage {
        val saved = withContext(io) { lists.list() }
        return LibraryPage(
            header = label(Category.LISTS),
            status = count(saved.size, R.plurals.library_count_lists),
            rows = saved.map { Row(it.name, detail = words.get(R.string.library_track_count_short, it.trackCount)) },
            open = saved.map { entry -> { drill(entry.name) { listTracksPage(entry.name) } } },
            tracksOf = saved.map { entry -> { withContext(io) { lists.load(entry.name) }.orEmpty().also { onListRead(it) } } },
            saved = saved.map { it.name },
            empty = words.get(R.string.library_no_saved_lists),
        )
    }

    private suspend fun listTracksPage(name: String): LibraryPage {
        val tracks = withContext(io) { lists.load(name) }.orEmpty().also { onListRead(it) }
        return trackListPage(name, tracks, empty = words.get(R.string.library_no_tracks), savedName = name, byArtist = true)
    }

    /** RADIO's one page: the stations, playable as a page of tracks. */
    suspend fun radio(): LibraryPage {
        // one level, no drill: the radio root is itself a playable page, so next and
        // previous step through the stations
        val all = withContext(io) { stations.list() }
        return LibraryPage(
            header = label(Category.RADIO),
            status = count(all.size, R.plurals.library_count_stations),
            rows = all.map { Row(it.title) },
            tracks = all,
            empty = words.get(R.string.library_no_stations),
        )
    }

    /**
     * An artist's records, or the kinds of record first. When a source tells albums,
     * singles and compilations apart and the artist has more than one kind, the kinds
     * are a level of their own; otherwise the records are listed directly.
     */
    private suspend fun albumsPage(
        src: BrowseSource,
        artist: LibraryArtist,
    ): LibraryPage {
        val albums = src.albums(artist.id)
        val incomplete = src.partial()
        val kinds = AlbumKind.entries.mapNotNull { kind -> albums.filter { it.kind == kind }.takeIf { it.isNotEmpty() } }
        if (kinds.size < 2) {
            // a single kind is counted under its own noun; a source that does not tell
            // kinds apart reports everything as an album
            val kind = albums.firstOrNull()?.kind ?: AlbumKind.ALBUM
            return recordsPage(src, artist.name, albums, kind.noun, incomplete)
        }
        return LibraryPage(
            header = artist.name,
            incomplete = incomplete,
            status = count(albums.size, R.plurals.library_count_records),
            rows =
                kinds.map { of ->
                    Row(words.get(of.first().kind.rowLabel), detail = count(of.size, R.plurals.library_count_records))
                },
            open =
                kinds.map { of ->
                    { drill(artist.name) { recordsPage(src, artist.name, of, of.first().kind.noun, incomplete) } }
                },
            tracksOf = kinds.map { of -> { gather(of.map { album -> { src.tracks(album.id) } }) } },
        )
    }

    /** Records, counted under the plural [noun], so a page of singles says "5 SINGLES". */
    private fun recordsPage(
        src: BrowseSource,
        header: String,
        albums: List<LibraryAlbum>,
        noun: Int,
        incomplete: Boolean,
    ) = LibraryPage(
        header = header,
        incomplete = incomplete,
        status = count(albums.size, noun),
        rows = albums.map { Row(it.title, detail = it.year?.toString() ?: "", marks = marksOf(src, it.id)) },
        open = albums.map { album -> { drill(album.title) { tracksPage(src, album) } } },
        tracksOf = albums.map { album -> { src.tracks(album.id) } },
    )

    private suspend fun tracksPage(
        src: BrowseSource,
        album: LibraryAlbum,
    ): LibraryPage {
        val tracks = src.tracks(album.id)
        // a record of one artist names only the songs; a compilation names whose each one is
        val many = tracks.map { ShelfBuilder.fold(it.artist) }.distinct().size > 1
        return trackListPage(
            album.title,
            tracks,
            empty = words.get(R.string.library_no_tracks),
            incomplete = src.partial(),
            byArtist = many,
        )
    }

    /**
     * A page of [tracks], each row its number and its title, or its song in the row format when
     * [byArtist]: a record of one artist names only its songs, as its header names the record.
     */
    @Suppress("LongParameterList") // the page's parts, and how its rows are worded
    private suspend fun trackListPage(
        header: String,
        tracks: List<Track>,
        empty: String,
        savedName: String? = null,
        incomplete: Boolean = false,
        byArtist: Boolean = false,
    ): LibraryPage {
        val time = if (tracks.isEmpty()) "" else " · ${clock(tracks.sumOf { it.durationSec })}"
        return LibraryPage(
            header = header,
            status = count(tracks.size, R.plurals.library_count_tracks) + time,
            rows =
                if (byArtist) {
                    songRows(tracks, numbered = true)
                } else {
                    tracks.mapIndexed { at, track -> Row("${at + 1}. ${track.title}", detail = clock(track.durationSec)) }
                },
            tracks = tracks,
            empty = empty,
            savedName = savedName,
            incomplete = incomplete,
            restyled = if (byArtist) ({ trackListPage(header, tracks, empty, savedName, incomplete, byArtist) }) else null,
        )
    }

    /**
     * What every fetch in [each] answers, in order. A few run at once, so an artist with many
     * records does not wait for one record at a time; the first that cannot be answered
     * stops the rest.
     */
    suspend fun gather(each: List<suspend () -> List<Track>>): List<Track> =
        coroutineScope {
            val gate = Semaphore(FETCHES_AT_ONCE)
            each.map { fetch -> async { gate.withPermit { fetch() } } }.awaitAll().flatten()
        }

    /** [n] under the plural [noun], as "1 ARTIST" or "5 ARTISTS". */
    private fun count(
        n: Int,
        noun: Int,
    ) = words.plural(noun, n)

    private fun clock(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)

    private companion object {
        /** How many of a page's fetches run at once; see [gather]. */
        const val FETCHES_AT_ONCE = 4

        /** The plural of a kind, for [count], which makes "1 SINGLE" and "5 SINGLES" of it. */
        val AlbumKind.noun: Int
            get() =
                when (this) {
                    AlbumKind.ALBUM -> R.plurals.library_count_albums
                    AlbumKind.SINGLE -> R.plurals.library_count_singles
                    AlbumKind.COMPILATION -> R.plurals.library_count_compilations
                }

        /** A kind as a row, under an artist whose records are of more than one kind. */
        val AlbumKind.rowLabel: Int
            get() =
                when (this) {
                    AlbumKind.ALBUM -> R.string.library_kind_albums
                    AlbumKind.SINGLE -> R.string.library_kind_singles
                    AlbumKind.COMPILATION -> R.string.library_kind_compilations
                }
    }
}

/**
 * A list the library makes itself rather than one the listener saved: what was added lately,
 * played most, played last, not played for a while, or bookmarked. Its [entries] are each a
 * track and what its row says on the right, or null for the track's length.
 */
class SmartList(
    val name: String,
    val entries: List<Pair<Track, String?>>,
) {
    /** The one source every entry comes from; null when they come from several. */
    val from: MusicSource? = entries.map { SourceForRows.of(it.first) }.distinctBy { it.id }.singleOrNull()
}
