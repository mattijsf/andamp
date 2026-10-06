// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import org.junit.Assert.assertEquals
import org.junit.Test

/** How far an audio track runs ahead of the ear, from the frames handed to it, its playback head and its timestamp. */
class TrackAheadMeterTest {
    private fun meter() = TrackAheadMeter(sampleRateHz = 44_100)

    @Test
    fun `it is what was handed over and has not been played`() {
        val meter = meter()
        meter.handed(4_096)
        meter.handed(4_096)

        assertEquals(8_192L, meter.ahead(head = 0))
        assertEquals(7_192L, meter.ahead(head = 1_000))
    }

    @Test
    fun `nothing handed over is no distance`() {
        assertEquals(0L, meter().ahead(head = 0))
    }

    @Test
    fun `a flush starts the count over, with the head at 0 again`() {
        val meter = meter()
        meter.handed(40_000)
        meter.ahead(head = 30_000)

        meter.emptied()
        meter.handed(4_096)

        assertEquals(4_096L, meter.ahead(head = 0))
        assertEquals(4_000L, meter.ahead(head = 96))
    }

    @Test
    fun `a head that starts over when its 32 bits are full goes on counting`() {
        val meter = meter()
        val full = 1L shl 32
        meter.handed(Int.MAX_VALUE)
        meter.ahead(head = Int.MAX_VALUE)
        meter.handed(Int.MAX_VALUE)
        // 100 short of full: past the sign bit, where the count reads as a negative Int
        meter.ahead(head = (full - 100).toInt())
        meter.handed(1_000)

        // handed: 2 * (2^31 - 1) + 1000 = 2^32 + 998; played: 2^32 + 50
        assertEquals(948L, meter.ahead(head = 50))
    }

    @Test
    fun `a head that steps back is followed from where it is`() {
        val meter = meter()
        meter.handed(10_000)
        meter.ahead(head = 6_000)

        assertEquals("a step back plays nothing", 4_000L, meter.ahead(head = 0))
        assertEquals(3_500L, meter.ahead(head = 500))
    }

    @Test
    fun `a head past what was handed over is no distance, and the count carries on from there`() {
        val meter = meter()
        meter.handed(1_000)

        assertEquals(0L, meter.ahead(head = 1_500))

        meter.handed(4_096)
        assertEquals(4_096L, meter.ahead(head = 1_500))
    }

    @Test
    fun `a timestamp adds what the output holds behind the track`() {
        val meter = meter()
        meter.handed(20_000)

        // frame 4,000 reached the speaker 100 ms ago, so frame 8,410 does now
        meter.presented(head = 10_000, framePosition = 4_000, sinceNanos = 100_000_000)

        assertEquals("10,000 in the track and 1,590 behind it", 11_590L, meter.ahead(head = 10_000))
    }

    @Test
    fun `the last timestamp counts until the next, across a flush`() {
        val meter = meter()
        meter.handed(20_000)
        meter.presented(head = 10_000, framePosition = 8_000, sinceNanos = 0)

        meter.emptied()
        meter.handed(4_096)

        assertEquals(6_096L, meter.ahead(head = 0))
    }

    @Test
    fun `an old timestamp is left out`() {
        val meter = meter()
        meter.handed(20_000)
        meter.presented(head = 10_000, framePosition = 9_000, sinceNanos = 0)

        // a paused track repeats its last timestamp
        meter.presented(head = 10_000, framePosition = 4_000, sinceNanos = 3_000_000_000)

        assertEquals(11_000L, meter.ahead(head = 10_000))
    }

    @Test
    fun `a timestamp of a track nothing has come out of yet is left out`() {
        val meter = meter()
        meter.handed(20_000)
        meter.presented(head = 10_000, framePosition = 9_000, sinceNanos = 0)
        meter.emptied()
        meter.handed(8_192)

        meter.presented(head = 5_292, framePosition = 0, sinceNanos = 0)

        assertEquals(8_192L - 5_292 + 1_000, meter.ahead(head = 5_292))
    }

    @Test
    fun `a timestamp ahead of the head, or seconds behind it, is left out`() {
        val meter = meter()
        meter.handed(400_000)
        meter.presented(head = 200_000, framePosition = 199_000, sinceNanos = 0)

        meter.presented(head = 200_000, framePosition = 200_500, sinceNanos = 0)
        meter.presented(head = 200_000, framePosition = 1_000, sinceNanos = 0)

        assertEquals(201_000L, meter.ahead(head = 200_000))
    }

    @Test
    fun `a timestamp holds across the point where the head starts over`() {
        val meter = meter()
        val full = 1L shl 32
        meter.handed(5_000)

        // the head is 50 past the point, the timestamp still 950 before it
        meter.presented(head = 50, framePosition = full - 950, sinceNanos = 0)

        assertEquals(5_000L - 50 + 1_000, meter.ahead(head = 50))
    }
}
