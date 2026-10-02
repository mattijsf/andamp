// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AVS's own detector, transcribed from main.cpp: full-wave amplitude against
 * a fast peak that drifts toward a slow envelope, with an absolute floor.
 */
class AvsBeatTest {
    @Test
    fun `a loud burst after quiet is a beat`() {
        val beat = AvsBeat()
        repeat(30) { beat.update(level(0.05f)) }

        assertTrue(beat.update(level(0.9f)))
    }

    @Test
    fun `a level under the absolute floor never beats`() {
        val beat = AvsBeat()
        repeat(60) { assertFalse(beat.update(level(0.01f))) }
    }

    @Test
    fun `a sustained level stops beating once the peak catches up`() {
        val beat = AvsBeat()
        repeat(30) { beat.update(level(0.05f)) }

        beat.update(level(0.9f)) // the hit
        val followers = (0 until 20).count { beat.update(level(0.9f)) }

        assertTrue("a plateau beats at most twice more: $followers", followers <= 2)
    }

    @Test
    fun `it recovers and beats again after the level drops`() {
        val beat = AvsBeat()
        repeat(30) { beat.update(level(0.05f)) }
        beat.update(level(0.9f))
        repeat(60) { beat.update(level(0.05f)) }

        assertTrue(beat.update(level(0.9f)))
    }

    private fun level(amplitude: Float) = FloatArray(AvsAudioFrame.SAMPLES) { i -> if (i % 2 == 0) amplitude else -amplitude }
}
