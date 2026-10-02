// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** The pan stage: constant power, so sweeping the image does not dip in the middle. */
class PanStageTest {
    private fun stage(
        pan: Float,
        enabled: Boolean = true,
    ) = PanStage().apply { update(DspSettings.Pan(enabled = enabled, pan = pan)) }

    private fun frameOf(
        l: Float = 1f,
        r: Float = 1f,
    ) = floatArrayOf(l, r)

    @Test
    fun `switched off it does nothing at all`() {
        val frame = frameOf()

        stage(pan = 0f, enabled = false).process(frame)

        assertEquals(1f, frame[0], 0f)
        assertEquals(1f, frame[1], 0f)
    }

    @Test
    fun `hard left silences the right channel`() {
        val frame = frameOf()

        stage(pan = 0f).process(frame)

        assertEquals(1f, frame[0], 0.001f)
        assertEquals(0f, frame[1], 0.001f)
    }

    @Test
    fun `hard right silences the left channel`() {
        val frame = frameOf()

        stage(pan = 1f).process(frame)

        assertEquals(0f, frame[0], 0.001f)
        assertEquals(1f, frame[1], 0.001f)
    }

    @Test
    fun `center keeps constant power rather than unity gain`() {
        val frame = frameOf()

        stage(pan = 0.5f).process(frame)

        // a quarter-circle law: both channels at 1/sqrt(2), total power unchanged
        assertEquals(1f / sqrt(2f), frame[0], 0.001f)
        assertEquals(1f / sqrt(2f), frame[1], 0.001f)
    }

    @Test
    fun `power stays roughly steady across the sweep`() {
        val powers =
            (0..10).map { step ->
                val frame = frameOf()
                stage(pan = step / 10f).process(frame)
                frame[0] * frame[0] + frame[1] * frame[1]
            }

        assertTrue("the power stays within 1% of one: $powers", powers.all { kotlin.math.abs(it - 1f) < 0.01f })
    }

    @Test
    fun `mono has nothing to pan between and is left alone`() {
        val frame = floatArrayOf(0.5f)

        stage(pan = 0f).process(frame)

        assertEquals(0.5f, frame[0], 0f)
    }
}
