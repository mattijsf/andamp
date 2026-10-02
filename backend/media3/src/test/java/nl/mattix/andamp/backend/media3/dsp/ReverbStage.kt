// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3.dsp

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A reverb after Jezar's Freeverb: a bank of damped comb filters in parallel,
 * summed into a chain of all-passes, with a short tapped delay line for the
 * early reflections.
 *
 * The comb lengths, the all-pass lengths and the stereo spread are Freeverb's,
 * quoted at 44.1 kHz and rescaled to the stream's rate. Six of Freeverb's
 * eight combs and three of its four all-passes are used, because the network
 * runs per channel on every frame.
 *
 * Parameter mapping; every slider is 0..1:
 * - `size`  -> comb delays 0.35x..1.6x the reference lengths, roughly 9..59 ms.
 *              The feedback per pass is fixed, so a bigger room also rings
 *              longer.
 * - `near`  -> early reflections at 0..1 against a tail at 1..0.2. The tail is
 *              turned down, not removed.
 * - `air`   -> damping 0.4..0.05 in each comb's loop, inverted because more air
 *              is less absorption: about -7 dB to -0.9 dB at Nyquist per pass.
 * - `level` -> a wet/dry crossfade, 0 fully dry .. 1 fully wet.
 *
 * Stability: a damped comb's loop gain is largest at DC, where its damping
 * filter is unity, so the worst case is [FEEDBACK], which no slider changes.
 * The all-passes have unity magnitude. The peak the comb bank reaches on one
 * of its resonances is bounded by [soften].
 */
