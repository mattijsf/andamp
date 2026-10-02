// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The stateful primitives: what they do to a signal, and that a reset clears their state. */
class StatefulPrimitiveTest {
    private fun engine(
        nodes: List<NodeSpec>,
        outputs: List<Int>,
        channels: Int = 1,
    ): FrameProcessor {
        // the validator recomputes delayFrames, so the test declares what the nodes ask for
        val frames = nodes.sumOf { ((it.consts["maxTime"] as? ConstArg.Num)?.value ?: 0f).toInt() }
        val spec =
            GraphSpec(
                sampleRate = 44_100,
                channels = channels,
                nodes = nodes,
                outputs = outputs,
                delayFrames = frames,
            )
        val compiled = GraphCompiler.compile(spec)
        assertTrue("the spec compiles to a tape: $compiled", compiled is GraphCompiler.Result.Compiled)
        return GraphEngine((compiled as GraphCompiler.Result.Compiled).tape)
    }

    private fun input(channel: Int = 0) = NodeSpec(Primitive.INPUT, consts = mapOf("ch" to ConstArg.Num(channel.toFloat())))

    private fun drive(
        engine: FrameProcessor,
        samples: List<Float>,
    ): List<Float> =
        samples.map {
            val frame = floatArrayOf(it)
            engine.process(frame)
            frame[0]
        }

    private fun impulse(length: Int) = List(length) { if (it == 0) 1f else 0f }

    @Test
    fun `a delay line returns a sample after the time asked for`() {
        val delay =
            NodeSpec(
                Primitive.DELAY,
                edges = mapOf("input" to Edge.Ref(0), "time" to Edge.Const(8f)),
                consts = mapOf("maxTime" to ConstArg.Num(64f), "interp" to ConstArg.Text("none")),
            )

        val out = drive(engine(listOf(input(), delay), outputs = listOf(1)), impulse(20))

        assertEquals("the impulse reappears at 8", 1f, out[8], 0f)
        assertTrue("no other frame carries the impulse", out.filterIndexed { i, _ -> i != 8 }.all { it == 0f })
    }

    @Test
    fun `a comb through a delay line loops in the delay it asked for`() {
        // the write is placed after the read, so the loop is `time` frames and not `time`
        // plus one
        val delay =
            NodeSpec(
                Primitive.DELAY,
                edges = mapOf("input" to Edge.Ref(3), "time" to Edge.Const(5f)),
                consts = mapOf("maxTime" to ConstArg.Num(64f), "interp" to ConstArg.Text("none")),
            )
        val fed = NodeSpec(Primitive.MUL, mapOf("a" to Edge.Ref(1), "b" to Edge.Const(0.5f)))
        val sum = NodeSpec(Primitive.ADD, mapOf("a" to Edge.Ref(0), "b" to Edge.Ref(2)))

        val out = drive(engine(listOf(input(), delay, fed, sum), outputs = listOf(1)), impulse(20))

        assertEquals(1f, out[5], 0f)
        assertEquals(0.5f, out[10], 0f)
        assertEquals(0.25f, out[15], 0f)
    }

    @Test
    fun `a tap pair holds a sample for one frame`() {
        val read = NodeSpec(Primitive.TAPOUT, consts = mapOf("name" to ConstArg.Text("fb")))
        val write = NodeSpec(Primitive.TAPIN, mapOf("source" to Edge.Ref(1)), mapOf("name" to ConstArg.Text("fb")))

        val out = drive(engine(listOf(read, input(), write), outputs = listOf(0)), impulse(5))

        assertEquals("the frame the sample is written on reads zero", 0f, out[0], 0f)
        assertEquals("the next frame reads the whole sample", 1f, out[1], 0f)
    }

    @Test
    fun `a lowpass passes a tone below its cutoff and stops one above`() {
        fun through(hz: Double): Float {
            val filter =
                NodeSpec(
                    Primitive.BIQUAD,
                    edges = mapOf("input" to Edge.Ref(0), "freq" to Edge.Const(500f), "q" to Edge.Const(0.707f)),
                    consts = mapOf("kind" to ConstArg.Text("lowpass")),
                )
            val engine = engine(listOf(input(), filter), outputs = listOf(1))
            val samples = List(4096) { kotlin.math.sin(2 * Math.PI * hz * it / 44_100).toFloat() }
            val out = drive(engine, samples)
            return out.drop(2048).maxOf { abs(it) }
        }

        assertTrue("100 Hz passes a 500 Hz lowpass", through(100.0) > 0.9f)
        assertTrue("8 kHz is stopped by a 500 Hz lowpass", through(8000.0) < 0.05f)
    }

