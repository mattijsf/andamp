// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Graphs compiled and run through [GraphEngine] one frame at a time. */
class GraphEngineTest {
    private fun run(
        spec: GraphSpec,
        frames: List<FloatArray>,
    ): List<FloatArray> {
        val compiled = GraphCompiler.compile(spec)
        assertTrue("the spec compiles to a tape: $compiled", compiled is GraphCompiler.Result.Compiled)
        val engine = GraphEngine((compiled as GraphCompiler.Result.Compiled).tape)
        return frames.map { it.copyOf().also(engine::process) }
    }

    private fun input(
        channel: Int,
        rate: Rate = Rate.AUDIO,
    ) = NodeSpec(Primitive.INPUT, consts = mapOf("ch" to ConstArg.Num(channel.toFloat())), rate = rate)

    private fun stereo(
        nodes: List<NodeSpec>,
        outputs: List<Int>,
    ) = GraphSpec(sampleRate = 44_100, channels = 2, nodes = nodes, outputs = outputs)

    @Test
    fun `a graph that only reads its input returns it unchanged`() {
        val spec = stereo(listOf(input(0), input(1)), outputs = listOf(0, 1))

        val out = run(spec, listOf(floatArrayOf(0.25f, -0.5f)))

        assertEquals(0.25f, out[0][0], 0f)
        assertEquals(-0.5f, out[0][1], 0f)
    }

    @Test
    fun `a node multiplies its input by a constant`() {
        val half = NodeSpec(Primitive.MUL, mapOf("a" to Edge.Ref(0), "b" to Edge.Const(0.5f)))
        val spec = stereo(listOf(input(0), half), outputs = listOf(1, 1))

        val out = run(spec, listOf(floatArrayOf(1f, 1f)))

        assertEquals(0.5f, out[0][0], 0f)
    }

    @Test
    fun `a node may read a channel other than its own`() {
        val side = NodeSpec(Primitive.SUB, mapOf("a" to Edge.Ref(0), "b" to Edge.Ref(1)))
        val spec = stereo(listOf(input(0), input(1), side), outputs = listOf(2, 2))

        val out = run(spec, listOf(floatArrayOf(0.75f, 0.25f)))

        assertEquals(0.5f, out[0][0], 0f)
    }

    @Test
    fun `outputs are written after every instruction`() {
        // channel 0 takes channel 1's input and the other way round: if outputs were
        // written as they were computed, the second would read the first
        val spec = stereo(listOf(input(0), input(1)), outputs = listOf(1, 0))

        val out = run(spec, listOf(floatArrayOf(1f, -1f)))

        assertEquals(-1f, out[0][0], 0f)
        assertEquals(1f, out[0][1], 0f)
    }

    @Test
    fun `a crossfade is exact at both ends`() {
        fun mix(t: Float): Float {
            val bad = NodeSpec(Primitive.DIV, mapOf("a" to Edge.Const(1f), "b" to Edge.Const(0f)), rate = Rate.CONTROL)
            val fade =
                NodeSpec(
                    Primitive.CROSSFADE,
                    mapOf("a" to Edge.Ref(0), "b" to Edge.Ref(1), "t" to Edge.Const(t)),
                )
            return run(stereo(listOf(input(0), bad, fade), outputs = listOf(2, 2)), listOf(floatArrayOf(0.5f, 0f)))[0][0]
        }

        assertEquals("t = 0 gives a while b is infinite", 0.5f, mix(0f), 0f)
        assertTrue("t = 1 gives b", mix(1f).isInfinite())
    }

    @Test
    fun `sanitise turns a NaN into zero`() {
        val bad = NodeSpec(Primitive.DIV, mapOf("a" to Edge.Const(0f), "b" to Edge.Const(0f)), rate = Rate.CONTROL)
        val clean = NodeSpec(Primitive.SANITISE, mapOf("input" to Edge.Ref(0)), rate = Rate.CONTROL)
        val spec = stereo(listOf(bad, clean), outputs = listOf(1, 1))

        assertEquals(0f, run(spec, listOf(floatArrayOf(0f, 0f)))[0][0], 0f)
    }

    @Test
    fun `a math node computes exp2`() {
        val fn =
            NodeSpec(
                Primitive.MATH,
                edges = mapOf("input" to Edge.Const(3f)),
                consts = mapOf("fn" to ConstArg.Text("exp2")),
                rate = Rate.CONTROL,
            )

        assertEquals(8f, run(stereo(listOf(fn), outputs = listOf(0, 0)), listOf(floatArrayOf(0f, 0f)))[0][0], 1e-5f)
    }

    @Test
    fun `a frame with another channel count is left unchanged`() {
        val spec = stereo(listOf(input(0)), outputs = listOf(0, 0))
        val compiled = GraphCompiler.compile(spec) as GraphCompiler.Result.Compiled
        val mono = floatArrayOf(0.4f)

        GraphEngine(compiled.tape).process(mono)

        assertEquals(0.4f, mono[0], 0f)
    }
}
