// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common.audio

import nl.mattix.andamp.core.playback.PcmProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** [PcmShape] and [PcmRate] wired together, on the buffer shapes a codec hands over. */
class PcmShaperTest {
    /**
     * What comes before the opening position is measured in the codec's own format: a
     * quarter of a second of 48 kHz stereo floats is twelve thousand frames of two
     * four-byte samples.
     */
    @Test
    fun `what a codec decoded before the opening position is measured in its own frames`() {
        val shaper = PcmShaper()
        shaper.reshape(sampleRate = 48_000, channelCount = 2, pcmEncoding = PcmShape.ENCODING_PCM_FLOAT)

        assertEquals(12_000 * 2 * 4, shaper.bytesBefore(startUs = 1_000_000, untilUs = 1_250_000, bytes = 1_000_000))
        assertEquals(
            "a buffer wholly before the opening position is cut in whole frames",
            96,
            shaper.bytesBefore(0, 1_000_000, 99),
        )
        assertEquals("a buffer at or after the opening position is not cut", 0, shaper.bytesBefore(2_000_000, 1_000_000, 4096))
    }

    @Test
    fun `a mono 48 kHz file arrives as stereo at 44 point 1`() {
        val shaper = PcmShaper()
        shaper.reshape(sampleRate = 48_000, channelCount = 1, pcmEncoding = PcmShape.ENCODING_PCM_16BIT)

        val bytes = shaper.shape(shorts(BLOCK_FRAMES), BLOCK_FRAMES * Short.SIZE_BYTES)

        // 480 mono frames at 48 kHz are 441 stereo frames at 44.1 kHz, and a
        // frame is four bytes
        assertEquals(441 * PcmProvider.BYTES_PER_FRAME, bytes)
    }

    @Test
    fun `a file already in the provider's format keeps its length`() {
        val shaper = PcmShaper()
        shaper.reshape(
            sampleRate = PcmProvider.SAMPLE_RATE_HZ,
            channelCount = PcmProvider.CHANNELS,
            pcmEncoding = PcmShape.ENCODING_PCM_16BIT,
        )
        val samples = BLOCK_FRAMES * PcmProvider.CHANNELS

        val bytes = shaper.shape(shorts(samples), samples * Short.SIZE_BYTES)

        assertEquals(BLOCK_FRAMES * PcmProvider.BYTES_PER_FRAME, bytes)
    }

    @Test
    fun `48 kHz stereo floats arrive at 44 point 1`() {
        val shaper = PcmShaper()
        shaper.reshape(sampleRate = 48_000, channelCount = 2, pcmEncoding = PcmShape.ENCODING_PCM_FLOAT)
        val samples = BLOCK_FRAMES * PcmProvider.CHANNELS
        val source = ByteBuffer.allocate(samples * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        repeat(samples) { source.putFloat(0.5f) }
        source.flip()

        val bytes = shaper.shape(source, samples * Float.SIZE_BYTES)

        assertEquals(441 * PcmProvider.BYTES_PER_FRAME, bytes)
    }

    @Test
    fun `the codec's own format wins over the track's`() {
        val shaper = PcmShaper()
        shaper.reshape(sampleRate = 44_100, channelCount = 2, pcmEncoding = PcmShape.ENCODING_PCM_16BIT)
        shaper.reshape(sampleRate = 48_000, channelCount = 1, pcmEncoding = PcmShape.ENCODING_PCM_16BIT)

        val bytes = shaper.shape(shorts(BLOCK_FRAMES), BLOCK_FRAMES * Short.SIZE_BYTES)

        // the second reshape, which is the codec's, is the one applied
        assertEquals(441 * PcmProvider.BYTES_PER_FRAME, bytes)
    }

    @Test
    fun `a steady stream reuses its output array`() {
        val shaper = PcmShaper()
        shaper.reshape(sampleRate = 48_000, channelCount = 1, pcmEncoding = PcmShape.ENCODING_PCM_16BIT)
        shaper.shape(shorts(BLOCK_FRAMES), BLOCK_FRAMES * Short.SIZE_BYTES)

        val first = shaper.shaped
        repeat(BLOCKS) { shaper.shape(shorts(BLOCK_FRAMES), BLOCK_FRAMES * Short.SIZE_BYTES) }

        assertSame(first, shaper.shaped)
    }

    @Test
    fun `an empty buffer shapes to nothing`() {
        val shaper = PcmShaper()
        shaper.reshape(sampleRate = 48_000, channelCount = 2, pcmEncoding = PcmShape.ENCODING_PCM_16BIT)

        assertEquals(0, shaper.shape(shorts(0), 0))
    }

    /** [count] 16-bit samples of a quiet ramp, laid out the way a codec lays them out. */
    private fun shorts(count: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(count * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        repeat(count) { index -> buffer.putShort((index % 1_000).toShort()) }
        buffer.flip()
        return buffer
    }

    private companion object {
        const val BLOCK_FRAMES = 480
        const val BLOCKS = 20
    }
}
