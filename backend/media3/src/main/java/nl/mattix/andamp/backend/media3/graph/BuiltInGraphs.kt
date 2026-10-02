// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3.graph

import nl.mattix.andamp.core.dsp.ConstArg
import nl.mattix.andamp.core.dsp.Edge
import nl.mattix.andamp.core.dsp.GraphParam
import nl.mattix.andamp.core.dsp.GraphSpec
import nl.mattix.andamp.core.dsp.NodeSpec
import nl.mattix.andamp.core.dsp.Primitive
import nl.mattix.andamp.core.model.EffectSpec
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val KEEP_MIN_HZ = 60f

/** log2(500 / 60): the slider's travel in octaves. */
private const val KEEP_OCTAVES = 3.0588937f
private const val MAX_CUTOFF_FRACTION = 0.45f
private const val BUTTERWORTH_Q = 0.70710677f
private const val MAX_DRY = 0.5f
private const val CEILING = 1.5f

// pitch modulation: the same constants as the reference ModulationStage in the
// tests. Chorus is mode 0.
private const val FLANGER = 1
private const val PHASER = 2
private const val MIN_RATE_HZ = 0.05f

/** log2(8 / 0.05): the rate slider's travel in octaves. */
private const val RATE_OCTAVES = 7.321928f
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

// the room: constants shared with ReverbStage in the tests
private const val REFERENCE_RATE = 44_100f
private val COMB_LENGTHS = intArrayOf(1116, 1188, 1277, 1356, 1491, 1617)
private val ALLPASS_LENGTHS = intArrayOf(556, 441, 341)

/**
 * How fast each line's read wanders when wander is on, in Hz, and the phase
 * each starts at. No two rates are equal or a multiple of another, so the
 * lines do not move in step.
 */
private val WANDER_HZ = floatArrayOf(0.71f, 0.93f, 1.13f, 1.29f, 1.49f, 1.61f)
private val WANDER_PHASES = floatArrayOf(0f, 0.37f, 0.11f, 0.68f, 0.24f, 0.83f)

/** One diffuser per line, inside its loop: short prime lengths. */
private val SCATTER_LENGTHS = intArrayOf(131, 167, 211, 257, 307, 353)
private const val STEREO_SPREAD = 23f
private val EARLY_TAPS_MS = floatArrayOf(6.7f, 11.3f, 17.9f, 23.1f)
private val EARLY_GAINS = floatArrayOf(1f, 0.72f, 0.53f, 0.38f)

/** What the busiest line keeps on a pass, for scaling the input against. */
private const val LOUDEST_PASS = 0.84f

private const val LOG2_OF_TEN = 3.321928f
private const val DIFFUSION = 0.5f
private const val MIN_SIZE = 0.35f
private const val MAX_SIZE = 1.6f
private const val MIN_DAMPING = 0.05f
private const val MAX_DAMPING = 0.4f
private const val TAIL_DUCK = 0.8f
private const val TAIL_HEADROOM = 0.7f
private const val KNEE = 0.8f
private const val SOFT_CEILING = 1.25f

/**
 * The built-in effects as graphs the rack can run.
 *
 * Each effect's controls are graph parameters, declared in the order its
 * [EffectSpec] declares them, so a slider's index is the same in both.
 */
internal object BuiltInGraphs {
    /** The parameters of [spec], in the order the rack will address them. */
    fun params(spec: EffectSpec): List<GraphParam> =
        spec.params.map { GraphParam(id = it.id, min = it.min, max = it.max, default = it.default) }

    /** Karaoke: the mid lowpassed and mixed back under the sides. */
    fun karaoke(
        spec: EffectSpec,
        sampleRate: Int,
        channels: Int,
    ): GraphSpec {
        val g = Builder(channels, sampleRate, params(spec))
        val level = g.param(0)
        val filter = g.param(1)
        val band = g.param(2)
        val width = g.param(3)
        val left = g.input(0)
        val right = g.input(1)
        val half = Edge.Const(0.5f)
        val mid = g.mul(g.add(left, right), half)
        val side = g.mul(g.sub(left, right), half)
        val cutoff =
            g.min(
                g.mul(Edge.Const(KEEP_MIN_HZ), g.math(g.mul(filter, Edge.Const(KEEP_OCTAVES)), "exp2")),
                Edge.Const(sampleRate * MAX_CUTOFF_FRACTION),
            )
        val twelve = g.lowpass(mid, cutoff, BUTTERWORTH_Q)
        val twentyFour = g.lowpass(twelve, cutoff, BUTTERWORTH_Q)
        val bass = g.crossfade(twentyFour, twelve, band)
        val stereoBack = g.mul(width, Edge.Const(MAX_DRY))
        val wet = listOf(g.add(side, bass), g.sub(bass, side))
        return g.spec(
            (0 until channels).map { channel ->
                val dry = if (channel % 2 == 0) left else right
                val widened = g.crossfade(wet[channel % 2], dry, stereoBack)
                g.clip(g.crossfade(dry, widened, level), -CEILING, CEILING)
            },
        )
    }

