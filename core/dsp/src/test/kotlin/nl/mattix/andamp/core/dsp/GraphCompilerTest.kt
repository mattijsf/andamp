// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the compiler derives itself, and which declared rates it refuses. */
class GraphCompilerTest {
    private fun spec(
        nodes: List<NodeSpec>,
        outputs: List<Int> = listOf(0, 0),
        params: List<GraphParam> = emptyList(),
    ) = GraphSpec(sampleRate = 44_100, channels = 2, params = params, nodes = nodes, outputs = outputs)

    private fun input(channel: Int = 0) = NodeSpec(Primitive.INPUT, consts = mapOf("ch" to ConstArg.Num(channel.toFloat())))

    private fun compile(spec: GraphSpec) = GraphCompiler.compile(spec)

    private fun tape(spec: GraphSpec): Tape {
        val result = compile(spec)
        assertTrue("the spec compiles to a tape: $result", result is GraphCompiler.Result.Compiled)
        return (result as GraphCompiler.Result.Compiled).tape
    }

    private fun errors(spec: GraphSpec) = (compile(spec) as? GraphCompiler.Result.Failed)?.errors?.map { it.code } ?: emptyList()

    @Test
    fun `a node fed only by constants runs at control rate`() {
        val folded = NodeSpec(Primitive.MUL, mapOf("a" to Edge.Const(2f), "b" to Edge.Const(3f)), rate = Rate.CONTROL)

        val tape = tape(spec(listOf(folded)))

        assertEquals("the whole graph is control rate", tape.op.size, tape.controlEnd)
    }

    @Test
    fun `a node fed by the input runs at audio rate`() {
        val touched = NodeSpec(Primitive.MUL, mapOf("a" to Edge.Ref(0), "b" to Edge.Const(0.5f)))

        val tape = tape(spec(listOf(input(), touched), outputs = listOf(1, 1)))

        assertEquals("no instruction is folded to control rate", 0, tape.controlEnd)
    }

    @Test
    fun `a stateful node tagged control rate is refused`() {
        // an oscillator's output depends on its own past, so it is audio rate however it
        // is fed
        val lfo =
            NodeSpec(
                Primitive.LFO,
                edges = mapOf("rate" to Edge.Const(2f)),
                consts = mapOf("shape" to ConstArg.Text("sine")),
                rate = Rate.CONTROL,
            )

        assertTrue("rateTagMismatch" in errors(spec(listOf(lfo))))
    }

    @Test
    fun `a graph that mislabels a node's rate is refused`() {
        val wrong = NodeSpec(Primitive.MUL, mapOf("a" to Edge.Ref(0), "b" to Edge.Const(1f)), rate = Rate.CONTROL)

        assertTrue("rateTagMismatch" in errors(spec(listOf(input(), wrong), outputs = listOf(1, 1))))
    }

    @Test
    fun `control instructions are emitted before audio ones`() {
        val slow = NodeSpec(Primitive.MUL, mapOf("a" to Edge.Const(2f), "b" to Edge.Const(3f)), rate = Rate.CONTROL)
        val fast = NodeSpec(Primitive.MUL, mapOf("a" to Edge.Ref(1), "b" to Edge.Ref(0)))

        val tape = tape(spec(listOf(input(), slow, fast), outputs = listOf(2, 2)))

        assertEquals("the one control instruction comes first", 1, tape.controlEnd)
        assertEquals(2, tape.op.size)
    }

    @Test
    fun `a filter fed by constants computes its coefficients in the control pass`() {
        val filter =
            NodeSpec(
                Primitive.BIQUAD,
                edges = mapOf("input" to Edge.Ref(0), "freq" to Edge.Const(500f), "q" to Edge.Const(0.707f)),
                consts = mapOf("kind" to ConstArg.Text("lowpass")),
            )

        val tape = tape(spec(listOf(input(), filter), outputs = listOf(1, 1)))

        // two instructions: the coefficients, which are control rate because nothing that
        // feeds them changes between ticks, and the filter itself
        assertEquals(2, tape.op.size)
        assertEquals("the coefficients are computed in the control pass", 1, tape.controlEnd)
    }

    @Test
    fun `a filter swept by an oscillator computes its coefficients in the audio pass`() {
        val lfo =
            NodeSpec(
                Primitive.LFO,
                edges = mapOf("rate" to Edge.Const(2f)),
                consts = mapOf("shape" to ConstArg.Text("sine")),
            )
        val swept =
            NodeSpec(
                Primitive.BIQUAD,
                edges = mapOf("input" to Edge.Ref(0), "freq" to Edge.Ref(1), "q" to Edge.Const(0.707f)),
                consts = mapOf("kind" to ConstArg.Text("lowpass")),
            )

        val tape = tape(spec(listOf(input(), lfo, swept), outputs = listOf(2, 2)))

        assertEquals("no instruction is folded to control rate", 0, tape.controlEnd)
    }

    @Test
    fun `a spec that does not validate is not compiled`() {
        val bad = NodeSpec(Primitive.SANITISE, mapOf("input" to Edge.Ref(9)))

        assertTrue("missingNode" in errors(spec(listOf(bad))))
    }

    @Test
    fun `a constant used twice is pooled once`() {
        val a = NodeSpec(Primitive.MUL, mapOf("a" to Edge.Ref(0), "b" to Edge.Const(0.25f)))
        val b = NodeSpec(Primitive.MUL, mapOf("a" to Edge.Ref(0), "b" to Edge.Const(0.25f)))

        val tape = tape(spec(listOf(input(), a, b), outputs = listOf(1, 2)))

        // two inputs reserved, then one shared 0.25
        assertEquals(3, tape.constants.size)
    }

    @Test
    fun `every operand and destination lands inside the bus`() {
        val chain =
            listOf(
                input(0),
                input(1),
                NodeSpec(Primitive.ADD, mapOf("a" to Edge.Ref(0), "b" to Edge.Ref(1))),
                NodeSpec(Primitive.MUL, mapOf("a" to Edge.Ref(2), "b" to Edge.Const(0.5f))),
            )

        val tape = tape(spec(chain, outputs = listOf(3, 3)))

        assertTrue(tape.dst.all { it in 0 until tape.busSize })
        assertTrue(tape.args.all { it in 0 until tape.busSize })
        assertTrue(tape.outSlot.all { it in 0 until tape.busSize })
        assertTrue(tape.inSlot.all { it in 0 until tape.busSize })
    }
}
