// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.dsp

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * The arithmetic [BlockRunner] runs. [GraphEngine] calls the noise functions here and has its
 * own copies of the rest, which must compute the same values.
 */
internal object Maths {
    const val TWO_PI = (2 * Math.PI).toFloat()
    const val QUARTER = 0.25f
    private const val DENORMAL = 1e-20f
    val DB_TO_GAIN = ln(10f) / 20f
    private val LN2 = ln(2f)

    fun sanitise(v: Float) = if (!v.isFinite() || abs(v) < DENORMAL) 0f else v

    /**
     * The noise generator: a full-period linear congruential step modulo 2^24, so the state
     * is an integer a Float holds exactly, and a reset to zero restarts the same sequence. The
     * seed picks the increment, `2 * seed + 1`, which is odd as the full period requires.
     */
    fun noiseStep(
        state: Float,
        seed: Int,
    ): Float = ((state.toLong() * NOISE_MULTIPLIER + 2L * seed + 1L) and NOISE_MASK).toFloat()

    /** A noise state as a sample, from -1 up to but not including 1. */
    fun noiseSample(state: Float): Float = state / NOISE_HALF - 1f

    /** How many different seeds there are; a seed is taken modulo this. */
    const val NOISE_SEEDS = 1 shl 22
    private const val NOISE_MULTIPLIER = 0xECE66DL
    private const val NOISE_MASK = (1L shl 24) - 1
    private const val NOISE_HALF = (1 shl 23).toFloat()

    fun crossfade(
        a: Float,
        b: Float,
        t: Float,
    ): Float =
        if (t == 0f) {
            a
        } else if (t == 1f) {
            b
        } else {
            a + (b - a) * t
        }

    fun clip(
        v: Float,
        low: Float,
        high: Float,
    ): Float =
        if (v < low) {
            low
        } else if (v > high) {
            high
        } else {
            v
        }

    fun fraction(v: Float) = v - floor(v)

    fun hold(v: Float): Float = clip(sanitise(v), -GraphValidator.LOOP_CEILING, GraphValidator.LOOP_CEILING)

    fun softclip(
        x: Float,
        knee: Float,
    ): Float {
        val magnitude = abs(x)
        if (magnitude <= knee) return x
        val room = (1f - knee).coerceAtLeast(1e-6f)
        val over = magnitude - knee
        val bent = knee + room * over / (over + room)
        return if (x < 0f) -bent else bent
    }

    @Suppress("CyclomaticComplexMethod") // one arm per math function
    fun of(
        fn: Int,
        x: Float,
    ): Float =
        when (MathFn.entries[fn]) {
            MathFn.EXP2 -> 2f.pow(x)
            MathFn.LOG2 -> ln(x) / LN2
            MathFn.SIN -> sin(x)
            MathFn.COS -> cos(x)
            MathFn.TAN -> tan(x)
            MathFn.TANH -> tanh(x)
            MathFn.SQRT -> sqrt(x)
            MathFn.ABS -> abs(x)
            MathFn.RECIP -> 1f / x
            MathFn.NEG -> -x
            MathFn.FLOOR -> floor(x)
            MathFn.ROUND -> kotlin.math.round(x)
        }

    fun biquad(
        state: FloatArray,
        base: Int,
        input: Float,
    ): Float {
        val x = sanitise(input)
        val y =
            sanitise(
                state[base] * x + state[base + 1] * state[base + 5] + state[base + 2] * state[base + 6] -
                    state[base + 3] * state[base + 7] - state[base + 4] * state[base + 8],
            )
        state[base + 6] = state[base + 5]
        state[base + 5] = x
        state[base + 8] = state[base + 7]
        state[base + 7] = y
        return y
    }

    fun read(
        memory: FloatArray,
        offset: Int,
        length: Int,
        head: Int,
        delay: Float,
        interpolate: Boolean,
    ): Float {
        val wanted = delay.coerceIn(1f, (length - 2).toFloat())
        val back = head % length - wanted + length
        val whole = back.toInt()
        var first = whole
        if (first >= length) first -= length
        if (!interpolate) return memory[offset + first]
        var second = first + 1
        if (second >= length) second = 0
        val a = memory[offset + first]
        return a + (memory[offset + second] - a) * (back - whole)
    }
}
