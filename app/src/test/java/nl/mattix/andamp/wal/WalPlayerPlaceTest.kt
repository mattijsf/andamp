// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.state.WinampState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The place in the song a skin reads: carried on between the backend's reports at the song's
 * pace, closing the gap to each report a little each frame, held while paused or starting, and
 * never past the length.
 */
class WalPlayerPlaceTest {
    private val state =
        WinampState().apply {
            playlist = List(3) { song(it) }
            currentIndex = 0
            transport = Transport.Playing
        }
    private val controls = DeferredControls(state)

    /** Set to stop the backend's reports where they are, as a stalled backend does. */
    private var stalledAt: Long? = null

    private val player =
        WalPlayer(
            controls = controls,
            state = state,
            tapOf = { null },
            positionMs = { stalledAt ?: controls.reportedMs },
            openFile = {},
            post = { it.run() },
            clock = { controls.now },
        )

    /** Moves the clock on in 16 ms frames, reading the place each frame as the engine's tick does; the last place read. */
    private fun frames(ms: Int): Int {
        var place = player.positionMs
        repeat(ms / FRAME_MS) {
            controls.advance(FRAME_MS.toLong())
            place = player.positionMs
        }
        return place
    }

    @Test
    fun `the place rises smoothly between the backend's reports`() {
        controls.advance(1_000)
        var last = player.positionMs
        repeat(150) {
            controls.advance(16)
            val now = player.positionMs
            assertTrue("never back, never a leap: $last then $now", now - last in 0..32)
            last = now
        }
        val song = 1_000 + 150 * 16
        assertTrue("on the song's place, $song: $last", last in song - 20..song)
    }

    @Test
    fun `a report that trails the carried place holds it instead of stepping back`() {
        // a backend whose clock runs 4% slow: each report is behind where the place was carried
        var reported = 0L
        stalledAt = 0L
        var last = player.positionMs
        repeat(200) { frame ->
            controls.advance(FRAME_MS.toLong())
            // a report every 250 ms, 240 ms on from the last
            if (controls.now / 250 != (controls.now - FRAME_MS) / 250) {
                reported += 240
                stalledAt = reported
            }
            val now = player.positionMs
            assertTrue("never back: $last then $now, frame $frame", now >= last)
            last = now
        }
        assertTrue("never more than a second past the report: $last", last <= reported + 1_000)
    }

    @Test
    fun `a report that falls behind slows the place down instead of stopping it`() {
        // reports every 250 ms of 250 ms each, but one moves only 17 ms on, as a backend's does now and then
        var reported = 1_000L
        stalledAt = reported
        controls.advance(1_000)
        var last = player.positionMs
        var glitched = false
        repeat(120) { frame ->
            controls.advance(FRAME_MS.toLong())
            if (controls.now % 250 < FRAME_MS) {
                reported += if (!glitched && frame > 20) 17 else 250
                if (reported % 250 == 17L) glitched = true
                stalledAt = reported
            }
            val now = player.positionMs
            assertTrue("on every frame, never back: $last then $now, frame $frame", now > last)
            last = now
        }
        assertTrue("the glitch happened", glitched)
    }

    @Test
    fun `a song still starting is not carried past its first report`() {
        stalledAt = 0L
        frames(500)
        assertEquals("held at the start while the backend says 0", 0, player.positionMs)
        stalledAt = 25L
        assertTrue("then carried on from the report", frames(160) in 150..185)
    }

    @Test
    fun `the place runs at most a second past the backend's last report`() {
        frames(10_000)
        stalledAt = controls.reportedMs

        frames(5_000)

        assertEquals(stalledAt!! + 1_000, player.positionMs.toLong())
    }

    @Test
    fun `the place holds while paused`() {
        controls.advance(3_000)
        player.positionMs
        state.transport = Transport.Paused
        val paused = player.positionMs

        controls.advance(2_000)

        assertEquals(paused, player.positionMs)
        assertTrue(paused in 3_000..3_016)
    }

    @Test
    fun `playing again after a pause goes on from where it stopped`() {
        frames(3_000)
        state.transport = Transport.Paused
        frames(5_000)
        val paused = player.positionMs

        state.transport = Transport.Playing
        val resumed = player.positionMs

        assertEquals(paused, resumed)
        assertTrue(frames(160) - resumed in 140..160)
    }

    @Test
    fun `the place never passes the length`() {
        state.playlist = listOf(song(0).copy(durationMs = 1_100))
        controls.advance(1_000)
        player.positionMs
        stalledAt = controls.reportedMs
        controls.advance(900)

        assertEquals(1_100, player.positionMs)
    }

    private companion object {
        const val FRAME_MS = 16

        fun song(n: Int) =
            Track(
                id = "song-$n",
                artist = "Artist $n",
                title = "Song $n",
                durationMs = 200_000,
                bitrateKbps = 128,
                sampleRateKhz = 44,
                uri = "file:///music/$n.mp3",
            )
    }
}
