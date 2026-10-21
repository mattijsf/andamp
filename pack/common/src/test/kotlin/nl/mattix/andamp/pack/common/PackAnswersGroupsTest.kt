// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common

import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.core.model.BrowseCapabilities
import nl.mattix.andamp.core.model.LibraryAlbum
import nl.mattix.andamp.core.model.LibraryArtist
import nl.mattix.andamp.core.model.LibraryGroup
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.packapi.PackPlaylist
import nl.mattix.andamp.core.packapi.PackQuestion
import nl.mattix.andamp.core.packapi.UNKNOWN_COUNT
import nl.mattix.andamp.core.playback.BrowseGroups
import nl.mattix.andamp.core.playback.BrowseSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Folders and genres asked of a library through [PackAnswers]: paged as lists are, and failed where the library has none. */
class PackAnswersGroupsTest {
    private open class Shelves : BrowseSource {
        override val capabilities = BrowseCapabilities()
        override val available = true

        override suspend fun artists(): List<LibraryArtist> = emptyList()

        override suspend fun albums(artistId: String?): List<LibraryAlbum> = emptyList()

        override suspend fun tracks(albumId: String): List<Track> = emptyList()
    }

    private class Grouped :
        Shelves(),
        BrowseGroups {
        override suspend fun folders() = listOf(LibraryGroup("f1", "Music/A", 2), LibraryGroup("f2", "Music/B"))

        override suspend fun folderTracks(id: String) = if (id == "f1") listOf(track("1"), track("2")) else emptyList()

        override suspend fun genres() = listOf(LibraryGroup("rock", "Rock", 1))

        override suspend fun genreTracks(id: String) = if (id == "rock") listOf(track("3")) else emptyList()
    }

    @Test
    fun `folders cross as lists with no owner, a page at a time`() =
        runTest {
            val answers = PackAnswers { Grouped() }

            val first = answers.to(PackQuestion(PackQuestion.FOLDERS, limit = 1))
            val second = answers.to(PackQuestion(PackQuestion.FOLDERS, offset = 1, limit = 1))

            assertEquals(listOf(PackPlaylist("f1", "Music/A", 2)), first.playlists)
            assertTrue(first.more)
            assertEquals(listOf(PackPlaylist("f2", "Music/B", UNKNOWN_COUNT)), second.playlists)
            assertFalse(second.more)
        }

    @Test
    fun `a folder's and a genre's tracks are asked for by id`() =
        runTest {
            val answers = PackAnswers { Grouped() }

            val inFolder = answers.to(PackQuestion(PackQuestion.FOLDER_TRACKS, id = "f1"))
            val genres = answers.to(PackQuestion(PackQuestion.GENRES))
            val inGenre = answers.to(PackQuestion(PackQuestion.GENRE_TRACKS, id = "rock"))

            assertEquals(listOf("1", "2"), inFolder.tracks.map { it.id })
            assertEquals(listOf(PackPlaylist("rock", "Rock", 1)), genres.playlists)
            assertEquals(listOf("3"), inGenre.tracks.map { it.id })
        }

    @Test
    fun `a library that files nothing by folder or genre answers those as failed`() =
        runTest {
            val answers = PackAnswers { Shelves() }

            assertTrue(answers.to(PackQuestion(PackQuestion.FOLDERS)).failed)
            assertTrue(answers.to(PackQuestion(PackQuestion.GENRE_TRACKS, id = "rock")).failed)
        }

    private companion object {
        fun track(id: String) =
            Track(id = id, artist = "Artist", title = "Title $id", durationMs = 1000, uri = "example:track:$id")
    }
}
