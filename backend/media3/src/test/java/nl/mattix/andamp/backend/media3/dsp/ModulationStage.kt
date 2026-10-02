// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * Pitch modulation: chorus, flanger and phaser, picked by [DspSettings.Mode].
 * The hand-written reference for the modulation graph.
 *
 * - FLANGER: a 1..6 ms delay swept under feedback. The sum of dry and delayed
 *   is a comb filter, and sweeping the delay moves its notches.
 * - CHORUS: the same line at 15..35 ms, where the ear hears a second voice
 *   instead of a comb. Sweeping the delay detunes that copy; feedback is kept
 *   low to avoid resonance.
 * - PHASER: no delay line, but six first-order all-pass sections whose corner
 *   frequency is swept. Their phase rotation against the dry signal makes
 *   notches that are unevenly spaced.
 *
 * The sliders, all 0..1, map as:
 * - `level`: wet/dry mix, 0 all dry .. 1 all wet.
 * - `lfo`: sweep shape, 0 sine .. 1 triangle, blended in between.
 * - `depth`: how far the sweep travels: flanger 1..6 ms, chorus 15..35 ms,
 *   phaser up to four octaves above its 200 Hz floor (200..3200 Hz).
 * - `rate`: sweep speed 0.05..8 Hz, exponential, so the slow end gets most of
 *   the slider.
 * - `feedback`: how much output returns: 0..0.7 for flanger and phaser, 0..0.25
 *   for chorus.
 * - `stereo`: 0 both channels sweep together .. 1 half a cycle (180 degrees)
 *   apart, odd channels against even ones.
 *
 * There is one delay line and one all-pass chain per channel.
 */
