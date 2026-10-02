// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.playback.AudioTap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class RealOscilloscopeTest {
    private class FixedTap(
        private val fill: (FloatArray) -> Unit,
    ) : AudioTap {
        override val sampleRateHz = 44100
        override val writtenSamples = 10_000_000L

        override fun readAt(
            endSample: Long,
            out: FloatArray,
        ): Boolean {
            fill(out)
            return true
        }
    }

    @Test
    fun `winamp byte math maps samples onto rows 0 to 15`() {
        assertEquals(7, RealOscilloscope.waveRow(0f)) // silence: byte 127.5 -> round(15.9)-9
        assertEquals(0, RealOscilloscope.waveRow(-1f)) // byte 0 -> -9, clamped
        assertEquals(15, RealOscilloscope.waveRow(1f)) // byte 255 -> 23, clamped
        assertEquals(11, RealOscilloscope.waveRow(0.25f)) // byte 159.4 -> round(19.9)-9 = 11
    }

    @Test
    fun `silence produces a flat center line across all 75 columns`() {
        val state = WinampState()
        RealOscilloscope(FixedTap { it.fill(0f) }, 16).step(state)
        assertEquals(75, state.visWave.size)
        assertTrue(state.visWave.all { it == RealOscilloscope.CENTER_ROW })
    }

    @Test
    fun `a sine sweeps the full row range and stays clamped`() {
        val state = WinampState()
        val tap =
            FixedTap { out ->
                for (i in out.indices) out[i] = sin(2.0 * PI * i / 576).toFloat()
            }
        RealOscilloscope(tap, 16).step(state)
        assertTrue(state.visWave.min() >= 0)
        assertTrue(state.visWave.max() <= 15)
        assertTrue("the wave swings above center", state.visWave.max() > RealOscilloscope.CENTER_ROW + 4)
        assertTrue("the wave swings below center", state.visWave.min() < RealOscilloscope.CENTER_ROW - 4)
    }

    @Test
    fun `columns sample the window at the slice1st stride of 7`() {
        val state = WinampState()
        val tap =
            FixedTap { out ->
                out.fill(0f)
                out[7] = 1f // only column 1's sample position is hot
            }
        RealOscilloscope(tap, 16).step(state)
        assertEquals(15, state.visWave[1])
        assertEquals(RealOscilloscope.CENTER_ROW, state.visWave[0])
        assertEquals(RealOscilloscope.CENTER_ROW, state.visWave[2])
    }
}
