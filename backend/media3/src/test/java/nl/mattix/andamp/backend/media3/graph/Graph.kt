// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3.graph

import nl.mattix.andamp.backend.media3.dsp.DspSettings
import nl.mattix.andamp.core.dsp.ConstArg
import nl.mattix.andamp.core.dsp.Edge
import nl.mattix.andamp.core.dsp.GraphSpec
import nl.mattix.andamp.core.dsp.NodeSpec
import nl.mattix.andamp.core.dsp.Primitive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The effects written as graphs with every setting folded into a constant,
 * for comparing against the hand-written stages (see GraphParityTest).
 *
 * A small builder keeps the graphs readable: nodes are appended and referred
 * to by the handle they return.
 */
internal class Graph(
    private val channels: Int,
    private val sampleRate: Int,
) {
    val nodes = mutableListOf<NodeSpec>()

    fun node(
        primitive: Primitive,
        edges: Map<String, Edge> = emptyMap(),
        consts: Map<String, ConstArg> = emptyMap(),
    ): Edge.Ref {
        nodes += NodeSpec(primitive, edges, consts)
        return Edge.Ref(nodes.size - 1)
    }

    fun input(channel: Int) = node(Primitive.INPUT, consts = mapOf("ch" to num(channel.toFloat())))

    fun add(
        a: Edge,
        b: Edge,
    ) = node(Primitive.ADD, mapOf("a" to a, "b" to b))

    fun sub(
        a: Edge,
        b: Edge,
    ) = node(Primitive.SUB, mapOf("a" to a, "b" to b))

    fun mul(
        a: Edge,
        b: Edge,
    ) = node(Primitive.MUL, mapOf("a" to a, "b" to b))

    fun crossfade(
        a: Edge,
        b: Edge,
        t: Edge,
    ) = node(Primitive.CROSSFADE, mapOf("a" to a, "b" to b, "t" to t))

    fun clip(
        input: Edge,
        low: Float,
        high: Float,
    ) = node(Primitive.CLIP, mapOf("input" to input, "min" to Edge.Const(low), "max" to Edge.Const(high)))

    fun lowpass(
        input: Edge,
        hz: Float,
        q: Float,
    ) = node(
        Primitive.BIQUAD,
        edges = mapOf("input" to input, "freq" to Edge.Const(hz), "q" to Edge.Const(q)),
        consts = mapOf("kind" to ConstArg.Text("lowpass")),
    )

    fun math(
        input: Edge,
        fn: String,
    ) = node(Primitive.MATH, mapOf("input" to input), mapOf("fn" to ConstArg.Text(fn)))

    fun div(
        a: Edge,
        b: Edge,
    ) = node(Primitive.DIV, mapOf("a" to a, "b" to b))

    fun sanitise(input: Edge) = node(Primitive.SANITISE, mapOf("input" to input))

    /** Sanitizes [input] and clips it to plus or minus [ceiling], as the stages' `clean` does. */
    fun clean(
        input: Edge,
        ceiling: Float,
    ) = clip(sanitise(input), -ceiling, ceiling)

    fun lfo(
        hz: Edge,
        shape: String,
        phase: Edge,
    ) = node(Primitive.LFO, mapOf("rate" to hz, "phase" to phase), mapOf("shape" to ConstArg.Text(shape)))

    fun allpass(
        input: Edge,
        coefficient: Edge,
        ceiling: Float,
    ) = node(
        Primitive.ALLPASS1,
        mapOf("input" to input, "coeff" to coefficient),
        mapOf("ceiling" to ConstArg.Num(ceiling)),
    )

    /**
     * A delay line whose input is not known yet: the write closes a feedback
     * loop, so the node is created first and [feed] sets its source afterwards.
     */
    fun delay(
        maxTime: Int,
        time: Edge,
        interp: String = "linear",
    ): Edge.Ref =
        node(
            Primitive.DELAY,
            mapOf("time" to time),
            mapOf("maxTime" to ConstArg.Num(maxTime.toFloat()), "interp" to ConstArg.Text(interp)),
        )

    fun feed(
        line: Edge.Ref,
        source: Edge,
    ) {
        nodes[line.node] = nodes[line.node].let { it.copy(edges = it.edges + ("input" to source)) }
    }

    fun tap(
        line: Edge.Ref,
        time: Edge,
        interp: String = "linear",
    ) = node(
        Primitive.TAP,
        mapOf("time" to time),
        mapOf("line" to ConstArg.Num(line.node.toFloat()), "interp" to ConstArg.Text(interp)),
    )

    fun softclip(
        input: Edge,
        drive: Edge,
        knee: Float,
    ) = node(
        Primitive.SOFTCLIP,
        mapOf("input" to input, "drive" to drive),
        mapOf("knee" to ConstArg.Num(knee)),
    )

    fun spec(outputs: List<Edge.Ref>): GraphSpec {
        val delayFrames =
            nodes.sumOf { ((it.consts["maxTime"] as? ConstArg.Num)?.value ?: 0f).toInt() }
        return GraphSpec(
            sampleRate = sampleRate,
            channels = channels,
            nodes = nodes.toList(),
            outputs = outputs.map { it.node },
            delayFrames = delayFrames,
        )
    }

    private fun num(value: Float) = ConstArg.Num(value)
}

