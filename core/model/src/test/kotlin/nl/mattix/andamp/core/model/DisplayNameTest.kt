// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The playlist line, rung by rung, after the ladder webamp keeps in `js/trackUtils.ts`:
 * artist and title, else the title, else the name the entry was added under, else the
 * filename off its uri.
 */
class DisplayNameTest {
    private fun track(
        artist: String = "",
        title: String = "",
        defaultName: String? = null,
        uri: String? = null,
    ) = Track("id", artist, title, 0, uri = uri, defaultName = defaultName)

    @Test
    fun `both tags give artist and title`() {
        assertEquals("Muse - Hexagons", track(artist = "Muse", title = "Hexagons").displayName)
    }

    @Test
    fun `no artist gives the bare title`() {
        assertEquals("Hexagons", track(title = "Hexagons").displayName)
    }

    @Test
    fun `an artist without a title gives the name it was added under`() {
        assertEquals("07 track.mp3", track(artist = "Muse", defaultName = "07 track.mp3").displayName)
    }

    @Test
    fun `no tags at all gives the name it was added under`() {
        assertEquals("01 Hexagons.mp3", track(defaultName = "01 Hexagons.mp3").displayName)
    }

    @Test
    fun `nothing but a uri gives the filename off its end`() {
        assertEquals("Hexagons.mp3", track(uri = "content://tree/music/Hexagons.mp3").displayName)
    }

    @Test
    fun `a query string is not part of the filename`() {
        assertEquals("Hexagons.mp3", track(uri = "https://host/Hexagons.mp3?token=abc").displayName)
    }

    @Test
    fun `nothing known at all gives an empty name`() {
        assertEquals("", track().displayName)
    }
}
