// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * One peaking-EQ biquad (RBJ Audio EQ Cookbook), direct form I, with
 * independent state per channel.
 */
class Biquad(
    channels: Int,
) {
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f

    private val x1 = FloatArray(channels)
    private val x2 = FloatArray(channels)
    private val y1 = FloatArray(channels)
    private val y2 = FloatArray(channels)

    /** Pass-through. Required for bands at or above Nyquist, where peaking coefficients go unstable. */
    fun setIdentity() {
        b0 = 1f
        b1 = 0f
        b2 = 0f
        a1 = 0f
        a2 = 0f
    }

    /** Peaking EQ at [frequencyHz] with [gainDb], bandwidth set by [q]. */
    fun setPeaking(
        frequencyHz: Float,
        sampleRateHz: Int,
        gainDb: Float,
        q: Float,
    ) {
        val a = 10f.pow(gainDb / 40f)
        val w0 = (2.0 * Math.PI * frequencyHz / sampleRateHz).toFloat()
        val alpha = sin(w0.toDouble()).toFloat() / (2f * q)
        val cosW0 = cos(w0.toDouble()).toFloat()
        val a0 = 1f + alpha / a
        b0 = (1f + alpha * a) / a0
        b1 = (-2f * cosW0) / a0
        b2 = (1f - alpha * a) / a0
        a1 = (-2f * cosW0) / a0
        a2 = (1f - alpha / a) / a0
    }

    fun process(
        sample: Float,
        channel: Int,
    ): Float {
        val y = b0 * sample + b1 * x1[channel] + b2 * x2[channel] - a1 * y1[channel] - a2 * y2[channel]
        x2[channel] = x1[channel]
        x1[channel] = sample
        y2[channel] = y1[channel]
        y1[channel] = y
        return y
    }

    fun reset() {
        x1.fill(0f)
        x2.fill(0f)
        y1.fill(0f)
        y2.fill(0f)
    }
}
