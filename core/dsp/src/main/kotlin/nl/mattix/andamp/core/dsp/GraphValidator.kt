// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.dsp

/**
 * Finds everything wrong with a graph before anything is built from it.
 *
 * It returns all the errors and not only the first, and it allocates no delay memory, so a
 * graph asking for a gigabyte of delay is refused cheaply. [GraphCompiler.compile] runs it
 * before lowering.
 *
 * The per-node rules are read off [Primitive], so a new primitive is a new row there.
 */
object GraphValidator {
    /** The largest magnitude a feedback path may hold; the engine clips to it. */
    const val LOOP_CEILING = 16f

    fun validate(
        spec: GraphSpec,
        budgets: Budgets = Budgets.DEFAULT,
    ): List<GraphError> {
        val errors = mutableListOf<GraphError>()
        if (spec.api != GraphSpec.API) errors += GraphError.UnknownApi(spec.api)
        spec.nodes.forEachIndexed { index, node -> NodeRules.check(spec, index, node, errors) }
        checkOutputs(spec, errors)
        checkTaps(spec, errors)
        checkDelayMemory(spec, budgets, errors)
        Cycles.check(spec, errors)

        if (spec.nodes.size > budgets.maxNodes) {
            errors += GraphError.NodeBudget(spec.nodes.size, budgets.maxNodes)
        }
        return errors
    }

    private fun checkOutputs(
        spec: GraphSpec,
        errors: MutableList<GraphError>,
    ) {
        if (spec.outputs.size != spec.channels) {
            errors += GraphError.OutputArity(spec.outputs.size, spec.channels)
        }
        spec.outputs.forEachIndexed { channel, node ->
            if (node !in spec.nodes.indices) errors += GraphError.OutputNodeMissing(channel, node)
        }
    }

    private fun checkTaps(
        spec: GraphSpec,
        errors: MutableList<GraphError>,
    ) {
        val writes = taps(spec, Primitive.TAPIN)
        val reads = taps(spec, Primitive.TAPOUT)
        writes.forEach { (name, count) ->
            if (count > 1) errors += GraphError.DuplicateTap(name, "write")
            if (name !in reads) errors += GraphError.UnmatchedTap(name, "reader")
        }
        reads.forEach { (name, count) ->
            if (count > 1) errors += GraphError.DuplicateTap(name, "reader")
            if (name !in writes) errors += GraphError.UnmatchedTap(name, "write")
        }
    }

    private fun taps(
        spec: GraphSpec,
        half: Primitive,
    ): Map<String, Int> =
        spec.nodes
            .filter { it.primitive == half }
            .mapNotNull { (it.consts["name"] as? ConstArg.Text)?.value }
            .groupingBy { it }
            .eachCount()

    private fun checkDelayMemory(
        spec: GraphSpec,
        budgets: Budgets,
        errors: MutableList<GraphError>,
    ) {
        // summed as a Long: two lines of a thousand million frames each would overflow an
        // Int to a negative number, which is under any budget
        var total = 0L
        spec.nodes.forEach {
            if (it.primitive == Primitive.DELAY) {
                total += ((it.consts["maxTime"] as? ConstArg.Num)?.value ?: 0f).toInt().coerceAtLeast(0)
            }
        }
        val frames = total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (total != spec.delayFrames.toLong()) errors += GraphError.DelayFramesMismatch(spec.delayFrames, frames)
        if (total > budgets.maxDelayFrames) errors += GraphError.DelayMemoryBudget(frames, budgets.maxDelayFrames)
    }
}
