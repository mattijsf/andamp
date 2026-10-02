// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The idle preset must always run: it is what a fresh install shows. Its
 * bodies are hand-written bytes, so a reader that changes its layout fails
 * here.
 */
class AvsIdlePresetTest {
    @Test
    fun `every component of the idle preset is one this build runs`() {
        assertTrue(AvsEngine.canRun(AvsIdlePreset.preset()))
    }

    @Test
    fun `the idle preset draws something within a few frames`() {
        AvsEngine(64, 48).use { engine ->
            engine.load(AvsIdlePreset.preset())
            assertEquals(emptyList<String>(), engine.unimplemented)

            val loud = AvsAudioFrame(waveform = FloatArray(AvsAudioFrame.SAMPLES) { 0.8f }, beat = true)
            repeat(3) { engine.render(loud) }

            assertTrue(
                "the idle preset draws something",
                engine.frame.pixels.any { it != AvsFrame.OPAQUE },
            )
        }
    }
}
