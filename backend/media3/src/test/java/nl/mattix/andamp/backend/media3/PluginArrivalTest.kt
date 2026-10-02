// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import nl.mattix.andamp.core.model.BuiltInEffects
import nl.mattix.andamp.core.model.RackSettings
import nl.mattix.andamp.core.model.RackSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * A stream does not wait for a plug-in's Lua.
 *
 * Loading a plug-in runs a script on a worker, not on the thread that
 * configures a stream. The plug-ins arrive afterwards, through the same fade
 * that covers switching an effect on.
 */
class PluginArrivalTest {
    private val frames = 512

    /** A loader that runs nothing until it is told to. */
    private class Held : Executor {
        private val waiting = mutableListOf<Runnable>()

        override fun execute(command: Runnable) {
            waiting += command
        }

        fun run() {
            val all = waiting.toList()
            waiting.clear()
            all.forEach { it.run() }
        }

        val pending get() = waiting.isNotEmpty()
    }

    /**
     * A plug-in per test.
     *
     * What is loaded is cached for the whole process, keyed by the format and
     * the sources, so two tests sharing a source would find the first's work
     * already done.
     */
    private fun gain(mark: String) =
        """
        -- $mark
        plugin { id = "com.example.arrival", name = "Arrival", version = "1" }
        local level = param.number { id = "level", min = 0, max = 1, default = 0.5 }
        function build(g, ctx)
          local o = {}
          for c = 0, ctx.channels - 1 do o[c] = g.mul(g.input(c), level) end
          return o
        end
        """.trimIndent()

    private fun configure(processor: DspAudioProcessor) {
        processor.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
    }

    @Test
    fun `configuring a stream does not run a plug-in's script`() {
        val held = Held()
        val processor = DspAudioProcessor(held)
        processor.setPlugins(listOf(gain("configuring")))

        configure(processor)

        assertTrue("the load is asked for", held.pending)
        assertFalse(
            "the effect is not on offer before its script runs",
            processor.effectsOnOffer().any { it.id == "com.example.arrival" },
        )
    }

    @Test
    fun `the plug-in is there once its script has run`() {
        val held = Held()
        val processor = DspAudioProcessor(held)
        processor.setPlugins(listOf(gain("arriving")))
        configure(processor)

        held.run()

        assertTrue(
            "the effect is on offer once its script has run",
            processor.effectsOnOffer().any { it.id == "com.example.arrival" },
        )
    }

    @Test
    fun `a plug-in arriving does not duck what is already playing`() {
        // the load finishes on a worker and the rack takes it. With nothing it
        // brought switched on, the rack must not fade to dry.
        val held = Held()
        val processor = DspAudioProcessor(held)
        processor.update(RackSettings(listOf(RackSlot(BuiltInEffects.KARAOKE, enabled = true))))
        processor.setPlugins(listOf(gain("quietly")))
        configure(processor)
        // past the fade-in and the filters' settling, so a fade would show as
        // a difference
        repeat(SETTLING_BUFFERS) { through(processor) }

        val before = through(processor)
        held.run()
        val after = through(processor)

        assertEquals("the output is unchanged by the arrival", before.toList(), after.toList())
    }

    /** One buffer of a steady stereo tone, as it comes out. */
    private fun through(processor: DspAudioProcessor): ShortArray {
        val input =
            java.nio.ByteBuffer
                .allocate(frames * 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        repeat(frames) {
            input.putShort(8_000)
            input.putShort(8_000)
        }
        input.flip()
        processor.queueInput(input)
        val out = processor.output.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        return ShortArray(out.remaining() / 2) { out.short }
    }

    @Test
    fun `a plug-in installed while a stream is running arrives the same way`() {
        val held = Held()
        val processor = DspAudioProcessor(held)
        configure(processor)
        held.run()

        processor.setPlugins(listOf(gain("mid-stream")))
        assertTrue("the load is asked for", held.pending)
        held.run()

        assertTrue(processor.effectsOnOffer().any { it.id == "com.example.arrival" })
        // and it can be switched on, which is what "arrived" has to mean
        processor.update(RackSettings(listOf(RackSlot("com.example.arrival", enabled = true))))
    }

    private companion object {
        const val SETTLING_BUFFERS = 40
    }
}
