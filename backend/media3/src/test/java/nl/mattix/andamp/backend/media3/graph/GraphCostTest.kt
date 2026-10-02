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
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.sin

/**
 * What the graphs cost against the hand-written stages they reproduce.
 *
 * It is here and not in `:core:dsp` because the comparison needs the stages.
 */
class GraphCostTest {
    private val rate = 44_100

    private val phaser =
        DspSettings.Modulation(
            enabled = true,
            mode = DspSettings.Mode.PHASER,
            level = 0.5f,
            lfo = 0.3f,
            depth = 0.7f,
            rate = 0.25f,
            feedback = 0.4f,
            stereo = 0.5f,
        )
    private val reverb = DspSettings.Reverb(enabled = true, level = 0.5f, size = 0.6f, near = 0.4f, air = 0.7f)
    private val karaoke = DspSettings.Karaoke(enabled = true, level = 0.8f, filter = 0.35f, band = 0.5f, width = 0f)
    private val pan = DspSettings.Pan(enabled = true, pan = 0.3f)

    private fun engine(spec: GraphSpec): FrameProcessor = GraphCompiler.engine(spec)!!

    private fun signal(frames: Int) =
        FloatArray(frames * 2) { i ->
            val t = i / 2
            (0.5 * sin(t * 0.031) + 0.3 * sin(t * 0.0071) + 0.2 * sin(t * 0.11)).toFloat()
        }

    /**
     * The same rack given whole buffers instead of single frames, which is
     * what the block runner needs.
     */
    private fun timeBlocks(engines: () -> List<FrameProcessor>): Float {
        val input = signal(BENCH_FRAMES)
        var best = 0f
        repeat(RUNS) { run ->
            val rack = engines()
            val buffer = Array(2) { FloatArray(BUFFER) }
            val start = System.nanoTime()
            var at = 0
            while (at + BUFFER <= BENCH_FRAMES) {
                for (f in 0 until BUFFER) {
                    buffer[0][f] = input[(at + f) * 2]
                    buffer[1][f] = input[(at + f) * 2 + 1]
                }
                rack.forEach { it.process(buffer, BUFFER) }
                at += BUFFER
            }
            val elapsed = System.nanoTime() - start
            val ratio = (at.toFloat() / rate) / (elapsed / NANOS_PER_SEC)
            if (run > 0) best = max(best, ratio)
        }
        return best
    }

    private fun rack(): List<FrameProcessor> =
        listOf(
            engine(karaokeGraph(karaoke.level, karaoke.filter, karaoke.band, karaoke.width, rate)),
            engine(
                modulationGraph(
                    phaser.mode,
                    phaser.level,
                    phaser.lfo,
                    phaser.depth,
                    phaser.rate,
                    phaser.feedback,
                    phaser.stereo,
                    rate,
                ),
            ),
            engine(reverbGraph(reverb.level, reverb.size, reverb.near, reverb.air, rate)),
            engine(panGraph(pan.pan, rate)),
        )

    private fun timeIt(setUp: () -> (FloatArray) -> Unit): Float {
        val input = signal(BENCH_FRAMES)
        val frame = FloatArray(2)
        var best = 0f
        repeat(RUNS) { run ->
            val body = setUp()
            val start = System.nanoTime()
            for (i in 0 until BENCH_FRAMES) {
                frame[0] = input[i * 2]
                frame[1] = input[i * 2 + 1]
                body(frame)
            }
            val elapsed = System.nanoTime() - start
            val ratio = (BENCH_FRAMES.toFloat() / rate) / (elapsed / NANOS_PER_SEC)
            if (run > 0) best = max(best, ratio)
        }
        return best
    }

    @Test
    fun `the stages and the graphs of the whole rack run faster than their floors`() {
        val stages =
            timeIt {
                val k = KaraokeStage(rate).apply { configure(rate) }
                k.update(karaoke)
                val m = ModulationStage(rate).apply { configure(rate, 2) }
                m.update(phaser)
                val r = ReverbStage(rate).apply { update(reverb) }
                val p = PanStage().apply { update(pan) }
                (
                    { f: FloatArray ->
                        k.process(f)
                        m.process(f)
                        r.process(f)
                        p.process(f)
                    }
                )
            }

        val graphs =
            timeIt {
                val engines =
                    listOf(
                        engine(karaokeGraph(karaoke.level, karaoke.filter, karaoke.band, karaoke.width, rate)),
                        engine(
                            modulationGraph(
                                phaser.mode,
                                phaser.level,
                                phaser.lfo,
                                phaser.depth,
                                phaser.rate,
                                phaser.feedback,
                                phaser.stereo,
                                rate,
                            ),
                        ),
                        engine(reverbGraph(reverb.level, reverb.size, reverb.near, reverb.air, rate)),
                        engine(panGraph(pan.pan, rate)),
                    )
                ({ f: FloatArray -> engines.forEach { it.process(f) } })
            }

        listOf(
            "karaoke" to karaokeGraph(karaoke.level, karaoke.filter, karaoke.band, karaoke.width, rate),
            "modulation" to
                modulationGraph(
                    phaser.mode,
                    phaser.level,
                    phaser.lfo,
                    phaser.depth,
                    phaser.rate,
                    phaser.feedback,
                    phaser.stereo,
                    rate,
                ),
            "reverb" to reverbGraph(reverb.level, reverb.size, reverb.near, reverb.air, rate),
            "pan" to panGraph(pan.pan, rate),
        ).forEach { (name, spec) ->
            val alone =
                timeIt {
                    val e = engine(spec)
                    ({ f: FloatArray -> e.process(f) })
                }
            println("$name: ${spec.nodes.size} nodes, ${"%.0f".format(alone)}x real time")
        }
        val blocked = timeBlocks(::rack)
        println(
            "whole rack: stages ${"%.1f".format(stages)}x, graphs per frame ${"%.1f".format(graphs)}x, " +
                "graphs per block ${"%.1f".format(blocked)}x real time",
        )
        assertTrue("the stages run faster than ${GATE}x real time: $stages", stages > GATE)
        assertTrue("the graphs run faster than ${FLOOR}x real time: $graphs", graphs > FLOOR)
    }

    private companion object {
        const val BENCH_FRAMES = 441_000
        const val RUNS = 5
        const val NANOS_PER_SEC = 1e9f

        /** A buffer larger than the engine's own block. */
        const val BUFFER = 512
        const val GATE = 30f

        /** The floor for the graphs, in times real time. */
        const val FLOOR = 10f
    }
}
