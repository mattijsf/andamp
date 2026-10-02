// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A codec's output becoming 16-bit stereo, in plain JUnit. */
class PcmShapeTest {
    @Test
    fun `a mono recording is duplicated to both channels`() {
        val mono = shortArrayOf(100, -200, 300)
        val into = ShortArray(6)

        val written = PcmShape.fold(mono, mono.size, 1, into)

        assertEquals(6, written)
        assertArrayEquals(shortArrayOf(100, 100, -200, -200, 300, 300), into)
    }

    @Test
    fun `stereo passes through untouched`() {
        val stereo = shortArrayOf(1, 2, 3, 4, 5, 6)
        val into = ShortArray(6)

        val written = PcmShape.fold(stereo, stereo.size, 2, into)

        assertEquals(6, written)
        assertArrayEquals(stereo, into)
    }

    @Test
    fun `more than two channels keep the front pair`() {
        // two frames of 5.1: front left, front right, center, LFE, and the two surrounds
        val surround = shortArrayOf(10, 20, 30, 40, 50, 60) + shortArrayOf(11, 21, 31, 41, 51, 61)
        val into = ShortArray(4)

        val written = PcmShape.fold(surround, surround.size, 6, into)

        assertEquals(4, written)
        assertArrayEquals(shortArrayOf(10, 20, 11, 21), into)
    }

    @Test
    fun `floats become 16-bit samples`() {
        val into = ShortArray(3)

        val written = PcmShape.samples(floats(0f, 0.25f, -0.25f), FLOAT_BYTES * 3, PcmShape.ENCODING_PCM_FLOAT, into)

        assertEquals(3, written)
        assertEquals(0, into[0].toInt())
        assertEquals(8_192, into[1].toInt())
        assertEquals(-8_192, into[2].toInt())
    }

    @Test
    fun `floats beyond full scale are clipped`() {
        val into = ShortArray(4)

        // a lossy format's decoder can answer above 1.0; unclipped, the conversion would
        // wrap
        PcmShape.samples(floats(1f, -1f, 1.5f, -1.5f), FLOAT_BYTES * 4, PcmShape.ENCODING_PCM_FLOAT, into)

        assertEquals(Short.MAX_VALUE.toInt(), into[0].toInt())
        assertEquals(-Short.MAX_VALUE.toInt(), into[1].toInt())
        assertEquals(Short.MAX_VALUE.toInt(), into[2].toInt())
        assertEquals(-Short.MAX_VALUE.toInt(), into[3].toInt())
    }

    @Test
    fun `plain 16-bit samples are read little endian`() {
        val source =
            ByteBuffer
                .allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(-2)
                .putShort(1234)
        source.flip()
        val into = ShortArray(2)

        val written = PcmShape.samples(source, 4, PcmShape.ENCODING_PCM_16BIT, into)

        assertEquals(2, written)
        assertArrayEquals(shortArrayOf(-2, 1234), into)
    }

    @Test
    fun `a wider codec is narrowed to the top 16 bits`() {
        // one packed 24-bit sample of 0x123456 and one 32-bit sample of 0x12345600
        val packed =
            ByteBuffer
                .allocate(3)
                .put(0x56.toByte())
                .put(0x34.toByte())
                .put(0x12.toByte())
        packed.flip()
        val wide = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x12345600)
        wide.flip()
        val into = ShortArray(1)

        PcmShape.samples(packed, 3, PcmShape.ENCODING_PCM_24BIT_PACKED, into)
        assertEquals(0x1234, into[0].toInt())

        PcmShape.samples(wide, 4, PcmShape.ENCODING_PCM_32BIT, into)
        assertEquals(0x1234, into[0].toInt())
    }

    @Test
    fun `samples leave as little endian bytes`() {
        val into = ByteArray(4)

        val written = PcmShape.bytes(shortArrayOf(0x0102, -1), 2, into)

        assertEquals(4, written)
        assertArrayEquals(byteArrayOf(0x02, 0x01, -1, -1), into)
    }

    private fun floats(vararg values: Float): ByteBuffer {
        val buffer = ByteBuffer.allocate(values.size * FLOAT_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (value in values) buffer.putFloat(value)
        buffer.flip()
        return buffer
    }

    private companion object {
        const val FLOAT_BYTES = 4
    }
}
