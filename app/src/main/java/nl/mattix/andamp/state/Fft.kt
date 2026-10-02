// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Minimal iterative radix-2 FFT, allocation-free after construction.
 * [size] must be a power of two.
 */
class Fft(
    val size: Int,
) {
    init {
        require(size > 0 && size and (size - 1) == 0) { "size must be a power of two" }
    }

    private val real = FloatArray(size)
    private val imag = FloatArray(size)
    private val cosTable = FloatArray(size / 2) { cos(-2.0 * Math.PI * it / size).toFloat() }
    private val sinTable = FloatArray(size / 2) { sin(-2.0 * Math.PI * it / size).toFloat() }

    /**
     * Computes magnitudes of the first size/2 bins from [samples] (size
     * elements), normalized so a full-scale sine reads ~1.0 in its bin.
     */
    fun magnitudes(
        samples: FloatArray,
        out: FloatArray,
    ) {
        samples.copyInto(real, 0, 0, size)
        imag.fill(0f)

        // bit-reversal permutation
        var j = 0
        for (i in 0 until size - 1) {
            if (i < j) {
                val tr = real[i]
                real[i] = real[j]
                real[j] = tr
            }
            var m = size shr 1
            while (m in 1..j) {
                j -= m
                m = m shr 1
            }
            j += m
        }

        // butterflies
        var len = 2
        while (len <= size) {
            val half = len shr 1
            val step = size / len
            for (base in 0 until size step len) {
                var k = 0
                for (offset in 0 until half) {
                    val i1 = base + offset
                    val i2 = i1 + half
                    val wr = cosTable[k]
                    val wi = sinTable[k]
                    val tr = real[i2] * wr - imag[i2] * wi
                    val ti = real[i2] * wi + imag[i2] * wr
                    real[i2] = real[i1] - tr
                    imag[i2] = imag[i1] - ti
                    real[i1] += tr
                    imag[i1] += ti
                    k += step
                }
            }
            len = len shl 1
        }

        val norm = 2f / size
        for (bin in 0 until size / 2) {
            out[bin] = sqrt(real[bin] * real[bin] + imag[bin] * imag[bin]) * norm
        }
    }
}
