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

    /** A ring at 44.1 kHz on a clock the test moves. */
    private class Timed {
        var nanos = 5_000_000_000L
        val ring = PcmRingBuffer(capacity = 8) { nanos }.apply { onFormatChanged(44_100) }

        fun write(samples: Int) = ring.write(FloatArray(samples), samples)

        fun pass(millis: Long) {
            nanos += millis * 1_000_000
        }
    }

    @Test
    fun `how far ahead of the ear it runs is unknown until it is told`() {
        val timed = Timed()
        timed.write(44_100)

        assertEquals(0L, timed.ring.aheadSamples)
    }

    @Test
    fun `the distance shrinks as the buffered audio plays and grows with each burst`() {
        val timed = Timed()
        timed.write(44_100)
        timed.ring.reportAhead(33_075) // three quarters of a second is still to be heard
        assertEquals(33_075L, timed.ring.aheadSamples)

        timed.pass(250)
        assertEquals("a quarter second has played", 22_050L, timed.ring.aheadSamples)

        timed.write(13_230) // the player writes its next burst
        assertEquals(35_280L, timed.ring.aheadSamples)
    }

    @Test
    fun `the distance is never negative`() {
        val timed = Timed()
        timed.write(44_100)
        timed.ring.reportAhead(4_410)

        timed.pass(500)

        assertEquals(0L, timed.ring.aheadSamples)
    }

    @Test
    fun `a pause holds the distance where it was`() {
        val timed = Timed()
        timed.write(44_100)
        timed.ring.reportAhead(33_075)
        timed.pass(100)

        timed.ring.holdAhead()
        timed.pass(60_000)

        assertEquals(28_665L, timed.ring.aheadSamples)
    }

    @Test
    fun `without a report for seconds the distance stands at the last one`() {
        val timed = Timed()
        timed.write(44_100)
        timed.ring.reportAhead(33_075)

        timed.pass(5_000)

        assertEquals(33_075L, timed.ring.aheadSamples)
    }

    @Test
    fun `a report after a pause sets it moving again`() {
        val timed = Timed()
        timed.write(44_100)
        timed.ring.reportAhead(33_075)
        timed.ring.holdAhead()

        timed.ring.reportAhead(30_000)
        timed.pass(100)

        assertEquals(25_590L, timed.ring.aheadSamples)
    }

    @Test
    fun `a window a second and a half behind the write head is still there at 96 kHz`() {
        val ring = PcmRingBuffer()
        val second = FloatArray(96_000) { 0.25f }
        repeat(5) { ring.write(second, second.size) }

        val out = FloatArray(2_048)
        assertTrue(ring.readAt(ring.writtenSamples - 144_000, out))
    }
}
