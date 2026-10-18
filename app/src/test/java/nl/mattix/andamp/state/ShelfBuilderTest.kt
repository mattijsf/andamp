// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** How the phone's files become the library's artists, albums and tracks. */
class ShelfBuilderTest {
    private var nextId = 1L

    @Suppress("LongParameterList") // the tags a file carries
    private fun file(
        title: String,
        artist: String,
        album: String,
        folderAlbum: Long,
        albumArtist: String? = null,
        number: Int? = null,
        disc: Int? = null,
        year: Int? = null,
        addedAt: Long = 0,
    ): PhoneFile {
        val id = nextId++
        return PhoneFile(
            id,
            "content://media/$id",
            title,
            artist,
            album,
            albumArtist,
            folderAlbum,
            number,
            disc,
            year,
            1000,
            addedAt,
            "Music/",
            null,
        )
    }

    @Test
    fun `an album is its album artist's, and a file without one is its own artist's`() {
        val shelf =
            ShelfBuilder.build(
                listOf(
                    file("Teardrop", "Massive Attack", "Mezzanine", 1),
                    file("Angel", "Massive Attack", "Mezzanine", 1),
                    file("Roads", "Portishead", "Dummy", 2, albumArtist = "Portishead"),
                ),
            )

        assertEquals(setOf("Massive Attack", "Portishead"), shelf.artists.map { it.name }.toSet())
        assertEquals(
            mapOf("Mezzanine" to "Massive Attack", "Dummy" to "Portishead"),
            shelf.albums.associate {
                it.title to
                    it.artist
            },
        )
    }

    @Test
    fun `an untagged album of several artists in one place is a compilation`() {
        val shelf =
            ShelfBuilder.build(
                listOf(
                    file("One", "Alpha", "Mix", 7),
                    file("Two", "Beta", "Mix", 7),
                ),
            )

        assertEquals(listOf(ShelfBuilder.VARIOUS), shelf.artists.map { it.id })
        assertEquals(ShelfBuilder.VARIOUS, shelf.albums.single().artistId)
        assertEquals(2, shelf.albums.single().trackCount)
    }

    @Test
    fun `two albums of one title by two artists stay two`() {
        val shelf =
            ShelfBuilder.build(
                listOf(
                    file("A", "Queen", "Greatest Hits", 1),
                    file("B", "ABBA", "Greatest Hits", 2),
                ),
            )

        assertEquals(2, shelf.albums.size)
        assertNotEquals(shelf.albums[0].id, shelf.albums[1].id)
    }

    @Test
    fun `an album in two places is one, its tracks in disc and number order`() {
        val shelf =
            ShelfBuilder.build(
                listOf(
                    file("Second", "Muse", "Wow", 1, number = 2),
                    file("Disc two", "Muse", "Wow", 2, number = 1, disc = 2),
                    file("First", "Muse", "Wow", 1, number = 1),
                    file("Unnumbered", "Muse", "Wow", 2),
                ),
            )

        val album = shelf.albums.single()
        val order =
            shelf.tracks
                .filter { it.albumId == album.id }
                .sortedBy { it.position }
                .map { it.track.title }
        assertEquals(listOf("Unnumbered", "First", "Second", "Disc two"), order)
    }

    @Test
    fun `an album's year is its earliest, and its arrival its latest file's`() {
        val shelf =
            ShelfBuilder.build(
                listOf(
                    file("A", "Muse", "Wow", 1, year = 2015, addedAt = 10),
                    file("B", "Muse", "Wow", 1, year = 2014, addedAt = 30),
                    file("C", "Muse", "Wow", 1, addedAt = 20),
                ),
            )

        assertEquals(2014, shelf.albums.single().year)
        assertEquals(30L, shelf.albums.single().addedAt)
    }

    @Test
    fun `files that name nobody are an unknown artist's`() {
        val shelf = ShelfBuilder.build(listOf(file("Track 1", "", "Recordings", 3)))

        assertEquals(ShelfBuilder.UNKNOWN, shelf.artists.single().id)
    }

    @Test
    fun `an artist counts their albums and tracks`() {
        val shelf =
            ShelfBuilder.build(
                listOf(
                    file("A", "Muse", "Wow", 1),
                    file("B", "Muse", "Wow", 1),
                    file("C", "Muse", "Absolution", 2),
                ),
            )

        val muse = shelf.artists.single()
        assertEquals(2, muse.albumCount)
        assertEquals(3, muse.trackCount)
    }

    @Test
    fun `ids come from the names, so a shelf made again has the same ones`() {
        val files = listOf(file("A", "Muse", "Wow", 1), file("B", "Muse", "Absolution", 2))

        val first = ShelfBuilder.build(files)
        val again = ShelfBuilder.build(files.reversed())

        assertEquals(first.albums.map { it.id }.toSet(), again.albums.map { it.id }.toSet())
        assertEquals(first.artists.map { it.id }, again.artists.map { it.id })
    }

    @Test
    fun `case, accents and spacing do not make two of one name`() {
        val shelf =
            ShelfBuilder.build(
                listOf(
                    file("A", "Sigur Rós", "Takk", 1),
                    file("B", "sigur  ros", "takk", 2),
                ),
            )

        assertEquals(1, shelf.artists.size)
        assertEquals(1, shelf.albums.size)
        assertEquals("sigur ros", ShelfBuilder.fold(" Sigur  Rós "))
    }
}