    @Test
    fun `an oscillator with a constant rate completes a turn in its period`() {
        val lfo =
            NodeSpec(
                Primitive.LFO,
                edges = mapOf("rate" to Edge.Const(441f)),
                consts = mapOf("shape" to ConstArg.Text("sine")),
            )

        val out = drive(engine(listOf(lfo), outputs = listOf(0)), List(100) { 0f })

        // 441 Hz at 44.1 kHz is one turn per hundred frames
        assertEquals(0f, out[0], 1e-4f)
        assertEquals(1f, out[25], 1e-3f)
        assertEquals(0f, out[50], 1e-3f)
        assertEquals(-1f, out[75], 1e-3f)
    }

    @Test
    fun `a one-pole settles towards its input`() {
        val filter = NodeSpec(Primitive.ONEPOLE, mapOf("input" to Edge.Ref(0), "coeff" to Edge.Const(0.5f)))

        val out = drive(engine(listOf(input(), filter), outputs = listOf(1)), List(20) { 1f })

        assertEquals(0.5f, out[0], 1e-6f)
        assertEquals(0.75f, out[1], 1e-6f)
        assertTrue("the output converges to the input", abs(out.last() - 1f) < 1e-3f)
    }

    @Test
    fun `an all-pass keeps the level of a sine`() {
        val section =
            NodeSpec(
                Primitive.ALLPASS1,
                edges = mapOf("input" to Edge.Ref(0), "coeff" to Edge.Const(0.5f)),
                consts = mapOf("ceiling" to ConstArg.Num(2f)),
            )
        val samples = List(2048) { kotlin.math.sin(2 * Math.PI * 1000.0 * it / 44_100).toFloat() }

        val out = drive(engine(listOf(input(), section), outputs = listOf(1)), samples)

        assertEquals("the all-pass keeps unity magnitude", 1f, out.drop(1024).maxOf { abs(it) }, 0.02f)
    }

    @Test
    fun `reset clears a delay line`() {
        val delay =
            NodeSpec(
                Primitive.DELAY,
                edges = mapOf("input" to Edge.Ref(0), "time" to Edge.Const(4f)),
                consts = mapOf("maxTime" to ConstArg.Num(64f), "interp" to ConstArg.Text("none")),
            )
        val engine = engine(listOf(input(), delay), outputs = listOf(1))

        drive(engine, impulse(2))
        engine.reset()
        val after = drive(engine, List(10) { 0f })

        assertTrue("the impulse from before the reset does not arrive: $after", after.all { it == 0f })
    }

    private fun noise(seed: Int) = NodeSpec(Primitive.NOISE, consts = mapOf("seed" to ConstArg.Num(seed.toFloat())))

    private fun noiseOf(
        seed: Int,
        frames: Int,
    ) = drive(engine(listOf(input(), noise(seed)), outputs = listOf(1)), List(frames) { 0f })

    @Test
    fun `noise stays inside -1 to 1 with zero mean and a variance of a third`() {
        val out = noiseOf(seed = 7, frames = 44_100)

        assertTrue("every sample lies in [-1, 1)", out.all { it >= -1f && it < 1f })
        assertEquals("the mean is close to zero", 0.0, out.average(), 0.02)
        // uniform white noise has a variance of a third
        assertEquals(1.0 / 3, out.sumOf { it.toDouble() * it } / out.size, 0.02)
    }

    @Test
    fun `one seed gives the same noise, and another seed gives different noise`() {
        assertEquals(noiseOf(seed = 3, frames = 1_000), noiseOf(seed = 3, frames = 1_000))
        val other = noiseOf(seed = 4, frames = 1_000)
        val same = noiseOf(seed = 3, frames = 1_000).zip(other).count { (a, b) -> a == b }
        assertTrue("fewer than 10 of 1000 samples agree: $same", same < 10)
    }

    @Test
    fun `noise does not correlate with itself a sample later`() {
        val out = noiseOf(seed = 11, frames = 44_100)
        val lagged = out.zipWithNext { a, b -> a.toDouble() * b }.average()

        assertEquals(0.0, lagged, 0.02)
    }

    @Test
    fun `a reset starts the noise over`() {
        val engine = engine(listOf(input(), noise(5)), outputs = listOf(1))
        val first = drive(engine, List(64) { 0f })
        engine.reset()

        assertEquals(first, drive(engine, List(64) { 0f }))
    }

    @Test
    fun `noise run a block at a time equals the noise run a frame at a time`() {
        val nodes = listOf(input(), noise(9))
        val framed = noiseOf(seed = 9, frames = 1_000)
        val blocked = engine(nodes, outputs = listOf(1))
        val buffer = arrayOf(FloatArray(1_000))
        blocked.process(buffer, 1_000)

        assertEquals(framed, buffer[0].toList())
    }
}
