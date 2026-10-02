// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.playback.AudioTap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The feed asks for the tap each frame until it has one. A backend that reaches its engine over
 * a binding has no audio to offer until that binding lands, which can be after playback has
 * started.
 */
class VisualizerFeedTest {
    private class SilentTap : AudioTap {
        override val sampleRateHz = 44_100
        override val writtenSamples = 0L

        override fun readAt(
            endSample: Long,
            out: FloatArray,
        ) = false
    }

    @Test
    fun `without a tap the decorative signal drives it`() {
        val feed = VisualizerFeed(frameMs = 25) { null }

        feed.stepAnalyzer(WinampState(), timeSec = 0.0)

        assertFalse(feed.onRealAudio)
    }

    @Test
    fun `a tap that arrives after the first frame is taken up`() {
        var tap: AudioTap? = null
        val feed = VisualizerFeed(frameMs = 25) { tap }
        val state = WinampState()
        feed.stepAnalyzer(state, timeSec = 0.0)
        assertFalse(feed.onRealAudio)

        tap = SilentTap()
        feed.stepAnalyzer(state, timeSec = 0.025)

        assertTrue(feed.onRealAudio)
    }

    @Test
    fun `the oscilloscope follows the same tap`() {
        var tap: AudioTap? = null
        val feed = VisualizerFeed(frameMs = 25) { tap }
        val state = WinampState()
        feed.stepOscilloscope(state, timeSec = 0.0)
        assertFalse(feed.onRealAudio)

        tap = SilentTap()
        feed.stepOscilloscope(state, timeSec = 0.025)

        assertTrue(feed.onRealAudio)
    }

    /** Both readers smooth over their own history; a rebuild per frame restarts the motion. */
    @Test
    fun `the tap is taken up once and not again`() {
        var handed = 0
        val feed =
            VisualizerFeed(frameMs = 25) {
                handed++
                SilentTap()
            }
        val state = WinampState()

        repeat(5) { feed.stepAnalyzer(state, timeSec = it * 0.025) }

        assertTrue("the tap is asked for once: $handed times", handed == 1)
    }
}
