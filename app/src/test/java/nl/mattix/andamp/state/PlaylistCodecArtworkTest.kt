// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The artwork uri is stored with each row and comes back with it. */
class PlaylistCodecArtworkTest {
    private val withArt =
        Track("t1", "Alex Warren", "Ordinary", 186_000, uri = "example:track:t1", artworkUri = "https://i.test/abc")

    @Test
    fun `the cover comes back with the row`() {
        val back = PlaylistCodec.decode(PlaylistCodec.encode(PlaylistCodec.Saved(listOf(withArt), 0)))

        assertEquals("https://i.test/abc", back.tracks.single().artworkUri)
    }

    @Test
    fun `a row that never had one still has none`() {
        val bare = withArt.copy(artworkUri = null)

        val back = PlaylistCodec.decode(PlaylistCodec.encode(PlaylistCodec.Saved(listOf(bare), 0)))

        assertNull(back.tracks.single().artworkUri)
    }

    @Test
    fun `a playlist with no artwork field still reads`() {
        val old =
            """
            #EXTM3U
            #ANDAMP-CURRENT:0
            #EXTINF:186,Alex Warren - Ordinary
            #ANDAMP:id=t1;artist=Alex+Warren;title=Ordinary;ms=186000
            example:track:t1
            """.trimIndent()

        val back = PlaylistCodec.decode(old)

        assertEquals("Ordinary", back.tracks.single().title)
        assertNull(back.tracks.single().artworkUri)
    }
}