internal class ModulationStage(
    sampleRateHz: Int = DEFAULT_SAMPLE_RATE,
) : DspStage {
    private var sampleRate = sampleRateHz.coerceAtLeast(MIN_SAMPLE_RATE)
    private var lineSize = lineSizeFor(sampleRate)

    @Volatile private var settings: DspSettings.Modulation = DspSettings.Modulation()

    private var compiled: DspSettings.Modulation? = null
    private var mode = DspSettings.Mode.FLANGER
    private var phaseStep = 0f
    private var triangleBlend = 0f
    private var stereoOffset = 0f
    private var baseDelay = 0f
    private var sweepDelay = 0f
    private var sweepOctaves = 0f
    private var feedback = 0f
    private var wet = 0f
    private var dry = 1f

    private var channels = 0
    private var lines = emptyArray<FloatArray>()
    private var writeIndex = IntArray(0)
    private var returned = FloatArray(0)
    private var allpassX = emptyArray<FloatArray>()
    private var allpassY = emptyArray<FloatArray>()
    private var phase = 0f

    fun update(settings: DspSettings.Modulation) {
        this.settings = settings
    }

    /**
     * Adopts the stream's format. The constructor's rate is a starting point:
     * sweeps are in seconds and delays in milliseconds, and both need the rate
     * to become samples.
     */
    fun configure(
        sampleRateHz: Int,
        channelCount: Int,
    ) {
        sampleRate = sampleRateHz.coerceAtLeast(MIN_SAMPLE_RATE)
        lineSize = lineSizeFor(sampleRate)
        compiled = null // the rate is baked into the delay and the phase step
        allocate(channelCount.coerceAtLeast(1))
    }

    override fun process(frame: FloatArray) {
        // read once: another thread can swap the settings between two frames
        val current = settings
        if (!current.enabled || frame.isEmpty()) return
        if (current != compiled) compile(current)
        if (frame.size != channels) allocate(frame.size)
        for (channel in frame.indices) {
            val sweep = lfo(phase + if (channel % 2 == 1) stereoOffset else 0f)
            val sample = clean(frame[channel])
            frame[channel] =
                if (mode == DspSettings.Mode.PHASER) phased(sample, channel, sweep) else combed(sample, channel, sweep)
        }
        phase = (phase + phaseStep) % 1f
    }

    override fun reset() {
        lines.forEach { it.fill(0f) }
        writeIndex.fill(0)
        returned.fill(0f)
        allpassX.forEach { it.fill(0f) }
        allpassY.forEach { it.fill(0f) }
        phase = 0f
    }

    /** The sweep at [at] turns, in -1..1: a sine, a triangle, or the blend `lfo` asks for. */
    private fun lfo(at: Float): Float {
        val turn = at - floor(at)
        val sine = sin(TWO_PI * turn)
        // shifted so the triangle crosses zero rising where the sine does, and
        // the two blend in phase
        val triangle = 1f - 4f * abs((turn + QUARTER_TURN) % 1f - 0.5f)
        return sine + (triangle - sine) * triangleBlend
    }

    /** Chorus and flanger: the same swept delay line with different numbers. */
    private fun combed(
        sample: Float,
        channel: Int,
        sweep: Float,
    ): Float {
        val line = lines[channel]
        val delay = (baseDelay + sweepDelay * (1f + sweep) * 0.5f).coerceIn(1f, (line.size - 2).toFloat())
        val tap = interpolated(line, writeIndex[channel], delay)
        line[writeIndex[channel]] = clean(sample + tap * feedback)
        writeIndex[channel] = (writeIndex[channel] + 1) % line.size
        return sample * dry + tap * wet
    }

    /** Phaser: the all-pass cascade, its corner swept, fed back into its own input. */
    private fun phased(
        sample: Float,
        channel: Int,
        sweep: Float,
    ): Float {
        // exponential in frequency, so the sweep spends equal time per octave
        val corner =
            (PHASER_FLOOR_HZ * 2f.pow(sweepOctaves * (1f + sweep) * 0.5f))
                .coerceIn(PHASER_FLOOR_HZ, sampleRate * MAX_CORNER_FRACTION)
        // One-pole all-pass coefficient for H(z) = (a + z^-1) / (1 + a z^-1).
        // The sign matters: a = (1-t)/(1+t) would mirror the corner around
        // Nyquist, so sweeping the notch up would move it down. |a| < 1 for
        // any corner below Nyquist, which the clamp guarantees.
        val t = tan(PI_F * corner / sampleRate)
        val a = (t - 1f) / (t + 1f)
        val x = allpassX[channel]
        val y = allpassY[channel]
        var v = clean(sample + returned[channel] * feedback)
        for (section in 0 until SECTIONS) {
            val out = a * v + x[section] - a * y[section]
            x[section] = v
            y[section] = clean(out)
            v = y[section]
        }
        returned[channel] = v
        return sample * dry + v * wet
    }

    /**
     * [line] read [delay] samples behind [head], linearly interpolated:
     * whole-sample reads would step the sweep.
     */
    private fun interpolated(
        line: FloatArray,
        head: Int,
        delay: Float,
    ): Float {
        val back = head - delay + line.size
        val index = back.toInt()
        val fraction = back - index
        val a = line[index % line.size]
        val b = line[(index + 1) % line.size]
        return a + (b - a) * fraction
    }

    /**
     * Applied to everything that feeds back. A NaN would stay in the loop, a
     * denormal tail is slow to compute, and a resonant comb can climb past
     * full scale, so state stays within [CEILING]. That bounds the output too,
     * since the dry and wet gains sum to 1.
     */
    private fun clean(value: Float): Float {
        if (!value.isFinite()) return 0f
        if (abs(value) < DENORMAL_FLOOR) return 0f
        return value.coerceIn(-CEILING, CEILING)
    }

    private fun allocate(count: Int) {
        channels = count
        // one line and one chain per channel
        lines = Array(count) { FloatArray(lineSize) }
        writeIndex = IntArray(count)
        returned = FloatArray(count)
        allpassX = Array(count) { FloatArray(SECTIONS) }
        allpassY = Array(count) { FloatArray(SECTIONS) }
        phase = 0f
    }

    private fun compile(current: DspSettings.Modulation) {
        compiled = current
        mode = current.mode
        val rateHz = MIN_RATE_HZ * (MAX_RATE_HZ / MIN_RATE_HZ).pow(current.rate.coerceIn(0f, 1f))
        phaseStep = rateHz / sampleRate
        triangleBlend = current.lfo.coerceIn(0f, 1f)
        stereoOffset = current.stereo.coerceIn(0f, 1f) * 0.5f
        val depth = current.depth.coerceIn(0f, 1f)
        val level = current.level.coerceIn(0f, 1f)
        wet = level
        dry = 1f - level
        val amount = current.feedback.coerceIn(0f, 1f)
        when (current.mode) {
            DspSettings.Mode.FLANGER -> {
                baseDelay = samples(FLANGER_BASE_MS)
                sweepDelay = samples(FLANGER_SWEEP_MS) * depth
                feedback = amount * MAX_FEEDBACK
            }

            DspSettings.Mode.CHORUS -> {
                baseDelay = samples(CHORUS_BASE_MS)
                sweepDelay = samples(CHORUS_SWEEP_MS) * depth
                feedback = amount * CHORUS_MAX_FEEDBACK
            }

            DspSettings.Mode.PHASER -> {
                sweepOctaves = PHASER_OCTAVES * depth
                feedback = amount * MAX_FEEDBACK
            }
        }
    }

    private fun samples(ms: Float) = sampleRate * ms / MS_PER_SEC

    private companion object {
        const val MS_PER_SEC = 1000f
        const val DEFAULT_SAMPLE_RATE = 44_100
        const val MIN_SAMPLE_RATE = 8000
        const val MIN_LINE_SIZE = 8
        const val TWO_PI = (2 * PI).toFloat()
        const val PI_F = PI.toFloat()
        const val QUARTER_TURN = 0.25f

        /** Room for the longest delay any mode asks for, plus the interpolator's neighbor. */
        const val MAX_DELAY_MS = 40f

        const val FLANGER_BASE_MS = 1f
        const val FLANGER_SWEEP_MS = 5f
        const val CHORUS_BASE_MS = 15f
        const val CHORUS_SWEEP_MS = 20f

        const val MIN_RATE_HZ = 0.05f
        const val MAX_RATE_HZ = 8f

        /** The highest feedback used; at 1 the comb would not decay. */
        const val MAX_FEEDBACK = 0.7f

        /** A chorus's feedback is kept low to avoid resonance. */
        const val CHORUS_MAX_FEEDBACK = 0.25f

        /** Six sections give three notches. */
        const val SECTIONS = 6
        const val PHASER_FLOOR_HZ = 200f
        const val PHASER_OCTAVES = 4f
        const val MAX_CORNER_FRACTION = 0.45f

        const val CEILING = 1.2f
        const val DENORMAL_FLOOR = 1e-20f

        fun lineSizeFor(sampleRate: Int) = (sampleRate * MAX_DELAY_MS / MS_PER_SEC).toInt().coerceAtLeast(MIN_LINE_SIZE)
    }
}
