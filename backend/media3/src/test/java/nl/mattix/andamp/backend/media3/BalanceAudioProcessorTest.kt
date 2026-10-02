// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The balance slider as it reaches the audio: Winamp attenuates the side you
 * pan away from and leaves the other alone.
 */
class BalanceAudioProcessorTest {
    private fun stereo(
        frames: Int,
        left: Short,
        right: Short,
    ): ByteBuffer =
        ByteBuffer.allocate(frames * 2 * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(frames) {
                putShort(left)
                putShort(right)
            }
            flip()
        }

    private fun run(
        processor: BalanceAudioProcessor,
        input: ByteBuffer,
        channelCount: Int = 2,
    ): List<Short> {
        processor.configure(AudioProcessor.AudioFormat(44_100, channelCount, C.ENCODING_PCM_16BIT))
        processor.flush()
        processor.queueInput(input)
        val out = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        return buildList { while (out.remaining() >= 2) add(out.short) }
    }

    @Test
    fun `center passes the samples through untouched`() {
        val processor = BalanceAudioProcessor().apply { update(0f) }

        val samples = run(processor, stereo(4, 1000, -2000))

        assertEquals(listOf<Short>(1000, -2000, 1000, -2000, 1000, -2000, 1000, -2000), samples)
    }

    @Test
    fun `hard left silences the right channel and leaves the left alone`() {
        val processor = BalanceAudioProcessor().apply { update(-1f) }

        val samples = run(processor, stereo(2, 1000, 2000))

        assertEquals(listOf<Short>(1000, 0, 1000, 0), samples)
    }

    @Test
    fun `hard right silences the left channel`() {
        val processor = BalanceAudioProcessor().apply { update(1f) }

        val samples = run(processor, stereo(2, 1000, 2000))

        assertEquals(listOf<Short>(0, 2000, 0, 2000), samples)
    }

    @Test
    fun `half left halves the right channel, Winamp's linear law`() {
        val processor = BalanceAudioProcessor().apply { update(-0.5f) }

        val samples = run(processor, stereo(1, 1000, 2000))

        assertEquals(1000, samples[0].toInt())
        assertEquals(1000, samples[1].toInt())
    }

    @Test
    fun `mono has nothing to pan between and is left as it is`() {
        val processor = BalanceAudioProcessor().apply { update(-1f) }
        val mono =
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).apply {
                putShort(500)
                putShort(600)
                flip()
            }

        assertEquals(listOf<Short>(500, 600), run(processor, mono, channelCount = 1))
    }

    @Test
    fun `a format it cannot speak bypasses instead of mangling the stream`() {
        val processor = BalanceAudioProcessor()

        val format = processor.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_24BIT))

        assertEquals(AudioProcessor.AudioFormat.NOT_SET, format)
        assertTrue("a bypassed processor is not active", !processor.isActive)
    }

    @Test
    fun `full-scale samples cannot wrap when panned`() {
        val processor = BalanceAudioProcessor().apply { update(-0.25f) }

        val samples = run(processor, stereo(1, Short.MIN_VALUE, Short.MAX_VALUE))

        assertTrue("a negative peak stays negative: ${samples[0]}", samples[0] < 0)
        assertTrue("a positive peak stays positive: ${samples[1]}", samples[1] > 0)
    }
}
