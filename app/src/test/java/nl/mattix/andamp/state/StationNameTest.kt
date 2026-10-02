// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.displayName
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A station is saved under its own name. ICY metadata rewrites the playing row to the current
 * song; [PlaylistCodec] stores the name the station was added under, or failing that its host.
 */
class StationNameTest {
    private fun saved(track: Track) =
        PlaylistCodec.decode(PlaylistCodec.encode(PlaylistCodec.Saved(listOf(track), 0))).tracks.single()

    @Test
    fun `a station added by hand is saved under the name it was given`() {
        val playing =
            Track(
                "url:1",
                "",
                "JONAS BLUE & MALIVE",
                0,
                uri = "https://stream.example.org/live/mp3",
                defaultName = "Radio Example",
                isStream = true,
            )

        assertEquals("Radio Example", saved(playing).displayName)
    }

    @Test
    fun `an entry without a stream flag is read as a station by its http address`() {
        // no stream flag, no name, and a song in the title
        val legacy =
            """
            #EXTM3U
            #ANDAMP-CURRENT:0
            #EXTINF:0,JONAS BLUE & MALIVE
            #ANDAMP:id=url%3A2;artist=;title=JONAS+BLUE+%26+MALIVE;ms=0
            https://stream.example.org/live/mp3
            """.trimIndent()

        val restored = PlaylistCodec.decode(legacy).tracks.single()

        assertEquals("an http address alone marks the entry as a station", true, restored.isStream)
        assertEquals("stream.example.org", saved(restored).displayName)
    }

    @Test
    fun `a local file is untouched by any of it`() {
        val song = Track("t", "Muse", "Hexagons", 6_000, uri = "content://media/42", defaultName = "07.mp3")

        assertEquals("Muse - Hexagons", saved(song).displayName)
    }

    @Test
    fun `a seekable http track is saved with its own name and length`() {
        // isStream is false here, so the entry is not rewritten as a station
        val overHttp = Track("x", "Muse", "Hexagons", 6_000, uri = "https://cdn.example/track.mp3")

        assertEquals("Muse - Hexagons", saved(overHttp).displayName)
        assertEquals(6_000, saved(overHttp).durationMs)
    }
}