/** The constant-power pan of `PanStage`, as a graph. */
internal fun panGraph(
    pan: Float,
    sampleRate: Int = 44_100,
    channels: Int = 2,
): GraphSpec {
    val g = Graph(channels, sampleRate)
    val angle = pan.coerceIn(0f, 1f) * (PI / 2).toFloat()
    val gains = listOf(Edge.Const(cos(angle)), Edge.Const(sin(angle)))
    val outputs = (0 until channels).map { g.mul(g.input(it), gains[it % 2]) }
    return g.spec(outputs)
}

/**
 * Karaoke, as a graph: the mid lowpassed and mixed back under the sides.
 */
internal fun karaokeGraph(
    level: Float,
    filter: Float,
    band: Float,
    width: Float,
    sampleRate: Int = 44_100,
): GraphSpec {
    val g = Graph(2, sampleRate)
    val left = g.input(0)
    val right = g.input(1)
    val half = Edge.Const(0.5f)
    val mid = g.mul(g.add(left, right), half)
    val side = g.mul(g.sub(left, right), half)
    val cutoff =
        (KEEP_MIN_HZ * (KEEP_MAX_HZ / KEEP_MIN_HZ).pow(filter.coerceIn(0f, 1f)))
            .coerceAtMost(sampleRate * MAX_CUTOFF_FRACTION)
    val twelve = g.lowpass(mid, cutoff, BUTTERWORTH_Q)
    val twentyFour = g.lowpass(twelve, cutoff, BUTTERWORTH_Q)
    val bass = g.crossfade(twentyFour, twelve, Edge.Const(band.coerceIn(0f, 1f)))
    val stereoBack = Edge.Const(width.coerceIn(0f, 1f) * MAX_DRY)
    val amount = Edge.Const(level.coerceIn(0f, 1f))
    val outputs =
        listOf(g.add(side, bass), g.sub(bass, side)).mapIndexed { channel, wet ->
            val dry = if (channel == 0) left else right
            val widened = g.crossfade(wet, dry, stereoBack)
            g.clip(g.crossfade(dry, widened, amount), -CEILING, CEILING)
        }
    return g.spec(outputs)
}

private const val KEEP_MIN_HZ = 60f
private const val KEEP_MAX_HZ = 500f
private const val MAX_CUTOFF_FRACTION = 0.45f
private const val MAX_DRY = 0.5f
private const val CEILING = 1.5f
private const val BUTTERWORTH_Q = 0.70710677f

/**
 * Pitch modulation as a graph: one swept delay for the flanger and the chorus,
 * six swept all-pass sections for the phaser.
 */
