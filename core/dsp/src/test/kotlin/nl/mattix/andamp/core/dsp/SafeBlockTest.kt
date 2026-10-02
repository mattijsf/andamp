// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Test

/** [GraphSpec.safeBlock]: how many frames of a graph can be run in one pass. */
class SafeBlockTest {
    private fun spec(nodes: List<NodeSpec>) = GraphSpec(sampleRate = 44_100, channels = 1, nodes = nodes, outputs = listOf(0))

    private fun delay(
        time: Edge,
        maxTime: Float = 4096f,
    ) = NodeSpec(
        Primitive.DELAY,
        edges = mapOf("input" to Edge.Const(0f), "time" to time),
        consts = mapOf("maxTime" to ConstArg.Num(maxTime)),
    )

    @Test
    fun `a graph without delays or taps takes the largest block`() {
        val nodes = listOf(NodeSpec(Primitive.INPUT, consts = mapOf("ch" to ConstArg.Num(0f))))

        assertEquals(GraphSpec.MAX_BLOCK, spec(nodes).safeBlock)
    }

    @Test
    fun `a delay longer than the largest block leaves the block at its largest`() {
        assertEquals(GraphSpec.MAX_BLOCK, spec(listOf(delay(Edge.Const(341f)))).safeBlock)
    }

    @Test
    fun `a delay shorter than the block sets the block`() {
        assertEquals(64, spec(listOf(delay(Edge.Const(64f)))).safeBlock)
    }

    @Test
    fun `the shortest delay sets the block`() {
        val nodes = listOf(delay(Edge.Const(512f)), delay(Edge.Const(48f)), delay(Edge.Const(200f)))

        assertEquals(48, spec(nodes).safeBlock)
    }

    @Test
    fun `a delay whose time is computed sets the block to one`() {
        // the time is a node, as in a swept flanger, so the compiler cannot know how short
        // it gets
        assertEquals(1, spec(listOf(delay(Edge.Ref(1)), delay(Edge.Const(512f)))).safeBlock)
    }

    @Test
    fun `a feedback tap sets the block to one`() {
        val nodes =
            listOf(
                NodeSpec(Primitive.TAPOUT, consts = mapOf("name" to ConstArg.Text("fb"))),
                NodeSpec(Primitive.TAPIN, mapOf("source" to Edge.Ref(0)), mapOf("name" to ConstArg.Text("fb"))),
                delay(Edge.Const(512f)),
            )

        assertEquals(1, spec(nodes).safeBlock)
    }
}
