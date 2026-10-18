// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.mattix.andamp.R
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.playback.BrowseSource
import nl.mattix.andamp.core.playback.SourceUnreachable
import nl.mattix.andamp.core.player.PlayerFacade
import kotlin.coroutines.CoroutineContext

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
 * ADD appends. Inside an artist or a list, PLAY and ADD take everything the page stands for.
 * Search is a level like any other: entering pushes a crumb, so backing out restores the
 * page, scroll and selection it covered. A fetched page replaces the list and the header
 * together, and only the latest fetch asked for lands. A tab the listener leaves keeps its
 * place, and coming back lands there.
 *
 * A source that could not answer ([SourceUnreachable]) gets a page that says CAN'T REACH
 * and the source's name, and a tap on it asks again. An empty answer gets its own text
 * (NO ARTISTS). A source that answered only part of a question gets INCOMPLETE beside the
 * count. Nothing here caches answers beyond the pages a tab keeps while it is left.
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
    /**
     * Told the rows of a saved list once they are read, before they are shown or played;
     * see [FolderAccessOps.listRead].
     */
    private val onListRead: suspend (List<Track>) -> Unit = {},
    /** Where the saved lists and the stations are read and written. */
    private val io: CoroutineContext = Dispatchers.IO,
    private val words: Words,
    /**
     * Whether the source answered its last question all the way through; false when it
     * stopped part way and handed back what it had. A [BrowseSource] cannot say this itself.
     */
    private val wholeOf: (BrowseSource) -> Boolean = { true },
) {
    /**
     * The library as it is now; see [sourceOf]. It is read once per page and handed to
     * that page's rows, because a row's id is valid only for the source that listed it.
     */
    private val source: BrowseSource get() = sourceOf()

    /** The tabs, each with the string resource of its label; see [label]. */
    enum class Category(
        val label: Int,
    ) {
        ARTISTS(R.string.library_tab_artists),
        ALBUMS(R.string.library_tab_albums),
        LISTS(R.string.library_tab_lists),
        RADIO(R.string.library_tab_radio),

        /**
         * A search of the source's catalog, beyond the listener's own shelves. Shown only
         * for a source that has a catalog.
         */
        FIND(R.string.library_tab_find),
    }

    /** What a tab is called, which is also the header of its root page. */
    fun label(category: Category): String = words.get(category.label)

    /** One row on screen: text only. */
    data class Row(
        val label: String,
        val detail: String = "",
    )

    /**
     * Where a drill came from, so up() restores it, including the search that was open on
     * it.
     */
    private class Crumb(
        val page: LibraryPage,
        val scroll: Int,
        val selection: Int,
        /** What was typed, when the level left was a search; null otherwise. */
        val query: String? = null,
        /** Whether that search was the FIND tab's; see [Category.FIND]. */
        val finding: Boolean = false,
    )

    private var page: LibraryPage by mutableStateOf(LibraryPage(label(Category.ARTISTS), "", emptyList()))
    private val crumbs = ArrayDeque<Crumb>()

    /** Makes the pages; see [LibraryShelves]. */
    private val shelves =
        LibraryShelves(
            words,
            lists,
            stations,
            io,
            onListRead,
            wholeOf,
            access = { needed -> needsAccess = needed },
            drill = ::drill,
        )

    val rows: List<Row> get() = page.rows

    /** The current level's name: the category at its root, an artist, an album. */
    val header: String get() = page.header

    /** The bottom bar's left text: "245 ARTISTS", "26 TRACKS · 81:09", "245 ARTISTS · INCOMPLETE". */
    val status: String get() =
        if (page.incomplete) "${page.status} · ${words.get(R.string.library_incomplete)}" else page.status

    /** The text an empty list shows, per page. */
    val emptyNote: String get() = page.empty ?: words.get(R.string.library_no_music)

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

    /**
     * Whether PLAY and ADD act on this page: a page of tracks, or a level inside an artist
     * or a list, whose rows stand for tracks. A tab's root is not one: it stands for the
     * whole library.
     */
    val canPlayPage: Boolean get() = atTracks || (depth > 0 && page.tracksOf.isNotEmpty())

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
     *
     * The tab left keeps its place ([Stash]), and a tab with a place kept lands there. The
     * lists and stations this app keeps as files are read again instead, at the root's
     * scroll, because they change without the library being told.
     */
    fun switchCategory(target: Category) {
        if (loading) return
        if (target == category && home && target != Category.FIND) return
        val leaving = category
        // the active tab while deep goes back to its root, so its place is not kept
        if (target == leaving) stashes.remove(target) else stash(leaving)
        val kept = stashes.remove(target)
        dropSearch()
        category = target
        if (kept != null && !readEachVisit(target)) {
            restore(kept)
            return
        }
        crumbs.clear()
        depth = 0
        // the FIND tab has no shelf: it opens on its search prompt
        if (target == Category.FIND) {
            enterFind()
        } else {
            load(
                label(target),
                scroll = kept?.rootScroll ?: 0,
                selection = kept?.rootSelection ?: -1,
            ) { shelves.root(target, source) }
        }
    }

    /**
     * Where the listener was in a tab they left: the page, the levels under it, the scroll and
     * the selection.
     */
    private class Stash(
        val crumbs: List<Crumb>,
        val page: LibraryPage,
        val scroll: Int,
        val selection: Int,
    ) {
        /** The tab's root page's scroll, which is the bottom crumb's when the tab was left deep. */
        val rootScroll: Int get() = crumbs.firstOrNull()?.scroll ?: scroll
        val rootSelection: Int get() = crumbs.firstOrNull()?.selection ?: selection
    }

    /** The places kept per tab; see [switchCategory]. The window's opening clears them. */
    private val stashes = mutableMapOf<Category, Stash>()

    /**
     * Keeps [tab]'s place as it is left. A search, a page that could not be fetched and the
     * permission prompt are not places to come back to: the tab then opens at its root.
     */
    private fun stash(tab: Category) {
        if (searching || page.retry != null || needsAccess) {
            stashes.remove(tab)
            return
        }
        stashes[tab] = Stash(crumbs.toList(), page, state.libraryScroll, state.librarySelected)
    }

    private fun restore(kept: Stash) {
        abandonLoad()
        crumbs.clear()
        crumbs.addAll(kept.crumbs)
        depth = crumbs.size
        needsAccess = false
        commit(kept.page, kept.scroll, kept.selection)
    }

    /** Whether [tab] shows files this app keeps, which are read again on every visit. */
    private fun readEachVisit(tab: Category): Boolean =
        tab == Category.RADIO || (tab == Category.LISTS && !source.capabilities.hasPlaylists)

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
        commit(shelves.prompt(finding = true))
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
        commit(shelves.prompt(finding = false))
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
                    commitInPlace(shelves.prompt(finding))
                    return@launch
                }
                val src = source
                val page =
                    try {
                        if (finding) {
                            shelves.artists(
                                src,
                                label(Category.FIND),
                                src.findArtists(text),
                                empty = words.get(R.string.library_no_artists),
                            )
                        } else {
                            shelves.results(src.search(text))
                        }
                    } catch (_: SourceUnreachable) {
                        // the retry sets the same query again, so a newer query still wins
                        unreachablePage(if (finding) label(Category.FIND) else shelves.searchHeader) { setQuery(text, caret) }
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
            commitInPlace(shelves.prompt(finding))
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

    // --- open / navigate ---

    fun open() {
        // this can be called while a page is still loading (a library picked from the main
        // menu, a sign-in, a sign-out); that page is for the previous source
        abandonLoad()
        state.libraryOpen = true
        // raised, so it is not opened behind another window
        state.raiseWindow(WindowStore.LIBRARY)
        crumbs.clear()
        stashes.clear()
        depth = 0
        needsAccess = false
        dropSearch()
        // the tab it was left on may be one the new source does not have (the phone has no
        // FIND tab)
        if (category !in visibleCategories) category = visibleCategories.first()
        if (category == Category.FIND) enterFind() else load(label(category)) { shelves.root(category, source) }
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
            showFlash(words.get(R.string.library_queued, chosen.size))
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
            showFlash(words.get(R.string.library_queued_next, chosen.size))
        }

    // --- the lists and stations this app keeps ---

    /** The kinds of row this app keeps as files, which its menu can rename and delete. */
    enum class Kept { LIST, STATION }

    /** What the row is when this app keeps it, a saved list or a station; null for any other row. */
    fun keptAt(index: Int): Kept? =
        when {
            index in page.saved.indices -> Kept.LIST
            stationAt(index) != null -> Kept.STATION
            else -> null
        }

    /** The station on the row, on RADIO's own page; null anywhere else. */
    private fun stationAt(index: Int): Track? =
        page.tracks?.getOrNull(index)?.takeIf { category == Category.RADIO && depth == 0 && !searching }

    /** Whether the row can be deleted: a saved list or a station. */
    fun canDeleteRow(index: Int): Boolean = keptAt(index) != null

    /** Whether the page shows the tracks of a saved list, which the bar's DEL LIST deletes. */
    val canDeletePage: Boolean get() = page.savedName != null

    /** The row menu's Delete list or Delete station. */
    fun promptDeleteRow(index: Int) {
        page.saved.getOrNull(index)?.let(keeping::deleteList)
        stationAt(index)?.let(keeping::deleteStation)
    }

    /** The row menu's Rename list or Rename station. */
    fun promptRenameRow(index: Int) {
        page.saved.getOrNull(index)?.let(keeping::renameList)
        stationAt(index)?.let(keeping::renameStation)
    }

    /** Asks before it renames or deletes a list or a station; see [LibraryKeeping]. */
    private val keeping by lazy {
        LibraryKeeping(
            state,
            scope,
            lists,
            stations,
            io,
            words,
            flash = ::showFlash,
            listsChanged = { gone -> if (gone == null) savedListsChanged() else savedListDeleted(gone) },
            stationsChanged = ::stationsChanged,
        )
    }

    /** The stations changed: RADIO's page, when it shows, is read again at its scroll. */
    private fun stationsChanged() {
        if (category == Category.RADIO && depth == 0 && !loading) {
            load(label(Category.RADIO), scroll = state.libraryScroll) { shelves.radio() }
        }
    }

    /**
     * The bar's DEL LIST, inside a saved list: deletes the list and goes back up to the
     * others. It acts on the whole list whatever row is selected, unlike PLAY and ADD beside
     * it, which is why it says LIST.
     */
    fun promptDeletePage() {
        page.savedName?.let(keeping::deleteList)
    }

    /**
     * The saved list [name] is gone, deleted here or from LIST > LOAD LIST. The page of
     * saved lists is read again wherever it is: on screen, or under an opened list, where
     * going up returns to. Its scroll is kept, so the next list to delete is still under
     * the finger. The deleted list's own page closes.
     */
    fun savedListDeleted(name: String) = savedListsChanged(closing = name)

    /**
     * The saved lists changed: their page is read again wherever it is, at its scroll, as
     * [savedListDeleted] says. The page of the list [closing], when one is given and open,
     * closes.
     */
    private fun savedListsChanged(closing: String? = null) {
        if (loading) return
        if (page.saved.isEmpty() && crumbs.all { it.page.saved.isEmpty() }) return
        scope.launch {
            val next = shelves.savedLists()
            // a level navigated to while the lists were read is left to land
            if (loading) return@launch
            for (at in crumbs.indices) {
                val crumb = crumbs[at]
                if (crumb.page.saved.isEmpty()) continue
                crumbs[at] = Crumb(next, scrollWithin(next, crumb.scroll), crumb.selection, crumb.query, crumb.finding)
            }
            if (page.saved.isNotEmpty()) {
                abandonLoad()
                commit(next, scroll = scrollWithin(next, state.libraryScroll))
            }
            if (closing != null && page.savedName == closing) up()
        }
    }

    /** [scroll], or the last row of [next] when the page has become shorter than that. */
    private fun scrollWithin(
        next: LibraryPage,
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
            if (chosen.isEmpty()) showFlash(words.get(R.string.library_nothing_to_play)) else act(chosen)
        }
    }

    /**
     * Plays from the selection, or from the top with nothing selected. Inside an artist or a
     * list, it plays everything the page stands for.
     */
    fun playSelection() {
        val tracks = page.tracks ?: return onPage { all -> facade.playQueue(all, 0) }
        if (tracks.isEmpty()) return
        playFrom(state.librarySelected.takeIf { it in tracks.indices } ?: 0)
    }

    /**
     * Enqueues the selected track, or the whole page with nothing selected. Inside an artist
     * or a list, it enqueues everything the page stands for.
     */
    fun enqueueSelection() {
        val tracks =
            page.tracks ?: return onPage { all ->
                facade.enqueue(all)
                showFlash(words.get(R.string.library_queued, all.size))
            }
        val chosen = state.librarySelected.takeIf { it in tracks.indices }?.let { listOf(tracks[it]) } ?: tracks
        if (chosen.isEmpty()) return
        facade.enqueue(chosen)
        showFlash(words.get(R.string.library_queued, chosen.size))
    }

    /** Fetches every track the rows of a page above the tracks stand for, then acts on them. */
    private fun onPage(act: (List<Track>) -> Unit) {
        if (!canPlayPage) return
        val each = page.tracksOf
        scope.launch {
            val all =
                try {
                    shelves.gather(each)
                } catch (_: SourceUnreachable) {
                    showFlash(cantReach())
                    return@launch
                }
            if (all.isEmpty()) showFlash(words.get(R.string.library_nothing_to_play)) else act(all)
        }
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

    /**
     * The letter the row at [index] files under, for the readout beside a dragged scroll
     * handle: its first letter, or # for a digit. Null on a page of tracks, which keeps an
     * order of its own, and for a row with neither.
     */
    fun letterAt(index: Int): String? {
        if (page.tracks != null) return null
        val first =
            page.rows
                .getOrNull(index)
                ?.label
                ?.firstOrNull { it.isLetterOrDigit() } ?: return null
        return if (first.isDigit()) "#" else first.uppercase()
    }

    /** The row of the playing track on this page, for the now-playing color; -1 for none. */
    fun rowOfTrack(trackId: String?): Int = trackId?.let { id -> page.tracks?.indexOfFirst { it.id == id } } ?: -1

    // --- radio's NEW ---

    /** RADIO's NEW cell: asks for a url, then for a name suggested from its host. */
    fun promptNewStation() = keeping.newStation()

    // --- the pages ---

    private fun playFrom(index: Int) {
        val tracks = page.tracks ?: return
        // the page becomes the queue and plays from the tapped track, as Winamp's media
        // library (gen_ml) does
        facade.playQueue(tracks, index)
    }

    /** Opens a row into the page [fetch] makes. [header] is also the header of the page shown if the fetch fails. */
    private fun drill(
        header: String,
        fetch: suspend () -> LibraryPage,
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
     *
     * The page lands scrolled to [scroll] and with [selection], each kept within its rows.
     */
    private fun load(
        header: String,
        onLanding: () -> Unit = {},
        scroll: Int = 0,
        selection: Int = -1,
        fetch: suspend () -> LibraryPage,
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
                commit(next, scrollWithin(next, scroll), selection.takeIf { it in next.rows.indices } ?: -1)
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
        next: LibraryPage,
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
    private fun commitInPlace(next: LibraryPage) {
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
    ) = LibraryPage(header, "", emptyList(), empty = words.get(R.string.library_tap_to_retry, cantReach()), retry = retry)

    /** "CAN'T REACH" and the name of the library; see [nameOf]. */
    private fun cantReach(): String {
        val name = nameOf().trim().uppercase()
        return if (name.isEmpty()) {
            words.get(
                R.string.library_cant_reach_library,
            )
        } else {
            words.get(R.string.library_cant_reach, name)
        }
    }

    private fun showFlash(text: String) {
        val stamp = ++flashes
        flash = text
        scope.launch {
            delay(FLASH_MS)
            if (flashes == stamp) flash = ""
        }
    }

    private companion object {
        const val FLASH_MS = 1500L

        const val SEARCH_DEBOUNCE_MS = 250L
        const val MIN_QUERY = 2
    }
}
