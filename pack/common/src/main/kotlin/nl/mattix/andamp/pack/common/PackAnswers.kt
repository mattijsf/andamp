// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common

import nl.mattix.andamp.core.model.LibraryAlbum
import nl.mattix.andamp.core.model.LibraryArtist
import nl.mattix.andamp.core.model.LibraryPlaylist
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.packapi.PackAnswer
import nl.mattix.andamp.core.packapi.PackQuestion
import nl.mattix.andamp.core.packapi.toPack
import nl.mattix.andamp.core.playback.BrowseSource
import nl.mattix.andamp.core.playback.SourceUnreachable
import kotlin.coroutines.cancellation.CancellationException

/**
 * Answers the player's library questions from a [BrowseSource].
 *
 * One question becomes one call on the source and one page of what came back. The paging
 * is here because it belongs to the wire: an answer is cut to what was asked for, and
 * [PackAnswer.more] says whether there is more further along.
 *
 * A failed answer is never an empty one. A source that is missing or unavailable, or that
 * throws [SourceUnreachable] or any other exception, is answered with [PackAnswer.failed].
 * Cancellation is rethrown.
 *
 * The source is asked for on every question, so a listener who signs in on the pack's own
 * screen is answered by the next question without anything being rebuilt.
 */
class PackAnswers(
    private val library: () -> BrowseSource?,
) {
    /** One page of one question; [PackAnswer.failed] when the library could not be asked at all. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    suspend fun to(question: PackQuestion): PackAnswer {
        val source = library()?.takeIf { it.available } ?: return FAILED
        // every failure becomes a failed answer: an exception thrown across a binder would
        // not reach the player as an answer it can ask for again
        return try {
            asked(source, question)
        } catch (withdrawn: CancellationException) {
            throw withdrawn
        } catch (_: SourceUnreachable) {
            FAILED
        } catch (_: Exception) {
            FAILED
        }
    }

    private suspend fun asked(
        source: BrowseSource,
        question: PackQuestion,
    ): PackAnswer =
        when (question.kind) {
            PackQuestion.ARTISTS -> source.artists().artists(question)

            // an empty id means every album, as a null artist does in BrowseSource.albums
            PackQuestion.ALBUMS -> source.albums(question.id.takeIf { it.isNotEmpty() }).albums(question)

            PackQuestion.TRACKS -> source.tracks(question.id).tracks(question)

            PackQuestion.PLAYLISTS -> source.playlists().playlists(question)

            PackQuestion.PLAYLIST_TRACKS -> source.playlistTracks(question.id).tracks(question)

            PackQuestion.SEARCH -> source.search(question.query, question.reach()).tracks(question)

            PackQuestion.FIND_ARTISTS -> source.findArtists(question.query, question.reach()).artists(question)

            // an unknown kind is a failed answer; an empty one would show the library as
            // empty
            else -> FAILED
        }

    private companion object {
        val FAILED = PackAnswer(failed = true)
    }
}

/**
 * How far a search has to reach to fill this page.
 *
 * A search takes a limit and no offset, so a later page is the same search with a larger
 * limit, and the page is cut out of its result.
 */
private fun PackQuestion.reach(): Int = if (limit <= 0) BrowseSource.SEARCH_LIMIT else offset + limit

/**
 * The slice this question asked for, and whether there is more behind it. An offset past
 * the end gives an empty page.
 */
private fun <T> List<T>.page(question: PackQuestion): Page<T> {
    val from = question.offset.coerceIn(0, size)
    val to = if (question.limit <= 0) size else (from + question.limit).coerceAtMost(size)
    return Page(subList(from, to).toList(), more = to < size)
}

private class Page<T>(
    val items: List<T>,
    val more: Boolean,
)

private fun List<LibraryArtist>.artists(question: PackQuestion): PackAnswer =
    page(question).let { page -> PackAnswer(artists = page.items.map { it.toPack() }, more = page.more) }

private fun List<LibraryAlbum>.albums(question: PackQuestion): PackAnswer =
    page(question).let { page -> PackAnswer(albums = page.items.map { it.toPack() }, more = page.more) }

private fun List<LibraryPlaylist>.playlists(question: PackQuestion): PackAnswer =
    page(question).let { page -> PackAnswer(playlists = page.items.map { it.toPack() }, more = page.more) }

private fun List<Track>.tracks(question: PackQuestion): PackAnswer =
    page(question).let { page -> PackAnswer(tracks = page.items.map { it.toPack() }, more = page.more) }
