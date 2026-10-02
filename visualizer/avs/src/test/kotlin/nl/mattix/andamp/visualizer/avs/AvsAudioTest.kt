// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * The audio bridge: PCM in, the 576-sample frame out. A pure tone must peak in
 * the right place, silence must stay silent, and the waveform must be the
 * newest samples in the order they arrived.
 */
class AvsAudioTest {
    @Test
    fun `the waveform is the newest samples, oldest first`() {
        val audio = AvsAudio()
        audio.feed(FloatArray(600) { it / 600f })

        val frame = audio.also { it.capture() }.frame(beat = false)

        assertEquals(AvsAudioFrame.SAMPLES, frame.waveform.size)
        assertEquals("the last sample fed is the last sample out", 599 / 600f, frame.waveform.last(), 0.0001f)
        assertEquals((600 - AvsAudioFrame.SAMPLES) / 600f, frame.waveform.first(), 0.0001f)
    }

    @Test
    fun `too few samples pad with silence`() {
        val audio = AvsAudio()
        audio.feed(floatArrayOf(0.5f, -0.5f))

        val frame = audio.also { it.capture() }.frame(beat = false)

        assertEquals(0f, frame.waveform.first(), 0f)
        assertEquals(-0.5f, frame.waveform.last(), 0f)
    }

    @Test
    fun `silence has an empty spectrum`() {
        val audio = AvsAudio()
        audio.feed(FloatArray(1024))

        assertTrue(
            audio
                .also { it.capture() }
                .frame(beat = false)
                .spectrum
                .all { it == 0f },
        )
    }

    @Test
    fun `a pure tone peaks where the tone is`() {
        val audio = AvsAudio()
        // bin 32 of 512 at whatever sample rate: 32 cycles across the window
        audio.feed(FloatArray(1024) { sin(2.0 * PI * 32.0 * it / 512.0).toFloat() })

        val spectrum = audio.also { it.capture() }.frame(beat = false).spectrum
        val peak = spectrum.indices.maxBy { spectrum[it] }

        // bin 32 of 256 lands at 575 * 32/255
        val expected = 32 * (AvsAudioFrame.SAMPLES - 1) / 255
        assertTrue("the peak $peak lies within 4 bins of $expected", peak in expected - 4..expected + 4)
        assertTrue("a full-scale tone deflects fully: ${spectrum[peak]}", spectrum[peak] > 0.9f)
        assertTrue("the spectrum away from the tone is quiet", spectrum[expected / 4] < 0.1f)
    }

    /**
     * Through AVS's log curve a half-scale tone lands at
     * log(0.5*60+1)/log(60) = 0.84, not at 0.5.
     */
    @Test
    fun `a half-scale tone reads where the log curve puts it`() {
        val audio = AvsAudio()
        audio.feed(FloatArray(1024) { (0.5 * sin(2.0 * PI * 32.0 * it / 512.0)).toFloat() })

        val spectrum = audio.also { it.capture() }.frame(beat = false).spectrum
        val peak = spectrum.max()

        assertTrue("a half-scale tone reads near the log curve's 0.84: $peak", peak in 0.78f..0.90f)
    }

    @Test
    fun `the beat flag is carried through`() {
        assertTrue(AvsAudio().also { it.capture() }.frame(beat = true).beat)
    }
}
