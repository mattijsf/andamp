// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A station's position bar stays at the left and does not follow a drag. `positionFraction`
 * goes by `isStream`, because a station's row can carry a length and a drag previews before
 * it commits.
 */
class StationPosbarTest {
    private fun stateWith(track: Track) =
        WinampState().apply {
            playlist = listOf(track)
            currentIndex = 0
            currentTimeSec = 30
        }

    @Test
    fun `a station's bar stays at the left however long it claims to be`() {
        // a station's row can carry a length, such as an HLS window
        val state = stateWith(Track("s", "", "Example HLS", 57_600, uri = "https://stream.example.org/x.m3u8", isStream = true))

        assertEquals(0f, state.positionFraction, 0.0001f)
    }

    @Test
    fun `a station's bar ignores a seek preview`() {
        val state = stateWith(Track("s", "", "Example HLS", 57_600, uri = "https://stream.example.org/x.m3u8", isStream = true))
        state.seekPreview = 0.9f

        assertEquals("a station's bar ignores the seek preview", 0f, state.positionFraction, 0.0001f)
    }

    @Test
    fun `a file's bar still tracks the position`() {
        val state = stateWith(Track("t", "Muse", "Hexagons", 60_000, uri = "content://media/42"))

        assertEquals(0.5f, state.positionFraction, 0.0001f)
    }

    @Test
    fun `a file's bar shows the seek preview`() {
        val state = stateWith(Track("t", "Muse", "Hexagons", 60_000, uri = "content://media/42"))
        state.seekPreview = 0.9f

        assertEquals(0.9f, state.positionFraction, 0.0001f)
    }
}
