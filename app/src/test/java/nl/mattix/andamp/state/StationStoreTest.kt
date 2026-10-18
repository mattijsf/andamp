// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The radio dial, against a temp file. */
class StationStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun store() = StationStore(File(temp.newFolder(), "stations.m3u"))

    @Test
    fun `an added station round-trips with a stream-shaped track`() {
        val dial = store()

        assertTrue(dial.add("Radio Example", "http://stream.example.org/live/mp3"))

        val station = dial.list().single()
        assertEquals("Radio Example", station.title)
        assertEquals("http://stream.example.org/live/mp3", station.uri)
        assertEquals(0L, station.durationMs)
        assertTrue(station.id.startsWith("station:"))
    }

    @Test
    fun `stations keep the order they were added in`() {
        val dial = store()
        dial.add("One", "http://a.example/1")
        dial.add("Two", "https://b.example/2")

        assertEquals(listOf("One", "Two"), dial.list().map { it.title })
    }

    @Test
    fun `a blank name falls back to the url's host`() {
        val dial = store()
        dial.add("  ", "http://stream.example.org/live/mp3")

        assertEquals("stream.example.org", dial.list().single().title)
    }

    @Test
    fun `anything that is not http is refused`() {
        val dial = store()
        assertFalse(dial.add("Nope", "ftp://example.com/x"))
        assertFalse(dial.add("Nope", "content://media/1"))
        assertFalse(dial.add("Nope", "not a url"))
        assertTrue(dial.list().isEmpty())
    }

    @Test
    fun `two stations may share a url and stay distinct`() {
        val dial = store()
        dial.add("A", "http://a.example/same")
        dial.add("B", "http://a.example/same")

        assertEquals(2, dial.list().size)
        assertEquals(
            2,
            dial
                .list()
                .map { it.id }
                .toSet()
                .size,
        )
    }

    /**
     * "Nothing there" and "could not be read" are different answers: an add
     * over the second would write the one new station in place of every one
     * the listener had.
     */
    @Test
    fun `a dial that could not be read is not added to, and keeps what it held`() {
        val file = File(temp.newFolder(), "stations.m3u")
        val dial = StationStore(file)
        dial.add("One", "http://a.example/1")
        dial.add("Two", "https://b.example/2")
        file.setReadable(false)
        assumeFalse("this user reads files whatever their mode says", file.canRead())

        assertFalse(dial.add("Three", "http://c.example/3"))

        file.setReadable(true)
        assertEquals(listOf("One", "Two"), dial.list().map { it.title })
    }

    @Test
    fun `a renamed station keeps its place, its stream and the name it plays under`() {
        val dial = store()
        dial.add("One", "http://a.example/1")
        dial.add("Two", "https://b.example/2")
        val id = dial.list().first().id

        assertTrue(dial.rename(id, "  First  "))

        val renamed = dial.list().first()
        assertEquals(listOf("First", "Two"), dial.list().map { it.title })
        assertEquals("First", renamed.defaultName)
        assertEquals("http://a.example/1", renamed.uri)
        assertEquals(id, renamed.id)
    }

    @Test
    fun `a blank name or a station that is not there is not renamed`() {
        val dial = store()
        dial.add("One", "http://a.example/1")

        assertFalse(dial.rename(dial.list().first().id, "   "))
        assertFalse(dial.rename("station:gone", "Two"))
        assertEquals(listOf("One"), dial.list().map { it.title })
    }

    @Test
    fun `a removed station is gone and the others stay in order`() {
        val dial = store()
        dial.add("One", "http://a.example/1")
        dial.add("Two", "https://b.example/2")
        dial.add("Three", "https://c.example/3")

        assertTrue(dial.remove(dial.list()[1].id))

        assertEquals(listOf("One", "Three"), dial.list().map { it.title })
        assertFalse(dial.remove("station:gone"))
    }
}
