// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import nl.mattix.andamp.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Play on an empty playlist opens the file browser, as Winamp does.
 *
 * The rule is tested here once for both windows that have it: the full player and the
 * window-shade strip.
 */
class PlayIntentTest {
    private var played = 0
    private var opened = 0

    private fun tap(queue: List<Track>) = playOrOpen(queue, { played++ }, { opened++ })

    private fun track(title: String) = Track(id = title, artist = "Muse", title = title, durationMs = 1000)

    @Test
    fun `play on an empty playlist opens the file browser`() {
        tap(emptyList())

        assertEquals("the file browser opens", 1, opened)
        assertEquals("nothing plays", 0, played)
    }

    @Test
    fun `play with something in the queue plays it`() {
        tap(listOf(track("The Dark Forest")))

        assertEquals(1, played)
        assertEquals("the file browser stays closed", 0, opened)
    }

    @Test
    fun `the queue emptying changes what the same button does`() {
        // the button is built once and read live, so the rule has to answer to
        // the queue as it is at the press, not as it was at construction
        tap(listOf(track("Hexagons")))
        tap(emptyList())

        assertEquals(1, played)
        assertEquals(1, opened)
    }
}
