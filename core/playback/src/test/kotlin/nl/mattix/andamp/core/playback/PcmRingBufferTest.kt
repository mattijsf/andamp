// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmRingBufferTest {
    @Test
    fun `readLatest returns false until enough audio has flowed`() {
        val ring = PcmRingBuffer(capacity = 8)
        val out = FloatArray(4)
        assertFalse(ring.readLatest(out))
        ring.write(floatArrayOf(1f, 2f), 2)
        assertFalse(ring.readLatest(out))
        ring.write(floatArrayOf(3f, 4f), 2)
        assertTrue(ring.readLatest(out))
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f), out, 0f)
    }

    @Test
    fun `readLatest returns the newest window across wraparound`() {
        val ring = PcmRingBuffer(capacity = 8)
        val samples = FloatArray(20) { it.toFloat() }
        ring.write(samples, 20)
        val out = FloatArray(6)
        assertTrue(ring.readLatest(out))
        assertArrayEquals(floatArrayOf(14f, 15f, 16f, 17f, 18f, 19f), out, 0f)
    }

    @Test
    fun `sample rate follows format changes`() {
        val ring = PcmRingBuffer()
        assertEquals(0, ring.sampleRateHz)
        ring.onFormatChanged(22050)
        assertEquals(22050, ring.sampleRateHz)
    }
}
