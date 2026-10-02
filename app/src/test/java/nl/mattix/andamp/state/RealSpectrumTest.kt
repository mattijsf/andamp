// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.playback.AudioTap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class RealSpectrumTest {
    private class SineTap(
        override val sampleRateHz: Int,
        private val freqHz: Double,
        private val amplitude: Double = 0.8,
    ) : AudioTap {
        override val writtenSamples = 10_000_000L

        override fun readAt(
            endSample: Long,
            out: FloatArray,
        ): Boolean {
            for (i in out.indices) out[i] = (amplitude * sin(2.0 * PI * freqHz * i / sampleRateHz)).toFloat()
            return true
        }
    }

    private class SilentTap : AudioTap {
        override val sampleRateHz = 44100
        override val writtenSamples = 10_000_000L

        override fun readAt(
            endSample: Long,
            out: FloatArray,
        ): Boolean {
            out.fill(0f)
            return true
        }
    }

    @Test
    fun `a 1kHz tone lights the mid band hardest`() {
        val state = WinampState()
        val spectrum = RealSpectrum(SineTap(44100, 1000.0), 16)
        repeat(3) { spectrum.step(state) }

        val strongest = state.visLevels.indices.maxBy { state.visLevels[it] }
        // the band spacing blends linear and logarithmic 9:91, which puts 1kHz about a
        // third across
        assertTrue("1kHz lands in bands 5 to 8: band $strongest", strongest in 5..8)
        assertTrue(state.visLevels[strongest] > 8f)
        // far bands stay quiet
        assertTrue(state.visLevels[0] < state.visLevels[strongest] / 2)
        assertTrue(state.visLevels[18] < state.visLevels[strongest] / 2)
    }

    @Test
    fun `a low tone lights the low bands`() {
        val state = WinampState()
        val spectrum = RealSpectrum(SineTap(44100, 80.0), 16)
        repeat(3) { spectrum.step(state) }
        val strongest = state.visLevels.indices.maxBy { state.visLevels[it] }
        assertTrue("80Hz lands in bands 0 to 2: band $strongest", strongest <= 2)
    }

    private class RecordingTap : AudioTap {
        override val sampleRateHz = 44100
        override var writtenSamples = 100_000L
        val requestedEnds = mutableListOf<Long>()

        override fun readAt(
            endSample: Long,
            out: FloatArray,
        ): Boolean {
            requestedEnds += endSample
            out.fill(0.1f)
            return true
        }
    }

    @Test
    fun `read position keeps advancing while the decode head is idle between bursts`() {
        // players write PCM in decode-time bursts, so the read position advances by about
        // one frame of samples per step while the write head stands still
        val tap = RecordingTap()
        val spectrum = RealSpectrum(tap, 16)
        repeat(5) { spectrum.step(WinampState()) }

        val ends = tap.requestedEnds
        assertTrue(ends.size >= 5)
        val perFrame = 44100 * 16 / 1000 // ~705 samples
        for (i in 2 until ends.size) {
            val advance = ends[i] - ends[i - 1]
            assertTrue(
                "the window advances about one frame of samples: $advance",
                advance in (perFrame / 2).toLong()..(perFrame * 2).toLong(),
            )
        }
    }

    /** The bar height is linear in the magnitude, so half the amplitude is about half the bar. */
    @Test
    fun `a tone at half the amplitude makes a bar under two thirds the height`() {
        fun peakOf(amplitude: Double): Float {
            val state = WinampState()
            RealSpectrum(SineTap(44100, 1000.0, amplitude), 16).let { repeat(3) { _ -> it.step(state) } }
            return state.visLevels.max()
        }

        // below the clamp, so both readings are the signal rather than the ceiling
        val loud = peakOf(0.02)
        val quiet = peakOf(0.01)

        assertTrue("the loud tone is near twice the quiet one: $loud vs $quiet", loud > quiet * 1.6f)
    }

    /** A tone that walks up the spectrum, one frame at a time. */
    private class SweepingTap : AudioTap {
        override val sampleRateHz = 44100

        // enough written to read a window from
        override var writtenSamples = 1_000_000L
        private var frame = 0

        override fun readAt(
            endSample: Long,
            out: FloatArray,
        ): Boolean {
            // a new frequency and a new level each frame
            val hz = 200.0 + (frame % 8) * 400.0
            val amp = 0.05 + (frame % 3) * 0.05
            for (i in out.indices) out[i] = (amp * sin(2.0 * PI * hz * i / sampleRateHz)).toFloat()
            frame++
            writtenSamples += out.size
            return true
        }
    }

    @Test
    fun `a changing signal moves a bar by at least two pixels and leaves some below the ceiling`() {
        val state = WinampState()
        val spectrum = RealSpectrum(SweepingTap(), 16)
        repeat(4) { spectrum.step(state) }
        val before = state.visLevels.copyOf()

        repeat(4) { spectrum.step(state) }

        val moved = before.indices.maxOf { kotlin.math.abs(state.visLevels[it] - before[it]) }
        assertTrue("a band moves at least 2 px in four frames: $moved", moved >= 2f)
        assertTrue(
            "some bands stay below the ceiling",
            state.visLevels.count { it >= SpectrumPhysics.MAX } < SpectrumPhysics.BANDS,
        )
    }

    @Test
    fun `silence decays the bars to zero`() {
        val state = WinampState()
        val loud = RealSpectrum(SineTap(44100, 1000.0), 16)
        repeat(3) { loud.step(state) }
        assertTrue(state.visLevels.max() > 0f)

        val quiet = RealSpectrum(SilentTap(), 16)
        repeat(60) { quiet.step(state) }
        assertEquals(0f, state.visLevels.max(), 0.01f)
    }
}
