// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.playback.AudioTap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** The analyzer's columns before any bar is made of them, at the 75 a modern skin's vis takes. */
class SpectrumColumnsTest {
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

    /**
     * The column a tone lands in: the bin it falls in (1024-point window), placed by the same
     * blend of linear and logarithmic spacing the columns use.
     */
    private fun columnOf(
        hz: Double,
        points: Int,
    ): Int {
        val bin = hz / (44_100.0 / 1024)
        return (0 until points).minBy { x ->
            val linear = x.toDouble() / (points - 1) * 511
            val log = 512.0.pow(x.toDouble() / (points - 1))
            kotlin.math.abs(0.09 * linear + 0.91 * log - bin)
        }
    }

    @Test
    fun `a 1kHz tone peaks in its column of 75`() {
        val columns = SpectrumColumns(SineTap(44_100, 1_000.0, amplitude = 0.01), 16, 75)

        assertTrue(columns.read())

        val values = columns.values
        assertEquals(75, values.size)
        val strongest = values.indices.maxBy { values[it] }
        val expected = columnOf(1_000.0, 75)
        assertTrue("1kHz peaks at column $expected: column $strongest", strongest in expected - 1..expected + 1)
        assertTrue(values[0] < values[strongest] / 4)
        assertTrue(values[74] < values[strongest] / 4)
    }

    @Test
    fun `a low tone peaks in a low column`() {
        val columns = SpectrumColumns(SineTap(44_100, 80.0, amplitude = 0.01), 16, 75)
        columns.read()

        val strongest = columns.values.indices.maxBy { columns.values[it] }

        assertTrue("80Hz in the first columns: column $strongest", strongest <= columnOf(80.0, 75) + 1)
    }

    @Test
    fun `the peak is the loudest sample of the window`() {
        val columns = SpectrumColumns(SineTap(44_100, 1_000.0, amplitude = 0.5), 16, 75)
        columns.read()

        assertEquals(0.5f, columns.peak, 0.01f)
    }

    @Test
    fun `samples that are not numbers read as silence`() {
        val broken =
            object : AudioTap {
                override val sampleRateHz = 44_100
                override val writtenSamples = 10_000_000L

                override fun readAt(
                    endSample: Long,
                    out: FloatArray,
                ): Boolean {
                    val samples = floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, 0.25f)
                    for (i in out.indices) out[i] = samples[i % samples.size]
                    return true
                }
            }
        val columns = SpectrumColumns(broken, 16, 75)

        assertTrue(columns.read())

        assertEquals(0.25f, columns.peak)
        assertTrue(columns.values.all { it.isFinite() })
    }

    @Test
    fun `a tap with no audio yet reads nothing`() {
        val silent =
            object : AudioTap {
                override val sampleRateHz = 0
                override val writtenSamples = 0L

                override fun readAt(
                    endSample: Long,
                    out: FloatArray,
                ) = false
            }
        val columns = SpectrumColumns(silent, 16, 75)

        assertFalse(columns.read(40))
        assertEquals(0f, columns.peak)
        assertTrue(columns.values.all { it == 0f })
    }
}
