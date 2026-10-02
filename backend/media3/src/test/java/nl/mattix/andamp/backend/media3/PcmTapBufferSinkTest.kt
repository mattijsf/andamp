// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import androidx.media3.common.C
import nl.mattix.andamp.core.playback.PcmRingBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmTapBufferSinkTest {
    @Test
    fun `16-bit stereo downmixes to mono floats`() {
        val ring = PcmRingBuffer(capacity = 16)
        val sink = PcmTapBufferSink(ring)
        sink.flush(44100, 2, C.ENCODING_PCM_16BIT)

        // two frames: (L=16384, R=0) and (L=-32768, R=-32768)
        val buffer =
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
                putShort(16384)
                putShort(0)
                putShort(Short.MIN_VALUE)
                putShort(Short.MIN_VALUE)
                flip()
            }
        sink.handleBuffer(buffer)

        val out = FloatArray(2)
        assertTrue(ring.readLatest(out))
        assertArrayEquals(floatArrayOf(0.25f, -1f), out, 0.001f)
        assertEquals(44100, ring.sampleRateHz)
    }

    @Test
    fun `unsupported encodings are ignored instead of corrupting the ring`() {
        val ring = PcmRingBuffer(capacity = 16)
        val sink = PcmTapBufferSink(ring)
        sink.flush(44100, 2, C.ENCODING_PCM_24BIT)
        sink.handleBuffer(ByteBuffer.allocate(12))
        assertTrue(!ring.readLatest(FloatArray(1)))
    }
}
