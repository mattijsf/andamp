// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.dsp

import kotlin.math.abs

/**
 * Checks one node: the right keys, of the right kind, pointing at nodes that exist.
 *
 * These are the rules read off the [Primitive] table. [GraphValidator]'s own checks are about
 * the graph as a whole.
 */
internal object NodeRules {
    fun check(
        spec: GraphSpec,
        index: Int,
        node: NodeSpec,
        errors: MutableList<GraphError>,
    ) {
        val primitive = node.primitive
        val given = node.edges.keys + node.consts.keys
        (given - primitive.keys).forEach {
            errors += GraphError.UnknownOption(index, primitive, it, nearest(it, primitive.keys))
        }
        (primitive.required - given).forEach { errors += GraphError.MissingOption(index, primitive, it) }
        node.edges.keys.filter { it in primitive.constKeys }.forEach {
            errors += GraphError.EdgeInConstSlot(index, it)
        }
        node.consts.keys.filter { it in primitive.edgeKeys }.forEach {
            errors += GraphError.ConstExpected(index, it)
        }
        node.edges.forEach { (key, edge) -> checkEdge(spec, index, key, edge, errors) }
        node.consts.forEach { (key, arg) -> checkConst(spec, index, node, key, arg, errors) }
    }

    private fun checkEdge(
        spec: GraphSpec,
        index: Int,
        key: String,
        edge: Edge,
        errors: MutableList<GraphError>,
    ) {
        if (edge !is Edge.Ref) return
        when {
            edge.node == index -> {
                errors += GraphError.SelfReference(index, key)
            }

            edge.node !in spec.nodes.indices -> {
                errors += GraphError.MissingNode(index, key, edge.node, spec.nodes.size)
            }
        }
    }

    private fun checkConst(
        spec: GraphSpec,
        index: Int,
        node: NodeSpec,
        key: String,
        arg: ConstArg,
        errors: MutableList<GraphError>,
    ) {
        if (node.primitive.isText(key)) {
            val text = (arg as? ConstArg.Text)?.value
            val allowed = node.primitive.enums[key]
            when {
                text == null -> errors += GraphError.ConstTypeMismatch(index, key, "a name")
                allowed != null && text !in allowed -> errors += GraphError.UnknownEnum(index, key, text, allowed)
            }
            return
        }
        val number = (arg as? ConstArg.Num)?.value
        if (number == null) {
            errors += GraphError.ConstTypeMismatch(index, key, "a number")
            return
        }
        checkNumericConst(spec, index, node, key, number, errors)
    }

    /** The bounds a numeric constant has to satisfy, per primitive. */
    private fun checkNumericConst(
        spec: GraphSpec,
        index: Int,
        node: NodeSpec,
        key: String,
        value: Float,
        errors: MutableList<GraphError>,
    ) {
        when (node.primitive) {
            Primitive.INPUT -> {
                if (!inRange(value, spec.channels)) {
                    errors += GraphError.ChannelOutOfRange(index, value.toInt(), spec.channels)
                }
            }

            Primitive.PARAM -> {
                if (!inRange(value, spec.params.size)) {
                    errors += GraphError.ParamOutOfRange(index, value.toInt(), spec.params.size)
                }
            }

            Primitive.ALLPASS1 -> {
                if (value <= 0f || value > GraphValidator.LOOP_CEILING) {
                    errors += GraphError.CeilingOutOfRange(index, value)
                }
            }

            Primitive.DELAY -> {
                // A fractional maxTime is accepted and the line is rounded up to hold it:
                // a delay sized from the sample rate is often fractional, as 70 ms at
                // 22.05 kHz is 1543.5 frames.
                if (key == "maxTime" && value < 1f) {
                    errors += GraphError.BadMaxTime(index, value)
                }
            }

            Primitive.TAP -> {
                if (key == "line") checkTapLine(spec, index, value, errors)
            }

            // no other primitive's numeric constants have bounds
            else -> {
                Unit
            }
        }
    }

    private fun inRange(
        value: Float,
        count: Int,
    ) = whole(value) && value >= 0f && value < count

    private fun checkTapLine(
        spec: GraphSpec,
        index: Int,
        value: Float,
        errors: MutableList<GraphError>,
    ) {
        val line = value.toInt()
        val target = spec.nodes.getOrNull(line)
        when {
            !whole(value) || target == null -> errors += GraphError.MissingNode(index, "line", line, spec.nodes.size)
            target.primitive != Primitive.DELAY -> errors += GraphError.TapOnNonDelay(index, line)
        }
    }

    private fun whole(value: Float) = value.isFinite() && abs(value - value.toInt()) < 1e-6f

    /** A key one edit away from [typo], offered as a suggestion. */
    private fun nearest(
        typo: String,
        keys: Set<String>,
    ): String? = keys.firstOrNull { distance(typo, it) == 1 }

    private fun distance(
        a: String,
        b: String,
    ): Int {
        if (abs(a.length - b.length) > 1) return Int.MAX_VALUE
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val row = IntArray(b.length + 1)
            row[0] = i
            for (j in 1..b.length) {
                val substitute = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                row[j] = minOf(row[j - 1] + 1, previous[j] + 1, substitute)
            }
            previous = row
        }
        return previous[b.length]
    }
}
