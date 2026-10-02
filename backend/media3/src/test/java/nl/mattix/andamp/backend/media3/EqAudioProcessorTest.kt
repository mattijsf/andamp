// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import nl.mattix.andamp.core.model.EqSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class EqAudioProcessorTest {
    private fun configured(settings: EqSettings): EqAudioProcessor {
        val eq = EqAudioProcessor()
        eq.update(settings)
        eq.configure(AudioProcessor.AudioFormat(44100, 1, C.ENCODING_PCM_16BIT))
        eq.flush()
        return eq
    }

    private fun sineBuffer(
        freqHz: Double,
        samples: Int,
        amplitude: Double = 0.25,
    ): ByteBuffer {
        // little-endian content in a buffer whose view is big-endian by
        // default: the processor must not rely on the view's order
        val buffer = ByteBuffer.allocateDirect(samples * 2)
        for (i in 0 until samples) {
            val v = (sin(2.0 * PI * freqHz * i / 44100) * amplitude * 32767).toInt()
            buffer.put((v and 0xFF).toByte())
            buffer.put(((v shr 8) and 0xFF).toByte())
        }
        buffer.flip()
        return buffer
    }

    private fun rms(buffer: ByteBuffer): Double {
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        var squares = 0.0
        var n = 0
        while (b.remaining() >= 2) {
            val s = b.short / 32768.0
            squares += s * s
            n++
        }
        return sqrt(squares / n)
    }

    @Test
    fun `a flat EQ passes bytes through untouched`() {
        // enabled, with every band at 0 dB: the filters are skipped
        val eq = configured(EqSettings.FLAT)
        val input = sineBuffer(1000.0, 4410)
        val expected = ByteArray(input.remaining()).also { input.duplicate().get(it) }

        eq.queueInput(input)

        val out = eq.output
        val actual = ByteArray(out.remaining()).also { out.get(it) }
        assertArrayEquals(expected, actual)
    }

    @Test
    fun `a band leaving flat filters again`() {
        // leaving the bypass resets the biquads, whose state dates from before it
        val eq = configured(EqSettings.FLAT)
        eq.queueInput(sineBuffer(1000.0, 4410))
        eq.output.let { it.position(it.limit()) }

        eq.update(EqSettings.FLAT.copy(bandsDb = List(10) { if (it == 4) 12f else 0f }))
        val input = sineBuffer(1000.0, 4410)
        eq.queueInput(input)

        val boosted = rms(eq.output)
        val flat = rms(sineBuffer(1000.0, 4410))
        assertTrue("a boosted band raises the level: $boosted vs $flat", boosted > flat * 1.5)
    }

    @Test
    fun `disabled EQ passes bytes through untouched`() {
        val eq = configured(EqSettings(enabled = false, preampDb = 12f, bandsDb = List(10) { 12f }))
        val input = sineBuffer(1000.0, 4410)
        val expected = ByteArray(input.remaining()).also { input.duplicate().get(it) }
        eq.queueInput(input)
        val out = eq.output
        val actual = ByteArray(out.remaining()).also { out.get(it) }
        assertArrayEquals(expected, actual)
    }

    @Test
    fun `boosting the 1k band raises a 1kHz tone`() {
        val bands = MutableList(10) { 0f }
        bands[4] = 12f // 1kHz band
        val eq = configured(EqSettings(enabled = true, preampDb = 0f, bandsDb = bands))
        val flat = configured(EqSettings.FLAT)

        val boosted =
            run {
                eq.queueInput(sineBuffer(1000.0, 44100))
                rms(eq.output)
            }
        val reference =
            run {
                flat.queueInput(sineBuffer(1000.0, 44100))
                rms(flat.output)
            }
        assertTrue("the boost raises the level more than 2.5x: ${boosted / reference}", boosted / reference > 2.5)
    }

    @Test
    fun `preamp scales the whole signal`() {
        val eq = configured(EqSettings(enabled = true, preampDb = -6f, bandsDb = List(10) { 0f }))
        eq.queueInput(sineBuffer(1000.0, 44100))
        val outRms = rms(eq.output)
        assertEquals(0.25 / sqrt(2.0) * 0.5, outRms, 0.01) // half of the input's RMS
    }

    @Test
    fun `bands above Nyquist stay stable at low sample rates`() {
        // 12k/14k/16k bands on a 22.05 kHz source would give unstable filters
        // (poles outside the unit circle), NaN state and silence
        val eq = EqAudioProcessor()
        eq.update(EqSettings(enabled = true, preampDb = 0f, bandsDb = List(10) { 12f }))
        eq.configure(AudioProcessor.AudioFormat(22050, 1, C.ENCODING_PCM_16BIT))
        eq.flush()
        eq.queueInput(sineBuffer(1000.0, 22050))
        val out = rms(eq.output)
        assertTrue("the output does not collapse to silence, rms=$out", out > 0.05)
        assertTrue("the output stays finite and clamped, rms=$out", out < 1.0)
    }

    @Test
    fun `unsupported encodings deactivate the processor`() {
        val eq = EqAudioProcessor()
        val format = eq.configure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_24BIT))
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, format)
        assertTrue(!eq.isActive)
    }
}
