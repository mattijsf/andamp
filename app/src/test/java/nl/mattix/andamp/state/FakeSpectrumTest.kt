// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FakeSpectrumTest {
    private val beat = FakeBeat.BAR / 4

    /** The bars as they stand [after] seconds in, stepped at sixty a second. */
    private fun runTo(after: Double): FloatArray {
        val state = WinampState()
        var t = 0.0
        while (t < after) {
            FakeSpectrum.step(state, t)
            t += FRAME
        }
        return state.visLevels.copyOf()
    }

    @Test
    fun `a kick moves the bottom of the display and leaves the top alone`() {
        val before = runTo(2 * beat - FRAME)
        val after = runTo(2 * beat + FRAME)

        assertTrue("the kick moves the bottom band", after[0] > before[0] + MOVED)
        assertTrue("the kick leaves the top band", after[17] <= before[17] + MOVED)
    }

    @Test
    fun `a hat moves the top of the display and leaves the bottom alone`() {
        val before = runTo(beat / 2 - FRAME)
        val after = runTo(beat / 2 + FRAME)

        assertTrue("the hat moves the top band", after[16] > before[16] + MOVED)
        assertTrue("the hat leaves the bottom band", after[0] <= before[0] + MOVED)
    }

    @Test
    fun `the low bands stand taller than the high ones`() {
        val bars = runTo(4 * FakeBeat.BAR)
        val low = (0..5).map { bars[it] }.average()
        val high = (13..18).map { bars[it] }.average()

        assertTrue("low $low stands above high $high", low > high + 1f)
    }

    @Test
    fun `neighboring bars stay near each other`() {
        // the median gap between neighboring bars over ten seconds
        val gaps = mutableListOf<Float>()
        var t = 0.0
        val state = WinampState()
        repeat(600) {
            FakeSpectrum.step(state, t)
            t += FRAME
            for (band in 0 until 18) gaps += abs(state.visLevels[band] - state.visLevels[band + 1])
        }
        val middle = gaps.sorted()[gaps.size / 2]

        assertTrue("neighbors differ by under 1.2 px at the median: $middle", middle < NEIGHBOURLY)
    }

    @Test
    fun `bars fall on more frames than they rise`() {
        val state = WinampState()
        var t = 0.0
        var rose = 0
        var moved = 0
        var last = state.visLevels.copyOf()
        repeat(600) {
            FakeSpectrum.step(state, t)
            t += FRAME
            for (band in 0 until 19) {
                if (state.visLevels[band] > last[band]) rose++
                if (state.visLevels[band] != last[band]) moved++
            }
            last = state.visLevels.copyOf()
        }

        assertTrue("under half the moving frames are a rise: $rose of $moved", rose * 2 < moved)
    }

    @Test
    fun `the same moment always draws the same bars`() {
        assertArrayEquals(runTo(3.0), runTo(3.0), 0f)
    }

    @Test
    fun `levels and peaks stay within the 16px visualizer range over many frames`() {
        val state = WinampState()
        var t = 0.0
        repeat(1000) {
            FakeSpectrum.step(state, t)
            t += 1.0 / 60
            for (i in 0 until 19) {
                assertTrue("level[$i]=${state.visLevels[i]}", state.visLevels[i] in 0f..16f)
                assertTrue("peak[$i]=${state.visPeaks[i]}", state.visPeaks[i] in 0f..16f)
            }
        }
    }

    @Test
    fun `a peak sits below its bar only while falling`() {
        val state = WinampState()
        var t = 0.0
        repeat(200) {
            FakeSpectrum.step(state, t)
            t += 1.0 / 60
            for (i in 0 until 19) {
                // a falling peak may pass through its bar mid-fall
                if (state.visPeaks[i] < state.visLevels[i]) {
                    assertTrue("a peak below its bar is falling", state.visPeakVel[i] > 0f)
                }
            }
        }
    }

    @Test
    fun `reset clears all bands`() {
        val state = WinampState()
        FakeSpectrum.step(state, 1.7)
        FakeSpectrum.reset(state)
        for (i in 0 until 19) {
            assertEquals(0f, state.visLevels[i], 0f)
            assertEquals(0f, state.visPeaks[i], 0f)
            assertEquals(0, state.visPeakHold[i])
        }
    }

    private companion object {
        const val FRAME = 1.0 / 60

        /** The rise, in pixels, that counts as a hit having moved a bar. */
        const val MOVED = 1.5f

        /** The ceiling, in pixels, for the median gap between neighboring bars. */
        const val NEIGHBOURLY = 1.2f
    }
}
