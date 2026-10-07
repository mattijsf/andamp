// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The saved-playlists shelf, against a temp directory. */
class PlaylistLibraryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun tracks(vararg titles: String) = titles.mapIndexed { i, t -> Track("t$i", "A", t, 60_000, uri = "content://$i") }

    @Test
    fun `a saved list comes back by name, tracks intact`() {
        val shelf = PlaylistLibrary(temp.newFolder())

        assertTrue(shelf.save("Road trip", tracks("One", "Two")))

        assertEquals(listOf("Road trip" to 2), shelf.list().map { it.name to it.trackCount })
        assertEquals(listOf("One", "Two"), shelf.load("Road trip")?.map { it.title })
    }

    @Test
    fun `saving the same name again replaces the list`() {
        val shelf = PlaylistLibrary(temp.newFolder())
        shelf.save("Mix", tracks("One"))

        shelf.save("Mix", tracks("Two", "Three"))

        assertEquals(1, shelf.list().size)
        assertEquals(listOf("Two", "Three"), shelf.load("Mix")?.map { it.title })
    }

    @Test
    fun `lists sort by name and an unreadable file is still listed`() {
        val dir = temp.newFolder()
        val shelf = PlaylistLibrary(dir)
        shelf.save("zebra", tracks("One"))
        shelf.save("Alpha", tracks("Two"))
        File(dir, "broken.m3u").writeBytes(byteArrayOf(0, 1, 2, 3))

        assertEquals(listOf("Alpha", "broken", "zebra"), shelf.list().map { it.name })
    }

    @Test
    fun `a name with path characters still lands inside the folder`() {
        val dir = temp.newFolder()
        val shelf = PlaylistLibrary(dir)

        assertTrue(shelf.save("../escape/attempt", tracks("One")))

        assertTrue(dir.listFiles().orEmpty().all { it.parentFile == dir })
        assertEquals(1, shelf.list().size)
    }

    @Test
    fun `a deleted list is gone and the others stay`() {
        val shelf = PlaylistLibrary(temp.newFolder())
        shelf.save("Keep", tracks("One"))
        shelf.save("Drop", tracks("Two"))

        assertTrue(shelf.delete("Drop"))

        assertEquals(listOf("Keep"), shelf.list().map { it.name })
        assertNull(shelf.load("Drop"))
    }

    @Test
    fun `deleting a name that was never saved says so`() {
        assertFalse(PlaylistLibrary(temp.newFolder()).delete("nothing"))
    }

    @Test
    fun `loading a name that was never saved is null`() {
        assertNull(PlaylistLibrary(temp.newFolder()).load("nothing"))
    }
}
