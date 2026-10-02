// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3.graph

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * How noise-like the spectrum of a slice of signal is.
 *
 * Spectral flatness is the geometric mean of the power spectrum over its
 * arithmetic mean. Noise spreads its power evenly and the two means meet, near
 * 1; a pitch puts its power in a few bins and the geometric mean collapses,
 * near 0.
 *
 * Echo density measures how noise-like a reverb tail is in time. This looks
 * for fixed pitches, which are spikes in the spectrum (docs/reverb-notes.md).
 *
 * Test-only, with a small FFT of its own, written for clarity.
 */
internal object Spectrum {
    /** Below this fraction of the mean a bin counts as silent, so no logarithm of zero is taken. */
    private const val SILENCE = 1e-12

    /**
     * Spectral flatness of [samples], between 0 (one pitch) and 1 (noise).
     * The length must be a power of two.
     */
    fun flatness(samples: FloatArray): Double {
        val power = power(samples)
        // bin 0 is the average of the slice, not a pitch in it
        val bins = power.size - 1
        if (bins <= 0) return 0.0
        var sum = 0.0
        for (bin in 1..bins) sum += power[bin]
        val mean = sum / bins
        if (mean <= 0.0) return 0.0

        // a floor relative to the slice's own level, so a bin the transform
        // rounded to zero costs the geometric mean a finite amount
        val floor = mean * SILENCE
        var logs = 0.0
        var floored = 0.0
        for (bin in 1..bins) {
            val p = if (power[bin] > floor) power[bin] else floor
            logs += ln(p)
            floored += p
        }
        return exp(logs / bins) / (floored / bins)
    }

    /** Where the weight of the spectrum sits, in Hz: how bright the slice is. */
    fun centroidHz(
        samples: FloatArray,
        rate: Int,
    ): Double {
        val power = power(samples)
        var mass = 0.0
        var moment = 0.0
        for (bin in 1 until power.size) {
            mass += power[bin]
            moment += power[bin] * bin
        }
        return if (mass <= 0.0) 0.0 else moment / mass * rate / samples.size
    }

    /** Flatness of one band only, which a tilt across the whole spectrum cannot move. */
    fun bandFlatness(
        samples: FloatArray,
        rate: Int,
        from: Double,
        until: Double,
    ): Double {
        val power = power(samples)
        val perBin = rate.toDouble() / samples.size
        val lo = (from / perBin).toInt().coerceAtLeast(1)
        val hi = (until / perBin).toInt().coerceAtMost(power.size - 1)
        if (hi <= lo) return 0.0
        var sum = 0.0
        for (bin in lo..hi) sum += power[bin]
        val bins = hi - lo + 1
        val mean = sum / bins
        if (mean <= 0.0) return 0.0
        val floor = mean * SILENCE
        var logs = 0.0
        var floored = 0.0
        for (bin in lo..hi) {
            val p = if (power[bin] > floor) power[bin] else floor
            logs += ln(p)
            floored += p
        }
        return exp(logs / bins) / (floored / bins)
    }

    /** The power in each bin up to Nyquist, Hann-windowed so a pitch stays one spike. */
    private fun power(samples: FloatArray): DoubleArray {
        val n = samples.size
        require(n > 0 && n and (n - 1) == 0) { "length must be a power of two, was $n" }
        val re = DoubleArray(n) { samples[it].toDouble() * hann(it, n) }
        val im = DoubleArray(n)

        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
            var m = n shr 1
            while (m in 1..j) {
                j -= m
                m = m shr 1
            }
            j += m
        }

        var len = 2
        while (len <= n) {
            val step = -2.0 * PI / len
            val half = len shr 1
            for (base in 0 until n step len) {
                for (k in 0 until half) {
                    val wr = cos(step * k)
                    val wi = sin(step * k)
                    val lo = base + k
                    val hi = lo + half
                    val tr = re[hi] * wr - im[hi] * wi
                    val ti = re[hi] * wi + im[hi] * wr
                    re[hi] = re[lo] - tr
                    im[hi] = im[lo] - ti
                    re[lo] += tr
                    im[lo] += ti
                }
            }
            len = len shl 1
        }
        return DoubleArray(n / 2) { re[it] * re[it] + im[it] * im[it] }
    }

    private fun hann(
        at: Int,
        length: Int,
    ): Double = 0.5 * (1.0 - cos(2.0 * PI * at / (length - 1)))
}
