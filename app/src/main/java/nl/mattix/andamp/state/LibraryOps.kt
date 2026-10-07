// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.mattix.andamp.core.model.AlbumKind
import nl.mattix.andamp.core.model.LibraryAlbum
import nl.mattix.andamp.core.model.LibraryArtist
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.durationSec
import nl.mattix.andamp.core.playback.BrowseSource
import nl.mattix.andamp.core.playback.SourceUnreachable
import nl.mattix.andamp.core.player.PlayerFacade

/**
 * The media library's drill-down: up to five categories along the top, one list at a time
 * under them, over the library the listener picked to show ([LibrarySources.showing]),
 * which need not be the one that is playing.
 *
 * ARTISTS and ALBUMS browse the shown source through [BrowseSource], and FIND searches its
 * catalog, where it has one. LISTS shows the source's own playlists when it keeps any, and
 * the app's saved lists otherwise; RADIO is the listener's stations. The saved lists and
 * the stations ([PlaylistLibrary], [StationStore]) are outside the browse contract.
 *
 * A tap on a track selects it, a second tap (or PLAY) replaces the queue from there, and
 * ADD appends. Search is a level like any other: entering pushes a crumb, so backing out
 * restores the page, scroll and selection it covered. A fetched page replaces the list and
 * the header together, and only the latest fetch asked for lands.
 *
 * A source that could not answer ([SourceUnreachable]) gets a page that says CAN'T REACH
 * and the source's name, and a tap on it asks again. An empty answer gets its own text
 * (NO ARTISTS). Nothing here caches answers.
 */
