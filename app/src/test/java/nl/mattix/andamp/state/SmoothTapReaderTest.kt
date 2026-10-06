// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.playback.AudioTap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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

    private companion object {
        const val RATE = 44_100
        const val FRAME_MS = 16L
    }
}