internal class ReverbStage(
    private val sampleRate: Int,
) : DspStage {
    @Volatile
    private var settings: DspSettings.Reverb = DspSettings.Reverb()

    /** What [coefficients] were last derived from; null forces a recompute. */
    private var applied: DspSettings.Reverb? = null

    /** Whether a tail is circulating, so switching off can drop it. */
    private var running = false

    private val rateScale = sampleRate / REFERENCE_RATE
    private val spreadSamples = (STEREO_SPREAD * rateScale).roundToInt()
    private val combBufferSamples = (COMB_LENGTHS.max() * MAX_SIZE * rateScale).toInt() + 2
    private val earlyBufferSamples = (EARLY_TAPS_MS.max() * MAX_SIZE * sampleRate / MS_PER_SEC).toInt() + 2
    private val earlyNormalise = 1f / EARLY_GAINS.sum()

    /**
     * The comb bank's power gain for broadband input is count / (1 - g²), so
     * its inverse square root puts the tail at about the level of the dry
     * signal; [TAIL_HEADROOM] backs it off further.
     */
    private val tailInputGain = TAIL_HEADROOM * sqrt((1f - FEEDBACK * FEEDBACK) / COMB_LENGTHS.size)

    private var rooms: Array<Room> = emptyArray()
    private var damping = 0f
    private var earlyGain = 0f
    private var tailGain = 0f
    private var dryGain = 1f
    private var wetGain = 0f

    fun update(settings: DspSettings.Reverb) {
        this.settings = settings
    }

    override fun process(frame: FloatArray) {
        val active = settings // read once: the settings can be swapped between frames
        if (!active.enabled || frame.isEmpty()) {
            // switching off stops the network mid-tail; it is reset, so the
            // old tail does not play when it is switched back on
            if (running) {
                reset()
                running = false
            }
            return
        }
        running = true
        if (rooms.size != frame.size) {
            // the channel count is known from the first frame
            rooms = Array(frame.size) { Room(it) }
            applied = null
        }
        if (active != applied) {
            coefficients(active)
            applied = active
        }

        var sum = 0f
        for (channel in frame.indices) {
            val sample = frame[channel]
            val clean = if (sample.isFinite()) sample else 0f
            frame[channel] = clean
            sum += clean
        }
        // every channel's network is fed the same mono signal, as Freeverb does
        val input = flush(sum / frame.size)

        for (channel in frame.indices) {
            frame[channel] = frame[channel] * dryGain + soften(rooms[channel].run(input)) * wetGain
        }
    }

    override fun reset() {
        rooms.forEach { it.reset() }
    }

    private fun coefficients(settings: DspSettings.Reverb) {
        val size = settings.size.coerceIn(0f, 1f)
        val near = settings.near.coerceIn(0f, 1f)
        val air = settings.air.coerceIn(0f, 1f)
        val level = settings.level.coerceIn(0f, 1f)

        damping = MAX_DAMPING - air * (MAX_DAMPING - MIN_DAMPING)
        earlyGain = near
        tailGain = 1f - near * TAIL_DUCK
        dryGain = 1f - level
        wetGain = level

        val scale = MIN_SIZE + size * (MAX_SIZE - MIN_SIZE)
        rooms.forEach { it.retune(scale) }
    }

    /** One channel's room: its own delay lines, offset by its own [spread]. */
    private inner class Room(
        index: Int,
    ) {
        // each further channel's lines are a little longer, so the channels
        // decorrelate
        private val spread = index * spreadSamples
        private val combs = Array(COMB_LENGTHS.size) { Comb(combBufferSamples + spread) }
        private val allpasses =
            Array(ALLPASS_LENGTHS.size) {
                // these lengths do not follow `size`
                Allpass(((ALLPASS_LENGTHS[it] * rateScale).roundToInt() + spread).coerceAtLeast(1))
            }
        private val early = Taps(earlyBufferSamples + spread)
        private val combDelays = IntArray(COMB_LENGTHS.size)
        private val earlyDelays = IntArray(EARLY_TAPS_MS.size)

        fun retune(scale: Float) {
            for (i in combDelays.indices) {
                combDelays[i] = (COMB_LENGTHS[i] * rateScale * scale).roundToInt() + spread
            }
            for (i in earlyDelays.indices) {
                earlyDelays[i] = (EARLY_TAPS_MS[i] * scale * sampleRate / MS_PER_SEC).roundToInt() + spread
            }
        }

        fun run(input: Float): Float {
            early.push(input)
            var reflections = 0f
            for (i in earlyDelays.indices) {
                reflections += early.read(earlyDelays[i]) * EARLY_GAINS[i]
            }

            val combInput = input * tailInputGain
            var tail = 0f
            for (i in combs.indices) {
                tail += combs[i].run(combInput, combDelays[i], damping)
            }
            for (allpass in allpasses) {
                tail = allpass.run(tail)
            }

            return reflections * earlyNormalise * earlyGain + tail * tailGain
        }

        fun reset() {
            combs.forEach { it.reset() }
            allpasses.forEach { it.reset() }
            early.reset()
        }
    }

    /** One comb: a delay fed back on itself through a one-pole lowpass. */
    private class Comb(
        samples: Int,
    ) {
        private val buffer = FloatArray(samples)
        private var write = 0
        private var store = 0f

        fun run(
            input: Float,
            delay: Int,
            damping: Float,
        ): Float {
            val read = write - delay
            val out = buffer[if (read < 0) read + buffer.size else read]
            // a one-pole lowpass inside the loop: the damping
            store = flush(out + (store - out) * damping)
            buffer[write] = input + store * FEEDBACK
            write = if (write + 1 == buffer.size) 0 else write + 1
            return out
        }

        fun reset() {
            buffer.fill(0f)
            write = 0
            store = 0f
        }
    }

    /**
     * A Schroeder all-pass: passes every frequency at the same level but delays
     * them by different amounts, which smears the combs' echoes. The buffer
     * length is the delay.
     */
    private class Allpass(
        samples: Int,
    ) {
        private val buffer = FloatArray(samples)
        private var write = 0

        fun run(input: Float): Float {
            val delayed = buffer[write]
            val stored = flush(input + delayed * DIFFUSION)
            buffer[write] = stored
            write = if (write + 1 == buffer.size) 0 else write + 1
            return delayed - stored * DIFFUSION
        }

        fun reset() {
            buffer.fill(0f)
            write = 0
        }
    }

    /** A plain delay line read at several points at once: the early reflections. */
    private class Taps(
        samples: Int,
    ) {
        private val buffer = FloatArray(samples)
        private var index = 0

        fun push(input: Float) {
            buffer[index] = input
            index = if (index + 1 == buffer.size) 0 else index + 1
        }

        fun read(delay: Int): Float {
            val read = index - delay
            return buffer[if (read < 0) read + buffer.size else read]
        }

        fun reset() {
            buffer.fill(0f)
            index = 0
        }
    }

    private companion object {
        const val REFERENCE_RATE = 44_100f
        const val MS_PER_SEC = 1000f

        /** Freeverb's comb and all-pass lengths at 44.1 kHz, six and three of them. */
        val COMB_LENGTHS = intArrayOf(1116, 1188, 1277, 1356, 1491, 1617)
        val ALLPASS_LENGTHS = intArrayOf(556, 441, 341)
        const val STEREO_SPREAD = 23f

        /** Early reflection times, each quieter than the last. */
        val EARLY_TAPS_MS = floatArrayOf(6.7f, 11.3f, 17.9f, 23.1f)
        val EARLY_GAINS = floatArrayOf(1f, 0.72f, 0.53f, 0.38f)

        /**
         * Fixed, and not on a slider: it decides whether the network is
         * stable.
         */
        const val FEEDBACK = 0.84f
        const val DIFFUSION = 0.5f

        const val MIN_SIZE = 0.35f
        const val MAX_SIZE = 1.6f
        const val MIN_DAMPING = 0.05f
        const val MAX_DAMPING = 0.4f
        const val TAIL_DUCK = 0.8f
        const val TAIL_HEADROOM = 0.7f

        const val KNEE = 0.8f
        const val CEILING = 1.25f

        /**
         * Far below anything audible, and above the range where floats go
         * denormal and arithmetic in the feedback loop slows down.
         */
        const val DENORMAL = 1e-20f

        fun flush(value: Float) = if (abs(value) < DENORMAL) 0f else value

        /**
         * Leaves anything below [KNEE] unchanged and bends the rest
         * asymptotically toward [CEILING]. The network's gain at its own
         * resonances is above 1, so this bounds the output for a full-scale
         * note on a resonance.
         */
        fun soften(value: Float): Float {
            val magnitude = abs(value)
            if (magnitude <= KNEE) return value
            val room = CEILING - KNEE
            val over = magnitude - KNEE
            val bent = KNEE + room * over / (over + room)
            return if (value < 0f) -bent else bent
        }
    }
}
