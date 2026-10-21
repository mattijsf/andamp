// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.core.model.LibraryGroup
import nl.mattix.andamp.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The library's own index: a shelf per source, stored whole and read back in order. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryIndexTest {
    private val index = LibraryIndex(ApplicationProvider.getApplicationContext<Application>(), null)

    private fun files(vararg rows: Triple<String, String, String>) =
        rows.mapIndexed { at, (title, artist, album) ->
            PhoneFile(
                at + 1L,
                "content://media/${at + 1}",
                title,
                artist,
                album,
                null,
                album.hashCode().toLong(),
                at + 1,
                null,
                null,
                1000,
                0,
                "Music/",
                null,
            )
        }

    private val shelf =
        ShelfBuilder.build(
            files(
                Triple("Mysterons", "Portishead", "Dummy"),
                Triple("Sour Times", "Portishead", "Dummy"),
                Triple("Teardrop", "Massive Attack", "Mezzanine"),
                Triple("Angel", "Massive Attack", "Mezzanine"),
                Triple("Glory Box", "Portishead", "Dummy"),
            ),
        )

    @Test
    fun `a stored shelf reads back by name, by artist and in album order`() {
        index.replace("phone", shelf, "s1")

        assertEquals(listOf("Massive Attack", "Portishead"), index.artists("phone").map { it.name })
        assertEquals(listOf("Dummy", "Mezzanine"), index.albums("phone").map { it.title })
        val portishead = index.artists("phone").single { it.name == "Portishead" }
        val dummy = index.albums("phone", portishead.id).single()
        assertEquals(listOf("Mysterons", "Sour Times", "Glory Box"), index.tracks("phone", dummy.id).map { it.title })
        assertEquals("s1", index.stamp("phone"))
    }

    @Test
    fun `a shelf stored again replaces the one before`() {
        index.replace("phone", shelf, "s1")

        index.replace("phone", ShelfBuilder.build(files(Triple("Teardrop", "Massive Attack", "Mezzanine"))), "s2")

        assertEquals(listOf("Massive Attack"), index.artists("phone").map { it.name })
        assertEquals(Triple(1, 1, 1), index.counts("phone"))
        assertEquals("s2", index.stamp("phone"))
    }

    @Test
    fun `each source has a shelf of its own`() {
        index.replace("phone", shelf, "s1")

        assertEquals(emptyList<Any>(), index.artists("elsewhere"))
        assertNull(index.stamp("elsewhere"))
    }

    @Test
    fun `a search finds a track by its title, its artist or its album, and keeps to its limit`() {
        index.replace("phone", shelf, "s1")

        assertEquals(listOf("Teardrop"), index.search("phone", "tear", 10).tracks.map { it.title })
        assertEquals(3, index.search("phone", "dummy", 10).tracks.size)
        assertEquals(2, index.search("phone", "dummy", 2).tracks.size)
        assertEquals(emptyList<Any>(), index.search("phone", "zzz", 10).tracks)
    }

    @Test
    fun `a search treats the signs of a LIKE pattern as plain text`() {
        index.replace("phone", ShelfBuilder.build(files(Triple("100% Pure", "Moby", "Play"))), "s1")

        assertEquals(listOf("100% Pure"), index.search("phone", "100%", 10).tracks.map { it.title })
        assertEquals(emptyList<Any>(), index.search("phone", "1_0", 10).tracks)
    }

    @Test
    fun `a track is followed to its album and its artist, and every track comes album by album`() {
        index.replace("phone", shelf, "s1")

        val (album, artist) = index.homeOf("phone", "media:3")

        assertEquals("Mezzanine", album?.title)
        assertEquals("Massive Attack", artist?.name)
        assertEquals(
            listOf("Mysterons", "Sour Times", "Glory Box", "Teardrop", "Angel"),
            index.everyTrack("phone").map { it.title },
        )
        assertEquals(null to null, index.homeOf("phone", "media:99"))
    }

    @Test
    fun `a list finds what the index knows of a track by its address`() {
        val roads =
            PhoneFile(
                7,
                "content://media/7",
                "Roads",
                "Portishead",
                "Dummy",
                null,
                1,
                11,
                null,
                1994,
                1000,
                0,
                "Music/",
                "Trip-Hop",
            )
        index.replace("phone", ShelfBuilder.build(listOf(roads)), "s1")

        assertEquals(
            mapOf("content://media/7" to TrackInfo("Dummy", 11, 1994, "Trip-Hop")),
            index.infoOf(listOf("content://media/7", "content://media/8")),
        )
    }

    @Test
    fun `a source's track has its place on the album for a number, and its album's year`() {
        val at = LibraryIndex.Stored(1, whole = true)
        index.storeAlbums("source:x", listOf(Shelf.ShelfAlbum("l1", "Dummy", "dummy", "a1", "Portishead", 1994, 2, 0)), at)
        index.storeTracks(
            "source:x",
            "l1",
            "Dummy",
            listOf(
                Track("t1", "Portishead", "Mysterons", 1000, uri = "x:1"),
                Track("t2", "Portishead", "Sour Times", 1000, uri = "x:2"),
            ),
            at,
        )

        assertEquals(TrackInfo("Dummy", 2, 1994, null), index.infoOf(listOf("x:2"))["x:2"])
    }

    @Test
    fun `the fields offered to a list are the ones the index has for some track`() {
        assertEquals(setOf(TrackField.ARTIST, TrackField.TITLE), index.knownFields())

        index.replace("phone", shelf, "s1")

        assertEquals(setOf(TrackField.ARTIST, TrackField.TITLE, TrackField.ALBUM, TrackField.NUMBER), index.knownFields())
    }

    @Test
    fun `a source's folders read back in its order, found by name, and go with the source`() {
        val folders =
            listOf(LibraryGroup("/srv/b", "music/b", 2), LibraryGroup("/srv/a", "music/a"), LibraryGroup("/old/a", "music/a", 1))
        val at = LibraryIndex.Stored(1, whole = true)

        index.storeGroups("source:example", LibraryIndex.Piece.FOLDERS, folders, at)
        index.storeGroups("source:example", LibraryIndex.Piece.GENRES, listOf(LibraryGroup("rock", "Rock", 4)), at)

        assertEquals(folders, index.storedGroups("source:example", LibraryIndex.Piece.FOLDERS))
        assertEquals(listOf("/srv/a", "/old/a"), index.groupIds("source:example", LibraryIndex.Piece.FOLDERS, "music/a"))
        assertEquals(at, index.stored("source:example", LibraryIndex.Piece.FOLDERS))

        index.clear("source:example")

        assertEquals(emptyList<LibraryGroup>(), index.storedGroups("source:example", LibraryIndex.Piece.GENRES))
        assertNull(index.stored("source:example", LibraryIndex.Piece.FOLDERS))
    }
}
