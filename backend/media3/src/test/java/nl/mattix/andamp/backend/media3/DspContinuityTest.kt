// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import nl.mattix.andamp.core.model.BuiltInEffects
import nl.mattix.andamp.core.model.RackSettings
import nl.mattix.andamp.core.model.RackSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Changing the rack while it plays must not be audible as anything but the
 * change asked for.
 *
 * A delay line, a comb and a biquad all carry past samples, and dropping them
 * on an update is a click. These tests bound the step an update may cause.
 */
class DspContinuityTest {
    private fun rack(vararg enabled: String) =
        RackSettings(
            BuiltInEffects.all.map { spec ->
                RackSlot(spec.id, enabled = spec.id in enabled, params = spec.defaults)
            },
        )

    private fun sine(frames: Int): FloatArray = FloatArray(frames) { sin(it * TURN).toFloat() * AMPLITUDE }

    /** One buffer of stereo 16-bit, both channels carrying [samples]. */
    private fun buffer(
        samples: FloatArray,
        from: Int,
        count: Int,
    ): ByteBuffer =
        ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (i in 0 until count) {
                val v = (samples[from + i] * FULL_SCALE).toInt().toShort()
                putShort(v)
                putShort(v)
            }
            flip()
        }

    /**
     * Runs [frames] of a sine through a processor, calling [duringRun] once at
     * the halfway point, and returns the left channel.
     */
    private fun playThrough(
        rack: RackSettings,
        frames: Int,
        duringRun: (DspAudioProcessor) -> Unit = {},
    ): FloatArray {
        val processor = DspAudioProcessor().apply { update(rack) }
        processor.configure(AudioProcessor.AudioFormat(RATE, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        val input = sine(frames)
        val out = FloatArray(frames)
        var written = 0
        var at = 0
        while (at < frames) {
            if (at >= frames / 2 && at - BLOCK < frames / 2) duringRun(processor)
            val count = minOf(BLOCK, frames - at)
            processor.queueInput(buffer(input, at, count))
            val produced = processor.output.order(ByteOrder.LITTLE_ENDIAN)
            while (produced.remaining() >= 4) {
                out[written++] = produced.short / FULL_SCALE
                produced.short
            }
            at += count
        }
        return out
    }

    /** The largest jump between neighboring samples: a click is a big one. */
    private fun worstStep(
        samples: FloatArray,
        from: Int,
        until: Int,
    ): Float {
        var worst = 0f
        for (i in max(1, from) until until) worst = max(worst, abs(samples[i] - samples[i - 1]))
        return worst
    }

    @Test
    fun `an update that changes nothing changes no samples`() {
        val settled = rack(BuiltInEffects.MODULATION)

        val untouched = playThrough(settled, FRAMES)
        val updated = playThrough(settled, FRAMES) { it.update(settled) }

        assertEquals("re-applying the same rack changes no sample", untouched.toList(), updated.toList())
    }

    @Test
    fun `changing one plug-in's value leaves another plug-in's memory alone`() {
        // the reverb has seconds of tail to lose
        val settled = rack(BuiltInEffects.REVERB)
        val untouched = playThrough(settled, FRAMES)

        val moved =
            playThrough(settled, FRAMES) {
                it.update(settled.setValue(BuiltInEffects.MODULATION, "depth", 0.9f))
            }

        assertEquals(
            "a slider on a disabled plug-in does not reach the audio",
            untouched.toList(),
            moved.toList(),
        )
    }

    @Test
    fun `moving a slider does not click`() {
        val settled = rack(BuiltInEffects.MODULATION)
        val steady = worstStep(playThrough(settled, FRAMES), FRAMES / 4, FRAMES)

        val moved =
            playThrough(settled, FRAMES) {
                it.update(settled.setValue(BuiltInEffects.MODULATION, "depth", 0.9f))
            }

        val jump = worstStep(moved, FRAMES / 2, FRAMES / 2 + BLOCK * 2)
        assertTrue(
            "a depth change does not click: $jump against a steady $steady",
            jump < steady * TOLERATED_STEP,
        )
    }

    @Test
    fun `moving the room's size does not click`() {
        // size moves six delay times and four early taps while the tail is
        // ringing; a delay time that steps is a step in the read position
        val settled = rack(BuiltInEffects.REVERB)
        val steady = worstStep(playThrough(settled, FRAMES), FRAMES / 4, FRAMES)

        val moved =
            playThrough(settled, FRAMES) {
                it.update(settled.setValue(BuiltInEffects.REVERB, "size", 0.9f))
            }

        val jump = worstStep(moved, FRAMES / 2, FRAMES / 2 + SETTLING)
        assertTrue("a size change does not click: $jump against a steady $steady", jump < steady * TOLERATED_STEP)
    }

    @Test
    fun `switching a mode does not click`() {
        // a mode is not a value: chorus and phaser are different arrangements.
        // The rack fades to dry and swaps, as it does for switching an effect on.
        val settled = rack(BuiltInEffects.MODULATION)
        val steady = worstStep(playThrough(settled, FRAMES), FRAMES / 4, FRAMES)

        val switched =
            playThrough(settled, FRAMES) {
                it.update(settled.setValue(BuiltInEffects.MODULATION, "mode", PHASER))
            }

        // wider than the other windows: the fade is 20 ms, a walked value
        // takes longer to land, and the steepest moment is not the first one
        val jump = worstStep(switched, FRAMES / 2, FRAMES / 2 + SETTLING)
        assertTrue("a mode change does not click: $jump against a steady $steady", jump < steady * TOLERATED_STEP)
    }

    @Test
    fun `switching a mode is heard`() {
        // the fade must not cover a change that did not happen: a stage kept
        // across a structural change would still sound like the old mode
        val settled = rack(BuiltInEffects.MODULATION)
        val stayed = playThrough(settled, FRAMES)

        val switched =
            playThrough(settled, FRAMES) {
                it.update(settled.setValue(BuiltInEffects.MODULATION, "mode", PHASER))
            }

        val worst = (FRAMES / 2 + SETTLING until FRAMES).maxOf { abs(switched[it] - stayed[it]) }
        assertTrue("the audio after a mode change differs from the old mode's: $worst", worst > AUDIBLE)
    }

    @Test
    fun `switching a plug-in on does not click`() {
        val silent = rack()
        val steady = worstStep(playThrough(silent, FRAMES), FRAMES / 4, FRAMES)

        val switched =
            playThrough(silent, FRAMES) {
                it.update(silent.setEnabled(BuiltInEffects.MODULATION, true))
            }

        val jump = worstStep(switched, FRAMES / 2, FRAMES / 2 + BLOCK * 2)
        assertTrue("switching an effect on does not click: $jump against a steady $steady", jump < steady * TOLERATED_STEP)
    }

    @Test
    fun `reordering the rack does not click`() {
        val both = rack(BuiltInEffects.MODULATION, BuiltInEffects.KARAOKE)
        val steady = worstStep(playThrough(both, FRAMES), FRAMES / 4, FRAMES)

        val reordered = playThrough(both, FRAMES) { it.update(both.move(BuiltInEffects.KARAOKE, 0)) }

        val jump = worstStep(reordered, FRAMES / 2, FRAMES / 2 + BLOCK * 2)
        assertTrue("a reorder does not click: $jump against a steady $steady", jump < steady * TOLERATED_STEP)
    }

    private companion object {
        const val RATE = 44_100
        const val FRAMES = 16_384
        const val BLOCK = 512
        const val FULL_SCALE = 32768f
        const val AMPLITUDE = 0.5f

        /** 440 Hz at 44.1 kHz, in radians per frame. */
        const val TURN = 2 * Math.PI * 440 / 44_100

        /**
         * How much bigger than the signal's own steepest step a change may be.
         * A click is far larger; this leaves room for the effect sounding
         * different afterwards.
         */
        const val TOLERATED_STEP = 2f

        /** The mode value of the phaser. */
        const val PHASER = 2f

        /** The fade, plus enough for a walked value to be most of the way there. */
        const val SETTLING = 4096

        /** Well above the last bit of a 16-bit sample. */
        const val AUDIBLE = 0.01f
    }
}
