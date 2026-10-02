// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class FftTest {
    @Test
    fun `a pure sine concentrates its energy in the matching bin`() {
        val n = 1024
        val bin = 64
        val samples = FloatArray(n) { sin(2.0 * PI * bin * it / n).toFloat() }
        val out = FloatArray(n / 2)
        Fft(n).magnitudes(samples, out)

        val maxBin = out.indices.maxBy { out[it] }
        assertEquals(bin, maxBin)
        assertEquals("a full-scale sine reads about 1.0", 1f, out[bin], 0.05f)
        // energy elsewhere stays negligible
        assertTrue(out[bin / 2] < 0.01f)
        assertTrue(out[bin * 2] < 0.01f)
    }

    @Test
    fun `silence produces zero magnitudes`() {
        val out = FloatArray(512)
        Fft(1024).magnitudes(FloatArray(1024), out)
        assertTrue(out.all { it == 0f })
    }

    @Test
    fun `two tones appear in both bins`() {
        val n = 1024
        val samples =
            FloatArray(n) {
                (0.5 * sin(2.0 * PI * 32 * it / n) + 0.25 * sin(2.0 * PI * 200 * it / n)).toFloat()
            }
        val out = FloatArray(n / 2)
        Fft(n).magnitudes(samples, out)
        assertEquals(0.5f, out[32], 0.05f)
        assertEquals(0.25f, out[200], 0.05f)
    }
}
