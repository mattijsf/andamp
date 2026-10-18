// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import androidx.test.core.app.ApplicationProvider
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
}
