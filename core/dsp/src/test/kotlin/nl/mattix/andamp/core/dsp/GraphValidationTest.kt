// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** One case per rejection rule, asserted on the error's code and not on its sentence. */
class GraphValidationTest {
    private fun graph(
        nodes: List<NodeSpec>,
        outputs: List<Int> = listOf(0, 0),
        channels: Int = 2,
        params: List<GraphParam> = emptyList(),
        delayFrames: Int = 0,
        api: Int = GraphSpec.API,
    ) = GraphSpec(
        api = api,
        sampleRate = 44_100,
        channels = channels,
        params = params,
        nodes = nodes,
        outputs = outputs,
        delayFrames = delayFrames,
    )

    private fun input(channel: Int = 0) = NodeSpec(Primitive.INPUT, consts = mapOf("ch" to ConstArg.Num(channel.toFloat())))

    private fun codes(
        nodes: List<NodeSpec>,
        outputs: List<Int> = listOf(0, 0),
        channels: Int = 2,
        params: List<GraphParam> = emptyList(),
        delayFrames: Int = 0,
        api: Int = GraphSpec.API,
    ) = GraphValidator.validate(graph(nodes, outputs, channels, params, delayFrames, api)).map { it.code }

    @Test
    fun `a valid graph has no errors`() {
        val nodes = listOf(input(0), input(1), NodeSpec(Primitive.ADD, mapOf("a" to Edge.Ref(0), "b" to Edge.Ref(1))))

        assertEquals(emptyList<String>(), codes(nodes, outputs = listOf(2, 2)))
    }

    @Test
    fun `an api this engine does not implement is refused`() {
        assertTrue("unknownApi" in codes(listOf(input()), api = 99))
    }

    @Test
    fun `an unknown option is refused with a suggestion`() {
        val typo = NodeSpec(Primitive.CLIP, mapOf("input" to Edge.Const(0f), "mim" to Edge.Const(0f), "max" to Edge.Const(1f)))

        val errors = GraphValidator.validate(graph(listOf(typo)))

        assertTrue("unknownOption" in errors.map { it.code })
        assertTrue("the error suggests 'min': ${errors.first().message}", errors.any { it.message.contains("'min'") })
    }

    @Test
    fun `a missing required option is refused`() {
        assertTrue("missingOption" in codes(listOf(NodeSpec(Primitive.CLIP, mapOf("input" to Edge.Const(0f))))))
    }

    @Test
    fun `an optional key may be left out`() {
        val softclip = NodeSpec(Primitive.SOFTCLIP, mapOf("input" to Edge.Const(0f), "drive" to Edge.Const(1f)))

        assertEquals(emptyList<String>(), codes(listOf(softclip), outputs = listOf(0, 0)))
    }

    @Test
    fun `an edge where a build-time constant belongs is refused`() {
        val delay =
            NodeSpec(
                Primitive.DELAY,
                edges = mapOf("input" to Edge.Const(0f), "time" to Edge.Const(1f), "maxTime" to Edge.Const(64f)),
            )

        assertTrue("edgeInConstSlot" in codes(listOf(delay)))
    }

    @Test
    fun `a constant where an edge belongs is refused`() {
        val node = NodeSpec(Primitive.SANITISE, consts = mapOf("input" to ConstArg.Num(0f)))

        assertTrue("constExpected" in codes(listOf(node)))
    }

    @Test
    fun `a name where a number belongs is refused, and the reverse`() {
        val named = NodeSpec(Primitive.INPUT, consts = mapOf("ch" to ConstArg.Text("left")))
        val numbered =
            NodeSpec(
                Primitive.LFO,
                edges = mapOf("rate" to Edge.Const(1f)),
                consts = mapOf("shape" to ConstArg.Num(0f)),
            )

        assertTrue("constTypeMismatch" in codes(listOf(named)))
        assertTrue("constTypeMismatch" in codes(listOf(numbered)))
    }

    @Test
    fun `a name that is not one of the enumerated values is refused`() {
        val lfo =
            NodeSpec(
                Primitive.LFO,
                edges = mapOf("rate" to Edge.Const(1f)),
                consts = mapOf("shape" to ConstArg.Text("sawtooth")),
            )

        assertTrue("unknownEnum" in codes(listOf(lfo)))
    }

    @Test
    fun `a reference to a missing node or to the node itself is refused`() {
        val far = NodeSpec(Primitive.SANITISE, mapOf("input" to Edge.Ref(9)))
        val itself = NodeSpec(Primitive.SANITISE, mapOf("input" to Edge.Ref(0)))

        assertTrue("missingNode" in codes(listOf(far)))
        assertTrue("selfReference" in codes(listOf(itself)))
    }

    @Test
    fun `a channel the graph does not have is refused`() {
        assertTrue("channelOutOfRange" in codes(listOf(input(5))))
    }

    @Test
    fun `an undeclared parameter index is refused`() {
        val param = NodeSpec(Primitive.PARAM, consts = mapOf("index" to ConstArg.Num(3f)))

        assertTrue("paramOutOfRange" in codes(listOf(param)))
    }

    @Test
    fun `a wrong output count or an output on a missing node is refused`() {
        assertTrue("outputArity" in codes(listOf(input()), outputs = listOf(0)))
        assertTrue("outputNodeMissing" in codes(listOf(input()), outputs = listOf(0, 7)))
    }

