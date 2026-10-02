// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [BookmarkStore] against a real file, because a bookmark has to survive the app closing.
 */
class BookmarkStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var store: BookmarkStore

    private fun track(
        at: Int,
        title: String = "Track $at",
    ) = Track("id$at", "Artist", title, 200_000, uri = "content://songs/$at")

    private fun open() = BookmarkStore(File(folder.root, "bookmarks.m3u")).also { store = it }

    @Test
    fun `a store without a file lists no bookmarks`() {
        assertEquals(emptyList<Track>(), open().list())
    }

    @Test
    fun `a bookmark is read back by a second store on the same file`() {
        open().add(track(1))

        val later = BookmarkStore(File(folder.root, "bookmarks.m3u"))

        assertEquals(listOf("content://songs/1"), later.list().map { it.uri })
        assertEquals("Track 1", later.list().single().title)
    }

    @Test
    fun `bookmarks keep the order they were made in`() {
        val store = open()
        store.add(track(1))
        store.add(track(2))
        store.add(track(3))

        assertEquals(listOf("Track 1", "Track 2", "Track 3"), store.list().map { it.title })
    }

    @Test
    fun `the same place is not bookmarked twice`() {
        // the same uri under another title
        val store = open()
        store.add(track(1))

        assertFalse(store.add(track(1, title = "Same song, other name")))
        assertEquals(1, store.list().size)
    }

    @Test
    fun `a track with nowhere to point at cannot be bookmarked`() {
        // a bookmark without a uri could never be opened
        val store = open()

        assertFalse(store.add(Track("m", "", "Mock", 0, uri = null)))
        assertFalse(store.add(null))
        assertEquals(emptyList<Track>(), store.list())
    }

    @Test
    fun `removing takes the one asked for and leaves the rest in order`() {
        val store = open()
        store.add(track(1))
        store.add(track(2))
        store.add(track(3))

        assertTrue(store.removeAt(1))

        assertEquals(listOf("Track 1", "Track 3"), store.list().map { it.title })
    }

    @Test
    fun `removing what is not there changes nothing`() {
        val store = open()
        store.add(track(1))

        assertFalse(store.removeAt(4))
        assertFalse(store.removeAt(-1))
        assertEquals(1, store.list().size)
    }

    @Test
    fun `a renamed bookmark keeps its place and its destination`() {
        val store = open()
        store.add(track(1))
        store.add(track(2))

        assertTrue(store.rename(0, "  The one with the llama  "))

        val first = store.list().first()
        assertEquals("The one with the llama", first.title)
        assertEquals("content://songs/1", first.uri)
        assertEquals("the rename keeps the order", "Track 2", store.list()[1].title)
    }

    @Test
    fun `a renamed bookmark shows only the name that was typed`() {
        // the playlist draws "artist - title", so the rename clears the artist
        val store = open()
        store.add(track(1))

        store.rename(0, "Llama")

        assertEquals("", store.list().single().artist)
    }

    @Test
    fun `a blank name is refused`() {
        val store = open()
        store.add(track(1))

        assertFalse(store.rename(0, "   "))
        assertEquals("Track 1", store.list().single().title)
    }

    @Test
    fun `a file that is not a playlist reads as no bookmarks`() {
        File(folder.root, "bookmarks.m3u").writeText("this is not a playlist")

        assertEquals(emptyList<Track>(), BookmarkStore(File(folder.root, "bookmarks.m3u")).list())
    }

    /** An edit written over a file that could not be read would replace every bookmark in it. */
    @Test
    fun `bookmarks that could not be read are left as they are`() {
        val file = File(folder.root, "bookmarks.m3u")
        val store = open()
        store.add(track(1))
        store.add(track(2))
        file.setReadable(false)
        assumeFalse("this user reads files whatever their mode says", file.canRead())

        assertFalse(store.add(track(3)))
        assertFalse(store.removeAt(0))
        assertFalse(store.rename(0, "Elsewhere"))

        file.setReadable(true)
        assertEquals(listOf("Track 1", "Track 2"), store.list().map { it.title })
    }
}
