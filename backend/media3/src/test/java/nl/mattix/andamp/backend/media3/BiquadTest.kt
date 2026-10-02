// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class BiquadTest {
    private fun gainAt(
        filterHz: Float,
        gainDb: Float,
        toneHz: Double,
        sampleRate: Int = 44100,
    ): Double {
        val filter = Biquad(1).apply { setPeaking(filterHz, sampleRate, gainDb, 1.4f) }
        val n = sampleRate // one second: plenty of settle time
        var inSquares = 0.0
        var outSquares = 0.0
        for (i in 0 until n) {
            val x = sin(2.0 * PI * toneHz * i / sampleRate).toFloat()
            val y = filter.process(x, 0)
            if (i >= n / 2) { // measure steady state only
                inSquares += x * x
                outSquares += y * y
            }
        }
        return sqrt(outSquares / inSquares)
    }

    @Test
    fun `plus 12dB peaking boosts its center frequency by 4x`() {
        assertEquals(3.98, gainAt(1000f, 12f, 1000.0), 0.4)
    }

    @Test
    fun `minus 12dB peaking cuts its center frequency to a quarter`() {
        assertEquals(0.25, gainAt(1000f, -12f, 1000.0), 0.05)
    }

    @Test
    fun `frequencies far from the bell pass through unchanged`() {
        assertEquals(1.0, gainAt(1000f, 12f, 60.0), 0.1)
        assertEquals(1.0, gainAt(1000f, 12f, 15000.0), 0.1)
    }

    @Test
    fun `zero dB is an identity filter`() {
        assertEquals(1.0, gainAt(1000f, 0f, 1000.0), 0.001)
    }
}
