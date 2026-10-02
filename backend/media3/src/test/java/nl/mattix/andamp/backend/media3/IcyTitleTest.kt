// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.displayName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What stations send, and what becomes of it. */
class IcyTitleTest {
    @Test
    fun `the usual shape is artist and title`() {
        assertEquals(IcyTitle.Split("Muse", "Hexagons"), IcyTitle.split("Muse - Hexagons"))
    }

    @Test
    fun `a name with no separator is a title with no artist`() {
        assertEquals(IcyTitle.Split("", "The Morning Show"), IcyTitle.split("The Morning Show"))
    }

    @Test
    fun `the first separator wins, so a dashed title stays whole`() {
        assertEquals(IcyTitle.Split("Emerson", "Lake - Palmer - Trilogy"), IcyTitle.split("Emerson - Lake - Palmer - Trilogy"))
    }

    @Test
    fun `a trailing separator is not two fields`() {
        assertEquals(IcyTitle.Split("", "Muse -"), IcyTitle.split("Muse -"))
    }

    @Test
    fun `surrounding space is trimmed`() {
        assertEquals(IcyTitle.Split("Muse", "Hexagons"), IcyTitle.split("  Muse - Hexagons  "))
    }

    @Test
    fun `blank text splits to nothing`() {
        assertNull(IcyTitle.split(null))
        assertNull(IcyTitle.split("   "))
    }

    private fun station(
        title: String = "Radio Example",
        artist: String = "",
    ) = Track("s1", artist, title, 0, uri = "http://radio.example.org/stream.mp3", defaultName = "Radio Example", isStream = true)

    @Test
    fun `a station's row becomes what it is playing`() {
        val now = IcyTitle.applyTo(station(), "Muse - Hexagons")

        assertEquals("Muse - Hexagons", now?.displayName)
    }

    @Test
    fun `a local file is left unchanged`() {
        val file = Track("t1", "Muse", "Hexagons", 6_000, uri = "content://media/42")

        assertNull("a local file is not renamed", IcyTitle.applyTo(file, "Some Station Junk"))
    }

    @Test
    fun `a station repeating itself republishes nothing`() {
        val playing = station(title = "Hexagons", artist = "Muse")

        assertNull(IcyTitle.applyTo(playing, "Muse - Hexagons"))
    }

    @Test
    fun `a station that goes quiet keeps the last song rather than blanking`() {
        val playing = station(title = "Hexagons", artist = "Muse")

        assertNull(IcyTitle.applyTo(playing, ""))
    }

    @Test
    fun `a station that has said nothing yet shows its own name`() {
        assertEquals("Radio Example", station().displayName)
    }

    @Test
    fun `two ID3 fields are taken as they come, not split`() {
        // HLS names the artist in its own frame, so a title with a dash in it
        // is not split
        val now = IcyTitle.applyTo(station(), "Lake - Palmer", artist = "Emerson")

        assertEquals("Emerson", now?.artist)
        assertEquals("Lake - Palmer", now?.title)
    }

    @Test
    fun `an ID3 title with no artist frame falls back to splitting`() {
        val now = IcyTitle.applyTo(station(), "Muse - Hexagons", artist = null)

        assertEquals("Muse", now?.artist)
        assertEquals("Hexagons", now?.title)
    }

    @Test
    fun `a blank artist frame is not an artist`() {
        val now = IcyTitle.applyTo(station(), "Muse - Hexagons", artist = "  ")

        assertEquals("Muse", now?.artist)
        assertEquals("Hexagons", now?.title)
    }
}
