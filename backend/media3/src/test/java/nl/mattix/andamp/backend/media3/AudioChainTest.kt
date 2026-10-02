// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import com.google.common.collect.ImmutableList
import nl.mattix.andamp.core.playback.PcmRingBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The processors as ExoPlayer runs them, driven through Media3's own pipeline.
 *
 * Covers what only the chain produces: the pipeline hands a processor the
 * shared empty buffer, and putting a buffer into itself throws.
 */
class AudioChainTest {
    private val format = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)

    private fun pipelineOf(vararg processors: AudioProcessor): AudioProcessingPipeline =
        AudioProcessingPipeline(ImmutableList.copyOf(processors)).also {
            it.configure(format)
            it.flush()
        }

    private fun stereoFrames(frames: Int): ByteBuffer =
        ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(frames) {
                putShort((it * 10).toShort())
                putShort((-it * 10).toShort())
            }
            flip()
        }

    /** Pushes [rounds] buffers through, returning everything that came out. */
    private fun drain(
        pipeline: AudioProcessingPipeline,
        rounds: Int,
        frames: Int = 8,
    ): List<Short> {
        val out = mutableListOf<Short>()
        repeat(rounds) {
            val input = stereoFrames(frames)
            while (input.hasRemaining()) {
                pipeline.queueInput(input)
                val output = pipeline.output.order(ByteOrder.LITTLE_ENDIAN)
                while (output.remaining() >= 2) out += output.short
            }
        }
        return out
    }

    @Test
    fun `the pipeline may ask for output before it has been given any input`() {
        // what DefaultAudioSink does on the first frame: getOutput() with
        // nothing queued runs the chain on the shared EMPTY_BUFFER, and a
        // processor that puts it into its own empty buffer throws
        val tap = TeeAudioProcessor(PcmTapBufferSink(PcmRingBuffer()))
        val pipeline = pipelineOf(EqAudioProcessor(), BalanceAudioProcessor(), tap)

        val output = pipeline.output

        assertEquals(0, output.remaining())
    }

    @Test
    fun `a disabled EQ survives the same empty first frame`() {
        val pipeline = pipelineOf(EqAudioProcessor())

        assertEquals(0, pipeline.output.remaining())
    }

    @Test
    fun `the chain survives a flat EQ and a centered balance`() {
        // both processors take their pass-through path on every buffer
        val tap = TeeAudioProcessor(PcmTapBufferSink(PcmRingBuffer()))
        val pipeline = pipelineOf(EqAudioProcessor(), BalanceAudioProcessor(), tap)

        val samples = drain(pipeline, rounds = 3)

        assertEquals(3 * 8 * 2, samples.size)
        assertEquals(0, samples.first().toInt())
    }

    @Test
    fun `the chain pans when balance is set and the EQ is off`() {
        val balance = BalanceAudioProcessor().apply { update(1f) } // hard right
        val pipeline = pipelineOf(EqAudioProcessor(), balance)

        val samples = drain(pipeline, rounds = 2)

        val left = samples.filterIndexed { i, _ -> i % 2 == 0 }
        assertTrue("hard right silences the left channel: $left", left.all { it.toInt() == 0 })
    }

    @Test
    fun `balance alone, centered, still passes every sample through`() {
        val pipeline = pipelineOf(BalanceAudioProcessor())

        val samples = drain(pipeline, rounds = 2, frames = 4)

        assertEquals(listOf<Short>(0, 0, 10, -10, 20, -20, 30, -30), samples.take(8))
    }

    /**
     * With the preamp at zero, the bands flat, the balance centered and no
     * effect in the rack, the output equals the input sample for sample. A
     * loud signal is used, because near full scale an added gain shows up as
     * clipping.
     */
    @Test
    fun `at its defaults the chain returns exactly what it was given`() {
        val dsp = DspAudioProcessor()
        val pipeline = pipelineOf(EqAudioProcessor(), BalanceAudioProcessor(), dsp)

        val input = loudFrames(FRAMES)
        val out = drainOf(pipeline, input)

        assertEquals("the chain keeps the number of samples", input.size, out.size)
        assertEquals("the chain is unity at its defaults", input, out)
    }

    @Test
    fun `at its defaults the loudest sample is as loud as it arrived`() {
        val pipeline = pipelineOf(EqAudioProcessor(), BalanceAudioProcessor(), DspAudioProcessor())

        val input = loudFrames(FRAMES)
        val out = drainOf(pipeline, input)

        val before = input.maxOf { kotlin.math.abs(it.toInt()) }
        val after = out.maxOf { kotlin.math.abs(it.toInt()) }
        assertEquals("the peak stays at $before: $after", before, after)
    }

    @Test
    fun `a tap in the chain leaves the output equal to the input`() {
        val ring = PcmRingBuffer()
        val pipeline =
            pipelineOf(
                EqAudioProcessor(),
                BalanceAudioProcessor(),
                DspAudioProcessor(),
                TeeAudioProcessor(PcmTapBufferSink(ring)),
            )

        val input = loudFrames(FRAMES)
        val out = drainOf(pipeline, input)

        assertEquals(input, out)
    }

    /** A sine near full scale. */
    private fun loudFrames(frames: Int): List<Short> =
        (0 until frames).flatMap { i ->
            val v = (kotlin.math.sin(2.0 * Math.PI * i / PERIOD) * PEAK).toInt().toShort()
            listOf(v, v)
        }

    private fun drainOf(
        pipeline: AudioProcessingPipeline,
        samples: List<Short>,
    ): List<Short> {
        val input =
            ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                samples.forEach { putShort(it) }
                flip()
            }
        val out = mutableListOf<Short>()
        while (input.hasRemaining()) {
            pipeline.queueInput(input)
            val output = pipeline.output.order(ByteOrder.LITTLE_ENDIAN)
            while (output.remaining() >= 2) out += output.short
        }
        return out
    }

    private companion object {
        const val FRAMES = 512
        const val PERIOD = 64.0

        /** Just under full scale, so an added gain clips. */
        const val PEAK = 32_000
    }
}