    @Test
    fun `a feedback tap with one half missing or one half doubled is refused`() {
        val write = NodeSpec(Primitive.TAPIN, mapOf("source" to Edge.Const(0f)), mapOf("name" to ConstArg.Text("fb")))
        val read = NodeSpec(Primitive.TAPOUT, consts = mapOf("name" to ConstArg.Text("fb")))

        assertTrue("unmatchedTap" in codes(listOf(write)))
        assertTrue("unmatchedTap" in codes(listOf(read)))
        assertTrue("duplicateTap" in codes(listOf(write, write, read)))
        assertEquals(emptyList<String>(), codes(listOf(write, read)))
    }

    @Test
    fun `a tap on a node that is not a delay is refused`() {
        val tap =
            NodeSpec(
                Primitive.TAP,
                edges = mapOf("time" to Edge.Const(4f)),
                consts = mapOf("line" to ConstArg.Num(0f)),
            )

        assertTrue("tapOnNonDelay" in codes(listOf(input(), tap)))
    }

    @Test
    fun `an all-pass ceiling above the loop ceiling is refused`() {
        val huge =
            NodeSpec(
                Primitive.ALLPASS1,
                edges = mapOf("input" to Edge.Const(0f), "coeff" to Edge.Const(0.5f)),
                consts = mapOf("ceiling" to ConstArg.Num(1e30f)),
            )

        assertTrue("ceilingOutOfRange" in codes(listOf(huge)))
    }

    @Test
    fun `a delay line shorter than one frame is refused, and a fractional one is not`() {
        fun delay(maxTime: Float) =
            NodeSpec(
                Primitive.DELAY,
                edges = mapOf("input" to Edge.Const(0f), "time" to Edge.Const(1f)),
                consts = mapOf("maxTime" to ConstArg.Num(maxTime)),
            )

        assertTrue("badMaxTime" in codes(listOf(delay(0f))))
        assertTrue("badMaxTime" in codes(listOf(delay(-4f))))
        // a fractional maxTime is accepted: a line sized from the sample rate is often
        // fractional, as 70 ms at 22.05 kHz is 1543.5 frames
        assertTrue("badMaxTime" !in codes(listOf(delay(12.5f))))
    }

    @Test
    fun `a declared delay total that differs from the nodes' is refused`() {
        val delay =
            NodeSpec(
                Primitive.DELAY,
                edges = mapOf("input" to Edge.Const(0f), "time" to Edge.Const(1f)),
                consts = mapOf("maxTime" to ConstArg.Num(1000f)),
            )

        // delayFrames is the producer's claim; the validator recomputes it from the nodes
        assertTrue("delayFramesMismatch" in codes(listOf(delay), delayFrames = 1))
        assertTrue("delayFramesMismatch" !in codes(listOf(delay), delayFrames = 1000))
    }

    @Test
    fun `more delay memory than the budget is refused`() {
        val delay =
            NodeSpec(
                Primitive.DELAY,
                edges = mapOf("input" to Edge.Const(0f), "time" to Edge.Const(1f)),
                consts = mapOf("maxTime" to ConstArg.Num(9_000_000f)),
            )

        assertTrue("delayMemoryBudget" in codes(listOf(delay), delayFrames = 9_000_000))
    }

    /**
     * Two lines whose lengths add up past what an Int holds. Guards against the total being
     * summed in an Int, where it would wrap to a negative number and pass the budget.
     */
    @Test
    fun `delay lines that overflow an Int in total are over budget`() {
        fun delay() =
            NodeSpec(
                Primitive.DELAY,
                edges = mapOf("input" to Edge.Const(0f), "time" to Edge.Const(1f)),
                consts = mapOf("maxTime" to ConstArg.Num((1 shl 30).toFloat())),
            )

        val wrapped = (1 shl 30) + (1 shl 30)

        assertTrue("delayMemoryBudget" in codes(listOf(delay(), delay()), delayFrames = wrapped))
    }

    @Test
    fun `more nodes than the budget is refused`() {
        val many = List(600) { input() }

        assertTrue("nodeBudget" in codes(many))
    }

    @Test
    fun `a loop with no delay or tap pair in it is refused`() {
        val a = NodeSpec(Primitive.ADD, mapOf("a" to Edge.Ref(1), "b" to Edge.Const(0f)))
        val b = NodeSpec(Primitive.MUL, mapOf("a" to Edge.Ref(0), "b" to Edge.Const(0.5f)))

        assertTrue("undelayedFeedback" in codes(listOf(a, b)))
    }

    @Test
    fun `a loop through a delay line passes`() {
        val delay =
            NodeSpec(
                Primitive.DELAY,
                edges = mapOf("input" to Edge.Ref(1), "time" to Edge.Const(64f)),
                consts = mapOf("maxTime" to ConstArg.Num(128f)),
            )
        val sum = NodeSpec(Primitive.ADD, mapOf("a" to Edge.Const(0f), "b" to Edge.Ref(0)))

        assertEquals(emptyList<String>(), codes(listOf(delay, sum), delayFrames = 128))
    }

    @Test
    fun `a loop through a tap pair passes`() {
        val read = NodeSpec(Primitive.TAPOUT, consts = mapOf("name" to ConstArg.Text("fb")))
        val sum = NodeSpec(Primitive.ADD, mapOf("a" to Edge.Const(0f), "b" to Edge.Ref(0)))
        val write = NodeSpec(Primitive.TAPIN, mapOf("source" to Edge.Ref(1)), mapOf("name" to ConstArg.Text("fb")))

        assertEquals(emptyList<String>(), codes(listOf(read, sum, write), outputs = listOf(1, 1)))
    }

    @Test
    fun `every error is reported`() {
        val nodes = listOf(input(9), NodeSpec(Primitive.CLIP, mapOf("input" to Edge.Const(0f))))

        val found = codes(nodes, api = 42).toSet()

        assertTrue("at least three errors are reported: $found", found.size >= 3)
    }
}
