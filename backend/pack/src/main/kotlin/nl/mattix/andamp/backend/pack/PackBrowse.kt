// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.pack

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.mattix.andamp.core.model.BrowseCapabilities
import nl.mattix.andamp.core.model.LibraryAlbum
import nl.mattix.andamp.core.model.LibraryArtist
import nl.mattix.andamp.core.model.LibraryPlaylist
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.packapi.PackAnswer
import nl.mattix.andamp.core.packapi.PackQuestion
import nl.mattix.andamp.core.packapi.toAlbum
import nl.mattix.andamp.core.packapi.toArtist
import nl.mattix.andamp.core.packapi.toPlaylist
import nl.mattix.andamp.core.packapi.toTrack
import nl.mattix.andamp.core.playback.BrowseSource
import nl.mattix.andamp.core.playback.SourceUnreachable

/**
 * A pack's library, asked one page at a time.
 *
 * The pack parses and shelves; rows cross. They cross in pages because a binder transaction is
 * limited to about a megabyte, so every question is a loop that asks again from further along
 * while the pack says there is more, up to [MAX_PAGES] pages.
 *
 * A question that failed is different from an empty shelf. When the pack cannot be asked, or
 * fails on the first page, the call throws [SourceUnreachable], which the browse contract
 * defines for this.
 *
 * A walk that fails after some pages returns the pages it read and sets [whole] to false.
 */
class PackBrowse internal constructor(
    private val client: PackClient,
) : BrowseSource {
    /**
     * What the pack says its library holds. A getter because the descriptor arrives with the
     * binding; until then no shelf is offered.
     */
    override val capabilities: BrowseCapabilities
        get() = client.known?.browse ?: BrowseCapabilities()

    /** True while the pack is installed, understood and signed into. */
    override val available: Boolean get() = client.reach.value is PackReach.Ready

    private val _whole = MutableStateFlow(true)

    /**
     * Whether the last question was answered all the way through. False when the pack failed,
     * on the first page (which also throws) or on a later one, and when a walk stopped at
     * [MAX_PAGES] with more to come.
     */
    val whole: StateFlow<Boolean> = _whole.asStateFlow()

    override suspend fun artists(): List<LibraryArtist> =
        gathered(PackQuestion(PackQuestion.ARTISTS)).flatMap { page -> page.artists.map { it.toArtist() } }

    override suspend fun albums(artistId: String?): List<LibraryAlbum> =
        gathered(PackQuestion(PackQuestion.ALBUMS, id = artistId.orEmpty()))
            .flatMap { page -> page.albums.map { it.toAlbum() } }

    override suspend fun tracks(albumId: String): List<Track> =
        gathered(PackQuestion(PackQuestion.TRACKS, id = albumId)).flatMap { page -> page.tracks.map { it.toTrack() } }

    override suspend fun playlists(): List<LibraryPlaylist> =
        gathered(PackQuestion(PackQuestion.PLAYLISTS)).flatMap { page -> page.playlists.map { it.toPlaylist() } }

    override suspend fun playlistTracks(id: String): List<Track> =
        gathered(PackQuestion(PackQuestion.PLAYLIST_TRACKS, id = id))
            .flatMap { page -> page.tracks.map { it.toTrack() } }

    /** One page: a search answers with its best matches first. */
    override suspend fun search(
        query: String,
        limit: Int,
    ): List<Track> =
        gathered(PackQuestion(PackQuestion.SEARCH, query = query, limit = limit), pages = 1)
            .flatMap { page -> page.tracks.map { it.toTrack() } }
            .take(limit)

    override suspend fun findArtists(
        query: String,
        limit: Int,
    ): List<LibraryArtist> =
        gathered(PackQuestion(PackQuestion.FIND_ARTISTS, query = query, limit = limit), pages = 1)
            .flatMap { page -> page.artists.map { it.toArtist() } }
            .take(limit)

    /**
     * One question's pages, in order, until the pack says there are no more.
     *
     * The offset moves by the rows that arrived, not by the page size asked for, so a short
     * page is followed correctly. A page with no rows that still claims more ends the walk.
     *
     * Running out of pages sets [whole] to false only when it was [MAX_PAGES] that ran out. A
     * search asks for one page, and the pages it did not ask for are not counted as missing.
     *
     * A page that failed ends the walk; see the class KDoc.
     */
    private suspend fun gathered(
        question: PackQuestion,
        pages: Int = MAX_PAGES,
    ): List<PackAnswer> {
        val read = mutableListOf<PackAnswer>()
        var offset = question.offset
        repeat(pages) {
            val answer = client.ask(question.copy(offset = offset))
            if (answer == null || answer.failed) {
                _whole.value = false
                if (read.isEmpty()) throw SourceUnreachable(unanswered(question, asked = answer != null))
                return told(read, whole = false)
            }
            read += answer
            if (!answer.more || answer.rows == 0) return told(read, whole = true)
            offset += answer.rows
        }
        return told(read, whole = pages < MAX_PAGES)
    }

    private fun told(
        pages: List<PackAnswer>,
        whole: Boolean,
    ): List<PackAnswer> {
        _whole.value = whole
        return pages
    }

    private fun unanswered(
        question: PackQuestion,
        asked: Boolean,
    ): String = if (asked) "the pack could not answer ${question.kind}" else "the pack could not be asked ${question.kind}"

    /** How many rows a page carries, over all four lists. */
    private val PackAnswer.rows: Int
        get() = tracks.size + artists.size + albums.size + playlists.size

    internal companion object {
        /**
         * Where a walk stops however much the pack claims is left: a hundred thousand rows at
         * [PackQuestion.PAGE] rows a page. It bounds a pack that never stops saying there is
         * more.
         */
        const val MAX_PAGES = 200
    }
}
