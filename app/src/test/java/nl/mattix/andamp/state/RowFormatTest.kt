// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.state.TrackField.ALBUM
import nl.mattix.andamp.state.TrackField.ARTIST
import nl.mattix.andamp.state.TrackField.GENRE
import nl.mattix.andamp.state.TrackField.NUMBER
import nl.mattix.andamp.state.TrackField.TITLE
import nl.mattix.andamp.state.TrackField.YEAR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a list writes a song: the blocks, the text between them, and what a song without a field shows. */
class RowFormatTest {
    private val roads = Track("t1", "Portishead", "Roads", 300_000, uri = "content://1")
    private val dummy = TrackInfo(album = "Dummy", number = 5, year = 1994, genre = "Trip-Hop")

    @Test
    fun `the default writes a song as the playlist always did`() {
        assertEquals("Portishead - Roads", RowFormat.DEFAULT.write(roads, null))
        assertEquals("Roads", RowFormat.DEFAULT.write(roads.copy(artist = ""), null))
        assertEquals(
            "a track whose tags are not read yet shows the name it was added under",
            "roads.mp3",
            RowFormat.DEFAULT.write(Track("t2", "Portishead", "", 0, defaultName = "roads.mp3"), null),
        )
        assertFalse(RowFormat.DEFAULT.needsIndex)
    }

    @Test
    fun `a format is stored as Winamp writes one and read back the same`() {
        val format = RowFormat.parse("%tracknumber%. %artist% - %title% (%year%)")

        assertEquals(listOf(NUMBER, ARTIST, TITLE, YEAR), format.fields)
        assertEquals(listOf(". ", " - ", " ("), format.gaps)
        assertEquals(")", format.trail)
        assertEquals("%tracknumber%. %artist% - %title% (%year%)", format.encode())
        assertEquals("5. Portishead - Roads (1994)", format.write(roads, dummy))
        assertTrue(format.needsIndex)
    }

    @Test
    fun `a field a song lacks is left out with the text after it, and the lead and trail go with their fields`() {
        val format = RowFormat.parse("[%genre%] %artist% - %album% - %title% (%year%)")

        assertEquals("[Trip-Hop] Portishead - Dummy - Roads (1994)", format.write(roads, dummy))
        assertEquals("Portishead - Roads", format.write(roads, null))
        assertEquals("Portishead - Dummy - Roads", format.write(roads, TrackInfo(album = "Dummy")))
    }

    @Test
    fun `a percent sign is text unless it names a field, and a field named twice is text the second time`() {
        val format = RowFormat.parse("100%% %title% %rating% %title%")

        assertEquals("100% ", format.lead)
        assertEquals(listOf(TITLE), format.fields)
        assertEquals(" %rating% %title%", format.trail)
        assertEquals(format, RowFormat.parse(format.encode()))
        assertEquals("text with no field", emptyList<TrackField>(), RowFormat.parse("just text").fields)
        assertEquals(
            "a format without fields writes the playlist's line",
            "Portishead - Roads",
            RowFormat.parse("x").write(roads, dummy),
        )
    }

    @Test
    fun `moving a block keeps the text between the blocks where it is`() {
        val format = RowFormat.parse("%artist% - %title% · %album%")

        val moved = format.moved(2, 0)

        assertEquals(listOf(ALBUM, ARTIST, TITLE), moved.fields)
        assertEquals(listOf(" - ", " · "), moved.gaps)
        assertEquals("Dummy - Portishead · Roads", moved.write(roads, dummy))
    }

    @Test
    fun `a block added comes last after a dash, and one taken out takes the text before it`() {
        val added = RowFormat.DEFAULT.added(GENRE)
        assertEquals("%artist% - %title% - %genre%", added.encode())

        assertEquals("%artist% - %genre%", added.removed(1).encode())
        assertEquals("the first takes the text after it", "%title% - %genre%", added.removed(0).encode())
        assertEquals("%artist%: %title% - %genre%", added.withGap(0, ": ").encode())
    }

    @Test
    fun `the fields' names spell the format, as the preview and the settings row say it`() {
        assertEquals(
            "Artist - Title (Year)",
            RowFormat.parse("%artist% - %title% (%year%)").spelled { it.name.lowercase().replaceFirstChar(Char::uppercase) },
        )
    }
}