    /**
     * Pitch modulation: one swept delay for the chorus and the flanger, six
     * swept all-pass sections for the phaser.
     *
     * [mode] is a build argument because it is a structural parameter: it
     * chooses which nodes exist. Selecting between the three topologies inside
     * one graph would run all three on every frame, since the engine has no
     * branch. Every other parameter is live.
     */
    fun modulation(
        spec: EffectSpec,
        sampleRate: Int,
        channels: Int,
        mode: Int,
    ): GraphSpec {
        val g = Builder(channels, sampleRate, params(spec))
        // 2^(x * log2(160)), because the primitive set has no pow
        val hz =
            g.mul(
                Edge.Const(MIN_RATE_HZ),
                g.math(g.mul(g.param(spec, "rate"), Edge.Const(RATE_OCTAVES)), "exp2"),
            )
        val blend = g.param(spec, "lfo")
        val depth = g.param(spec, "depth")
        val level = g.param(spec, "level")
        val wet = level
        val dry = g.sub(Edge.Const(1f), level)
        val feedback = g.param(spec, "feedback")
        val stereoOffset = g.mul(g.param(spec, "stereo"), Edge.Const(0.5f))
        val lineSize = (sampleRate * MAX_DELAY_MS / MS_PER_SEC).toInt().coerceAtLeast(MIN_LINE_SIZE)

        return g.spec(
            (0 until channels).map { channel ->
                val phase = if (channel % 2 == 1) stereoOffset else Edge.Const(0f)
                val sweep = g.crossfade(g.lfo(hz, "sine", phase), g.lfo(hz, "triangle", phase), blend)
                val sample = g.clean(g.input(channel), MOD_CEILING)
                val voice =
                    if (mode == PHASER) {
                        g.phased(sample, sweep, depth, g.mul(feedback, Edge.Const(MAX_FEEDBACK)), sampleRate, channel)
                    } else {
                        g.combed(sample, sweep, mode == FLANGER, depth, feedback, lineSize, sampleRate)
                    }
                g.add(g.mul(sample, dry), g.mul(voice, wet))
            },
        )
    }

