// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.dsp

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/** Processes audio in place. A backend adapts this to its own audio stage. */
interface FrameProcessor {
    fun process(frame: FloatArray)

    /**
     * [frames] frames of every channel, in place.
     *
     * Given a whole buffer, the engine runs each instruction over up to
     * [GraphSpec.safeBlock] frames at a time. A feedback tap or a delay with a computed time
     * brings that down to one frame at a time.
     */
    fun process(
        channels: Array<FloatArray>,
        frames: Int,
    )

    fun reset()

    /**
     * Moves a parameter. Safe to call from the UI thread: the value is a target, and the
     * audio thread walks towards it on control ticks, so a drag does not step the signal.
     */
    fun setParameter(
        index: Int,
        value: Float,
    )

    /**
     * Puts every parameter on its target at once, without the walk.
     *
     * For an engine that was just built or reset. [reset] leaves the values at the declared
     * defaults, so without this the first moments would sweep from the defaults to the
     * listener's settings.
     *
     * Only safe while nothing is processing this engine.
     */
    fun settleParameters()
}

/**
 * The inner loop: one `when` over a flat program, with no allocation and no virtual call per
 * node.
 *
 * Control instructions occupy the front of the tape and run once every [CONTROL_PERIOD]
 * frames; the rest run every frame.
 */