internal fun modulationGraph(
    mode: DspSettings.Mode,
    level: Float,
    lfo: Float,
    depth: Float,
    rate: Float,
    feedback: Float,
    stereo: Float,
    sampleRate: Int = 44_100,
    channels: Int = 2,
): GraphSpec {
    val g = Graph(channels, sampleRate)
    val hz = Edge.Const(MIN_RATE_HZ * (MAX_RATE_HZ / MIN_RATE_HZ).pow(rate.coerceIn(0f, 1f)))
    val blend = Edge.Const(lfo.coerceIn(0f, 1f))
    val stereoOffset = stereo.coerceIn(0f, 1f) * 0.5f
    val wet = Edge.Const(level.coerceIn(0f, 1f))
    val dry = Edge.Const(1f - level.coerceIn(0f, 1f))
    val amount = feedback.coerceIn(0f, 1f)
    val lineSize = (sampleRate * MAX_DELAY_MS / MS_PER_SEC).toInt().coerceAtLeast(MIN_LINE_SIZE)

    val outputs =
        (0 until channels).map { channel ->
            val phase = Edge.Const(if (channel % 2 == 1) stereoOffset else 0f)
            val sweep = g.crossfade(g.lfo(hz, "sine", phase), g.lfo(hz, "triangle", phase), blend)
            val sample = g.clean(g.input(channel), MOD_CEILING)
            val voice =
                if (mode == DspSettings.Mode.PHASER) {
                    phased(g, sample, sweep, depth, amount * MAX_FEEDBACK, sampleRate, channel)
                } else {
                    combed(g, sample, sweep, mode, depth, amount, lineSize, sampleRate)
                }
            g.add(g.mul(sample, dry), g.mul(voice, wet))
        }
    return g.spec(outputs)
}

/** Chorus and flanger: one swept delay line with different numbers. */
private fun combed(
    g: Graph,
    sample: Edge,
    sweep: Edge,
    mode: DspSettings.Mode,
    depth: Float,
    feedback: Float,
    lineSize: Int,
    sampleRate: Int,
): Edge {
    val flanging = mode == DspSettings.Mode.FLANGER
    val base = sampleRate * (if (flanging) FLANGER_BASE_MS else CHORUS_BASE_MS) / MS_PER_SEC
    val span = sampleRate * (if (flanging) FLANGER_SWEEP_MS else CHORUS_SWEEP_MS) / MS_PER_SEC * depth.coerceIn(0f, 1f)
    val gain = Edge.Const(feedback * if (flanging) MAX_FEEDBACK else CHORUS_MAX_FEEDBACK)
    // base + span * (1 + sweep) * 0.5, associated as ModulationStage associates it
    val travelled = g.mul(g.mul(Edge.Const(span), g.add(Edge.Const(1f), sweep)), Edge.Const(0.5f))
    val time = g.clip(g.add(Edge.Const(base), travelled), 1f, (lineSize - 2).toFloat())
    val line = g.delay(lineSize - 2, time)
    g.feed(line, g.clean(g.add(sample, g.mul(line, gain)), MOD_CEILING))
    return line
}

/** The phaser: six all-pass sections around a tap pair, and no delay line. */
private fun phased(
    g: Graph,
    sample: Edge,
    sweep: Edge,
    depth: Float,
    feedback: Float,
    sampleRate: Int,
    channel: Int,
): Edge {
    val octaves = Edge.Const(PHASER_OCTAVES * depth.coerceIn(0f, 1f))
    val exponent = g.mul(g.mul(octaves, g.add(Edge.Const(1f), sweep)), Edge.Const(0.5f))
    val corner =
        g.clip(
            g.mul(Edge.Const(PHASER_FLOOR_HZ), g.math(exponent, "exp2")),
            PHASER_FLOOR_HZ,
            sampleRate * MAX_CORNER_FRACTION,
        )
    // (PI * corner) / rate, associated as ModulationStage does, so the
    // coefficient is the same float
    val tangent = g.math(g.div(g.mul(Edge.Const(PI.toFloat()), corner), Edge.Const(sampleRate.toFloat())), "tan")
    val coefficient = g.div(g.sub(tangent, Edge.Const(1f)), g.add(tangent, Edge.Const(1f)))
    val name = "fb$channel"
    val returned = g.node(Primitive.TAPOUT, consts = mapOf("name" to ConstArg.Text(name)))
    var voice = g.clean(g.add(sample, g.mul(returned, Edge.Const(feedback))), MOD_CEILING)
    repeat(SECTIONS) { voice = g.allpass(voice, coefficient, MOD_CEILING) }
    g.node(Primitive.TAPIN, mapOf("source" to voice), mapOf("name" to ConstArg.Text(name)))
    return voice
}