    /**
     * The reverb: six damped delay lines mixed through a Householder matrix,
     * into three all-passes, with a tapped line for the early reflections.
     *
     * Size is a live edge: it moves the line and early-tap times while the
     * tail is ringing. The times are rounded in the graph (see
     * [Builder.rounded]) and read without interpolation. The all-pass lengths
     * do not follow size.
     *
     * [wander] moves each line's read by up to that many samples, to break up
     * the pitches that survive in the late tail. It is zero by default;
     * docs/reverb-notes.md describes it and ReverbQualityTest measures what
     * it changes. At zero the reads are whole samples and the graph has no
     * oscillators.
     */
    @Suppress("LongMethod") // it is a wiring diagram; splitting it hides the signal path
    fun reverb(
        spec: EffectSpec,
        sampleRate: Int,
        channels: Int,
        wander: Float = 0f,
    ): GraphSpec {
        val g = Builder(channels, sampleRate, params(spec))
        val rateScale = sampleRate / REFERENCE_RATE
        val spreadSamples = (STEREO_SPREAD * rateScale).roundToInt()
        val combBuffer = (COMB_LENGTHS.max() * MAX_SIZE * rateScale).toInt() + 2 + ceil(wander).toInt()
        val earlyBuffer = (EARLY_TAPS_MS.max() * MAX_SIZE * sampleRate / MS_PER_SEC).toInt() + 2
        val earlyNormalise = Edge.Const(1f / EARLY_GAINS.sum())
        // the input is scaled so that a network which recirculates most of its
        // input does not come out louder than it went in
        val tailInput = Edge.Const(TAIL_HEADROOM * sqrt((1f - LOUDEST_PASS * LOUDEST_PASS) / COMB_LENGTHS.size))
        val near = g.param(spec, "near")
        val level = g.param(spec, "level")
        // T60 from the control, in seconds. What a line keeps on each pass is
        // 2^(-3 * log2(10) * M / (T60 * Fs)). This is that exponent without M,
        // the line's length, which is multiplied in per line so that every
        // line decays at the same T60.
        val perSample =
            g.mul(
                Edge.Const(-3f * LOG2_OF_TEN / sampleRate),
                g.math(g.param(spec, "decay"), "recip"),
            )
        val scale = g.add(Edge.Const(MIN_SIZE), g.mul(g.param(spec, "size"), Edge.Const(MAX_SIZE - MIN_SIZE)))
        val damping =
            g.sub(Edge.Const(MAX_DAMPING), g.mul(g.param(spec, "air"), Edge.Const(MAX_DAMPING - MIN_DAMPING)))
        val tailGain = g.sub(Edge.Const(1f), g.mul(near, Edge.Const(TAIL_DUCK)))
        val dry = g.sub(Edge.Const(1f), level)
        val diffusion = Edge.Const(DIFFUSION)

        val cleaned = (0 until channels).map { g.sanitise(g.input(it)) }
        var sum: Edge = cleaned[0]
        for (channel in 1 until channels) sum = g.add(sum, cleaned[channel])
        val mono = g.sanitise(g.mul(sum, Edge.Const(1f / channels)))
        val combInput = g.mul(mono, tailInput)

        return g.spec(
            (0 until channels).map { channel ->
                val spread = channel * spreadSamples

                // ReverbStage pushes its line before it reads, so its delay of
                // d is this line's d - 1
                val delays =
                    EARLY_TAPS_MS.map { ms ->
                        val scaled =
                            g.div(
                                g.mul(g.mul(Edge.Const(ms), scale), Edge.Const(sampleRate.toFloat())),
                                Edge.Const(MS_PER_SEC),
                            )
                        g.add(g.rounded(scaled), Edge.Const((spread - 1).toFloat()))
                    }
                // the delay node's own read is the first reflection; the others
                // are taps on the same line
                val early = g.delay(earlyBuffer + spread, delays[0], interp = "none")
                g.feed(early, mono)
                var reflections: Edge = g.mul(early, Edge.Const(EARLY_GAINS[0]))
                delays.drop(1).forEachIndexed { i, delay ->
                    val heard = g.mul(g.tap(early, delay, interp = "none"), Edge.Const(EARLY_GAINS[i + 1]))
                    reflections = g.add(reflections, heard)
                }

                // The lines feed each other through a Householder matrix (Jot;
                // Smith, Physical Audio Signal Processing). Six parallel combs
                // would each hear only themselves and ring at six pitches. The
                // matrix is orthogonal, so it spreads energy between the lines
                // without gain or loss. A = I - (2/N)J means
                // y = x - (2/N) * sum(x): one sum and one subtraction per line.
                val times =
                    COMB_LENGTHS.map { length ->
                        g.add(
                            g.rounded(g.mul(Edge.Const(length * rateScale), scale)),
                            Edge.Const(spread.toFloat()),
                        )
                    }
                // Where each line is read: its time, plus a slow wander when
                // one is asked for. The gains below use the nominal times, so
                // the decay does not vary with the modulation.
                val readTimes =
                    if (wander <= 0f) {
                        times
                    } else {
                        times.mapIndexed { at, time ->
                            g.add(
                                time,
                                g.mul(
                                    g.lfo(Edge.Const(WANDER_HZ[at]), "sine", Edge.Const(WANDER_PHASES[at])),
                                    Edge.Const(wander),
                                ),
                            )
                        }
                    }
                val lines =
                    readTimes.map { time ->
                        g.delay(combBuffer + spread, time, interp = if (wander > 0f) "linear" else "none")
                    }
                // short and prime at the reference rate, so the diffusers do
                // not line up with each other
                val scatterLengths = SCATTER_LENGTHS.map { ((it * rateScale).roundToInt() + spread).coerceAtLeast(1) }
                val damped =
                    lines.map { line -> g.node(Primitive.ONEPOLE, mapOf("input" to line, "coeff" to damping)) }
                val shared =
                    g.mul(
                        damped.reduce { a, b -> g.add(a, b) },
                        Edge.Const(2f / COMB_LENGTHS.size),
                    )
                lines.forEachIndexed { at, line ->
                    // A diffuser inside the loop, so the echo density grows on
                    // every pass, as a room's does (Gerzon, Griesinger,
                    // Gardner; docs/reverb-notes.md).
                    val mixed = g.scatter(g.sub(damped[at], shared), scatterLengths[at], diffusion)
                    // One gain per line, from its own length. A short line goes
                    // round more often per second, so a shared gain would let
                    // the long lines ring after the short ones have gone.
                    // Jot's rule g = a^M gives every line the same T60. The
                    // times are live, so the size control changes the room and
                    // not the decay.
                    g.feed(line, g.add(combInput, g.mul(mixed, g.math(g.mul(times[at], perSample), "exp2"))))
                }
                var tail: Edge? = lines.reduce { a, b -> g.add(a, b) }
                ALLPASS_LENGTHS.forEach { length ->
                    val frames = ((length * rateScale).roundToInt() + spread).coerceAtLeast(1)
                    val line = g.delay(frames, Edge.Const(frames.toFloat()), interp = "none")
                    val stored = g.sanitise(g.add(tail!!, g.mul(line, diffusion)))
                    g.feed(line, stored)
                    tail = g.sub(line, g.mul(stored, diffusion))
                }

                val room =
                    g.add(
                        g.mul(g.mul(reflections, earlyNormalise), near),
                        g.mul(tail!!, tailGain),
                    )
                g.add(g.mul(cleaned[channel], dry), g.mul(g.soften(room), level))
            },
        )
    }

