// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Whose row this is, read from its address alone. The playlist routes every row by this
 * answer, and names a row whose source is absent by it.
 */
class SourceForRowsTest {
    private fun row(uri: String?) = Track("t", "A", "T", 1_000, uri = uri)

    @Test
    fun `a file on the phone belongs to the phone`() {
        assertEquals(MusicSource.LOCAL, SourceForRows.of(row("content://media/external/audio/1")))
    }

    @Test
    fun `a file uri belongs to the phone`() {
        assertEquals(MusicSource.LOCAL, SourceForRows.of(row("file:///storage/emulated/0/Music/a.mp3")))
    }

    @Test
    fun `a station belongs to the phone`() {
        assertEquals(MusicSource.LOCAL, SourceForRows.of(row("http://example.com/stream")))
    }

    @Test
    fun `a row with no address, or a relative path, is the phone's`() {
        assertEquals(MusicSource.LOCAL, SourceForRows.of(row(null)))
        assertEquals(MusicSource.LOCAL, SourceForRows.of(row("Music/Muse/Hexagons.mp3")))
    }

    @Test
    fun `a drive letter from a playlist written on a PC is not a source`() {
        assertEquals(MusicSource.LOCAL, SourceForRows.of(row("C:\\Music\\a.mp3")))
    }

    @Test
    fun `any other scheme is the source of that name, and the same source its pack declares`() {
        val found = SourceForRows.of(row("tidal:track:abc"))

        assertEquals(MusicSource("TIDAL", "whatever the pack calls it"), found)
        assertEquals("Tidal", found.label)
    }
}
