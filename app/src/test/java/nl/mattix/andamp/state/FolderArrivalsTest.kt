// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.displayName
import nl.mattix.andamp.core.playback.QueuePatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FolderScan.arrivals]: the rows a picked folder puts on the playlist before its tags
 * are read, and how [QueuePatch] rewrites them as the tags arrive.
 */
class FolderArrivalsTest {
    private fun doc(name: String) = FolderScan.Doc(id = name, name = name, mimeType = "audio/mpeg")

    private val files = listOf(doc("01 Hexagons.mp3"), doc("02 Cryogen.mp3"), doc("03 Unravelling.mp3"))

    private fun arrivals() = FolderScan.arrivals(files, stamp = 42L) { "content://tree/${it.id}" }

    @Test
    fun `there is one arrival per file`() {
        assertEquals(3, arrivals().size)
    }

    @Test
    fun `a row with no tags yet shows its filename`() {
        assertEquals("01 Hexagons.mp3", arrivals()[0].displayName)
    }

    @Test
    fun `every arrival has a uri`() {
        assertTrue(arrivals().all { !it.uri.isNullOrBlank() })
    }

    @Test
    fun `arrivals have distinct ids`() {
        assertEquals(3, arrivals().map { it.id }.toSet().size)
    }

    @Test
    fun `tags landing rewrite the row and leave its neighbors alone`() {
        val queue = arrivals()
        val read = Track(id = queue[1].id, artist = "Muse", title = "Cryogen", durationMs = 301_000)

        val after = QueuePatch.apply(queue, listOf(read))

        assertEquals("Muse - Cryogen", after[1].displayName)
        assertEquals("01 Hexagons.mp3", after[0].displayName)
        assertEquals("03 Unravelling.mp3", after[2].displayName)
        assertEquals("the row keeps its uri", queue[1].uri, after[1].uri)
    }

    @Test
    fun `a file whose tags carry no artist keeps the bare title`() {
        val queue = arrivals()
        val read = Track(id = queue[0].id, artist = "", title = "Hexagons", durationMs = 1)

        assertEquals("Hexagons", QueuePatch.apply(queue, listOf(read))[0].displayName)
    }

    @Test
    fun `a file whose tags are unreadable keeps its filename`() {
        val queue = arrivals()
        // a read that returns no tags at all
        val read = Track(id = queue[2].id, artist = "", title = "", durationMs = 0)

        assertEquals("03 Unravelling.mp3", QueuePatch.apply(queue, listOf(read))[2].displayName)
    }
}
