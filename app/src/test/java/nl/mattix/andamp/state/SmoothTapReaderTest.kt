// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.playback.AudioTap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Where behind the write head a visualizer reads. A player hands audio to the device well
 * before it is heard, so reading too close to the write head draws a beat before it sounds.
 */
class SmoothTapReaderTest {
    private class Tap(
        override var aheadSamples: Long = 0L,
    ) : AudioTap {
        override val sampleRateHz = RATE
        override var writtenSamples = 1_000_000L
        var askedFor = -1L

        override fun readAt(
            endSample: Long,
            out: FloatArray,
        ): Boolean {
            askedFor = endSample
            return true
        }
    }

    @Test
    fun `it reads as far behind the write head as the tap says the ear is`() {
        // what a phone with a 750 ms sink buffer and some output delay reports
        val tap = Tap(aheadSamples = 37_926)
        val reader = SmoothTapReader(tap, FRAME_MS)

        assertTrue(reader.read(FloatArray(480)))

        assertEquals(tap.writtenSamples - 37_926, tap.askedFor)
    }

    @Test
    fun `a tap that cannot tell is read a quarter of a second behind`() {
        val tap = Tap()
        val reader = SmoothTapReader(tap, FRAME_MS)

        assertTrue(reader.read(FloatArray(480)))

        assertEquals(tap.writtenSamples - RATE / 4, tap.askedFor)
    }

    @Test
    fun `it follows the ear when the distance changes, without jumping`() {
        val tap = Tap(aheadSamples = 11_025)
        val reader = SmoothTapReader(tap, FRAME_MS)
        val out = FloatArray(480)
        reader.read(out)

        // the output moved to a speaker that is further behind
        tap.aheadSamples = 33_075
        var last = tap.askedFor
        repeat(600) {
            tap.writtenSamples += RATE * FRAME_MS / 1000
            reader.read(out)
            assertTrue("the window never moves backwards by a jump", tap.askedFor - last > -RATE / 20)
            last = tap.askedFor
        }

        val behind = tap.writtenSamples - tap.askedFor
        assertTrue("settled near the new distance, was $behind", behind in 31_000..35_000)
    }

    @Test
    fun `uneven steps keep the window on the ear without drifting`() {
        val tap = Tap(aheadSamples = 11_025)
        val reader = SmoothTapReader(tap, FRAME_MS)
        val out = FloatArray(480)
        val start = tap.writtenSamples
        reader.read(out)

        var ms = 0L
        repeat(100) {
            for (step in longArrayOf(8, 33, 0, 100)) {
                ms += step
                tap.writtenSamples = start + RATE * ms / 1000
                val before = tap.askedFor

                assertTrue(reader.read(out, step))

                val target = tap.writtenSamples - tap.aheadSamples
                assertTrue("on the ear after $ms ms: off by ${tap.askedFor - target}", abs(tap.askedFor - target) <= 20)
                val moved = tap.askedFor - before
                assertTrue("a $step ms step moves $step ms of audio: $moved", abs(moved - RATE * step / 1000) <= 20)
            }
        }
    }

    @Test
    fun `uneven steps over a write head that moves in bursts slide the window without jumping`() {
        val tap = Tap(aheadSamples = 11_025)
        val reader = SmoothTapReader(tap, FRAME_MS)
        val out = FloatArray(480)
        val start = tap.writtenSamples

        // the player decodes 200 ms at a time, ahead of what is heard
        fun decodedBy(ms: Long) = start + RATE * ((ms / BURST_MS + 1) * BURST_MS) / 1000
        tap.writtenSamples = decodedBy(0)
        reader.read(out)
        val first = tap.askedFor

        var ms = 0L
        repeat(100) {
            for (step in longArrayOf(8, 33, 0, 100)) {
                ms += step
                tap.writtenSamples = decodedBy(ms)
                val before = tap.askedFor

                assertTrue(reader.read(out, step))

                val moved = tap.askedFor - before
                assertTrue(
                    "a $step ms step moves about $step ms of audio, never a burst: $moved",
                    abs(moved - RATE * step / 1000) <= RATE / 50,
                )
                val heard = first + RATE * ms / 1000
                assertTrue(
                    "within a burst of the ear after $ms ms: ${tap.askedFor - heard}",
                    abs(tap.askedFor - heard) <= RATE * BURST_MS / 1000,
                )
            }
        }
    }

    private companion object {
        const val RATE = 44_100
        const val FRAME_MS = 16L
        const val BURST_MS = 200L
    }
}
