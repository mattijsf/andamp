// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** A parameter reaches the audio, and moves there smoothly: a control value that jumps is a step in the signal. */
class ParameterTest {
    private fun gain(
        default: Float = 1f,
        smoothMs: Float = GraphParam.DEFAULT_SMOOTH_MS,
    ): FrameProcessor {
        val spec =
            GraphSpec(
                sampleRate = 44_100,
                channels = 1,
                params = listOf(GraphParam("gain", default = default, smoothMs = smoothMs)),
                nodes =
                    listOf(
                        NodeSpec(Primitive.INPUT, consts = mapOf("ch" to ConstArg.Num(0f))),
                        NodeSpec(Primitive.PARAM, consts = mapOf("index" to ConstArg.Num(0f)), rate = Rate.CONTROL),
                        NodeSpec(Primitive.MUL, mapOf("a" to Edge.Ref(0), "b" to Edge.Ref(1))),
                    ),
                outputs = listOf(2),
            )
        return GraphCompiler.engine(spec)!!
    }

    private fun drive(
        engine: FrameProcessor,
        frames: Int,
    ): List<Float> =
        List(frames) {
            val frame = floatArrayOf(1f)
            engine.process(frame)
            frame[0]
        }

    @Test
    fun `a parameter starts at its declared default`() {
        assertEquals(0.25f, drive(gain(default = 0.25f), 8).last(), 0f)
    }

    @Test
    fun `settleParameters puts a parameter on its target at once`() {
        // an engine that was just built or reset holds its declared defaults; walking from
        // there to the listener's settings would be heard as a sweep
        val engine = gain(default = 1f)

        engine.setParameter(0, 0.25f)
        engine.settleParameters()

        assertEquals("the parameter is on its target in the first frame", 0.25f, drive(engine, 1).single(), 0f)
    }

    @Test
    fun `a move after settling is smoothed`() {
        val engine = gain(default = 1f)
        engine.setParameter(0, 0.25f)
        engine.settleParameters()
        drive(engine, 1)

        engine.setParameter(0, 1f)
        val out = drive(engine, 4)

        assertTrue("a move after settling is smoothed: ${out.last()} after four frames", out.last() > 0.25f && out.last() < 0.35f)
    }

    @Test
    fun `a moved parameter arrives at its target`() {
        val engine = gain(default = 1f)
        drive(engine, 64)

        engine.setParameter(0, 0f)
        // the walk approaches its target and lands on it once it is close enough, so this
        // runs long enough for that
        val out = drive(engine, 44_100 / 4)

        assertEquals("the parameter reaches its target", 0f, out.last(), 0f)
    }

    @Test
    fun `a moved parameter walks in small steps`() {
        val engine = gain(default = 1f)
        // the sample before the change is part of the measurement: the jump would happen
        // across that boundary
        val before = drive(engine, 64).last()

        engine.setParameter(0, 0f)
        val out = listOf(before) + drive(engine, 2048)

        val worstStep = (1 until out.size).maxOf { abs(out[it] - out[it - 1]) }
        assertTrue("every step is below 0.05: $worstStep", worstStep < 0.05f)
        assertNotEquals("the output moves", out.first(), out.last())
    }

    @Test
    fun `a parameter with no smoothing time arrives at once`() {
        val engine = gain(default = 1f, smoothMs = 0f)
        drive(engine, 64)

        engine.setParameter(0, 0f)
        val out = drive(engine, 64)

        assertEquals("the parameter is on its target within 64 frames", 0f, out.last(), 0f)
    }

    @Test
    fun `a parameter is 63 percent of the way after its smoothing time`() {
        val engine = gain(default = 0f, smoothMs = 20f)
        drive(engine, 64)

        engine.setParameter(0, 1f)
        val out = drive(engine, 44_100 / 20)

        // one time constant is 63% of the way there, and 20 ms is 882 frames
        assertEquals("the parameter is 63% of the way at 20 ms", 0.63f, out[882], 0.08f)
    }

    @Test
    fun `reset puts a parameter back at its declared default`() {
        val engine = gain(default = 1f)
        engine.setParameter(0, 0f)
        drive(engine, 4096)

        engine.reset()
        val out = drive(engine, 4)

        assertEquals("reset restores the declared default", 1f, out.first(), 0f)
    }

    @Test
    fun `setting a parameter that does not exist is ignored`() {
        val engine = gain()

        engine.setParameter(9, 0.5f)

        assertEquals(1f, drive(engine, 8).last(), 0f)
    }
}
