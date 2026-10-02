// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Whether a delay line reads where it was asked to, to less than a sample.
 *
 * The probe is an impulse. A line reading `d` frames back with linear interpolation splits a
 * lone impulse across the two samples either side of `d`, in the proportion of its fractional
 * part: `1 - frac` at `floor(d)` and `frac` at `floor(d) + 1`. The split can therefore be read
 * back into an effective delay. These tests assert the split on delays whose answer is known.
 */
class FractionalDelayTest {
    private fun engine(
        nodes: List<NodeSpec>,
        outputs: List<Int>,
    ): FrameProcessor {
        val frames = nodes.sumOf { ((it.consts["maxTime"] as? ConstArg.Num)?.value ?: 0f).toInt() }
        val spec =
            GraphSpec(
                sampleRate = 44_100,
                channels = 1,
                nodes = nodes,
                outputs = outputs,
                delayFrames = frames,
            )
        val compiled = GraphCompiler.compile(spec)
        assertTrue("the spec compiles to a tape: $compiled", compiled is GraphCompiler.Result.Compiled)
        return GraphEngine((compiled as GraphCompiler.Result.Compiled).tape)
    }

    private fun input(channel: Int = 0) = NodeSpec(Primitive.INPUT, consts = mapOf("ch" to ConstArg.Num(channel.toFloat())))

    /** One line, read at a fixed [time] with linear interpolation. */
    private fun lineAt(time: Float): FrameProcessor {
        val delay =
            NodeSpec(
                Primitive.DELAY,
                edges = mapOf("input" to Edge.Ref(0), "time" to Edge.Const(time)),
                consts = mapOf("maxTime" to ConstArg.Num(512f), "interp" to ConstArg.Text("linear")),
            )
        return engine(listOf(input(), delay), outputs = listOf(1))
    }

    /** The impulse response of a line, [frames] long. */
    private fun response(
        time: Float,
        frames: Int = 160,
    ): FloatArray {
        val engine = lineAt(time)
        return FloatArray(frames) { n ->
            val frame = floatArrayOf(if (n == 0) 1f else 0f)
            engine.process(frame)
            frame[0]
        }
    }

    /**
     * Where the impulse landed, in frames: the center of mass of the response. For a
     * fractional delay that is the two samples weighted by how much of the impulse each
     * received.
     */
    private fun arrival(response: FloatArray): Float {
        var mass = 0f
        var moment = 0f
        response.forEachIndexed { at, value ->
            val energy = abs(value)
            mass += energy
            moment += energy * at
        }
        return if (mass == 0f) -1f else moment / mass
    }

    @Test
    fun `a whole-sample delay puts the impulse on one sample`() {
        val out = response(100f)

        assertEquals("the whole impulse arrives at frame 100", 1f, out[100], 1e-6f)
        assertEquals("frame 101 carries none of the impulse", 0f, out[101], 1e-6f)
    }

    @Test
    fun `half a sample splits the impulse evenly across two samples`() {
        // a line that ignored the fraction would put all of it on one sample
        val out = response(100.5f)

        assertEquals(0.5f, out[100], 1e-5f)
        assertEquals(0.5f, out[101], 1e-5f)
    }

    @Test
    fun `the split follows the fraction`() {
        // a quarter of a sample late puts three quarters of the impulse on the earlier
        // sample
        val out = response(100.25f)

        assertEquals(0.75f, out[100], 1e-5f)
        assertEquals(0.25f, out[101], 1e-5f)
    }

    @Test
    fun `the split impulse sums to one`() {
        // an interpolating read must not lose energy
        listOf(100f, 100.25f, 100.5f, 100.75f, 137.1f).forEach { time ->
            val total = response(time).sumOf { abs(it).toDouble() }
            assertEquals("a line at $time gives back a total of 1: $total", 1.0, total, 1e-4)
        }
    }

    @Test
    fun `the arrival measure recovers the delay to a hundredth of a sample`() {
        // checked against delays whose answer is known
        listOf(100f, 100.25f, 100.5f, 100.75f, 101f, 137.6f, 200.3f).forEach { asked ->
            val got = arrival(response(asked, frames = 320))
            assertEquals("the measured delay $got matches $asked", asked, got, 0.01f)
        }
    }

    @Test
    fun `the arrival measure tells a whole-sample delay from a half-sample one`() {
        // the two delays differ by half a sample
        val whole = arrival(response(100f, frames = 320))
        val half = arrival(response(100.5f, frames = 320))

        assertTrue("a whole and a half sample measure more than 0.4 apart", abs(half - whole) > 0.4f)
    }
}
