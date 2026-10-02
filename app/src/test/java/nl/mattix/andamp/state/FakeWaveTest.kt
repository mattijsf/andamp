// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [FakeWave], the oscilloscope trace behind a player with nothing playing: grainy, moving
 * every frame, and larger on the beat.
 */
class FakeWaveTest {
    private fun frameAt(seconds: Double): IntArray {
        val state = WinampState()
        FakeWave.step(state, seconds)
        return state.visWave.copyOf()
    }

    private fun reversals(wave: IntArray): Int {
        var turns = 0
        var going = 0
        for (j in 1 until wave.size) {
            val step = wave[j] - wave[j - 1]
            if (step == 0) continue
            val now = if (step > 0) 1 else -1
            if (going != 0 && now != going) turns++
            going = now
        }
        return turns
    }

    @Test
    fun `every column lands on a row the window has`() {
        var t = 0.0
        repeat(600) {
            val wave = frameAt(t)
            t += 1.0 / 60
            assertTrue("every column is in rows 0 to 15: ${wave.min()}..${wave.max()}", wave.all { it in 0..15 })
        }
    }

    @Test
    fun `the trace reverses direction more than twenty times a frame`() {
        var t = 0.0
        val turns = (0 until 60).map { reversals(frameAt(t)).also { _ -> t += 1.0 / 60 } }

        assertTrue("the trace reverses over 20 times a frame: ${turns.average()}", turns.average() > GRAINY)
    }

    @Test
    fun `the trace jumps between consecutive frames`() {
        // the phase advances by a large step each frame
        val one = frameAt(1.0)
        val next = frameAt(1.0 + 1.0 / 60)
        val moved = one.indices.sumOf { abs(one[it] - next[it]) }.toDouble() / one.size

        assertTrue("the trace moves over 1.5 rows: $moved", moved > JUMPED)
    }

    @Test
    fun `it swells on the beat`() {
        val onIt = frameAt(0.0).sumOf { abs(it - RealOscilloscope.CENTER_ROW) }
        val between = frameAt(FakeBeat.BAR / 4 * 0.75).sumOf { abs(it - RealOscilloscope.CENTER_ROW) }

        assertTrue("the trace swells on the kick: $onIt against $between", onIt > between)
    }

    @Test
    fun `the same moment always draws the same trace`() {
        assertArrayEquals(frameAt(2.5), frameAt(2.5))
    }

    private companion object {
        /** Direction reversals across one frame's columns, averaged over a second. */
        const val GRAINY = 20.0

        const val JUMPED = 1.5
    }
}
