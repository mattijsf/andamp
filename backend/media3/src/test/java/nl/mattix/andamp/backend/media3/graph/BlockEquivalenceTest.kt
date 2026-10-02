// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3.graph

import nl.mattix.andamp.backend.media3.dsp.DspSettings
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
 * The engine's two runners produce the same audio.
 *
 * One runs an instruction over many frames, and the other runs the whole
 * program over one frame. Every graph that can use the first must produce
 * what it produces with the second.
 */
class BlockEquivalenceTest {
    private val rate = 44_100

    private fun music(frames: Int) =
        FloatArray(frames) { n ->
            (0.4 * sin(2 * PI * 220 * n / rate) + 0.3 * sin(2 * PI * 1310 * n / rate)).toFloat()
        }

    /** The same graph, once a frame at a time and once in blocks, compared. */
    private fun difference(
        spec: GraphSpec,
        frames: Int = 6000,
    ): Float {
        val left = music(frames)
        val right = FloatArray(frames) { music(frames)[it] * 0.7f }

        val byFrame = GraphCompiler.engine(spec)!!
        val perFrame = Array(2) { if (it == 0) left.copyOf() else right.copyOf() }
        for (f in 0 until frames) {
            val frame = floatArrayOf(perFrame[0][f], perFrame[1][f])
            byFrame.process(frame)
            perFrame[0][f] = frame[0]
            perFrame[1][f] = frame[1]
        }

        val byBlock = GraphCompiler.engine(spec)!!
        val blocked = Array(2) { if (it == 0) left.copyOf() else right.copyOf() }
        // not a multiple of the block, so the last block is a partial one
        var at = 0
        while (at < frames) {
            val count = minOf(BUFFER, frames - at)
            val slice = Array(2) { ch -> FloatArray(count) { blocked[ch][at + it] } }
            byBlock.process(slice, count)
            for (ch in 0..1) for (f in 0 until count) blocked[ch][at + f] = slice[ch][f]
            at += count
        }

        var worst = 0f
        for (ch in 0..1) for (f in 0 until frames) worst = max(worst, abs(perFrame[ch][f] - blocked[ch][f]))
        return worst
    }

    @Test
    fun `the reverb blocks and is the same audio either way`() {
        val spec = reverbGraph(level = 0.5f, size = 0.6f, near = 0.4f, air = 0.7f, sampleRate = rate)

        assertTrue("the reverb runs in blocks", spec.safeBlock > 1)
        assertEquals(0f, difference(spec), 0f)
    }

    @Test
    fun `karaoke is the same audio either way`() {
        val spec = karaokeGraph(level = 1f, filter = 0.35f, band = 0.5f, width = 0f, sampleRate = rate)

        assertTrue("karaoke runs in blocks", spec.safeBlock > 1)
        assertEquals(0f, difference(spec), 0f)
    }

    @Test
    fun `pan is the same audio either way`() {
        assertEquals(0f, difference(panGraph(0.3f, rate)), 0f)
    }

    @Test
    fun `the phaser falls back to a frame at a time, and is still the same audio`() {
        val spec =
            modulationGraph(
                mode = DspSettings.Mode.PHASER,
                level = 0.5f,
                lfo = 0.3f,
                depth = 0.7f,
                rate = 0.25f,
                feedback = 0.4f,
                stereo = 0.5f,
                sampleRate = rate,
            )

        assertEquals("the phaser runs a frame at a time", 1, spec.safeBlock)
        assertEquals(0f, difference(spec), 0f)
    }

    @Test
    fun `the flanger runs a frame at a time and is the same audio`() {
        val spec =
            modulationGraph(
                mode = DspSettings.Mode.FLANGER,
                level = 0.5f,
                lfo = 0.3f,
                depth = 0.7f,
                rate = 0.25f,
                feedback = 0.4f,
                stereo = 0.5f,
                sampleRate = rate,
            )

        assertEquals(1, spec.safeBlock)
        assertEquals(0f, difference(spec), 0f)
    }

    private companion object {
        /** Not a multiple of the block, so a partial one is always exercised. */
        const val BUFFER = 300
    }
}
