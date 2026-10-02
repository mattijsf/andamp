// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.dsp

import kotlin.math.ceil

/**
 * Turns ordered nodes into instructions and the layout they run against.
 *
 * Constants are pooled, so an edge is always a bus slot and the inner loop never asks whether
 * an operand is a literal.
 *
 * Instructions are partitioned by rate, control first, so the control instructions are a
 * range and the loop needs no rate test. Rate is decided per instruction: a filter's
 * coefficients are a control instruction when its cutoff comes from a parameter, and an audio
 * one when it comes from an oscillator.
 *
 * A delay's write is placed after every read of the same line, so a loop through a delay is
 * the delay it asked for and not one sample more.
 */
internal class Lowering(
    private val spec: GraphSpec,
    private val order: List<Int>,
    private val rates: Map<Int, Rate>,
) {
    private class Pending(
        val op: Int,
        val a: Int,
        val b: Int,
        val c: Int,
        val state: Int,
        val aux: Int,
        val control: Boolean,
    )

    private val constants = mutableListOf<Float>()
    private val inSlot = IntArray(spec.channels) { -1 }

    /** A parameter is a slot the engine writes as the parameter moves, not a constant. */
    private val paramSlot = IntArray(spec.params.size) { -1 }
    private val pending = mutableListOf<Pending>()

    /**
     * Delay writes, held back until every reader of every line has been emitted.
     *
     * The operand cannot be resolved when the delay is reached, because its source is on the
     * far side of the cut edge and is emitted later. The node is kept and its slot is looked
     * up after the pass.
     */
    private val writes = mutableListOf<Pair<NodeSpec, Int>>()

    /** Where each node's result lives: a bus slot, or a negative handle into [pending]. */
    private val slotOf = HashMap<Int, Int>(spec.nodes.size)
    private val stateOf = HashMap<Int, Int>()
    private val tapState = HashMap<String, Int>()
    private val lineOf = HashMap<Int, Int>()
    private val lineOffsets = mutableListOf<Int>()
    private val lineLengths = mutableListOf<Int>()
    private var stateWords = 0
    private var memory = 0

    /** Where the pooled constants begin: after every reserved slot. */
    private var poolStart = 0

    fun lower(): Tape {
        repeat(spec.channels) { inSlot[it] = reserve() }
        spec.params.forEachIndexed { index, param -> paramSlot[index] = reserve(param.default) }
        order.forEach(::emit)
        val written = writes.map { (at, line) -> Pending(Op.DELAY_WRITE, slot(at, "input"), 0, 0, line, 0, false) }
        val instructions = pending.filter { it.control } + pending.filterNot { it.control } + written
        val finalIndex = HashMap<Pending, Int>(instructions.size)
        instructions.forEachIndexed { index, at -> finalIndex[at] = index }
        val place = { handle: Int ->
            if (handle < 0) constants.size + finalIndex.getValue(pending[-handle - 1]) else handle
        }
        return Tape(
            op = IntArray(instructions.size) { instructions[it].op },
            dst = IntArray(instructions.size) { constants.size + it },
            args =
                IntArray(instructions.size * Tape.ARGS) {
                    val at = instructions[it / Tape.ARGS]
                    place(
                        if (it % Tape.ARGS == 0) {
                            at.a
                        } else if (it % Tape.ARGS == 1) {
                            at.b
                        } else {
                            at.c
                        },
                    )
                },
            state = IntArray(instructions.size) { instructions[it].state },
            aux = IntArray(instructions.size) { instructions[it].aux },
            constants = constants.toFloatArray(),
            inSlot = inSlot,
            paramSlot = paramSlot,
            paramStep = FloatArray(spec.params.size) { spec.params[it].stepPerTick(spec.sampleRate) },
            paramDefault = FloatArray(spec.params.size) { spec.params[it].default },
            outSlot = IntArray(spec.outputs.size) { place(slotOf[spec.outputs[it]] ?: 0) },
            controlEnd = instructions.indexOfFirst { !it.control }.let { if (it < 0) instructions.size else it },
            busSize = constants.size + instructions.size,
            stateSize = stateWords,
            lineOffset = lineOffsets.toIntArray(),
            lineLength = lineLengths.toIntArray(),
            memorySize = memory,
            sampleRate = spec.sampleRate,
            block = spec.safeBlock,
        )
    }

    /** A bus slot no instruction computes: an input or a parameter. */
    private fun reserve(value: Float = 0f): Int {
        constants += value
        poolStart = constants.size
        return constants.size - 1
    }

    /**
     * A slot holding [value], shared with any other use of the same number.
     *
     * Pooling starts after the reserved slots. Otherwise a constant equal to a parameter's
     * default would be given that parameter's slot, and moving the parameter would change
     * the constant.
     */
    private fun constant(value: Float): Int {
        for (i in poolStart until constants.size) if (constants[i] == value) return i
        constants += value
        return constants.size - 1
    }

    private fun state(
        node: Int,
        words: Int,
    ): Int = stateOf.getOrPut(node) { stateWords.also { stateWords += words } }

    private fun line(
        node: Int,
        frames: Int,
    ): Int =
        lineOf.getOrPut(node) {
            lineOffsets += memory
            lineLengths += frames
            memory += frames
            lineOffsets.size - 1
        }

    @Suppress("CyclomaticComplexMethod") // one arm per primitive
    private fun emit(node: Int) {
        val at = spec.nodes[node]
        val control = rates[node] == Rate.CONTROL
        when (at.primitive) {
            Primitive.INPUT -> {
                slotOf[node] = inSlot[Args.number(at, "ch").toInt()]
            }

            Primitive.PARAM -> {
                // the parameter's slot, not a constant of its default value
                slotOf[node] = paramSlot[Args.number(at, "index").toInt()]
            }

            Primitive.ADD -> {
                result(node, Op.ADD, slot(at, "a"), slot(at, "b"), control = control)
            }

            Primitive.SUB -> {
                result(node, Op.SUB, slot(at, "a"), slot(at, "b"), control = control)
            }

            Primitive.MUL -> {
                result(node, Op.MUL, slot(at, "a"), slot(at, "b"), control = control)
            }

            Primitive.DIV -> {
                result(node, Op.DIV, slot(at, "a"), slot(at, "b"), control = control)
            }

            Primitive.MIN -> {
                result(node, Op.MIN, slot(at, "a"), slot(at, "b"), control = control)
            }

            Primitive.MAX -> {
                result(node, Op.MAX, slot(at, "a"), slot(at, "b"), control = control)
            }

            Primitive.CROSSFADE -> {
                result(node, Op.CROSSFADE, slot(at, "a"), slot(at, "b"), slot(at, "t"), control = control)
            }

            Primitive.CLIP -> {
                result(node, Op.CLIP, slot(at, "input"), slot(at, "min"), slot(at, "max"), control = control)
            }

            Primitive.SANITISE -> {
                result(node, Op.SANITISE, slot(at, "input"), control = control)
            }

            Primitive.MATH -> {
                result(
                    node,
                    Op.MATH,
                    slot(at, "input"),
                    aux = MathFn.valueOf(Args.text(at, "fn").uppercase()).ordinal,
                    control = control,
                )
            }

            Primitive.GAIN -> {
                result(node, Op.GAIN, slot(at, "input"), slot(at, "db"), control = control)
            }

            Primitive.ONEPOLE -> {
                result(node, Op.ONEPOLE, slot(at, "input"), slot(at, "coeff"), state = state(node, 1))
            }

            Primitive.ALLPASS1 -> {
                result(
                    node,
                    Op.ALLPASS1,
                    slot(at, "input"),
                    slot(at, "coeff"),
                    constant(Args.number(at, "ceiling")),
                    state = state(node, 2),
                )
            }

            Primitive.BIQUAD -> {
                biquad(node, at)
            }

            Primitive.LFO -> {
                lfo(node, at)
            }

            Primitive.ENVELOPE -> {
                envelope(node, at)
            }

            Primitive.NOISE -> {
                val seed = Args.optionalNumber(at, "seed", 0f).toInt().mod(Maths.NOISE_SEEDS)
                result(node, Op.NOISE, 0, state = state(node, 1), aux = seed)
            }

            Primitive.SOFTCLIP -> {
                result(
                    node,
                    Op.SOFTCLIP,
                    slot(at, "input"),
                    slot(at, "drive"),
                    constant(Args.optionalNumber(at, "knee", 0f)),
                    control = control,
                )
            }

            Primitive.DELAY -> {
                delay(node, at)
            }

            Primitive.TAP -> {
                tap(node, at)
            }

            Primitive.TAPOUT -> {
                result(node, Op.TAP_READ, 0, state = tap(at))
            }

            Primitive.TAPIN -> {
                result(node, Op.TAP_WRITE, slot(at, "source"), state = tap(at))
            }
        }
    }

    /**
     * Coefficients are an instruction of their own, at the rate of what feeds them: per
     * control tick when frequency, Q and gain are all steady, per frame otherwise.
     */
    private fun biquad(
        node: Int,
        at: NodeSpec,
    ) {
        val base = state(node, Primitive.BIQUAD.stateWords)
        val slow = listOf("freq", "q", "gainDb").all { steady(at.edges[it]) }
        pending +=
            Pending(
                Op.BIQUAD_COEFFICIENTS,
                slot(at, "freq"),
                slot(at, "q"),
                optionalSlot(at, "gainDb", 0f),
                base,
                BiquadKind.valueOf(Args.text(at, "kind").uppercase()).ordinal,
                slow,
            )
        result(node, Op.BIQUAD, slot(at, "input"), state = base)
    }

    private fun lfo(
        node: Int,
        at: NodeSpec,
    ) {
        val op = if (Args.text(at, "shape") == "triangle") Op.LFO_TRIANGLE else Op.LFO_SINE
        result(node, op, slot(at, "rate"), optionalSlot(at, "phase", 0f), state = state(node, 1))
    }

    private fun envelope(
        node: Int,
        at: NodeSpec,
    ) {
        val attack = coefficient(Args.number(at, "attack"))
        val release = coefficient(Args.number(at, "release"))
        val rms = Args.optionalText(at, "kind", "peak") == "rms"
        result(node, Op.ENVELOPE, slot(at, "input"), constant(attack), constant(release), state(node, 1), if (rms) 1 else 0)
    }

    /** A one-pole coefficient for a time constant in milliseconds. */
    private fun coefficient(milliseconds: Float): Float {
        if (milliseconds <= 0f) return 0f
        return kotlin.math.exp(-1.0 / (milliseconds / MS_PER_SEC * spec.sampleRate)).toFloat()
    }

    private fun delay(
        node: Int,
        at: NodeSpec,
    ) {
        // room for the longest delay asked for plus two frames, with a fractional maxTime
        // rounded up
        val id = line(node, ceil(Args.number(at, "maxTime")).toInt() + 2)
        val nearest = Args.optionalText(at, "interp", "linear") == "none"
        result(node, if (nearest) Op.DELAY_READ_NEAREST else Op.DELAY_READ_LINEAR, slot(at, "time"), state = id)
        // held back: every reader of this line runs before it is written, which makes a
        // loop through it `time` frames and not `time` plus one
        writes += at to id
    }

    private fun tap(
        node: Int,
        at: NodeSpec,
    ) {
        val target = Args.number(at, "line").toInt()
        val id = lineOf.getValue(target)
        val nearest = Args.optionalText(at, "interp", "linear") == "none"
        result(node, if (nearest) Op.DELAY_READ_NEAREST else Op.DELAY_READ_LINEAR, slot(at, "time"), state = id)
    }

    /** The state word a named feedback pair shares. */
    private fun tap(at: NodeSpec): Int = tapState.getOrPut(Args.text(at, "name")) { stateWords.also { stateWords += 1 } }

    /** True when an edge is a constant or a control-rate node. */
    private fun steady(edge: Edge?): Boolean =
        when (edge) {
            null, is Edge.Const -> true
            is Edge.Ref -> rates[edge.node] == Rate.CONTROL
        }

    private fun result(
        node: Int,
        op: Int,
        a: Int,
        b: Int = 0,
        c: Int = 0,
        state: Int = 0,
        aux: Int = 0,
        control: Boolean = false,
    ) {
        pending += Pending(op, a, b, c, state, aux, control)
        slotOf[node] = -pending.size
    }

    private fun slot(
        at: NodeSpec,
        key: String,
    ): Int =
        when (val edge = at.edges[key]) {
            is Edge.Const -> constant(edge.value)
            is Edge.Ref -> slotOf[edge.node] ?: 0
            null -> constant(0f)
        }

    private fun optionalSlot(
        at: NodeSpec,
        key: String,
        fallback: Float,
    ): Int = if (at.edges[key] == null) constant(fallback) else slot(at, key)

    private companion object {
        const val MS_PER_SEC = 1000f
    }
}
