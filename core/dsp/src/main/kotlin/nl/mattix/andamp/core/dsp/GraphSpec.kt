// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.dsp

/**
 * One input to one node: a literal, or the output of another node.
 *
 * Tagged, the way a SuperCollider SynthDef encodes its inputs. Constant folding follows from
 * the encoding, and a mistyped constant is a different load error from a missing node.
 *
 * There is no output index, because a primitive in api 1 has exactly one output.
 */
sealed interface Edge {
    @JvmInline
    value class Const(
        val value: Float,
    ) : Edge

    @JvmInline
    value class Ref(
        val node: Int,
    ) : Edge
}

/**
 * A build-time argument: fixed when the graph is built, and not something a slider can move.
 * Typed, so that a number where a name belongs is reported as an error.
 */
sealed interface ConstArg {
    @JvmInline
    value class Num(
        val value: Float,
    ) : ConstArg

    @JvmInline
    value class Text(
        val value: String,
    ) : ConstArg
}

/**
 * How often a node's output can change, as in a SynthDef. The compiler infers each node's
 * rate and refuses a graph whose declared rate disagrees, which verifies that a node fed only
 * by parameters runs once per control tick (docs/dsp-plugin-spec.md section 5).
 */
enum class Rate {
    /** Once per control tick. */
    CONTROL,

    /** Every frame. */
    AUDIO,
}

/**
 * A control the host owns and the graph reads. Values arrive smoothed; see
 * docs/dsp-plugin-spec.md section 5.
 */
data class GraphParam(
    val id: String,
    val min: Float = 0f,
    val max: Float = 1f,
    val default: Float = 0f,
    val smoothMs: Float = DEFAULT_SMOOTH_MS,
    /** The label; the id when the plug-in gave none. Never reaches the graph. */
    val name: String = id,
    val help: String = "",
    /**
     * The LV2 unit symbol the value is read in: "hz", "ms", "db", "pc" and so on. Display
     * only, like [name]: the graph always gets the number in the declared range
     * (docs/dsp-plugin-spec.md section 2).
     */
    val unit: String = "",
    /** Whether the host draws a switch for it. Display only; the graph sees 0 or 1. */
    val toggle: Boolean = false,
    /** What the readout multiplies by, so that a mix the graph reads as 0..1 can be shown as 0..100. */
    val displayScale: Float = 1f,
    /** Decimals in the readout, or -1 to let the host work it out from the range. */
    val displayDecimals: Int = -1,
    /** What the readout says at the minimum, for a setting that reads OFF and not 0. */
    val displayZero: String = "",
    /**
     * Label to value, when the parameter is one of a few named settings. Display only: the
     * graph reads the number the chosen setting stands for.
     */
    val choices: Map<String, Float> = emptyMap(),
) {
    /**
     * How far this parameter moves towards its target on each control tick, as a fraction of
     * the remaining distance. With no smoothing time it jumps.
     */
    fun stepPerTick(sampleRate: Int): Float {
        if (smoothMs <= 0f) return 1f
        val ticksPerSecond = sampleRate.toFloat() / CONTROL_PERIOD
        return (1f / (ticksPerSecond * smoothMs / MS_PER_SEC)).coerceIn(0f, 1f)
    }

    companion object {
        const val DEFAULT_SMOOTH_MS = 20f
        const val MS_PER_SEC = 1000f

        /** Frames between control ticks. The engine reads this constant. */
        const val CONTROL_PERIOD = 32
    }
}

/**
 * One node. Keys are strings, so an unknown key is found as a set difference against what
 * the primitive declares and is reported by name.
 *
 * [rate] is the producer's claim about this node's output. The compiler infers the rate
 * itself and rejects a disagreement.
 */
data class NodeSpec(
    val primitive: Primitive,
    val edges: Map<String, Edge> = emptyMap(),
    val consts: Map<String, ConstArg> = emptyMap(),
    val rate: Rate = Rate.AUDIO,
)

/**
 * A whole effect, as data. This is what a Lua plug-in's `build` produces and what the compiler
 * lowers. It carries no order, no slot assignments and no sizes: everything derived is derived
 * again by the compiler, because a wrong buffer size from a producer could crash the audio
 * thread.
 *
 * [outputs] names the node feeding each channel. [delayFrames] is the producer's claim about
 * total delay memory, kept only so that the validator can recompute it and reject a mismatch.
 */
data class GraphSpec(
    val api: Int = API,
    val sampleRate: Int,
    val channels: Int,
    val params: List<GraphParam> = emptyList(),
    val nodes: List<NodeSpec> = emptyList(),
    val outputs: List<Int> = emptyList(),
    val delayFrames: Int = 0,
) {
    /**
     * The largest number of frames this graph may be run in one pass.
     *
     * Running a block through one instruction before the next is valid when nothing in the
     * block needs a sample the block has not produced yet. A delay or tap with a constant
     * time shrinks the block to that time. One whose time is computed counts as a time of
     * one frame, since the compiler cannot know how short it gets. A feedback tap holds a
     * sample for one frame, so a graph with one runs a frame at a time.
     */
    val safeBlock: Int
        get() {
            if (nodes.any { it.primitive == Primitive.TAPIN || it.primitive == Primitive.TAPOUT }) return 1
            var smallest = MAX_BLOCK
            nodes.forEach { node ->
                if (node.primitive != Primitive.DELAY && node.primitive != Primitive.TAP) return@forEach
                val declared = (node.edges["time"] as? Edge.Const)?.value?.toInt() ?: 1
                smallest = minOf(smallest, declared.coerceAtLeast(1))
            }
            return smallest
        }

    companion object {
        /** The only version this engine implements. */
        const val API = 1

        /** As many frames as the engine will take in one pass. */
        const val MAX_BLOCK = 128
    }
}