    /**
     * Leaves anything below the knee unchanged and bends the rest toward
     * [SOFT_CEILING], so a resonance in the network does not leave the effect
     * above it.
     *
     * Built from arithmetic instead of `softclip`: the sign is restored by
     * clipping the input to the softened magnitude, which is exact at zero.
     */
    private fun Builder.soften(input: Edge): Edge {
        val knee = Edge.Const(KNEE)
        val room = Edge.Const(SOFT_CEILING - KNEE)
        val magnitude = math(input, "abs")
        val over = max(sub(magnitude, knee), Edge.Const(0f))
        val bent = add(knee, div(mul(room, over), add(over, room)))
        val wanted = min(magnitude, bent)
        return node(Primitive.CLIP, mapOf("input" to input, "min" to math(wanted, "neg"), "max" to wanted))
    }

    /** Chorus and flanger: one swept delay line, with a different base time, sweep and feedback. */
    private fun Builder.combed(
        sample: Edge,
        sweep: Edge,
        flanging: Boolean,
        depth: Edge,
        feedback: Edge,
        lineSize: Int,
        sampleRate: Int,
    ): Edge {
        val base = sampleRate * (if (flanging) FLANGER_BASE_MS else CHORUS_BASE_MS) / MS_PER_SEC
        val reach = sampleRate * (if (flanging) FLANGER_SWEEP_MS else CHORUS_SWEEP_MS) / MS_PER_SEC
        val gain = mul(feedback, Edge.Const(if (flanging) MAX_FEEDBACK else CHORUS_MAX_FEEDBACK))
        val span = mul(Edge.Const(reach), depth)
        // base + span * (1 + sweep) * 0.5, associated as ModulationStage associates it
        val travelled = mul(mul(span, add(Edge.Const(1f), sweep)), Edge.Const(0.5f))
        val time = clip(add(Edge.Const(base), travelled), 1f, (lineSize - 2).toFloat())
        val line = delay(lineSize - 2, time)
        feed(line, clean(add(sample, mul(line, gain)), MOD_CEILING))
        return line
    }

    /** The phaser: six all-pass sections with feedback through a tap pair, and no delay line. */
    private fun Builder.phased(
        sample: Edge,
        sweep: Edge,
        depth: Edge,
        feedback: Edge,
        sampleRate: Int,
        channel: Int,
    ): Edge {
        val octaves = mul(Edge.Const(PHASER_OCTAVES), depth)
        val exponent = mul(mul(octaves, add(Edge.Const(1f), sweep)), Edge.Const(0.5f))
        val corner =
            clip(
                mul(Edge.Const(PHASER_FLOOR_HZ), math(exponent, "exp2")),
                PHASER_FLOOR_HZ,
                sampleRate * MAX_CORNER_FRACTION,
            )
        // (PI * corner) / rate, associated as ModulationStage does, so the
        // coefficient is the same float as the reference's
        val tangent = math(div(mul(Edge.Const(PI.toFloat()), corner), Edge.Const(sampleRate.toFloat())), "tan")
        val coefficient = div(sub(tangent, Edge.Const(1f)), add(tangent, Edge.Const(1f)))
        val name = "fb$channel"
        val returned = node(Primitive.TAPOUT, consts = mapOf("name" to ConstArg.Text(name)))
        var voice = clean(add(sample, mul(returned, feedback)), MOD_CEILING)
        repeat(SECTIONS) { voice = allpass(voice, coefficient, MOD_CEILING) }
        node(Primitive.TAPIN, mapOf("source" to voice), mapOf("name" to ConstArg.Text(name)))
        return voice
    }

