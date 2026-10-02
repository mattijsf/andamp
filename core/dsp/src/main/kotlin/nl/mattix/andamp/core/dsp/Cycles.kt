// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.dsp

/** Finds cycles the engine could not evaluate. */
internal object Cycles {
    /**
     * Reports every cycle with nothing in it to hold a sample for a frame.
     *
     * One edge is cut before looking: a delay's `input`, because the delay's output is what
     * was written at least a frame earlier. A tap pair needs no cut, because the reading half
     * takes no input. Anything still circular is reported as
     * [GraphError.UndelayedFeedback].
     */
    fun check(
        spec: GraphSpec,
        errors: MutableList<GraphError>,
    ) {
        val visiting = BooleanArray(spec.nodes.size)
        val done = BooleanArray(spec.nodes.size)
        val path = ArrayDeque<Int>()
        spec.nodes.indices.forEach { walk(spec, it, visiting, done, path, errors) }
    }

    private fun walk(
        spec: GraphSpec,
        at: Int,
        visiting: BooleanArray,
        done: BooleanArray,
        path: ArrayDeque<Int>,
        errors: MutableList<GraphError>,
    ) {
        if (done[at]) return
        if (visiting[at]) {
            errors += GraphError.UndelayedFeedback(path.dropWhile { it != at } + at)
            return
        }
        visiting[at] = true
        path.addLast(at)
        val node = spec.nodes[at]
        node.edges.forEach { (key, edge) ->
            val cut = node.primitive == Primitive.DELAY && key == "input"
            if (!cut && edge is Edge.Ref && edge.node in spec.nodes.indices) {
                walk(spec, edge.node, visiting, done, path, errors)
            }
        }
        path.removeLast()
        visiting[at] = false
        done[at] = true
    }
}
