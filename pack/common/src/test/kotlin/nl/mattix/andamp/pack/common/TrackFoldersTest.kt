// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common

import nl.mattix.andamp.core.model.LibraryGroup
import org.junit.Assert.assertEquals
import org.junit.Test

/** Folders found from the paths of the tracks' files, and the names they are listed by. */
class TrackFoldersTest {
    @Test
    fun `folders are named from the folder above the one they share`() {
        val found =
            TrackFolders.of(
                listOf(
                    "1" to "/srv/music/Artist/Album/01.flac",
                    "2" to "/srv/music/Artist/Album/02.flac",
                    "3" to "/srv/music/Loose/03.mp3",
                ),
            )

        assertEquals(
            listOf(
                LibraryGroup("/srv/music/Artist/Album", "music/Artist/Album", 2),
                LibraryGroup("/srv/music/Loose", "music/Loose", 1),
            ),
            found.groups(),
        )
        assertEquals(listOf("1", "2"), found.trackIds("/srv/music/Artist/Album"))
    }

    @Test
    fun `a library of one folder is named by that folder`() {
        val found = TrackFolders.of(listOf("1" to "/Users/me/Downloads/An Album/01.mp3"))

        assertEquals(listOf("An Album"), found.folders.map { it.name })
    }

    @Test
    fun `a folder is named from the parent of the root that holds it`() {
        val found =
            TrackFolders.of(
                listOf(
                    "1" to "/volume1/Music/Artist/Album/01.flac",
                    "2" to "/home/me/Audiobooks/Book/01.mp3",
                ),
                roots = listOf("/volume1/Music", "/home/me/Audiobooks"),
            )

        assertEquals(listOf("Audiobooks/Book", "Music/Artist/Album"), found.folders.map { it.name })
    }

    @Test
    fun `paths already relative to the top keep their whole path`() {
        val found = TrackFolders.of(listOf("1" to "Artist/Album/01.mp3"), roots = listOf(""))

        assertEquals(listOf("Artist/Album"), found.folders.map { it.name })
    }

    @Test
    fun `backslashes separate folders as slashes do`() {
        val found =
            TrackFolders.of(
                listOf(
                    "1" to "D:\\Music\\A\\01.mp3",
                    "2" to "D:\\Music\\B\\01.mp3",
                ),
            )

        assertEquals(listOf("Music/A", "Music/B"), found.folders.map { it.name })
        assertEquals(listOf("1"), found.trackIds("D:\\Music\\A"))
    }

    @Test
    fun `tracks without a folder are left out and folders are listed by name without case`() {
        val found =
            TrackFolders.of(
                listOf(
                    "1" to "",
                    "2" to "loose.mp3",
                    "3" to "/m/b/01.mp3",
                    "4" to "/m/A/01.mp3",
                ),
            )

        assertEquals(listOf("m/A", "m/b"), found.folders.map { it.name })
        assertEquals(emptyList<String>(), found.trackIds("/m/c"))
    }
}
