// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a row is called while the player is connecting to it, and when its source cannot be
 * played here. The main window's marquee and the playlist both read [rowLabel].
 */
class RowLabelTest {
    private val station =
        Track(
            "s",
            "",
            "Radio Example",
            0,
            uri = "https://stream.example.org/live/mp3",
            defaultName = "Radio Example",
            isStream = true,
        )
    private val song = Track("t", "Muse", "Hexagons", 6_000, uri = "content://media/42")

    private fun state(
        connecting: Boolean,
        at: Int = 0,
    ) = WinampState().apply {
        this.connecting = connecting
        currentIndex = at
    }

    @Test
    fun `a station being reached for shows its address`() {
        assertEquals(
            "[Connecting] https://stream.example.org/live/mp3",
            state(connecting = true).rowLabel(station, 0),
        )
    }

    @Test
    fun `once it is playing it shows what it is playing`() {
        assertEquals("Radio Example", state(connecting = false).rowLabel(station, 0))
    }

    @Test
    fun `only the row being connected to says so`() {
        assertEquals("Radio Example", state(connecting = true, at = 3).rowLabel(station, 0))
    }

    @Test
    fun `a file never says it is connecting`() {
        assertEquals("Muse - Hexagons", state(connecting = true).rowLabel(song, 0))
    }

    @Test
    fun `a station nobody is connecting to does not say it is`() {
        assertEquals("Radio Example", state(connecting = false).rowLabel(station, 0))
    }

    private val chop = Track("c", "System of a Down", "Chop Suey!", 210, uri = "example:track:chop")

    // the source as the pack that brings it declares it
    private val carried = MusicSource("EXAMPLE", "Example")

    private fun reaching(reach: SourceReach) = WinampState().apply { this.reach = reach }

    private val phoneOnly = SourceReach()
    private val signedOut = SourceReach(listOf(MusicSource.LOCAL, carried))
    private val signedIn = SourceReach(listOf(MusicSource.LOCAL, carried), setOf(carried))

    @Test
    fun `a row whose source is not installed is named from its own address, and keeps its name`() {
        // with no pack for it installed, the source's name comes from the row's uri
        assertEquals("[Missing source: Example] System of a Down - Chop Suey!", reaching(phoneOnly).rowLabel(chop, 0))
    }

    @Test
    fun `a row whose source is signed out is not called missing`() {
        assertEquals("[Signed out: Example] System of a Down - Chop Suey!", reaching(signedOut).rowLabel(chop, 0))
    }

    @Test
    fun `only the rows of the absent source say it`() {
        assertEquals("Muse - Hexagons", reaching(phoneOnly).rowLabel(song, 0))
        assertEquals("System of a Down - Chop Suey!", reaching(signedIn).rowLabel(chop, 0))
        assertEquals(
            "a row shows no source notice before the reach is known",
            "System of a Down - Chop Suey!",
            WinampState().rowLabel(chop, 0),
        )
    }

    @Test
    fun `a pack's own name for its source wins over the one read from an address`() {
        val styled = MusicSource("EXAMPLE", "EXAMPLE PREMIUM")

        assertEquals(
            "[Signed out: EXAMPLE PREMIUM] System of a Down - Chop Suey!",
            reaching(SourceReach(listOf(MusicSource.LOCAL, styled))).rowLabel(chop, 0),
        )
    }
}
