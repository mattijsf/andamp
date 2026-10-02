// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Karaoke: the lead vocal removed by cancelling what both channels share. The
 * hand-written reference for the karaoke graph.
 *
 * A lead vocal is mixed in the center, so it is in the mid of a mid/side
 * split. Dropping the mid removes the singer, and the kick and the bass with
 * it, so the mid is lowpassed and mixed back in. The sides go back in
 * antiphase, which keeps the image of what was panned wide.
 *
 * The four sliders, all 0..1:
 * - `level` 0 returns the untouched audio, 1 is the full cancellation.
 * - `filter` 60..500 Hz, logarithmic, is the cutoff under which the mono low
 *   end is kept; the middle of the slider is at 173 Hz.
 * - `band` 24..12 dB/oct is the rolloff of that kept end: 0 keeps a narrow
 *   band of deep bass, 1 a wider one reaching into the low mids.
 * - `width` blends 0..50% of the untouched stereo back over the result,
 *   trading some vocal leakage for a more natural image.
 *
 * A mono stream is passed through.
 */
internal class KaraokeStage(
    sampleRateHz: Int = DEFAULT_SAMPLE_RATE,
) : DspStage {
    @Volatile private var settings: DspSettings.Karaoke = DspSettings.Karaoke()

    private val gentle = Lowpass()
    private val steep = Lowpass()

    private var sampleRate = sampleRateHz
    private var compiled: DspSettings.Karaoke? = null
    private var compiledRate = 0

    private var amount = 0f
    private var stereoBack = 0f
    private var skirt = 0f

    fun update(settings: DspSettings.Karaoke) {
        this.settings = settings
    }

    /** The stream's sample rate, once the pipeline knows it. */
    fun configure(sampleRateHz: Int) {
        // clear the filters' state from the old stream
        reset()
        sampleRate = sampleRateHz
    }

    override fun process(frame: FloatArray) {
        val current = settings // read once: another thread writes this field between frames
        if (!current.enabled || frame.size < 2) return
        if (current != compiled || sampleRate != compiledRate) compile(current)

        val left = frame[0]
        val right = frame[1]
        val mid = (left + right) * 0.5f
        val side = (left - right) * 0.5f

        // one section is 12 dB/oct, the two cascaded are 24; crossfading the two
        // outputs sweeps the skirt continuously instead of switching order
        val twelve = gentle.process(mid)
        val twentyFour = steep.process(twelve)
        val bass = twentyFour + (twelve - twentyFour) * skirt

        frame[0] = blend(left, side + bass)
        frame[1] = blend(right, bass - side)
        // channels past the front pair (center, LFE, surrounds) are left as
        // they are
    }

    override fun reset() {
        gentle.reset()
        steep.reset()
    }

    /** The cancelled [wet] under the untouched [dry] by width, the pair blended by level. */
    private fun blend(
        dry: Float,
        wet: Float,
    ): Float {
        val widened = wet + (dry - wet) * stereoBack
        // `bass` is the filtered mid, with its own phase, so the wet signal
        // can exceed the input's peak; the clamp below bounds it
        return (dry + (widened - dry) * amount).coerceIn(-CEILING, CEILING)
    }

    private fun compile(current: DspSettings.Karaoke) {
        compiled = current
        compiledRate = sampleRate
        amount = current.level.coerceIn(0f, 1f)
        stereoBack = current.width.coerceIn(0f, 1f) * MAX_DRY
        skirt = current.band.coerceIn(0f, 1f)
        val cutoff =
            (KEEP_MIN_HZ * (KEEP_MAX_HZ / KEEP_MIN_HZ).pow(current.filter.coerceIn(0f, 1f)))
                // unreachable while KEEP_MAX_HZ is 500; guards a wider range
                // or a very low rate
                .coerceAtMost(sampleRate * MAX_CUTOFF_FRACTION)
        gentle.setCutoff(cutoff, sampleRate)
        steep.setCutoff(cutoff, sampleRate)
    }

    /**
     * One RBJ Audio EQ Cookbook lowpass section, direct form I, at Butterworth
     * Q. The EQ's Biquad has only a peaking shape.
     */
    private class Lowpass {
        private var b0 = 1f
        private var b1 = 0f
        private var b2 = 0f
        private var a1 = 0f
        private var a2 = 0f

        private var x1 = 0f
        private var x2 = 0f
        private var y1 = 0f
        private var y2 = 0f

        fun setCutoff(
            frequencyHz: Float,
            sampleRateHz: Int,
        ) {
            val w0 = 2.0 * PI * frequencyHz / sampleRateHz
            val alpha = sin(w0) / (2.0 * Q)
            val cosW0 = cos(w0)
            val a0 = 1.0 + alpha
            b0 = (((1.0 - cosW0) / 2.0) / a0).toFloat()
            b1 = ((1.0 - cosW0) / a0).toFloat()
            b2 = b0
            a1 = ((-2.0 * cosW0) / a0).toFloat()
            a2 = ((1.0 - alpha) / a0).toFloat()
        }

        fun process(sample: Float): Float {
            val x0 = tame(sample)
            val y0 = tame(b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2)
            x2 = x1
            x1 = x0
            y2 = y1
            y1 = y0
            return y0
        }

        fun reset() {
            x1 = 0f
            x2 = 0f
            y1 = 0f
            y2 = 0f
        }

        /**
         * A recursive filter keeps what it is fed: a NaN or a denormal would
         * stay in the feedback path.
         */
        private fun tame(value: Float) = if (!value.isFinite() || abs(value) < DENORMAL) 0f else value

        private companion object {
            const val Q = 0.70710677f
            const val DENORMAL = 1e-20f
        }
    }

    private companion object {
        const val DEFAULT_SAMPLE_RATE = 44_100

        const val KEEP_MIN_HZ = 60f
        const val KEEP_MAX_HZ = 500f
        const val MAX_CUTOFF_FRACTION = 0.45f
        const val MAX_DRY = 0.5f
        const val CEILING = 1.5f
    }
}
