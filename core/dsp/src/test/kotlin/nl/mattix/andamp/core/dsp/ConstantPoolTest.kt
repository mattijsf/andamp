// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Test

/** A constant and a parameter or input that hold the same number do not share a bus slot. */
class ConstantPoolTest {
    @Test
    fun `a constant equal to a parameter's default does not follow the parameter`() {
        // 0.5 is a literal here and also the parameter's default; in a shared slot, moving
        // the parameter would change the literal
        val half = 0.5f
        val spec =
            GraphSpec(
                sampleRate = 44_100,
                channels = 1,
                params = listOf(GraphParam("amount", default = half, smoothMs = 0f)),
                nodes =
                    listOf(
                        NodeSpec(Primitive.INPUT, consts = mapOf("ch" to ConstArg.Num(0f))),
                        NodeSpec(Primitive.MUL, mapOf("a" to Edge.Ref(0), "b" to Edge.Const(half))),
                        NodeSpec(Primitive.PARAM, consts = mapOf("index" to ConstArg.Num(0f)), rate = Rate.CONTROL),
                        NodeSpec(Primitive.MUL, mapOf("a" to Edge.Ref(1), "b" to Edge.Ref(2))),
                    ),
                outputs = listOf(3),
            )
        val engine = GraphCompiler.engine(spec)!!

        val atDefault = floatArrayOf(1f).also(engine::process)[0]
        engine.setParameter(0, 1f)
        engine.process(floatArrayOf(0f))
        val moved = floatArrayOf(1f).also(engine::process)[0]

        assertEquals("input * 0.5 * 0.5", 0.25f, atDefault, 0f)
        assertEquals("the literal stays at 0.5", 0.5f, moved, 0f)
    }

    @Test
    fun `a constant zero does not read an input channel's slot`() {
        val spec =
            GraphSpec(
                sampleRate = 44_100,
                channels = 2,
                nodes =
                    listOf(
                        NodeSpec(Primitive.INPUT, consts = mapOf("ch" to ConstArg.Num(0f))),
                        NodeSpec(Primitive.ADD, mapOf("a" to Edge.Ref(0), "b" to Edge.Const(0f))),
                    ),
                outputs = listOf(1, 1),
            )
        val engine = GraphCompiler.engine(spec)!!

        val frame = floatArrayOf(0.75f, 0.25f)
        engine.process(frame)

        assertEquals("adding a literal zero leaves the input unchanged", 0.75f, frame[0], 0f)
    }
}
