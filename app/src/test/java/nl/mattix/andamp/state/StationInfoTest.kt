// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.core.model.StationHeaders
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.TrackInfo
import nl.mattix.andamp.core.player.TrackInfoSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the file info box says about a station: its address, its name, what the broadcaster
 * calls itself, and the bitrate and sample rate. A station is not asked of the file source.
 */
class StationInfoTest {
    private val station =
        Track(
            id = "station:q",
            artist = "",
            title = "Radio Example",
            durationMs = 0,
            uri = "https://stream.example.org/live/mp3",
            defaultName = "Radio Example",
            bitrateKbps = 128,
            sampleRateKhz = 48,
            isStream = true,
        )

    /** A source that describes nothing. */
    private object NoFile : TrackInfoSource {
        override suspend fun describe(track: Track): TrackInfo? = null
    }

    @Test
    fun `a station is described by its url even when it said nothing`() {
        val state = WinampState()
        val info = describe(state, station)

        assertTrue(info!!.lines.any { it.label == "Url" && it.value.contains("stream.example.org") })
    }

    @Test
    fun `what the broadcaster calls itself wins over what was typed`() {
        val state = WinampState().apply { station = StationHeaders(name = "Radio Example Network", genre = "Pop") }
        val info = describe(state, station.copy(defaultName = "q"))

        assertEquals("Radio Example Network", info!!.heading)
        assertTrue(info.lines.any { it.label == "Genre" && it.value == "Pop" })
    }

    @Test
    fun `the station's own name is not repeated as what is playing`() {
        val state = WinampState()
        val info = describe(state, station)

        assertNull(info!!.lines.firstOrNull { it.label == "Now playing" })
    }

    @Test
    fun `a song the station named does appear`() {
        val state = WinampState()
        val info = describe(state, station.copy(artist = "Muse", title = "Hexagons"))

        assertEquals("Muse - Hexagons", info!!.lines.first { it.label == "Now playing" }.value)
    }

    @Test
    fun `a measured bitrate beats what the station intended`() {
        val state = WinampState().apply { station = StationHeaders(bitrateKbps = 96) }
        val info = describe(state, station)

        assertEquals("128 kbps", info!!.lines.first { it.label == "Bitrate" }.value)
    }

    private fun describe(
        state: WinampState,
        track: Track,
    ): TrackInfo? {
        var out: TrackInfo? = null
        runTest {
            val ops = TrackInfoOps(state, NoFile, TestScope(StandardTestDispatcher(testScheduler)))
            ops.show(track)
            advanceUntilIdle()
            out = state.trackInfo
        }
        return out
    }
}
