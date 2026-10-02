// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.model.LibraryAlbum
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MediaStore identifies an album by its tags and its folder, so an album whose
 * files live in two places arrives twice. [AlbumMerge] folds those rows on title and artist.
 */
class AlbumMergeTest {
    private fun album(
        id: String,
        title: String,
        artist: String = "Muse",
        year: Int? = null,
        tracks: Int? = null,
    ) = LibraryAlbum(id = id, title = title, artist = artist, year = year, trackCount = tracks)

    @Test
    fun `an album split across two folders is listed once`() {
        val merged =
            AlbumMerge.byTags(
                listOf(
                    album("4007", "The Wow! Signal", tracks = 9),
                    album("7187", "The Wow! Signal", tracks = 1),
                ),
            )

        assertEquals(1, merged.size)
        assertEquals("The Wow! Signal", merged.single().title)
    }

    @Test
    fun `the merged album counts every track behind it`() {
        val merged =
            AlbumMerge.byTags(
                listOf(album("4007", "The Wow! Signal", tracks = 9), album("7187", "The Wow! Signal", tracks = 1)),
            )

        assertEquals(10, merged.single().trackCount)
    }

    @Test
    fun `the merged album carries both ids`() {
        val merged =
            AlbumMerge.byTags(
                listOf(album("4007", "The Wow! Signal", tracks = 9), album("7187", "The Wow! Signal", tracks = 1)),
            )

        assertEquals(listOf("4007", "7187"), AlbumMerge.idsOf(merged.single().id))
    }

    @Test
    fun `an unmerged album's id still names one row`() {
        assertEquals(listOf("4007"), AlbumMerge.idsOf("4007"))
    }

    @Test
    fun `an empty id names no row`() {
        assertEquals(emptyList<String>(), AlbumMerge.idsOf(""))
    }

    @Test
    fun `same title by another artist stays another album`() {
        val merged =
            AlbumMerge.byTags(
                listOf(album("1", "Greatest Hits", artist = "Muse"), album("2", "Greatest Hits", artist = "Queen")),
            )

        assertEquals(2, merged.size)
    }

    @Test
    fun `case and stray spacing do not make a second album`() {
        val merged =
            AlbumMerge.byTags(listOf(album("1", "The Wow! Signal"), album("2", " the wow! signal ", artist = "muse")))

        assertEquals(1, merged.size)
        // the merged album is spelled the way its first row is
        assertEquals("The Wow! Signal", merged.single().title)
    }

    @Test
    fun `the merged album takes the year of the row that names one`() {
        val merged =
            AlbumMerge.byTags(listOf(album("1", "The Wow! Signal", year = null), album("2", "The Wow! Signal", year = 2014)))

        assertEquals(2014, merged.single().year)
    }

    @Test
    fun `rows without track counts merge to an album without one`() {
        val merged =
            AlbumMerge.byTags(listOf(album("1", "The Wow! Signal"), album("2", "The Wow! Signal")))

        assertEquals(null, merged.single().trackCount)
    }

    @Test
    fun `albums keep the order the source listed them in`() {
        val merged =
            AlbumMerge.byTags(
                listOf(
                    album("1", "Absolution"),
                    album("2", "The Wow! Signal"),
                    album("3", "Absolution"),
                    album("4", "Cryogen"),
                ),
            )

        assertEquals(listOf("Absolution", "The Wow! Signal", "Cryogen"), merged.map { it.title })
    }
}
