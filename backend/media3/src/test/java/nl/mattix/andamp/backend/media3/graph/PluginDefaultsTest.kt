// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3.graph

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import nl.mattix.andamp.backend.media3.DspAudioProcessor
import nl.mattix.andamp.core.model.ParamValues
import nl.mattix.andamp.core.model.RackSettings
import nl.mattix.andamp.core.model.RackSlot
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What a plug-in sounds like before anybody has touched it.
 *
 * A rack slot that has never been changed carries no stored values, and a
 * control then takes the value the plug-in declared, which is also what the
 * screen draws.
 */
class PluginDefaultsTest {
    // the built-in karaoke, a graph that takes its controls the way a
    // plug-in's does
    private val karaoke = nl.mattix.andamp.core.model.BuiltInEffects.KARAOKE

    /** Past the rack's fade-in. */
    private val frames = 20_000

    private fun run(rack: RackSettings): List<Short> {
        val processor = DspAudioProcessor().apply { update(rack) }
        processor.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        val input =
            ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
                repeat(frames) {
                    // a centered voice, which is what karaoke removes
                    val v = (8000 * kotlin.math.sin(it / 9.0)).toInt().toShort()
                    putShort(v)
                    putShort(v)
                }
                flip()
            }
        processor.queueInput(input)
        val out = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        return buildList { while (out.remaining() >= 2) add(out.short) }
    }

    @Test
    fun `an untouched plug-in runs at the settings it declared, not at zero`() {
        val untouched = RackSettings(listOf(RackSlot(karaoke, enabled = true, params = ParamValues.EMPTY)))
        val spelledOut =
            RackSettings(
                listOf(
                    RackSlot(
                        karaoke,
                        enabled = true,
                        // the same values the plug-in declares, written out
                        params = ParamValues(mapOf("level" to 1f, "filter" to 0.35f, "band" to 0.5f, "width" to 0f)),
                    ),
                ),
            )

        assertEquals(
            "storing nothing sounds like storing the declared defaults",
            spelledOut.let(::run),
            untouched.let(::run),
        )
    }

    @Test
    fun `an untouched karaoke cancels a centered voice`() {
        val untouched = RackSettings(listOf(RackSlot(karaoke, enabled = true, params = ParamValues.EMPTY)))
        val off = RackSettings(listOf(RackSlot(karaoke, enabled = false, params = ParamValues.EMPTY)))

        val cancelled =
            run(untouched).takeLast(4096).filterIndexed { i, _ -> i % 2 == 0 }.maxOf { kotlin.math.abs(it.toInt()) }
        val untouchedAudio =
            run(off).takeLast(4096).filterIndexed { i, _ -> i % 2 == 0 }.maxOf { kotlin.math.abs(it.toInt()) }

        // level defaults to 1, so a centered voice mostly goes; with the
        // controls at zero the effect would return the audio unchanged
        assertEquals("a centered voice is cancelled", true, cancelled < untouchedAudio / 2)
    }
}