@Suppress("TooManyFunctions") // the categories, the search flow and the row actions are all here
class LibraryOps(
    /**
     * The library to browse, asked for each time, because the listener can pick another
     * source from the main menu while the window is open. [Libraries] caches the answer.
     */
    private val sourceOf: () -> BrowseSource,
    private val facade: PlayerFacade,
    private val state: WinampState,
    private val scope: CoroutineScope,
    private val lists: PlaylistLibrary,
    private val stations: StationStore,
    /**
     * The name of the library being shown, for the page that says it could not be
     * reached. A [BrowseSource] has no name; this is the label of the menu entry the
     * listener picked. Blank gives "the library".
     */
    private val nameOf: () -> String = { "" },
) {
    /**
     * The library as it is now; see [sourceOf]. It is read once per page and handed to
     * that page's rows, because a row's id is valid only for the source that listed it.
     */
    private val source: BrowseSource get() = sourceOf()

    /** The tabs. */
    enum class Category(
        val label: String,
    ) {
        ARTISTS("ARTISTS"),
        ALBUMS("ALBUMS"),
        LISTS("LISTS"),
        RADIO("RADIO"),

        /**
         * A search of the source's catalog, beyond the listener's own shelves. Shown only
         * for a source that has a catalog.
         */
        FIND("FIND"),
    }

    /** One row on screen: text only. */
    data class Row(
        val label: String,
        val detail: String = "",
    )

    /** A level of the drill-down, replaced as one piece when its fetch lands. */
    private class Page(
        val header: String,
        val status: String,
        val rows: List<Row>,
        /** Drill actions, indexed like [rows]; empty at the leaf. */
        val open: List<() -> Unit> = emptyList(),
        /**
         * The tracks each row stands for, indexed like [rows], for a row's menu, which
         * acts on a row without opening it. For an artist or an album this is a fetch.
         * Empty where the leaf's own [tracks] answer it.
         */
        val tracksOf: List<suspend () -> List<Track>> = emptyList(),
        /**
         * The saved list each row stands for, indexed like [rows], on the page of the
         * lists this app keeps as files; empty on every other page. Only these rows can
         * be deleted.
         */
        val saved: List<String> = emptyList(),
        /** The saved list whose tracks this page shows; null on every other page. */
        val savedName: String? = null,
        /** The playable tracks, when this page is a leaf. */
        val tracks: List<Track>? = null,
        /** The text an empty page shows. */
        val empty: String = NO_MUSIC,
        /**
         * Asks again, on a page for a fetch the source could not answer; null on every
         * other page. When it is set, a tap anywhere on the page, or on its own tab,
         * retries.
         */
        val retry: (() -> Unit)? = null,
    )

    /**
     * Where a drill came from, so up() restores it, including the search that was open on
     * it.
     */
    private class Crumb(
        val page: Page,
        val scroll: Int,
        val selection: Int,
        /** What was typed, when the level left was a search; null otherwise. */
        val query: String? = null,
        /** Whether that search was the FIND tab's; see [Category.FIND]. */
        val finding: Boolean = false,
    )

    private var page: Page by mutableStateOf(Page(Category.ARTISTS.label, "", emptyList()))
    private val crumbs = ArrayDeque<Crumb>()

    val rows: List<Row> get() = page.rows

    /** The current level's name: the category at its root, an artist, an album. */
    val header: String get() = page.header

    /** The bottom bar's left text: "245 ARTISTS", "26 TRACKS · 81:09". */
    val status: String get() = page.status

    /** The text an empty list shows, per page. */
    val emptyNote: String get() = page.empty

    /** Whether this page stands for a fetch the source could not answer; a tap retries it. */
    val unreachable: Boolean get() = page.retry != null

    /** Incremented whenever the page changes, so a raster cache can key on it. */
    var revision: Int by mutableIntStateOf(0)
        private set

    var depth: Int by mutableIntStateOf(0)
        private set

    /** A fetch is in flight: the previous rows stay visible and taps are ignored. */
    var loading: Boolean by mutableStateOf(false)
        private set

    /** The library cannot be read; the window offers the permission prompt. */
    var needsAccess: Boolean by mutableStateOf(false)
        private set

    /** A short confirmation ("+N QUEUED") for the bottom bar. */
    var flash: String by mutableStateOf("")
        private set
    private var flashes = 0

    val atTracks: Boolean get() = page.tracks != null

    // --- categories ---

    var category: Category by mutableStateOf(Category.ARTISTS)
        private set

    /**
     * The tabs to draw, in strip order; a source without artists has no ARTISTS tab. Read
     * from the source on each call, like [canSearch], so both follow a newly picked
     * library or a newly available source.
     */
    val visibleCategories: List<Category> get() =
        listOfNotNull(
            Category.ARTISTS.takeIf { source.capabilities.hasArtists },
            Category.ALBUMS.takeIf { source.capabilities.hasAlbums },
            Category.LISTS,
            Category.RADIO,
            Category.FIND.takeIf { source.capabilities.hasCatalogue },
        )

    /**
     * Switches tab, from any depth. The active tab at its own root does nothing; the
     * active tab while deep goes back to its root. The FIND tab's root is its search, so
     * tapping it at its root starts the search afresh.
     */
    fun switchCategory(target: Category) {
        if (loading) return
        if (target == category && home && target != Category.FIND) return
        dropSearch()
        category = target
        crumbs.clear()
        depth = 0
        // the FIND tab has no shelf: it opens on its search prompt
        if (target == Category.FIND) enterFind() else load(target.label) { rootFor(target) }
    }

    /**
     * At a tab's root, with that root on screen. A root that could not be reached does
     * not count, so its own tab retries.
     */
    private val home: Boolean get() = depth == 0 && page.retry == null

    /** The FIND tab: a search at depth zero, for artists. */
    private fun enterFind() {
        abandonLoad()
        searching = true
        finding = true
        query = ""
        caret = 0
        searchEpoch++
        commit(findPromptPage())
    }

    // --- search ---

    var searching: Boolean by mutableStateOf(false)
        private set
    var query: String by mutableStateOf("")
        private set
    var caret: Int by mutableIntStateOf(0)
        private set

    /** Incremented to sync and refocus the text input. */
    var searchEpoch: Int by mutableIntStateOf(0)
        private set
    private var searchJob: Job? = null

    val canSearch: Boolean get() = source.capabilities.canSearch

    /**
     * The header's tap: while searching it brings the keyboard back; deep it goes up; at
     * a searchable root it opens the search.
     */
    fun headerTap() {
        when {
            searching -> searchEpoch++
            depth > 0 -> up()
            canSearch -> enterSearch()
            else -> Unit
        }
    }

    /** Opens the search as a level: it pushes a crumb, and leaving restores it. */
    fun enterSearch() {
        if (!canSearch || searching || loading) return
        abandonLoad()
        crumbs.addLast(Crumb(page, state.libraryScroll, state.librarySelected))
        depth = crumbs.size
        searching = true
        query = ""
        caret = 0
        searchEpoch++
        commit(promptPage())
    }

    /** The text input's mirror: text and caret arrive here on every keystroke. */
    fun setQuery(
        text: String,
        caretAt: Int,
    ) {
        if (!searching) return
        query = text
        caret = caretAt.coerceIn(0, text.length)
        searchJob?.cancel()
        searchJob =
            scope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                if (text.length < MIN_QUERY) {
                    commitInPlace(searchPrompt())
                    return@launch
                }
                val src = source
                val page =
                    try {
                        if (finding) {
                            artistsPage(src, Category.FIND.label, src.findArtists(text), empty = "NO ARTISTS")
                        } else {
                            resultsPage(src.search(text))
                        }
                    } catch (_: SourceUnreachable) {
                        // the retry sets the same query again, so a newer query still wins
                        unreachablePage(if (finding) Category.FIND.label else SEARCH_HEADER) { setQuery(text, caret) }
                    }
                // a slow answer to an old query must not replace a newer one
                if (searching && query == text) commitInPlace(page)
            }
    }

    /** The bar's x: clears a typed query; with an empty one it closes the search. */
    fun searchClear() {
        if (!searching) return
        if (query.isEmpty()) {
            // the FIND tab's root is the search, so there is no level to go up to
            if (finding && depth == 0) return
            up()
        } else {
            query = ""
            caret = 0
            searchEpoch++
            searchJob?.cancel()
            commitInPlace(searchPrompt())
        }
    }

    private fun dropSearch() {
        if (!searching) return
        searching = false
        finding = false
        searchJob?.cancel()
        query = ""
        caret = 0
    }

    /** Restores the search a drill was opened from: which search, its text, and the keyboard. */
    private fun resumeSearch(
        text: String,
        find: Boolean,
    ) {
        searchJob?.cancel()
        searching = true
        finding = find
        query = text
        caret = text.length
        searchEpoch++
    }

    /** Whether the open search is the FIND tab's; see [Category.FIND]. */
    private var finding = false

    private fun promptPage() = Page(SEARCH_HEADER, "", emptyList(), empty = "TYPE TO SEARCH")

    private fun findPromptPage() = Page(Category.FIND.label, "", emptyList(), empty = "TYPE AN ARTIST")

    /** The prompt page for an empty or too-short query, for whichever search is open. */
    private fun searchPrompt() = if (finding) findPromptPage() else promptPage()

    private fun resultsPage(hits: List<Track>): Page {
        val capped = hits.size >= BrowseSource.SEARCH_LIMIT
        val count = if (capped) "${BrowseSource.SEARCH_LIMIT}+ HITS" else count(hits.size, "HIT")
        val time = if (hits.isEmpty() || capped) "" else " · ${clock(hits.sumOf { it.durationSec })}"
        return Page(
            header = SEARCH_HEADER,
            status = count + time,
            rows = hits.map { Row(it.title, detail = it.artist) },
            tracks = hits,
            empty = "NO MATCHES",
        )
    }

    // --- open / navigate ---

    fun open() {
        // this can be called while a page is still loading (a library picked from the main
        // menu, a sign-in, a sign-out); that page is for the previous source
        abandonLoad()
        state.libraryOpen = true
        // raised, so it is not opened behind another window
        state.raiseWindow("library")
        crumbs.clear()
        depth = 0
        needsAccess = false
        dropSearch()
        // the tab it was left on may be one the new source does not have (the phone has no
        // FIND tab)
        if (category !in visibleCategories) category = visibleCategories.first()
        if (category == Category.FIND) enterFind() else load(category.label) { rootFor(category) }
    }

    /**
     * A tap on a row: artist, album and list rows drill in; a track row selects, and a
     * second tap on the selection plays from it.
     */
    fun tapRow(index: Int) {
        if (loading) return
        // a page for an unanswered fetch has no rows; a tap anywhere on it retries
        page.retry?.let { again ->
            again()
            return
        }
        if (index !in page.rows.indices) {
            // a tap on the empty area under the last row clears the selection
            state.librarySelected = -1
            return
        }
        val tracks = page.tracks
        if (tracks == null) {
            page.open.getOrNull(index)?.invoke()
            return
        }
        if (state.librarySelected == index) playFrom(index) else state.librarySelected = index
    }

    /**
     * The tracks a row stands for: the leaf's own entry, or everything under an artist or
     * an album. Empty when the row stands for none.
     */
    private suspend fun tracksOfRow(index: Int): List<Track> {
        val leaf = page.tracks
        if (leaf != null) return listOfNotNull(leaf.getOrNull(index))
        return page.tracksOf
            .getOrNull(index)
            ?.invoke()
            .orEmpty()
    }

    /** The row menu's actions. Each fetches the row's tracks, then acts on them. */
    fun playRow(index: Int) = onRow(index) { chosen -> facade.playQueue(chosen, 0) }

    fun enqueueRow(index: Int) =
        onRow(index) { chosen ->
            facade.enqueue(chosen)
            showFlash("+${chosen.size} QUEUED")
        }

    /**
     * Winamp's Enqueue next: inserts straight after the current track. It is one
     * [PlayerFacade.setQueue] with the current track still in it, which the backend
     * contract says leaves playback alone.
     */
    fun enqueueNextRow(index: Int) =
        onRow(index) { chosen ->
            // read from the backend's queue, not the render state's copy: this runs after
            // a fetch
            val playing = facade.state.value
            val after = (playing.currentIndex + 1).coerceIn(0, playing.queue.size)
            facade.setQueue(playing.queue.take(after) + chosen + playing.queue.drop(after), playing.currentIndex)
            showFlash("+${chosen.size} NEXT")
        }

    // --- deleting a saved list ---

    /** Whether the row is one of the app's saved lists, the only rows that can be deleted. */
    fun canDeleteRow(index: Int): Boolean = index in page.saved.indices

    /** Whether the page shows the tracks of a saved list, which the bar's DEL LIST deletes. */
    val canDeletePage: Boolean get() = page.savedName != null

    /** The row menu's Delete list, on the page of saved lists. */
    fun promptDeleteRow(index: Int) {
        page.saved.getOrNull(index)?.let(::promptDelete)
    }

    /**
     * The bar's DEL LIST, inside a saved list: deletes the list and goes back up to the
     * others. It acts on the whole list whatever row is selected, unlike PLAY and ADD beside
     * it, which is why it says LIST.
     */
    fun promptDeletePage() {
        page.savedName?.let(::promptDelete)
    }

    private fun promptDelete(name: String) {
        state.prompt =
            deleteListPrompt(name, onDone = { state.prompt = null }) {
                scope.launch { if (lists.delete(name)) savedListDeleted(name) else showFlash("CAN'T DELETE") }
            }
    }

    /**
     * The saved list [name] is gone, deleted here or from LIST > LOAD LIST. The page of
     * saved lists is read again wherever it is: on screen, or under an opened list, where
     * going up returns to. Its scroll is kept, so the next list to delete is still under
     * the finger. The deleted list's own page closes.
     */
    fun savedListDeleted(name: String) {
        if (loading) return
        for (at in crumbs.indices) {
            val crumb = crumbs[at]
            if (crumb.page.saved.isEmpty()) continue
            val next = savedListsPage()
            crumbs[at] = Crumb(next, scrollWithin(next, crumb.scroll), crumb.selection, crumb.query, crumb.finding)
        }
        if (page.saved.isNotEmpty()) {
            val next = savedListsPage()
            abandonLoad()
            commit(next, scroll = scrollWithin(next, state.libraryScroll))
        }
        if (page.savedName == name) up()
    }

    /** [scroll], or the last row of [next] when the page has become shorter than that. */
    private fun scrollWithin(
        next: Page,
        scroll: Int,
    ) = scroll.coerceIn(0, (next.rows.size - 1).coerceAtLeast(0))

    private fun onRow(
        index: Int,
        act: (List<Track>) -> Unit,
    ) {
        scope.launch {
            val chosen =
                try {
                    tracksOfRow(index)
                } catch (_: SourceUnreachable) {
                    // the row stays; the bar says why nothing happened
                    showFlash(cantReach())
                    return@launch
                }
            if (chosen.isEmpty()) showFlash("NOTHING TO PLAY") else act(chosen)
        }
    }

    /** Plays from the selection, or from the top with nothing selected. */
    fun playSelection() {
        val tracks = page.tracks ?: return
        if (tracks.isEmpty()) return
        playFrom(state.librarySelected.takeIf { it in tracks.indices } ?: 0)
    }

    /** Enqueues the selected track, or the whole page with nothing selected. */
    fun enqueueSelection() {
        val tracks = page.tracks ?: return
        val chosen = state.librarySelected.takeIf { it in tracks.indices }?.let { listOf(tracks[it]) } ?: tracks
        if (chosen.isEmpty()) return
        facade.enqueue(chosen)
        showFlash("+${chosen.size} QUEUED")
    }

    fun up() {
        if (loading) return
        val crumb = crumbs.removeLastOrNull() ?: return
        abandonLoad()
        val question = crumb.query
        if (question == null) dropSearch() else resumeSearch(question, crumb.finding)
        depth = crumbs.size
        commit(crumb.page, crumb.scroll, crumb.selection)
    }

    /** The back gesture: up a level while there is one, then the window closes. */
    fun back() {
        if (crumbs.isNotEmpty()) up() else state.libraryOpen = false
    }

    /** The row of the playing track on this page, for the now-playing color; -1 for none. */
    fun rowOfTrack(trackId: String?): Int = trackId?.let { id -> page.tracks?.indexOfFirst { it.id == id } } ?: -1

    // --- radio's NEW ---

    /** RADIO's NEW cell: asks for a url, then for a name suggested from its host. */
    fun promptNewStation() {
        state.namePrompt =
            NamePrompt(title = "Station URL", confirmLabel = "Next") { typedUrl ->
                state.namePrompt = null
                val url = typedUrl.trim()
                if (!StationStore.isStreamUrl(url)) {
                    showFlash("NOT AN HTTP URL")
                } else {
                    state.namePrompt =
                        NamePrompt(
                            title = "Station name",
                            initial = StationStore.hostOf(url).orEmpty(),
                            confirmLabel = "Add",
                        ) { name ->
                            state.namePrompt = null
                            scope.launch {
                                stations.add(name, url)
                                if (category == Category.RADIO && depth == 0) load(Category.RADIO.label) { radioRootPage() }
                            }
                        }
                }
            }
    }

    // --- the pages ---

    private fun playFrom(index: Int) {
        val tracks = page.tracks ?: return
        // the page becomes the queue and plays from the tapped track, as Winamp's media
        // library (gen_ml) does
        facade.playQueue(tracks, index)
    }

    private suspend fun rootFor(target: Category): Page {
        // read once here and handed down; see [source]
        val src = source
        return when (target) {
            Category.ARTISTS -> artistsRootPage(src)

            Category.ALBUMS -> albumsRootPage(src)

            Category.LISTS -> listsRootPage(src)

            Category.RADIO -> radioRootPage()

            // the FIND tab has no root page other than its prompt
            Category.FIND -> findPromptPage()
        }
    }

    private suspend fun artistsRootPage(src: BrowseSource): Page {
        if (!src.available) {
            needsAccess = true
            return Page(Category.ARTISTS.label, count(0, "ARTIST"), emptyList())
        }
        needsAccess = false
        val artists = src.artists()
        // checked again: access can be revoked while the read runs, and the empty answer
        // then means no permission
        if (!src.available) {
            needsAccess = true
            return Page(Category.ARTISTS.label, count(0, "ARTIST"), emptyList())
        }
        return artistsPage(src, Category.ARTISTS.label, artists) { artist ->
            artist.albumCount?.let { count(it, "ALBUM") } ?: ""
        }
    }

    /**
     * Artists as a page, for the listener's own shelf and for the FIND tab's results
     * alike: each row opens into the artist's records, and stands for every track under
     * them.
     */
    private fun artistsPage(
        src: BrowseSource,
        header: String,
        artists: List<LibraryArtist>,
        empty: String = NO_MUSIC,
        detail: (LibraryArtist) -> String = { "" },
    ) = Page(
        header = header,
        status = count(artists.size, "ARTIST"),
        rows = artists.map { Row(it.name, detail = detail(it)) },
        open = artists.map { artist -> { drill(artist.name) { albumsPage(src, artist) } } },
        tracksOf = artists.map { artist -> { src.albums(artist.id).flatMap { src.tracks(it.id) } } },
        empty = empty,
    )

    private suspend fun albumsRootPage(src: BrowseSource): Page {
        if (!src.available) {
            needsAccess = true
            return Page(Category.ALBUMS.label, count(0, "ALBUM"), emptyList())
        }
        needsAccess = false
        val albums = src.albums(null)
        // checked again, as for the artists' root
        if (!src.available) {
            needsAccess = true
            return Page(Category.ALBUMS.label, count(0, "ALBUM"), emptyList())
        }
        return Page(
            header = Category.ALBUMS.label,
            status = count(albums.size, "ALBUM"),
            // the artist as the detail, since two albums may share a title
            rows = albums.map { Row(it.title, detail = it.artist) },
            open = albums.map { album -> { drill(album.title) { tracksPage(src, album) } } },
            tracksOf = albums.map { album -> { src.tracks(album.id) } },
        )
    }

    /**
     * The LISTS tab's root, for the source the window is showing
     * ([LibrarySources.showing]). A source that keeps its own playlists shows those; one
     * that does not shows the lists this app has saved as files. The two are never mixed.
     */
    private suspend fun listsRootPage(src: BrowseSource): Page =
        if (src.capabilities.hasPlaylists) sourceListsPage(src) else savedListsPage()

    private suspend fun sourceListsPage(src: BrowseSource): Page {
        val their = src.playlists()
        return Page(
            header = Category.LISTS.label,
            status = count(their.size, "LIST"),
            rows = their.map { Row(it.name, detail = it.trackCount?.let { n -> "$n TRK" } ?: "") },
            open = their.map { list -> { drill(list.name) { sourceListPage(src, list) } } },
            tracksOf = their.map { list -> { src.playlistTracks(list.id) } },
            empty = "NO LISTS",
        )
    }

    private suspend fun sourceListPage(
        src: BrowseSource,
        list: nl.mattix.andamp.core.model.LibraryPlaylist,
    ): Page = trackListPage(list.name, src.playlistTracks(list.id), empty = "NO TRACKS")

    private fun savedListsPage(): Page {
        val saved = lists.list()
        return Page(
            header = Category.LISTS.label,
            status = count(saved.size, "LIST"),
            rows = saved.map { Row(it.name, detail = "${it.trackCount} TRK") },
            open = saved.map { entry -> { drill(entry.name) { listTracksPage(entry.name) } } },
            tracksOf = saved.map { entry -> { lists.load(entry.name).orEmpty() } },
            saved = saved.map { it.name },
            empty = "NO SAVED LISTS · LIST > SAVE KEEPS ONE",
        )
    }

    private fun listTracksPage(name: String): Page {
        val tracks = lists.load(name).orEmpty()
        return trackListPage(name, tracks, empty = "NO TRACKS", savedName = name)
    }

    private fun radioRootPage(): Page {
        // one level, no drill: the radio root is itself a playable page, so next and
        // previous step through the stations
        val all = stations.list()
        return Page(
            header = Category.RADIO.label,
            status = count(all.size, "STATION"),
            rows = all.map { Row(it.title) },
            tracks = all,
            empty = "NO STATIONS · NEW ADDS ONE",
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
    ): Page {
        val albums = src.albums(artist.id)
        val kinds = AlbumKind.entries.mapNotNull { kind -> albums.filter { it.kind == kind }.takeIf { it.isNotEmpty() } }
        if (kinds.size < 2) {
            // a single kind is counted under its own noun; a source that does not tell
            // kinds apart reports everything as an album
            val kind = albums.firstOrNull()?.kind ?: AlbumKind.ALBUM
            return recordsPage(src, artist.name, albums, kind.noun)
        }
        return Page(
            header = artist.name,
            status = count(albums.size, "RECORD"),
            rows = kinds.map { of -> Row(of.first().kind.label, detail = count(of.size, "RECORD")) },
            open = kinds.map { of -> { drill(artist.name) { recordsPage(src, artist.name, of, of.first().kind.noun) } } },
            tracksOf = kinds.map { of -> { of.flatMap { src.tracks(it.id) } } },
        )
    }

    /** Records, counted under [noun], so a page of singles says "5 SINGLES". */
    private fun recordsPage(
        src: BrowseSource,
        header: String,
        albums: List<LibraryAlbum>,
        noun: String,
    ) = Page(
        header = header,
        status = count(albums.size, noun),
        rows = albums.map { Row(it.title, detail = it.year?.toString() ?: "") },
        open = albums.map { album -> { drill(album.title) { tracksPage(src, album) } } },
        tracksOf = albums.map { album -> { src.tracks(album.id) } },
    )

    private suspend fun tracksPage(
        src: BrowseSource,
        album: LibraryAlbum,
    ): Page = trackListPage(album.title, src.tracks(album.id), empty = "NO TRACKS")

    private fun trackListPage(
        header: String,
        tracks: List<Track>,
        empty: String,
        savedName: String? = null,
    ): Page {
        val time = if (tracks.isEmpty()) "" else " · ${clock(tracks.sumOf { it.durationSec })}"
        return Page(
            header = header,
            status = count(tracks.size, "TRACK") + time,
            rows = tracks.mapIndexed { at, track -> Row("${at + 1}. ${track.title}", detail = clock(track.durationSec)) },
            tracks = tracks,
            empty = empty,
            savedName = savedName,
        )
    }

    /** Opens a row into the page [fetch] makes. [header] is also the header of the page shown if the fetch fails. */
    private fun drill(
        header: String,
        fetch: suspend () -> Page,
    ) {
        if (loading) return
        // the search it was opened from is kept in the crumb, so going back up restores it
        val from = Crumb(page, state.libraryScroll, state.librarySelected, query.takeIf { searching }, finding)
        load(
            header = header,
            onLanding = {
                crumbs.addLast(from)
                depth = crumbs.size
                // the opened page is not a search result, so the search is closed
                dropSearch()
            },
            fetch = fetch,
        )
    }

    /** The fetch in flight, if any; see [load]. */
    private var loadJob: Job? = null

    /** How many times a page has been asked for, so a fetch can tell it was overtaken; see [load]. */
    private var loads = 0

    /**
     * Fetches the next page and lands it, unless another was asked for first.
     *
     * A fetch can take seconds, and the window can be reopened meanwhile for another
     * source (a library picked from the main menu, a sign-in, a sign-out). So the
     * previous fetch is cancelled, and one that finishes anyway lands only if it is still
     * the latest.
     *
     * [onLanding] runs just before the page lands and only if it does, so a drill's crumb
     * is not pushed for a page that never arrived.
     *
     * A fetch the source could not answer lands too, as the unreachable page under
     * [header]. Its retry is the same fetch without [onLanding], so it replaces the
     * failed page in place.
     */
    private fun load(
        header: String,
        onLanding: () -> Unit = {},
        fetch: suspend () -> Page,
    ) {
        val stamp = abandonLoad()
        loading = true
        loadJob =
            scope.launch {
                val next =
                    try {
                        fetch()
                    } catch (_: SourceUnreachable) {
                        unreachablePage(header) { load(header, fetch = fetch) }
                    }
                if (stamp != loads) return@launch
                onLanding()
                commit(next)
            }
    }

    /**
     * Cancels the fetch in flight, so its result does not land; returns the stamp the
     * next one lands under.
     *
     * Every fetch starts with this, and so does every level reached without a fetch, so
     * the page on screen is always the last one navigated to. A search result is not
     * navigation and does not pass through here; see [commitInPlace].
     */
    private fun abandonLoad(): Int {
        loadJob?.cancel()
        loadJob = null
        loading = false
        return ++loads
    }

    /** The one place a navigated page is set, with its scroll and selection. */
    private fun commit(
        next: Page,
        scroll: Int = 0,
        selection: Int = -1,
    ) {
        page = next
        state.libraryScroll = scroll
        state.librarySelected = selection
        revision++
        loading = false
    }

    /** A page swap that is not navigation: search results landing under the field. */
    private fun commitInPlace(next: Page) {
        page = next
        state.libraryScroll = 0
        state.librarySelected = -1
        revision++
    }

    /**
     * The page for a fetch the source could not answer: no rows and no count, and a note
     * that names the source and says a tap retries.
     */
    private fun unreachablePage(
        header: String,
        retry: () -> Unit,
    ) = Page(header, "", emptyList(), empty = "${cantReach()} · TAP TO RETRY", retry = retry)

    /** "CAN'T REACH" and the name of the library; see [nameOf]. */
    private fun cantReach(): String {
        val name = nameOf().trim().uppercase()
        return if (name.isEmpty()) "CAN'T REACH THE LIBRARY" else "CAN'T REACH $name"
    }

    private fun showFlash(text: String) {
        val stamp = ++flashes
        flash = text
        scope.launch {
            delay(FLASH_MS)
            if (flashes == stamp) flash = ""
        }
    }

    private fun count(
        n: Int,
        noun: String,
    ) = "$n $noun${if (n == 1) "" else "S"}"

    private fun clock(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)

    private companion object {
        const val SEARCH_HEADER = "SEARCH"
        const val NO_MUSIC = "NO MUSIC FOUND"
        const val FLASH_MS = 1500L
        const val SEARCH_DEBOUNCE_MS = 250L
        const val MIN_QUERY = 2

        /** The singular of a kind, for [count], which makes "1 SINGLE" and "5 SINGLES" of it. */
        val AlbumKind.noun: String
            get() =
                when (this) {
                    AlbumKind.ALBUM -> "ALBUM"
                    AlbumKind.SINGLE -> "SINGLE"
                    AlbumKind.COMPILATION -> "COMPILATION"
                }
    }
}
