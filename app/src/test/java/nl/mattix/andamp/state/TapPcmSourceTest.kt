// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.playback.AudioTap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TapPcmSourceTest {
    private class FakeTap(
        override var sampleRateHz: Int,
        override var writtenSamples: Long = 1_000_000L,
    ) : AudioTap {
        var lastWindow = 0

        override fun readAt(
            endSample: Long,
            out: FloatArray,
        ): Boolean {
            lastWindow = out.size
            out.fill(0.5f)
            return true
        }
    }

    @Test
    fun `one frame of PCM is the sample rate divided by the frame rate`() {
        assertEquals(735, samplesPerFrame(44_100, 60, MAX))
        assertEquals(367, samplesPerFrame(44_100, 120, MAX))
        assertEquals(800, samplesPerFrame(48_000, 60, MAX))
    }

    @Test
    fun `a very high frame rate still asks for a usable window`() {
        assertEquals(128, samplesPerFrame(44_100, 1_000, MAX))
    }

    @Test
    fun `the window never exceeds what projectM accepts`() {
        assertEquals(512, samplesPerFrame(44_100, 60, 512))
    }

    /**
     * A paused player stops writing. The source then reports silence, so a visualizer's beat
     * detector is not fed a loop of the last written audio.
     */
    @Test
    fun `a tap that stops advancing goes silent instead of looping stale audio`() {
        val tap = FakeTap(sampleRateHz = 44_100)
        val source = TapPcmSource(FPS, MAX) { tap }
        assertNotNull("playing: samples flow", source.read())

        // paused: the write head freezes; the first frames may still read the
        // tail, and two seconds of them are past the point where the source reports silence
        repeat(2 * FPS) { source.read() }
        assertNull("frozen: the source reports silence", source.read())

        tap.writtenSamples += 44_100L
        assertNotNull("resumed: samples flow again", source.read())
    }

    @Test
    fun `no tap means no samples`() {
        assertNull(TapPcmSource(FPS, MAX) { null }.read())
    }

    @Test
    fun `a tap with no rate yet means no samples`() {
        assertNull(TapPcmSource(FPS, MAX) { FakeTap(sampleRateHz = 0) }.read())
    }

    @Test
    fun `the buffer is sized from the tap's rate and reused across frames`() {
        val tap = FakeTap(sampleRateHz = 44_100)
        val source = TapPcmSource(FPS, MAX) { tap }
        val first = source.read()
        assertNotNull(first)
        assertEquals(735, tap.lastWindow)
        assertSame("the buffer is reused across frames", first, source.read())
    }

    @Test
    fun `a rate change resizes the buffer`() {
        val tap = FakeTap(sampleRateHz = 44_100)
        val source = TapPcmSource(FPS, MAX) { tap }
        source.read()
        tap.sampleRateHz = 48_000
        val resized = source.read()
        assertEquals(800, resized?.size)
    }

    @Test
    fun `a new tap starts a new reader`() {
        var tap = FakeTap(sampleRateHz = 44_100)
        val source = TapPcmSource(FPS, MAX) { tap }
        val first = source.read()
        tap = FakeTap(sampleRateHz = 44_100)
        val second = source.read()
        assertNotNull(second)
        assertEquals(735, second?.size)
        assertEquals("the new tap is read", 735, tap.lastWindow)
        assertNotNull(first)
    }

    private companion object {
        const val FPS = 60
        const val MAX = 2048
    }
}
