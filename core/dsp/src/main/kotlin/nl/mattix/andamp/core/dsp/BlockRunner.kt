// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Runs the same tape as [GraphEngine], one instruction over many frames.
 *
 * Per frame, every node costs a dispatch on every frame; per block, a node costs one dispatch
 * and then a loop over the block.
 *
 * This is only valid when nothing in the block needs a sample the block has not produced
 * yet. [GraphSpec.safeBlock] decides the block size: a delay with a constant time shrinks
 * the block to that time, and a feedback tap or a delay with a computed time brings it down
 * to one frame, in which case this class is not used. Nothing here checks that again.
 *
 * Every operation is the one [GraphEngine] performs, in the same order and with the same
 * association, so that both produce the same samples.
 */
internal class BlockRunner(
    private val tape: Tape,
    private val state: FloatArray,
    private val memory: FloatArray,
    private val head: IntArray,
) {
    /** One row per bus slot. Constant rows are filled by [prime]; the others are written per frame. */
    private val rows = Array(tape.busSize) { FloatArray(tape.block) }

    fun prime() {
        tape.constants.forEachIndexed { slot, value -> rows[slot].fill(value) }
    }

    /** Fills a slot's row with [value], as for a parameter, which is one value for the block. */
    fun hold(
        slot: Int,
        value: Float,
    ) = rows[slot].fill(value)

    fun input(
        channel: Int,
        frame: Int,
        value: Float,
    ) {
        rows[tape.inSlot[channel]][frame] = value
    }

    fun output(
        channel: Int,
        frame: Int,
    ): Float = rows[tape.outSlot[channel]][frame]

    /**
     * The control pass: computed for one frame and copied across the block. Everything it
     * reads is a constant or a parameter, and both hold one value for the whole block.
     */
    fun runControl(
        to: Int,
        frames: Int,
    ) {
        run(0, to, 1)
        for (i in 0 until to) {
            val row = rows[tape.dst[i]]
            row.fill(row[0], 0, frames)
        }
    }

    /** Runs instructions [from] until [to] over [frames] frames. */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
    fun run(
        from: Int,
        to: Int,
        frames: Int,
    ) {
        var i = from
        while (i < to) {
            val k = i * Tape.ARGS
            val out = rows[tape.dst[i]]
            val a = rows[tape.args[k]]
            val b = rows[tape.args[k + 1]]
            val c = rows[tape.args[k + 2]]
            when (tape.op[i]) {
                Op.ADD -> for (f in 0 until frames) out[f] = a[f] + b[f]
                Op.SUB -> for (f in 0 until frames) out[f] = a[f] - b[f]
                Op.MUL -> for (f in 0 until frames) out[f] = a[f] * b[f]
                Op.DIV -> for (f in 0 until frames) out[f] = a[f] / b[f]
                Op.MIN -> for (f in 0 until frames) out[f] = minOf(a[f], b[f])
                Op.MAX -> for (f in 0 until frames) out[f] = maxOf(a[f], b[f])
                Op.CROSSFADE -> for (f in 0 until frames) out[f] = Maths.crossfade(a[f], b[f], c[f])
                Op.CLIP -> for (f in 0 until frames) out[f] = Maths.clip(a[f], b[f], c[f])
                Op.SANITISE -> for (f in 0 until frames) out[f] = Maths.sanitise(a[f])
                Op.MATH -> for (f in 0 until frames) out[f] = Maths.of(tape.aux[i], a[f])
                Op.GAIN -> for (f in 0 until frames) out[f] = a[f] * exp(b[f] * Maths.DB_TO_GAIN)
                Op.SOFTCLIP -> for (f in 0 until frames) out[f] = Maths.softclip(a[f] * b[f], c[f])
                Op.ONEPOLE -> onepole(tape.state[i], out, a, b, frames)
                Op.ALLPASS1 -> allpass(tape.state[i], out, a, b, c, frames)
                Op.BIQUAD_COEFFICIENTS -> coefficients(i, a, b, c, frames)
                Op.BIQUAD -> biquad(tape.state[i], out, a, frames)
                Op.LFO_SINE -> lfo(tape.state[i], out, a, b, frames, sine = true)
                Op.LFO_TRIANGLE -> lfo(tape.state[i], out, a, b, frames, sine = false)
                Op.ENVELOPE -> envelope(tape.state[i], tape.aux[i], out, a, b, c, frames)
                Op.NOISE -> noise(tape.state[i], tape.aux[i], out, frames)
                Op.DELAY_READ_LINEAR -> read(tape.state[i], out, a, frames, interpolate = true)
                Op.DELAY_READ_NEAREST -> read(tape.state[i], out, a, frames, interpolate = false)
                Op.DELAY_WRITE -> write(tape.state[i], a, frames)
                else -> error("opcode ${tape.op[i]} cannot be blocked")
            }
            i++
        }
    }

    private fun onepole(
        base: Int,
        out: FloatArray,
        input: FloatArray,
        coefficient: FloatArray,
        frames: Int,
    ) {
        var held = state[base]
        for (f in 0 until frames) {
            held = Maths.sanitise(input[f] + (held - input[f]) * coefficient[f])
            out[f] = held
        }
        state[base] = held
    }

    private fun allpass(
        base: Int,
        out: FloatArray,
        input: FloatArray,
        gain: FloatArray,
        ceiling: FloatArray,
        frames: Int,
    ) {
        var x = state[base]
        var y = state[base + 1]
        for (f in 0 until frames) {
            val next = Maths.clip(Maths.sanitise(gain[f] * input[f] + x - gain[f] * y), -ceiling[f], ceiling[f])
            x = input[f]
            y = next
            out[f] = next
        }
        state[base] = x
        state[base + 1] = y
    }

    /**
     * Computes the coefficients once per block, from the last frame's inputs. In the audio
     * pass the inputs are signals, so the filter follows them at block rate here.
     */
    private fun coefficients(
        instruction: Int,
        frequency: FloatArray,
        q: FloatArray,
        gainDb: FloatArray,
        frames: Int,
    ) {
        Rbj.coefficients(
            state,
            tape.state[instruction],
            tape.aux[instruction],
            frequency[frames - 1],
            q[frames - 1],
            gainDb[frames - 1],
            tape.sampleRate,
        )
    }

    private fun biquad(
        base: Int,
        out: FloatArray,
        input: FloatArray,
        frames: Int,
    ) {
        for (f in 0 until frames) out[f] = Maths.biquad(state, base, input[f])
    }

    private fun noise(
        base: Int,
        seed: Int,
        out: FloatArray,
        frames: Int,
    ) {
        var at = state[base]
        for (f in 0 until frames) {
            at = Maths.noiseStep(at, seed)
            out[f] = Maths.noiseSample(at)
        }
        state[base] = at
    }

    private fun lfo(
        base: Int,
        out: FloatArray,
        hz: FloatArray,
        phase: FloatArray,
        frames: Int,
        sine: Boolean,
    ) {
        var at = state[base]
        for (f in 0 until frames) {
            val turn = Maths.fraction(at + phase[f])
            out[f] = if (sine) sin(Maths.TWO_PI * turn) else 1f - 4f * abs(Maths.fraction(turn + Maths.QUARTER) - 0.5f)
            at = Maths.fraction(at + hz[f] / tape.sampleRate)
        }
        state[base] = at
    }

    private fun envelope(
        base: Int,
        kind: Int,
        out: FloatArray,
        input: FloatArray,
        attack: FloatArray,
        release: FloatArray,
        frames: Int,
    ) {
        var held = state[base]
        for (f in 0 until frames) {
            val level = if (kind == 1) input[f] * input[f] else abs(input[f])
            held = Maths.sanitise(level + (held - level) * if (level > held) attack[f] else release[f])
            out[f] = if (kind == 1) sqrt(held) else held
        }
        state[base] = held
    }

    /**
     * A block's worth of reads from one line. The compiler orders every read before the
     * line's write, so the head has not moved and frame f reads relative to `head + f`.
     */
    private fun read(
        line: Int,
        out: FloatArray,
        time: FloatArray,
        frames: Int,
        interpolate: Boolean,
    ) {
        val length = tape.lineLength[line]
        val offset = tape.lineOffset[line]
        val start = head[line]
        for (f in 0 until frames) {
            out[f] = Maths.read(memory, offset, length, start + f, time[f], interpolate)
        }
    }

    private fun write(
        line: Int,
        input: FloatArray,
        frames: Int,
    ) {
        val length = tape.lineLength[line]
        val offset = tape.lineOffset[line]
        var at = head[line]
        for (f in 0 until frames) {
            memory[offset + at] = Maths.hold(input[f])
            at = if (at + 1 == length) 0 else at + 1
        }
        head[line] = at
    }
}
