// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import com.google.common.collect.ImmutableList
import nl.mattix.andamp.core.model.BuiltInEffects
import nl.mattix.andamp.core.model.RackSettings
import nl.mattix.andamp.core.model.RackSlot
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What the effect chain costs when every effect is switched off.
 *
 * Each processor stays in ExoPlayer's chain whether or not it has anything to
 * do, so an idle rack still copies each buffer through. The ratio measured is
 * seconds of audio processed per second.
 */
class IdleChainCostTest {
    private val rate = 44_100
    private val channels = 2
    private val framesPerBuffer = 1024

    private fun buffer(): ByteBuffer =
        ByteBuffer.allocateDirect(framesPerBuffer * channels * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(framesPerBuffer * channels) { putShort((it % 3000).toShort()) }
            flip()
        }

    private fun pipeline(vararg processors: AudioProcessor) =
        AudioProcessingPipeline(ImmutableList.copyOf(processors)).also {
            it.configure(AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_16BIT))
            it.flush()
        }

    /**
     * Seconds of audio processed per second of this thread's own processor time, the best of
     * [RUNS] runs. The clock on the wall also counts the time this thread waits for a
     * processor while other work has it, which a build running tests side by side makes
     * long and uneven; that wait is not the chain's cost.
     */
    private fun secondsOfAudioPerSecond(pipeline: AudioProcessingPipeline): Double {
        val buffers = 1000 // about 23 seconds of audio
        val audioSec = buffers * framesPerBuffer.toDouble() / rate
        val clock = ManagementFactory.getThreadMXBean()
        repeat(200) { push(pipeline) } // warm up the JIT
        return (1..RUNS).maxOf {
            val start = clock.currentThreadCpuTime
            repeat(buffers) { push(pipeline) }
            audioSec / ((clock.currentThreadCpuTime - start) / NANOS_PER_SECOND)
        }
    }

    private fun push(pipeline: AudioProcessingPipeline) {
        val input = buffer()
        while (input.hasRemaining()) {
            pipeline.queueInput(input)
            val out = pipeline.output
            out.position(out.limit())
        }
    }

    @Test
    fun `an idle rack costs a small fraction of real time`() {
        val idle =
            pipeline(
                EqAudioProcessor(),
                BalanceAudioProcessor(),
                DspAudioProcessor(),
            )

        val ratio = secondsOfAudioPerSecond(idle)

        println("idle chain: ${"%.0f".format(ratio)}x real time")
        assertTrue("an idle chain runs more than 100x real time: ${"%.0f".format(ratio)}x", ratio > 100)
    }

    @Test
    fun `a working rack still runs far ahead of real time`() {
        val busy =
            pipeline(
                EqAudioProcessor(),
                BalanceAudioProcessor().apply { update(0.3f) },
                DspAudioProcessor().apply {
                    update(
                        RackSettings(
                            BuiltInEffects.all.map { RackSlot(it.id, enabled = true, params = it.defaults) },
                        ),
                    )
                },
            )

        val ratio = secondsOfAudioPerSecond(busy)

        println("full rack: ${"%.0f".format(ratio)}x real time")
        // a floor well above real time for the whole rack
        assertTrue("the whole rack runs more than 15x real time: ${"%.0f".format(ratio)}x", ratio > 15)
    }

    private companion object {
        /** Short runs and several of them: one is enough to have had a processor to itself. */
        const val RUNS = 6
        const val NANOS_PER_SECOND = 1e9
    }
}
