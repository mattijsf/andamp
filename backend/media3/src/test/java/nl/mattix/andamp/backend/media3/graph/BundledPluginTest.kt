// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3.graph

import nl.mattix.andamp.backend.media3.dsp.DspSettings
import nl.mattix.andamp.backend.media3.dsp.KaraokeStage
import nl.mattix.andamp.core.dsp.FrameProcessor
import nl.mattix.andamp.core.dsp.GraphCompiler
import nl.mattix.andamp.core.dsp.Primitive
import nl.mattix.andamp.core.dsp.Rate
import nl.mattix.andamp.core.plugin.PluginLoader
import nl.mattix.andamp.core.plugin.PluginSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Plug-ins loaded from their Lua files: mono.lua, pan.lua and karaoke.lua from
 * the test resources, and tremolo.lua from docs/examples.
 *
 * The pan exists three times: the hand-written stage, a graph built in Kotlin,
 * and a Lua script. The Lua pan is compared with the Kotlin graph, which
 * GraphParityTest compares with the stage.
 */
class BundledPluginTest {
    /** Fast enough that a whole tremolo sweep fits in a short run. */
    private val fastHz = 8f

    /** One sweep at [fastHz], and a little more. */
    private val sweepFrames = 44_100 / 8 + 100

    private val rate = 44_100

    /** One part in a million of full scale. */
    private val audible = 1e-6f