internal class GraphEngine(
    private val tape: Tape,
) : FrameProcessor {
    private val bus = FloatArray(tape.busSize)

    /** Where each parameter is being moved to, written by [setParameter]. */
    private val target = FloatArray(tape.paramSlot.size)

    /** Where each parameter is. Only the audio thread moves it. */
    private val here = FloatArray(tape.paramSlot.size)

    /**
     * Set when a parameter gets a new target, so that the next frame runs a control tick
     * without waiting for the period to come round.
     */
    @Volatile private var moved = false
    private val state = FloatArray(maxOf(1, tape.stateSize))

    /** Every delay line, back to back in one array. */
    private val memory = FloatArray(maxOf(1, tape.memorySize))
    private val head = IntArray(maxOf(1, tape.lineLength.size))
    private var untilTick = 0

    /** Built only when the tape's block is larger than one; see [GraphSpec.safeBlock]. */
    private val blocks = if (tape.block > 1) BlockRunner(tape, state, memory, head) else null

    init {
        reset()
    }

    override fun process(
        channels: Array<FloatArray>,
        frames: Int,
    ) {
        val runner = blocks
        if (runner == null || channels.size != tape.inSlot.size) {
            perFrame(channels, frames)
            return
        }
        var at = 0
        while (at < frames) {
            val count = minOf(tape.block, frames - at)
            var channel = 0
            while (channel < channels.size) {
                for (f in 0 until count) runner.input(channel, f, channels[channel][at + f])
                channel++
            }
            if (untilTick <= 0 || moved) {
                moved = false
                walkParameters()
                tape.paramSlot.forEachIndexed { index, slot -> runner.hold(slot, bus[slot]) }
                runner.runControl(tape.controlEnd, count)
                untilTick = CONTROL_PERIOD
            }
            untilTick -= count
            runner.run(tape.controlEnd, tape.op.size, count)
            channel = 0
            while (channel < channels.size) {
                for (f in 0 until count) channels[channel][at + f] = runner.output(channel, f)
                channel++
            }
            at += count
        }
    }

    /** One frame at a time: for a graph whose block is one, and for a buffer with another channel count. */
    private fun perFrame(
        channels: Array<FloatArray>,
        frames: Int,
    ) {
        val frame = FloatArray(channels.size)
        for (f in 0 until frames) {
            for (channel in channels.indices) frame[channel] = channels[channel][f]
            process(frame)
            for (channel in channels.indices) channels[channel][f] = frame[channel]
        }
    }

    override fun setParameter(
        index: Int,
        value: Float,
    ) {
        if (index !in target.indices) return
        target[index] = value
        moved = true
    }

    override fun settleParameters() {
        target.copyInto(here)
        var i = 0
        while (i < here.size) {
            bus[tape.paramSlot[i]] = here[i]
            i++
        }
        moved = false
    }

    override fun reset() {
        tape.constants.copyInto(bus)
        bus.fill(0f, tape.constants.size, bus.size)
        state.fill(0f)
        memory.fill(0f)
        head.fill(0)
        // parameters go back to their declared defaults; a caller that has other values
        // sets them and calls settleParameters
        tape.paramDefault.copyInto(target)
        tape.paramDefault.copyInto(here)
        blocks?.prime()
        untilTick = 0
    }

    override fun process(frame: FloatArray) {
        if (frame.size != tape.inSlot.size) return // built for another channel count
        var channel = 0
        while (channel < frame.size) {
            bus[tape.inSlot[channel]] = frame[channel]
            channel++
        }
        if (untilTick <= 0 || moved) {
            moved = false
            walkParameters()
            run(0, tape.controlEnd)
            untilTick = CONTROL_PERIOD
        }
        untilTick--
        run(tape.controlEnd, tape.op.size)
        channel = 0
        // written only after every instruction has run, so a node reading another channel
        // sees the frame as it arrived
        while (channel < frame.size) {
            frame[channel] = bus[tape.outSlot[channel]]
            channel++
        }
    }

    /**
     * One step of every parameter towards its target, once per control tick. A value within
     * [CLOSE] of its target lands on it.
     */
    private fun walkParameters() {
        var i = 0
        while (i < here.size) {
            val wanted = target[i]
            val step = tape.paramStep[i]
            val next =
                if (abs(wanted - here[i]) < CLOSE) wanted else here[i] + (wanted - here[i]) * step
            here[i] = next
            bus[tape.paramSlot[i]] = next
            i++
        }
    }

    // One `when` over a flat program replaces a virtual call per node per frame; splitting
    // it into methods would bring that call back.
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    private fun run(
        from: Int,
        to: Int,
    ) {
        val op = tape.op
        val dst = tape.dst
        val a = tape.args
        val bus = this.bus
        var i = from
        while (i < to) {
            val k = i * Tape.ARGS
            bus[dst[i]] =
                when (op[i]) {
                    Op.ADD -> {
                        bus[a[k]] + bus[a[k + 1]]
                    }

                    Op.SUB -> {
                        bus[a[k]] - bus[a[k + 1]]
                    }

                    Op.MUL -> {
                        bus[a[k]] * bus[a[k + 1]]
                    }

                    Op.DIV -> {
                        bus[a[k]] / bus[a[k + 1]]
                    }

                    Op.MIN -> {
                        minOf(bus[a[k]], bus[a[k + 1]])
                    }

                    Op.MAX -> {
                        maxOf(bus[a[k]], bus[a[k + 1]])
                    }

                    Op.CROSSFADE -> {
                        // exactly a at t = 0 and exactly b at t = 1
                        val x = bus[a[k]]
                        val t = bus[a[k + 2]]
                        if (t == 0f) x else bus[a[k + 1]].let { y -> if (t == 1f) y else x + (y - x) * t }
                    }

                    Op.CLIP -> {
                        val v = bus[a[k]]
                        val low = bus[a[k + 1]]
                        val high = bus[a[k + 2]]
                        if (v < low) {
                            low
                        } else if (v > high) {
                            high
                        } else {
                            v
                        }
                    }

                    Op.SANITISE -> {
                        sanitise(bus[a[k]])
                    }

                    Op.MATH -> {
                        math(tape.aux[i], bus[a[k]])
                    }

                    Op.GAIN -> {
                        bus[a[k]] * exp(bus[a[k + 1]] * DB_TO_GAIN)
                    }

                    Op.SOFTCLIP -> {
                        softclip(bus[a[k]] * bus[a[k + 1]], bus[a[k + 2]])
                    }

                    Op.ONEPOLE -> {
                        onepole(tape.state[i], bus[a[k]], bus[a[k + 1]])
                    }

                    Op.ALLPASS1 -> {
                        allpass(tape.state[i], bus[a[k]], bus[a[k + 1]], bus[a[k + 2]])
                    }

                    Op.BIQUAD_COEFFICIENTS -> {
                        coefficients(tape.state[i], tape.aux[i], bus[a[k]], bus[a[k + 1]], bus[a[k + 2]])
                        0f
                    }

                    Op.BIQUAD -> {
                        biquad(tape.state[i], bus[a[k]])
                    }

                    Op.LFO_SINE -> {
                        lfo(tape.state[i], bus[a[k]], bus[a[k + 1]], sine = true)
                    }

                    Op.LFO_TRIANGLE -> {
                        lfo(tape.state[i], bus[a[k]], bus[a[k + 1]], sine = false)
                    }

                    Op.ENVELOPE -> {
                        envelope(tape.state[i], tape.aux[i], bus[a[k]], bus[a[k + 1]], bus[a[k + 2]])
                    }

                    Op.NOISE -> {
                        val base = tape.state[i]
                        val next = Maths.noiseStep(state[base], tape.aux[i])
                        state[base] = next
                        Maths.noiseSample(next)
                    }

                    Op.DELAY_READ_LINEAR -> {
                        readLine(tape.state[i], bus[a[k]], interpolate = true)
                    }

                    Op.DELAY_READ_NEAREST -> {
                        readLine(tape.state[i], bus[a[k]], interpolate = false)
                    }

                    Op.DELAY_WRITE -> {
                        writeLine(tape.state[i], bus[a[k]])
                        0f
                    }

                    Op.TAP_READ -> {
                        state[tape.state[i]]
                    }

                    Op.TAP_WRITE -> {
                        // bounded here, because a loop whose gain is on a slider cannot
                        // be bounded at load
                        state[tape.state[i]] = hold(bus[a[k]])
                        0f
                    }

                    else -> {
                        0f
                    }
                }
            i++
        }
    }

    private fun onepole(
        base: Int,
        x: Float,
        coefficient: Float,
    ): Float {
        val y = sanitise(x + (state[base] - x) * coefficient)
        state[base] = y
        return y
    }

    /** One first-order all-pass section, with its output clipped to its ceiling. */
    private fun allpass(
        base: Int,
        x: Float,
        g: Float,
        ceiling: Float,
    ): Float {
        val y = clip(sanitise(g * x + state[base] - g * state[base + 1]), -ceiling, ceiling)
        state[base] = x
        state[base + 1] = y
        return y
    }

    /** RBJ cookbook coefficients, computed in double and stored normalized by a0. */
    private fun coefficients(
        base: Int,
        kind: Int,
        frequency: Float,
        q: Float,
        gainDb: Float,
    ) {
        val w = TWO_PI_D * frequency.toDouble().coerceIn(1.0, tape.sampleRate * NYQUIST_FRACTION) / tape.sampleRate
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

    /** Direct form I, with the input sanitised before it enters the state. */
    private fun biquad(
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

    private fun lfo(
        base: Int,
        hz: Float,
        phase: Float,
        sine: Boolean,
    ): Float {
        val at = state[base]
        state[base] = fraction(at + hz / tape.sampleRate)
        val turn = fraction(at + phase)
        return if (sine) {
            sin(TWO_PI * turn)
        } else {
            // shifted so that the triangle crosses zero rising where the sine does
            1f - 4f * abs(fraction(turn + QUARTER) - 0.5f)
        }
    }

    private fun envelope(
        base: Int,
        kind: Int,
        input: Float,
        attack: Float,
        release: Float,
    ): Float {
        val level = if (kind == 1) input * input else abs(input)
        val coefficient = if (level > state[base]) attack else release
        val y = sanitise(level + (state[base] - level) * coefficient)
        state[base] = y
        return if (kind == 1) sqrt(y) else y
    }

    private fun readLine(
        line: Int,
        delay: Float,
        interpolate: Boolean,
    ): Float {
        val length = tape.lineLength[line]
        val offset = tape.lineOffset[line]
        val wanted = delay.coerceIn(1f, (length - 2).toFloat())
        val back = head[line] - wanted + length
        val whole = back.toInt()
        var first = whole
        if (first >= length) first -= length
        if (!interpolate) return memory[offset + first]
        var second = first + 1
        if (second >= length) second = 0
        val a = memory[offset + first]
        return a + (memory[offset + second] - a) * (back - whole)
    }

    private fun writeLine(
        line: Int,
        value: Float,
    ) {
        val length = tape.lineLength[line]
        memory[tape.lineOffset[line] + head[line]] = hold(value)
        head[line] = if (head[line] + 1 == length) 0 else head[line] + 1
    }

    private fun math(
        fn: Int,
        x: Float,
    ): Float =
        when (MathFn.entries[fn]) {
            // 2^x and not exp(x * ln2): the two differ in the last bits
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

    private companion object {
        /** Coefficients for one RBJ shape: numerator, then denominator. */
        fun shape(
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
                    doubleArrayOf(1 - alpha, -2 * cosine, 1 + alpha) to
                        doubleArrayOf(1 + alpha, -2 * cosine, 1 - alpha)
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

        /**
         * Returns its input up to the knee, and above it bends asymptotically towards 1.
         * Hyperbolic, which costs a divide where tanh costs a transcendental.
         */
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

        fun fraction(v: Float) = v - floor(v)

        /** Sanitises a value written into a feedback path and clips it to the loop ceiling. */
        fun hold(v: Float): Float = clip(sanitise(v), -GraphValidator.LOOP_CEILING, GraphValidator.LOOP_CEILING)

        /** Exactly [a] at t = 0 and exactly [b] at t = 1. */
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

        /** Frames between control ticks: 0.73 ms at 44.1 kHz. */
        const val CONTROL_PERIOD = GraphParam.CONTROL_PERIOD
        const val DENORMAL = 1e-20f
        const val QUARTER = 0.25f

        /** Closer than this to its target, a parameter lands on it. */
        const val CLOSE = 1e-3f
        const val TWO_PI = (2 * Math.PI).toFloat()
        const val TWO_PI_D = 2 * Math.PI
        const val NYQUIST_FRACTION = 0.49
        const val MIN_Q = 0.05
        const val SHELF_DB = 40.0
        val LN2 = ln(2f)

        /** ln(10) / 20, so that a dB value becomes a gain with one multiply and one exp. */
        val DB_TO_GAIN = (ln(10f) / 20f)

        fun sanitise(v: Float) = if (!v.isFinite() || abs(v) < DENORMAL) 0f else v
    }
}