    /**
     * A small graph builder, with one method per primitive the built-in
     * graphs use (docs/dsp-plugin-spec.md section 6 lists the primitives).
     */
    @Suppress("TooManyFunctions") // one method per primitive
    internal class Builder(
        private val channels: Int,
        private val sampleRate: Int,
        private val params: List<GraphParam>,
    ) {
        private val nodes = mutableListOf<NodeSpec>()
        private val paramNode = HashMap<Int, Edge.Ref>()

        fun node(
            primitive: Primitive,
            edges: Map<String, Edge> = emptyMap(),
            consts: Map<String, ConstArg> = emptyMap(),
        ): Edge.Ref {
            nodes += NodeSpec(primitive, edges, consts)
            return Edge.Ref(nodes.size - 1)
        }

        fun input(channel: Int) = node(Primitive.INPUT, consts = mapOf("ch" to ConstArg.Num(channel.toFloat())))

        /** A control by its id in the effect's spec. */
        fun param(
            spec: EffectSpec,
            id: String,
        ): Edge.Ref = param(spec.params.indexOfFirst { it.id == id })

        fun param(index: Int): Edge.Ref =
            paramNode.getOrPut(index) {
                node(Primitive.PARAM, consts = mapOf("index" to ConstArg.Num(index.toFloat())))
            }

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

        fun min(
            a: Edge,
            b: Edge,
        ) = node(Primitive.MIN, mapOf("a" to a, "b" to b))

        fun max(
            a: Edge,
            b: Edge,
        ) = node(Primitive.MAX, mapOf("a" to a, "b" to b))

        /**
         * ReverbStage's `roundToInt`: `math{fn="round"}` rounds a tie
         * to even, and this rounds a tie up.
         */
        fun rounded(value: Edge) = math(add(value, Edge.Const(0.5f)), "floor")

        /**
         * A Schroeder all-pass: a delay with [amount] fed forward and back.
         * ALLPASS1 is a one-pole phase filter and does not do this.
         */
        fun scatter(
            input: Edge,
            frames: Int,
            amount: Edge,
        ): Edge {
            val line = delay(frames, Edge.Const(frames.toFloat()), interp = "none")
            val stored = sanitise(add(input, mul(line, amount)))
            feed(line, stored)
            return sub(line, mul(stored, amount))
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

        fun crossfade(
            a: Edge,
            b: Edge,
            t: Edge,
        ) = node(Primitive.CROSSFADE, mapOf("a" to a, "b" to b, "t" to t))

        fun clip(
            input: Edge,
            low: Float,
            high: Float,
        ) = node(
            Primitive.CLIP,
            mapOf("input" to input, "min" to Edge.Const(low), "max" to Edge.Const(high)),
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

        /** Sanitizes [input] and clips it to plus or minus [ceiling]. */
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
         * A delay line whose input is not known yet: the write closes a
         * feedback loop, so the node is created first and [feed] sets its
         * source afterwards.
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

        fun lowpass(
            input: Edge,
            frequency: Edge,
            q: Float,
        ) = node(
            Primitive.BIQUAD,
            edges = mapOf("input" to input, "freq" to frequency, "q" to Edge.Const(q)),
            consts = mapOf("kind" to ConstArg.Text("lowpass")),
        )

        fun spec(outputs: List<Edge.Ref>) =
            GraphSpec(
                sampleRate = sampleRate,
                channels = channels,
                params = params,
                nodes = nodes.toList(),
                outputs = outputs.map { it.node },
                delayFrames = nodes.sumOf { ((it.consts["maxTime"] as? ConstArg.Num)?.value ?: 0f).toInt() },
            )
    }
}
