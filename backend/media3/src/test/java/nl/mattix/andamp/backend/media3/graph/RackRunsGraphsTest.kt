// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3.graph

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import nl.mattix.andamp.backend.media3.DspAudioProcessor
import nl.mattix.andamp.backend.media3.dsp.DspSettings
import nl.mattix.andamp.backend.media3.dsp.KaraokeStage
import nl.mattix.andamp.backend.media3.dsp.ModulationStage
import nl.mattix.andamp.core.model.BuiltInEffects
import nl.mattix.andamp.core.model.RackSettings
import nl.mattix.andamp.core.model.RackSlot
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * The rack runs graphs for karaoke and modulation, and what comes out of the
 * processor matches the hand-written stages.
 *
 * [GraphParityTest] compares graph and stage in isolation; this is the same
 * comparison through the rack, with its smoothing and its fade.
 */
class RackRunsGraphsTest {
    private val rate = 44_100

    private fun signal(frames: Int) =
        FloatArray(frames) { n ->
            (0.4 * sin(2 * Math.PI * 220 * n / rate) + 0.3 * sin(2 * Math.PI * 1310 * n / rate)).toFloat()
        }

    /** The rack's output for one effect, in full-scale samples. */
    private fun throughRack(
        pluginId: String,
        values: Map<String, Float>,
        frames: Int,
    ): List<Float> {
        val spec = BuiltInEffects.byId(pluginId)!!
        val rack =
            RackSettings(
                listOf(
                    RackSlot(
                        pluginId,
                        enabled = true,
                        params =
                            nl.mattix.andamp.core.model
                                .ParamValues(values),
                    ),
                ),
            )
        val processor = DspAudioProcessor().apply { update(rack) }
        processor.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        val left = signal(frames)
        val input =
            ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
                for (f in 0 until frames) {
                    putShort((left[f] * 32768f).toInt().toShort())
                    putShort((left[f] * 0.6f * 32768f).toInt().toShort())
                }
                flip()
            }
        processor.queueInput(input)
        val out = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        return buildList { while (out.remaining() >= 2) add(out.short / 32768f) }.also { spec.name }
    }

    /** The same audio through a hand-written stage. */
    private fun throughStage(
        stage: (FloatArray) -> Unit,
        frames: Int,
    ): List<Float> {
        val left = signal(frames)

        // the same input the rack gets: it reads 16-bit samples, and an effect
        // with feedback amplifies the difference between a quantized input and
        // a float one
        fun quantised(v: Float) = (v * 32768f).toInt().toShort() / 32768f
        return buildList {
            for (f in 0 until frames) {
                val frame = floatArrayOf(quantised(left[f]), quantised(left[f] * 0.6f))
                stage(frame)
                // what the processor writes back, quantized the same way
                add((frame[0] * 32768f).toInt().toShort() / 32768f)
                add((frame[1] * 32768f).toInt().toShort() / 32768f)
            }
        }
    }

    @Test
    fun `the rack's karaoke is the karaoke stage`() {
        val stage =
            KaraokeStage(rate).apply {
                configure(rate)
                update(DspSettings.Karaoke(enabled = true, level = 1f, filter = 0.35f, band = 0.5f, width = 0f))
            }

        val fromRack =
            throughRack(
                BuiltInEffects.KARAOKE,
                mapOf("level" to 1f, "filter" to 0.35f, "band" to 0.5f, "width" to 0f),
                FRAMES,
            )
        val fromStage = throughStage(stage::process, FRAMES)

        var worst = 0f
        for (i in SETTLED until fromStage.size) worst = max(worst, abs(fromRack[i] - fromStage[i]))
        // one step of a 16-bit sample: the crossfade's exact ends, quantized
        assertTrue("the rack matches the stage within one step: $worst", worst <= STEP)
    }

    @Test
    fun `the rack's modulation is the modulation stage, in all three modes`() {
        val values =
            mapOf(
                "level" to 0.5f,
                "lfo" to 0.3f,
                "depth" to 0.7f,
                "rate" to 0.25f,
                "feedback" to 0.4f,
                "stereo" to 0.5f,
            )

        DspSettings.Mode.entries.forEach { mode ->
            val stage =
                ModulationStage(rate).apply {
                    configure(rate, 2)
                    update(
                        DspSettings.Modulation(
                            enabled = true,
                            mode = mode,
                            level = values.getValue("level"),
                            lfo = values.getValue("lfo"),
                            depth = values.getValue("depth"),
                            rate = values.getValue("rate"),
                            feedback = values.getValue("feedback"),
                            stereo = values.getValue("stereo"),
                        ),
                    )
                }

            val fromRack =
                throughRack(BuiltInEffects.MODULATION, values + ("mode" to mode.ordinal.toFloat()), FRAMES)
            val fromStage = throughStage(stage::process, FRAMES)

            var worst = 0f
            for (i in SETTLED until fromStage.size) worst = max(worst, abs(fromRack[i] - fromStage[i]))
            // the graph computes its sweep rate with exp2 where the stage uses
            // pow; the difference stays under one step of a 16-bit sample
            assertTrue("$mode matches the stage within one step: $worst", worst <= STEP)
        }
    }

    // The reverb graph mixes its lines and does not match ReverbStage;
    // ReverbQualityTest covers it.

    private companion object {
        const val FRAMES = 20_000

        /** One step of a 16-bit sample. */
        const val STEP = 1f / 32768f

        /**
         * Past the rack's fade-in and past the glide of any control that starts
         * somewhere other than where the test puts it.
         */
        const val SETTLED = 12_000
    }
}
