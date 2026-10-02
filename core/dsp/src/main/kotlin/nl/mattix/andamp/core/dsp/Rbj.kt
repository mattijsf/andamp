// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.dsp

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The biquad coefficients of the Audio EQ Cookbook (Robert Bristow-Johnson), computed in
 * double. Eight shapes, transcribed from the cookbook.
 */
internal object Rbj {
    private const val TWO_PI_D = 2 * Math.PI
    private const val NYQUIST_FRACTION = 0.49
    private const val MIN_Q = 0.05
    private const val SHELF_DB = 40.0

    /** Writes the five coefficients, normalized by a0, into [state] at [base]. */
    fun coefficients(
        state: FloatArray,
        base: Int,
        kind: Int,
        frequency: Float,
        q: Float,
        gainDb: Float,
        sampleRate: Int,
    ) {
        val w = TWO_PI_D * frequency.toDouble().coerceIn(1.0, sampleRate * NYQUIST_FRACTION) / sampleRate
        val alpha = sin(w) / (2.0 * q.toDouble().coerceAtLeast(MIN_Q))
        val cosine = cos(w)
        val amplitude = Math.pow(10.0, gainDb.toDouble() / SHELF_DB)
        val (b, a) = shape(BiquadKind.entries[kind], alpha, cosine, amplitude)
        val a0 = a[0]
        state[base] = (b[0] / a0).toFloat()
        state[base + 1] = (b[1] / a0).toFloat()
        state[base + 2] = (b[2] / a0).toFloat()
        state[base + 3] = (a[1] / a0).toFloat()
        state[base + 4] = (a[2] / a0).toFloat()
    }

    @Suppress("LongMethod") // eight shapes, each the cookbook's algebra for it
    private fun shape(
        kind: BiquadKind,
        alpha: Double,
        cosine: Double,
        amplitude: Double,
    ): Pair<DoubleArray, DoubleArray> {
        val root = 2.0 * sqrt(amplitude) * alpha
        return when (kind) {
            BiquadKind.LOWPASS -> {
                doubleArrayOf((1 - cosine) / 2, 1 - cosine, (1 - cosine) / 2) to
                    doubleArrayOf(1 + alpha, -2 * cosine, 1 - alpha)
            }

            BiquadKind.HIGHPASS -> {
                doubleArrayOf((1 + cosine) / 2, -(1 + cosine), (1 + cosine) / 2) to
                    doubleArrayOf(1 + alpha, -2 * cosine, 1 - alpha)
            }

            BiquadKind.BANDPASS -> {
                doubleArrayOf(alpha, 0.0, -alpha) to doubleArrayOf(1 + alpha, -2 * cosine, 1 - alpha)
            }

            BiquadKind.NOTCH -> {
                doubleArrayOf(1.0, -2 * cosine, 1.0) to doubleArrayOf(1 + alpha, -2 * cosine, 1 - alpha)
            }

            BiquadKind.ALLPASS -> {
                doubleArrayOf(1 - alpha, -2 * cosine, 1 + alpha) to doubleArrayOf(1 + alpha, -2 * cosine, 1 - alpha)
            }

            BiquadKind.PEAKING -> {
                doubleArrayOf(1 + alpha * amplitude, -2 * cosine, 1 - alpha * amplitude) to
                    doubleArrayOf(1 + alpha / amplitude, -2 * cosine, 1 - alpha / amplitude)
            }

            BiquadKind.LOWSHELF -> {
                doubleArrayOf(
                    amplitude * ((amplitude + 1) - (amplitude - 1) * cosine + root),
                    2 * amplitude * ((amplitude - 1) - (amplitude + 1) * cosine),
                    amplitude * ((amplitude + 1) - (amplitude - 1) * cosine - root),
                ) to
                    doubleArrayOf(
                        (amplitude + 1) + (amplitude - 1) * cosine + root,
                        -2 * ((amplitude - 1) + (amplitude + 1) * cosine),
                        (amplitude + 1) + (amplitude - 1) * cosine - root,
                    )
            }

            BiquadKind.HIGHSHELF -> {
                doubleArrayOf(
                    amplitude * ((amplitude + 1) + (amplitude - 1) * cosine + root),
                    -2 * amplitude * ((amplitude - 1) + (amplitude + 1) * cosine),
                    amplitude * ((amplitude + 1) + (amplitude - 1) * cosine - root),
                ) to
                    doubleArrayOf(
                        (amplitude + 1) - (amplitude - 1) * cosine + root,
                        2 * ((amplitude - 1) - (amplitude + 1) * cosine),
                        (amplitude + 1) - (amplitude - 1) * cosine - root,
                    )
            }
        }
    }
}
