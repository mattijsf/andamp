// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

class AvsPlaylistTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a directory of presets plays in name order`() {
        val playlist = playlist("b.avs", "a.avs", "c.avs")

        assertEquals("a.avs", playlist.current?.substringAfterLast('/'))
        playlist.next()
        assertEquals("b.avs", playlist.current?.substringAfterLast('/'))
    }

    @Test
    fun `presets in subdirectories count`() {
        folder.newFolder("Nullsoft")
        folder.newFile("Nullsoft/deep.avs")
        folder.newFile("top.avs")

        val playlist = AvsPlaylist().also { it.setDirectory(folder.root, shuffle = false) }

        assertEquals(2, playlist.paths.size)
    }

    @Test
    fun `only avs files count, whatever their case`() {
        val playlist = playlist("one.AVS", "skip.milk", "note.txt")

        assertEquals(1, playlist.paths.size)
    }

    @Test
    fun `next and previous wrap around`() {
        val playlist = playlist("a.avs", "b.avs")

        playlist.previous()
        assertEquals("b.avs", playlist.current?.substringAfterLast('/'))
        playlist.next()
        assertEquals("a.avs", playlist.current?.substringAfterLast('/'))
    }

    @Test
    fun `go to indexes the visible list, not the shuffled order`() {
        val playlist = playlist("a.avs", "b.avs", "c.avs", shuffle = true, random = Random(7))

        playlist.goTo(2)

        assertEquals("c.avs", playlist.current?.substringAfterLast('/'))
        assertEquals(2, playlist.position)
    }

    @Test
    fun `shuffle plays every preset once per cycle`() {
        val playlist = playlist("a.avs", "b.avs", "c.avs", "d.avs", shuffle = true, random = Random(3))

        val seen = (0 until 4).map { playlist.current.also { playlist.next() } }

        assertEquals(4, seen.distinct().size)
    }

    @Test
    fun `a rescan that changes nothing says so`() {
        val playlist = playlist("a.avs")

        assertFalse(playlist.setDirectory(folder.root, shuffle = false))
    }

    @Test
    fun `the current preset survives a rescan that keeps it`() {
        val playlist = playlist("a.avs", "b.avs")
        playlist.next()

        folder.newFile("c.avs")
        playlist.setDirectory(folder.root, shuffle = false)

        assertEquals("b.avs", playlist.current?.substringAfterLast('/'))
    }

    @Test
    fun `no directory is an empty list`() {
        val playlist = AvsPlaylist()
        playlist.setDirectory(null, shuffle = false)

        assertNull(playlist.current)
        assertEquals(-1, playlist.position)
        playlist.next() // does nothing on an empty list
        assertTrue(playlist.paths.isEmpty())
    }

    /**
     * Guards against the render loop walking the directory every frame. The
     * same File instance is not rescanned, whatever the disk did; a new
     * instance of the same path is rescanned once.
     */
    @Test
    fun `the sync guard touches the disk only when its inputs change`() {
        folder.newFile("a.avs")
        val playlist = AvsPlaylist()
        var announced = 0
        val sync = PlaylistSync(playlist) { announced++ }
        val directory = folder.root

        sync.apply(directory, shuffle = false)
        assertEquals(1, playlist.paths.size)

        folder.newFile("b.avs")
        sync.apply(directory, shuffle = false)
        assertEquals("the same instance is not rescanned", 1, playlist.paths.size)

        sync.apply(java.io.File(directory.path), shuffle = false)
        assertEquals("a fresh instance of the same path rescans", 2, playlist.paths.size)
        assertEquals(2, announced)
    }

    @Test
    fun `a shuffle change alone reaches the playlist`() {
        folder.newFile("a.avs")
        val playlist = AvsPlaylist()
        val sync = PlaylistSync(playlist) {}
        val directory = folder.root

        sync.apply(directory, shuffle = false)
        sync.apply(directory, shuffle = true)

        assertEquals(1, playlist.paths.size)
    }

    /** A rebuilt render thread steers back to the preset that was playing. */
    @Test
    fun `a path can be found again in a fresh shuffled playlist`() {
        val first = playlist("a.avs", "b.avs", "c.avs", shuffle = true, random = Random(1))
        val playing = first.current!!

        // a new thread means a new playlist and a new shuffle order
        val rebuilt = AvsPlaylist(Random(99)).also { it.setDirectory(folder.root, shuffle = true) }

        assertTrue(rebuilt.goTo(playing))
        assertEquals(playing, rebuilt.current)
        assertFalse("a path no longer on disk is refused", rebuilt.goTo("/gone/x.avs"))
    }

    private fun playlist(
        vararg names: String,
        shuffle: Boolean = false,
        random: Random = Random.Default,
    ): AvsPlaylist {
        names.forEach { folder.newFile(it) }
        return AvsPlaylist(random).also { it.setDirectory(folder.root, shuffle) }
    }
}
