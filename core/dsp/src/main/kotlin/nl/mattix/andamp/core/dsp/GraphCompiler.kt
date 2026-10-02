// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.dsp

/**
 * Arranges a checked graph into something the audio thread can run.
 *
 * The compiler derives the order, the bus slot of each result, the state to allocate, and
 * which instructions run once per control tick. A producer does not declare these, because
 * a wrong value could crash the audio thread.
 *
 * Rate is the one thing a graph declares. The compiler infers it again and refuses a
 * disagreement, which verifies the control-rate rule in docs/dsp-plugin-spec.md section 5.
 */
object GraphCompiler {
    /** Validates [spec] with [GraphValidator] and returns the lowered tape, or the errors that stopped it. */
    fun compile(spec: GraphSpec): Result {
        val invalid = GraphValidator.validate(spec)
        if (invalid.isNotEmpty()) return Result.Failed(invalid)
        val order = order(spec)
        val rates = rates(spec, order)
        val mismatched =
            order.mapNotNull { node ->
                val inferred = rates[node]!!
                if (spec.nodes[node].rate != inferred) {
                    GraphError.RateTagMismatch(node, spec.nodes[node].rate, inferred)
                } else {
                    null
                }
            }
        if (mismatched.isNotEmpty()) return Result.Failed(mismatched)
        return Result.Compiled(Lowering(spec, order, rates).lower())
    }

    /**
     * The same graph with every node's rate filled in. A producer building a graph calls this
     * and does not work rates out itself; [compile] then verifies the tags.
     */
    fun annotate(spec: GraphSpec): GraphSpec {
        val rates = rates(spec, order(spec))
        return spec.copy(nodes = spec.nodes.mapIndexed { index, node -> node.copy(rate = rates.getValue(index)) })
    }

    /** The graph as something runnable, or null when it would not compile. */
    fun engine(spec: GraphSpec): FrameProcessor? = (compile(spec) as? Result.Compiled)?.let { GraphEngine(it.tape) }

    sealed interface Result {
        class Compiled internal constructor(
            internal val tape: Tape,
        ) : Result

        data class Failed(
            val errors: List<GraphError>,
        ) : Result
    }

    /**
     * Depth first, with the edge cut that [Cycles] cuts, so a node is emitted after
     * everything it reads within a frame.
     */
    private fun order(spec: GraphSpec): List<Int> {
        val seen = BooleanArray(spec.nodes.size)
        val order = ArrayList<Int>(spec.nodes.size)

        fun visit(at: Int) {
            if (seen[at]) return
            seen[at] = true
            val node = spec.nodes[at]
            node.edges.forEach { (key, edge) ->
                if (edge is Edge.Ref && !cut(node, key)) visit(edge.node)
            }
            order += at
        }
        spec.nodes.indices.forEach(::visit)
        return order
    }

    /** A delay reads what was written at least a frame earlier, so its input does not order it. */
    private fun cut(
        node: NodeSpec,
        key: String,
    ) = node.primitive == Primitive.DELAY && key == "input"

    /**
     * A node fed by an audio-rate node is audio rate, and so is every stateful node: an
     * oscillator fed only by parameters still runs every frame, because its output depends
     * on its own past.
     */
    private fun rates(
        spec: GraphSpec,
        order: List<Int>,
    ): Map<Int, Rate> {
        val rates = HashMap<Int, Rate>(spec.nodes.size)
        order.forEach { index ->
            val node = spec.nodes[index]
            val fed =
                node.edges.values.any { edge ->
                    edge is Edge.Ref && rates[edge.node] == Rate.AUDIO
                }
            rates[index] =
                node.primitive.fixedRate
                    ?: if (node.primitive.stateful || fed) Rate.AUDIO else Rate.CONTROL
        }
        return rates
    }
}
