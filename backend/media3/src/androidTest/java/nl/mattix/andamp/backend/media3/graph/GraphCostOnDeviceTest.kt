// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3.graph

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.mattix.andamp.core.dsp.FrameProcessor
import nl.mattix.andamp.core.dsp.GraphCompiler
import nl.mattix.andamp.core.dsp.GraphSpec
import nl.mattix.andamp.core.model.BuiltInEffects
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sin

/**
 * What the rack costs on a phone, where ART and the phone's cores decide the
 * number.
 *
 * The result is written to logcat as well as asserted.
 *
 * Method names are plain identifiers: a name with spaces is illegal in DEX
 * below version 040, and this module runs from API 26.
 */
@RunWith(AndroidJUnit4::class)
class GraphCostOnDeviceTest {
    private val rate = 44_100

    private fun engine(spec: GraphSpec): FrameProcessor = GraphCompiler.engine(GraphCompiler.annotate(spec))!!

    private fun signal(frames: Int) =
        FloatArray(frames * 2) { i ->
            val t = i / 2
            (0.5 * sin(t * 0.031) + 0.3 * sin(t * 0.0071) + 0.2 * sin(t * 0.11)).toFloat()
        }

    /** The whole rack, one frame at a time, as the audio path runs it. */
    private fun rack(): List<FrameProcessor> =
        listOf(
            engine(BuiltInGraphs.reverb(BuiltInEffects.reverb, rate, CHANNELS)),
            engine(BuiltInGraphs.karaoke(BuiltInEffects.karaoke, rate, CHANNELS)),
            engine(BuiltInGraphs.modulation(BuiltInEffects.modulation, rate, CHANNELS, PHASER)),
        ) + BundledPlugins.load(rate, CHANNELS).mapNotNull { BundledPlugins.engine(it.graph) }

    /** How many times faster than the music itself the rack runs. */
    private fun timesRealTime(engines: List<FrameProcessor>): Float {
        val samples = signal(FRAMES)
        val frame = FloatArray(CHANNELS)
        // the first pass warms up; the second is timed
        repeat(2) { pass ->
            val started = System.nanoTime()
            for (f in 0 until FRAMES) {
                frame[0] = samples[f * 2]
                frame[1] = samples[f * 2 + 1]
                engines.forEach { it.process(frame) }
            }
            if (pass == 1) {
                val seconds = (System.nanoTime() - started) / 1e9f
                return (FRAMES.toFloat() / rate) / seconds
            }
        }
        error("unreachable")
    }

    @Test
    fun rackRunsFarFasterThanTheMusic() {
        val times = timesRealTime(rack())

        Log.i("AndAmpCost", "whole rack on device: ${times}x real time")
        println("whole rack on device: ${times}x real time")
        // below several times real time the rack would stutter when anything
        // else wants the core
        assertTrue("the rack runs faster than ${FLOOR}x real time: ${times}x", times > FLOOR)
    }

    private companion object {
        const val CHANNELS = 2
        const val FRAMES = 44_100
        const val PHASER = 2

        /** The least the rack must manage, in times real time. */
        const val FLOOR = 4f
    }
}