    private fun read(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/plugins/$name")) { "the plug-in resource $name exists" }
            .bufferedReader()
            .readText()

    /** One of docs/examples, which the app does not bundle. */
    private fun example(name: String): PluginSpec {
        val result = PluginLoader().load(java.io.File("../../docs/examples/$name").readText(), sampleRate = rate, channels = 2)
        assertTrue("$name loads: $result", result is PluginLoader.Result.Loaded)
        return (result as PluginLoader.Result.Loaded).plugin
    }

    private fun load(name: String): PluginSpec {
        val result = PluginLoader().load(read(name), sampleRate = rate, channels = 2)
        assertTrue("$name loads: $result", result is PluginLoader.Result.Loaded)
        return (result as PluginLoader.Result.Loaded).plugin
    }

    private fun music(frames: Int): List<FloatArray> =
        List(frames) { n ->
            val voice = (0.4 * sin(2 * PI * 220 * n / rate)).toFloat()
            val guitar = (0.3 * sin(2 * PI * 440 * n / rate)).toFloat()
            floatArrayOf(voice + guitar, voice)
        }

    private fun worst(
        a: FrameProcessor,
        b: FrameProcessor,
        frames: Int = 4000,
    ): Float {
        var worst = 0f
        music(frames).forEach { frame ->
            val left = frame.copyOf()
            val right = frame.copyOf()
            a.process(left)
            b.process(right)
            worst = max(worst, max(abs(left[0] - right[0]), abs(left[1] - right[1])))
        }
        return worst
    }

    // mono.lua is a test resource: a plug-in with no controls
    @Test
    fun `mono sums the channels and sends the same signal to both`() {
        val engine = GraphCompiler.engine(load("mono.lua").graph)!!
        val frame = floatArrayOf(1f, 0f)

        engine.process(frame)

        assertEquals(0.5f, frame[0], 0f)
        assertEquals(0.5f, frame[1], 0f)
    }

    @Test
    fun `mono declares no controls`() {
        val plugin = load("mono.lua")

        assertTrue("mono declares no parameters", plugin.params.isEmpty())
        assertEquals("nl.mattix.andamp.mono", plugin.id)
    }

    /**
     * Drives silence until a moved control has finished traveling.
     *
     * The Lua pan's position is live and smoothed; the Kotlin fixture's is a
     * constant. They can be compared once the glide is over.
     */
    private fun settle(engine: FrameProcessor) = repeat(rate / 4) { engine.process(floatArrayOf(0f, 0f)) }

    @Test
    fun `a control declared in hertz is described in hertz`() {
        // the unit is display only and does not reach the graph; the rack
        // needs it to show a rate in hertz
        val described = BundledPlugins.describe(example("tremolo.lua"))

        assertEquals("hz", described.params.first { it.id == "rate" }.unit)
        assertEquals("", described.params.first { it.id == "depth" }.unit)
    }

    @Test
    fun `tremolo sways the level, and sits still when its depth is zero`() {
        val plugin = example("tremolo.lua")
        val engine = GraphCompiler.engine(plugin.graph)!!
        val rateAt = plugin.params.indexOfFirst { it.id == "rate" }
        val depthAt = plugin.params.indexOfFirst { it.id == "depth" }
        engine.setParameter(rateAt, fastHz)
        engine.setParameter(depthAt, 1f)
        engine.settleParameters()

        val swaying = drive(engine, sweepFrames)

        assertTrue("the level sways by more than 0.5: ${swaying.max() - swaying.min()}", swaying.max() - swaying.min() > 0.5f)

        // depth 0 is the bypass
        engine.setParameter(depthAt, 0f)
        engine.settleParameters()
        val still = drive(engine, sweepFrames)

        assertTrue("at depth 0 the level stays still: ${still.max() - still.min()}", still.max() - still.min() < 1e-3f)
    }

    /** A constant one through the engine, so what comes out is the effect alone. */
    private fun drive(
        engine: FrameProcessor,
        frames: Int,
    ): List<Float> =
        List(frames) {
            val frame = floatArrayOf(1f, 1f)
            engine.process(frame)
            frame[0]
        }

    /** Stereo 0 moves both sides together; 100 % moves them in turn, loud on one side while quiet on the other. */
    @Test
    fun `tremolo's stereo sways the sides together, or in turn`() {
        val plugin = example("tremolo.lua")
        val engine = GraphCompiler.engine(plugin.graph)!!
        engine.setParameter(plugin.params.indexOfFirst { it.id == "rate" }, fastHz)
        engine.setParameter(plugin.params.indexOfFirst { it.id == "depth" }, 1f)

        fun sides(stereo: Float): Pair<List<Float>, List<Float>> {
            engine.setParameter(plugin.params.indexOfFirst { it.id == "stereo" }, stereo)
            engine.settleParameters()
            val left = ArrayList<Float>()
            val right = ArrayList<Float>()
            repeat(sweepFrames) {
                val frame = floatArrayOf(1f, 1f)
                engine.process(frame)
                left += frame[0]
                right += frame[1]
            }
            return left to right
        }

        val (togetherL, togetherR) = sides(0f)
        val apart = togetherL.indices.maxOf { abs(togetherL[it] - togetherR[it]) }
        assertTrue("at stereo 0 the sides move together: $apart", apart < 1e-3f)

        val (turnL, turnR) = sides(1f)
        // half a cycle apart at full depth, the two add up to about one.
        // Compared from the second control tick: the phase is control-rate
        // arithmetic, so a change reaches the oscillator at the next tick
        val worst = (CONTROL_TICK until turnL.size).maxOf { abs(turnL[it] + turnR[it] - 1f) }
        assertTrue("at stereo 100 % the sides move in turn: $worst", worst < 0.02f)
    }

    @Test
    fun `the pan written in Lua is the pan written in Kotlin`() {
        val plugin = load("pan.lua")

        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { position ->
            val fromLua = GraphCompiler.engine(plugin.graph)!!
            fromLua.setParameter(0, position)
            settle(fromLua)
            val fromKotlin = GraphCompiler.engine(panGraph(position, rate))!!

            assertEquals("at $position", 0f, worst(fromLua, fromKotlin), 0f)
        }
    }

    @Test
    fun `karaoke written in Lua is the karaoke stage`() {
        val plugin = load("karaoke.lua")
        val settings = DspSettings.Karaoke(enabled = true, level = 1f, filter = 0.35f, band = 0.5f, width = 0f)
        val stage = KaraokeStage(rate).apply { configure(rate) }
        stage.update(settings)
        val engine = GraphCompiler.engine(plugin.graph)!!

        // its controls start where the plug-in declared them, which is where
        // the stage is set, so there is nothing to settle
        var difference = 0f
        music(4000).forEach { frame ->
            val fromStage = frame.copyOf()
            val fromLua = frame.copyOf()
            stage.process(fromStage)
            engine.process(fromLua)
            difference = max(difference, max(abs(fromStage[0] - fromLua[0]), abs(fromStage[1] - fromLua[1])))
        }

        // The cutoff is a graph node here, so it is 60 * 2^(filter *
        // log2(500/60)) where the stage raises 500/60 to the filter directly.
        // The two agree closely, and the difference passes through two
        // recursive filters.
        assertTrue("the Lua karaoke matches the stage: $difference", difference < audible)
    }

    @Test
    fun `karaoke's cutoff is live and computed at control rate`() {
        val plugin = load("karaoke.lua")
        val engine = GraphCompiler.engine(plugin.graph)!!
        val filter = plugin.params.indexOfFirst { it.id == "filter" }

        // measured twice with nothing changed first, so a rise cannot be the
        // filters settling
        val narrow = level(engine)
        assertEquals("a second measurement with nothing moved matches the first", narrow, level(engine), 1e-5f)

        engine.setParameter(filter, 1f)
        settle(engine)
        val wide = level(engine)

        assertTrue("a 500 Hz corner keeps more of the low end than a 125 Hz one: $narrow then $wide", wide > narrow)

        // everything feeding the cutoff is a parameter, so the compiler runs
        // it once per control tick
        val annotated = GraphCompiler.annotate(plugin.graph)
        val cutoff = annotated.nodes.indexOfFirst { it.primitive == Primitive.MIN }
        assertEquals("the cutoff is control rate", Rate.CONTROL, annotated.nodes[cutoff].rate)
        assertTrue(
            "everything feeding the cutoff is control rate",
            annotated.nodes.filter { it.primitive == Primitive.MATH }.all { it.rate == Rate.CONTROL },
        )
    }

    /** How much of a 300 Hz centered tone survives, which the cutoff decides. */
    private fun level(engine: FrameProcessor): Float {
        var peak = 0f
        repeat(4000) { n ->
            val tone = (0.5 * sin(2 * PI * 300 * n / rate)).toFloat()
            val frame = floatArrayOf(tone, tone)
            engine.process(frame)
            if (n > 2000) peak = max(peak, abs(frame[0]))
        }
        return peak
    }

    @Test
    fun `the pan's control is live`() {
        val engine = GraphCompiler.engine(load("pan.lua").graph)!!

        engine.setParameter(0, 0f)
        settle(engine)
        val hardLeft = floatArrayOf(1f, 1f).also(engine::process)

        assertEquals("hard left keeps the left channel", 1f, hardLeft[0], 0f)
        assertEquals("hard left empties the right channel", 0f, hardLeft[1], 0f)
    }

    private companion object {
        /** Longer than one control period. */
        const val CONTROL_TICK = 256
    }
}
