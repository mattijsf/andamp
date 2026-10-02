// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3.graph

import nl.mattix.andamp.backend.media3.dsp.DspSettings
import nl.mattix.andamp.backend.media3.dsp.KaraokeStage
import nl.mattix.andamp.backend.media3.dsp.ModulationStage
import nl.mattix.andamp.backend.media3.dsp.PanStage
import nl.mattix.andamp.backend.media3.dsp.ReverbStage
import nl.mattix.andamp.core.dsp.FrameProcessor
import nl.mattix.andamp.core.dsp.GraphCompiler
import nl.mattix.andamp.core.dsp.GraphSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Whether a graph reproduces the hand-written stage, sample for sample.
 *
 * Where the two compute the same arithmetic, that is asserted at delta 0.
 *
 * Karaoke is the exception. The graph's crossfade is exact at both ends; the
 * stage computes `dry + (widened - dry) * amount`, which at amount = 1 is not
 * bit-identical to `widened`. The two differ by about an ULP at full level,
 * and the difference is bounded here.
 */
class GraphParityTest {
    private val rate = 44_100

    /** Below one step of a 24-bit sample. */
    private val ulp = 1e-7f

    private fun engine(spec: GraphSpec): FrameProcessor {
        val compiled = GraphCompiler.compile(spec)
        assertTrue("the graph compiles: $compiled", compiled is GraphCompiler.Result.Compiled)
        return GraphCompiler.engine(spec)!!
    }

    /** A voice down the middle over a guitar to one side. */
    private fun music(frames: Int): List<FloatArray> =
        List(frames) { n ->
            val voice = (0.4 * sin(2 * PI * 220 * n / rate) + 0.2 * sin(2 * PI * 660 * n / rate)).toFloat()
            val guitar = (0.3 * sin(2 * PI * 440 * n / rate)).toFloat()
            floatArrayOf(voice + guitar, voice)
        }

    private fun worst(
        stage: (FloatArray) -> Unit,
        spec: GraphSpec,
        frames: Int = 8000,
    ): Float {
        val graph = engine(spec)
        var worst = 0f
        music(frames).forEach { frame ->
            val fromStage = frame.copyOf()
            val fromGraph = frame.copyOf()
            stage(fromStage)
            graph.process(fromGraph)
            worst = max(worst, max(abs(fromStage[0] - fromGraph[0]), abs(fromStage[1] - fromGraph[1])))
        }
        return worst
    }

    @Test
    fun `the pan graph is the pan stage`() {
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { pan ->
            val stage = PanStage().apply { update(DspSettings.Pan(enabled = true, pan = pan)) }

            assertEquals("pan $pan", 0f, worst(stage::process, panGraph(pan, rate)), 0f)
        }
    }

    @Test
    fun `the modulation graph is the modulation stage, in all three modes`() {
        DspSettings.Mode.entries.forEach { mode ->
            val settings =
                DspSettings.Modulation(
                    enabled = true,
                    mode = mode,
                    level = 0.5f,
                    lfo = 0.3f,
                    depth = 0.7f,
                    rate = 0.25f,
                    feedback = 0.4f,
                    stereo = 0.5f,
                )
            val stage = ModulationStage(rate).apply { configure(rate, 2) }
            stage.update(settings)
            val graph =
                modulationGraph(
                    mode = mode,
                    level = settings.level,
                    lfo = settings.lfo,
                    depth = settings.depth,
                    rate = settings.rate,
                    feedback = settings.feedback,
                    stereo = settings.stereo,
                    sampleRate = rate,
                )

            assertEquals("$mode", 0f, worst(stage::process, graph), 0f)
        }
    }

    @Test
    fun `the reverb graph is the reverb stage`() {
        val settings =
            listOf(
                DspSettings.Reverb(enabled = true, level = 0.5f, size = 0.6f, near = 0.4f, air = 0.7f),
                DspSettings.Reverb(enabled = true, level = 1f, size = 0f, near = 1f, air = 0f),
                DspSettings.Reverb(enabled = true, level = 0.25f, size = 1f, near = 0f, air = 1f),
            )

        settings.forEach {
            val stage = ReverbStage(rate).apply { update(it) }
            val graph = reverbGraph(it.level, it.size, it.near, it.air, rate)

            assertEquals("$it", 0f, worst(stage::process, graph), 0f)
        }
    }

    @Test
    fun `the karaoke graph is the karaoke stage`() {
        val settings =
            listOf(
                DspSettings.Karaoke(enabled = true, level = 1f, filter = 0.35f, band = 0.5f, width = 0f),
                DspSettings.Karaoke(enabled = true, level = 0.6f, filter = 0.1f, band = 1f, width = 0.3f),
                DspSettings.Karaoke(enabled = true, level = 1f, filter = 1f, band = 0f, width = 1f),
            )

        settings.forEach {
            val stage = KaraokeStage(rate).apply { configure(rate) }
            stage.update(it)
            val graph = karaokeGraph(it.level, it.filter, it.band, it.width, rate)

            // measured once: a stage carries filter state, so running it twice
            // would compare a settled filter against a fresh one
            val difference = worst(stage::process, graph)

            // one ULP at full scale, from the crossfade's exact ends
            assertTrue("$it differs by less than one ULP: $difference", difference < ulp)
        }
    }
}