private const val MIN_RATE_HZ = 0.05f
private const val MAX_RATE_HZ = 8f
private const val MAX_FEEDBACK = 0.7f
private const val CHORUS_MAX_FEEDBACK = 0.25f
private const val MS_PER_SEC = 1000f
private const val MAX_DELAY_MS = 40f
private const val MIN_LINE_SIZE = 8
private const val FLANGER_BASE_MS = 1f
private const val FLANGER_SWEEP_MS = 5f
private const val CHORUS_BASE_MS = 15f
private const val CHORUS_SWEEP_MS = 20f
private const val SECTIONS = 6
private const val PHASER_FLOOR_HZ = 200f
private const val PHASER_OCTAVES = 4f
private const val MAX_CORNER_FRACTION = 0.45f
private const val MOD_CEILING = 1.2f

/**
 * `ReverbStage` as a graph: six damped combs into three all-passes, with a
 * tapped line for the early reflections.
 *
 * Every channel's network is fed the same mono sum, as Freeverb does.
 */
@Suppress("LongMethod") // it is a wiring diagram; splitting it hides the signal path
internal fun reverbGraph(
    level: Float,
    size: Float,
    near: Float,
    air: Float,
    sampleRate: Int = 44_100,
    channels: Int = 2,
): GraphSpec {
    val g = Graph(channels, sampleRate)
    val rateScale = sampleRate / REFERENCE_RATE
    val spreadSamples = (STEREO_SPREAD * rateScale).roundToInt()
    val combBuffer = (COMB_LENGTHS.max() * MAX_SIZE * rateScale).toInt() + 2
    val earlyBuffer = (EARLY_TAPS_MS.max() * MAX_SIZE * sampleRate / MS_PER_SEC).toInt() + 2
    val earlyNormalise = Edge.Const(1f / EARLY_GAINS.sum())
    val tailInput = Edge.Const(TAIL_HEADROOM * sqrt((1f - FEEDBACK * FEEDBACK) / COMB_LENGTHS.size))
    val scale = MIN_SIZE + size.coerceIn(0f, 1f) * (MAX_SIZE - MIN_SIZE)
    val damping = Edge.Const(MAX_DAMPING - air.coerceIn(0f, 1f) * (MAX_DAMPING - MIN_DAMPING))
    val earlyGain = Edge.Const(near.coerceIn(0f, 1f))
    val tailGain = Edge.Const(1f - near.coerceIn(0f, 1f) * TAIL_DUCK)
    val wet = Edge.Const(level.coerceIn(0f, 1f))
    val dry = Edge.Const(1f - level.coerceIn(0f, 1f))
    val feedbackGain = Edge.Const(FEEDBACK)
    val diffusion = Edge.Const(DIFFUSION)

    val cleaned = (0 until channels).map { g.sanitise(g.input(it)) }
    var sum: Edge = cleaned[0]
    for (channel in 1 until channels) sum = g.add(sum, cleaned[channel])
    val mono = g.sanitise(g.mul(sum, Edge.Const(1f / channels)))
    val combInput = g.mul(mono, tailInput)

    val outputs =
        (0 until channels).map { channel ->
            val spread = channel * spreadSamples

            // ReverbStage pushes its line before it reads, so its delay of d
            // is this line's d - 1
            val delays = EARLY_TAPS_MS.map { (it * scale * sampleRate / MS_PER_SEC).roundToInt() + spread - 1 }
            // the delay node's own read is the first reflection; the others
            // are taps on the same line
            val early = g.delay(earlyBuffer + spread, Edge.Const(delays[0].toFloat()), interp = "none")
            g.feed(early, mono)
            var reflections: Edge = g.mul(early, Edge.Const(EARLY_GAINS[0]))
            delays.drop(1).forEachIndexed { i, delay ->
                val heard =
                    g.mul(g.tap(early, Edge.Const(delay.toFloat()), interp = "none"), Edge.Const(EARLY_GAINS[i + 1]))
                reflections = g.add(reflections, heard)
            }

            var tail: Edge? = null
            COMB_LENGTHS.forEach { length ->
                val delay = (length * rateScale * scale).roundToInt() + spread
                val line = g.delay(combBuffer + spread, Edge.Const(delay.toFloat()), interp = "none")
                val damped = g.node(Primitive.ONEPOLE, mapOf("input" to line, "coeff" to damping))
                g.feed(line, g.add(combInput, g.mul(damped, feedbackGain)))
                tail = tail?.let { g.add(it, line) } ?: line
            }
            ALLPASS_LENGTHS.forEach { length ->
                val frames = ((length * rateScale).roundToInt() + spread).coerceAtLeast(1)
                val line = g.delay(frames, Edge.Const(frames.toFloat()), interp = "none")
                val stored = g.sanitise(g.add(tail!!, g.mul(line, diffusion)))
                g.feed(line, stored)
                tail = g.sub(line, g.mul(stored, diffusion))
            }

            val room =
                g.add(
                    g.mul(g.mul(reflections!!, earlyNormalise), earlyGain),
                    g.mul(tail!!, tailGain),
                )
            g.add(g.mul(cleaned[channel], dry), g.mul(soften(g, room), wet))
        }
    return g.spec(outputs)
}

