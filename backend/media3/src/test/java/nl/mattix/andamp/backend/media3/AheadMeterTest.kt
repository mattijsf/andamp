// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import org.junit.Assert.assertEquals
import org.junit.Test

/** How far the sink runs ahead of the ear, from the buffers it takes and the position it reports. */
class AheadMeterTest {
    /** 16-bit stereo at 44.1 kHz, so one second is 176,400 bytes. */
    private fun meter() = AheadMeter().apply { configure(sampleRateHz = 44_100, bytesPerFrame = 4) }

    @Test
    fun `nothing is known before the sink has taken a buffer`() {
        val meter = meter()
        assertEquals(-1L, meter.aheadSamples(heardUs = 0))

        meter.offered(presentationTimeUs = 0, bytes = 176_400)
        assertEquals("offered is not taken", -1L, meter.aheadSamples(heardUs = 0))
    }

    @Test
    fun `it is the audio taken that has not been heard yet`() {
        val meter = meter()
        meter.offered(presentationTimeUs = 0, bytes = 176_400)
        meter.taken()

        assertEquals(44_100L, meter.aheadSamples(heardUs = 0))
        assertEquals(33_075L, meter.aheadSamples(heardUs = 250_000))
    }

    @Test
    fun `a buffer offered again with less in it still ends where it ended`() {
        val meter = meter()
        meter.offered(presentationTimeUs = 1_000_000, bytes = 176_400)
        // the sink took part of it and is offered the rest
        meter.offered(presentationTimeUs = 1_000_000, bytes = 4_000)
        meter.taken()

        assertEquals(44_100L, meter.aheadSamples(heardUs = 1_000_000))
    }

    @Test
    fun `each buffer taken moves the end on`() {
        val meter = meter()
        meter.offered(presentationTimeUs = 0, bytes = 176_400)
        meter.taken()
        meter.offered(presentationTimeUs = 1_000_000, bytes = 88_200)
        meter.taken()

        assertEquals(66_150L, meter.aheadSamples(heardUs = 0))
    }

    @Test
    fun `a position past what was taken is no distance, not a negative one`() {
        val meter = meter()
        meter.offered(presentationTimeUs = 0, bytes = 176_400)
        meter.taken()

        assertEquals(0L, meter.aheadSamples(heardUs = 2_000_000))
    }

    @Test
    fun `a seek forgets what was taken before it`() {
        val meter = meter()
        meter.offered(presentationTimeUs = 0, bytes = 176_400)
        meter.taken()

        meter.forget()

        assertEquals(-1L, meter.aheadSamples(heardUs = 0))
    }

    @Test
    fun `a stream that is not PCM is not measured`() {
        val meter = AheadMeter().apply { configure(sampleRateHz = 48_000, bytesPerFrame = 0) }
        meter.offered(presentationTimeUs = 0, bytes = 1_024)
        meter.taken()

        assertEquals(-1L, meter.aheadSamples(heardUs = 0))
    }
}
