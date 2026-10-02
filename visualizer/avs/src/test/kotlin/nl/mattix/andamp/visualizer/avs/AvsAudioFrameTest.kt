// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two sampling contracts a frame answers: [AvsAudioFrame.valueAt] for the
 * simple Render components (spectrum stays 0..1) and [AvsAudioFrame.scopeValueAt]
 * for Super Scope's `v` (-1..1 for both sources, fractional-position lerp),
 * with the numbers taken from vis_avs e_superscope.cpp.
 */
class AvsAudioFrameTest {
    @Test
    fun `valueAt keeps a spectrum magnitude in 0 to 1`() {
        val frame = AvsAudioFrame(spectrum = FloatArray(AvsAudioFrame.SAMPLES) { 0.25f })

        assertEquals(0.25f, frame.valueAt(AvsAudioSource.SPECTRUM, 0, 10), 0f)
    }

    @Test
    fun `a scope reads spectrum silence as minus one`() {
        val frame = AvsAudioFrame(spectrum = FloatArray(AvsAudioFrame.SAMPLES))

        assertEquals(-1.0, frame.scopeValueAt(AvsAudioSource.SPECTRUM, 0, 100), 0.0)
    }

    @Test
    fun `a scope reads a full spectrum magnitude as plus one`() {
        val frame = AvsAudioFrame(spectrum = FloatArray(AvsAudioFrame.SAMPLES) { 1f })

        assertEquals(1.0, frame.scopeValueAt(AvsAudioSource.SPECTRUM, 42, 100), 1e-9)
    }

    @Test
    fun `a scope reads waveform samples unscaled when the point count matches the sample count`() {
        // point i sits at fractional sample i * 576 / 576 = i, so there is no lerp
        val waveform = FloatArray(AvsAudioFrame.SAMPLES) { (it % 100) / 100f }
        val frame = AvsAudioFrame(waveform = waveform)

        assertEquals(waveform[123].toDouble(), frame.scopeValueAt(AvsAudioSource.WAVEFORM, 123, AvsAudioFrame.SAMPLES), 1e-9)
    }

    @Test
    fun `a scope with more points than samples interpolates between neighbors`() {
        val waveform = FloatArray(AvsAudioFrame.SAMPLES)
        waveform[0] = 0f
        waveform[1] = 1f
        val frame = AvsAudioFrame(waveform = waveform)

        // point 1 of 1152 sits at sample 1 * 576 / 1152 = 0.5: halfway up
        assertEquals(0.5, frame.scopeValueAt(AvsAudioSource.WAVEFORM, 1, 2 * AvsAudioFrame.SAMPLES), 1e-9)
    }

    @Test
    fun `the sample past the end reads as the last one`() {
        // AVS reads one byte off the end of its buffer here; scopeValueAt
        // clamps to the last sample
        val waveform = FloatArray(AvsAudioFrame.SAMPLES) { if (it == AvsAudioFrame.SAMPLES - 1) 0.75f else 0f }
        val frame = AvsAudioFrame(waveform = waveform)

        assertEquals(0.75, frame.scopeValueAt(AvsAudioSource.WAVEFORM, AvsAudioFrame.SAMPLES - 1, AvsAudioFrame.SAMPLES), 1e-9)
    }

    @Test
    fun `no points or no samples answer zero`() {
        assertEquals(0.0, AvsAudioFrame().scopeValueAt(AvsAudioSource.WAVEFORM, 0, 0), 0.0)
        assertEquals(0.0, AvsAudioFrame(waveform = FloatArray(0)).scopeValueAt(AvsAudioSource.WAVEFORM, 0, 10), 0.0)
    }
}