/**
 * Leaves anything below the knee unchanged and bends the rest toward the
 * ceiling, as `ReverbStage.soften` does.
 *
 * Built from arithmetic instead of `softclip`: the sign is restored by
 * clipping the input to the softened magnitude, which is exact at zero.
 */
private fun soften(
    g: Graph,
    input: Edge,
): Edge {
    val knee = Edge.Const(KNEE)
    val room = Edge.Const(SOFT_CEILING - KNEE)
    val magnitude = g.math(input, "abs")
    val over = g.node(Primitive.MAX, mapOf("a" to g.sub(magnitude, knee), "b" to Edge.Const(0f)))
    val bent = g.add(knee, g.div(g.mul(room, over), g.add(over, room)))
    val wanted = g.node(Primitive.MIN, mapOf("a" to magnitude, "b" to bent))
    return g.node(
        Primitive.CLIP,
        mapOf("input" to input, "min" to g.math(wanted, "neg"), "max" to wanted),
    )
}

private const val REFERENCE_RATE = 44_100f
private val COMB_LENGTHS = intArrayOf(1116, 1188, 1277, 1356, 1491, 1617)
private val ALLPASS_LENGTHS = intArrayOf(556, 441, 341)
private const val STEREO_SPREAD = 23f
private val EARLY_TAPS_MS = floatArrayOf(6.7f, 11.3f, 17.9f, 23.1f)
private val EARLY_GAINS = floatArrayOf(1f, 0.72f, 0.53f, 0.38f)
private const val FEEDBACK = 0.84f
private const val DIFFUSION = 0.5f
private const val MIN_SIZE = 0.35f
private const val MAX_SIZE = 1.6f
private const val MIN_DAMPING = 0.05f
private const val MAX_DAMPING = 0.4f
private const val TAIL_DUCK = 0.8f
private const val TAIL_HEADROOM = 0.7f
private const val KNEE = 0.8f
private const val SOFT_CEILING = 1.25f
