// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import nl.mattix.andamp.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The two readouts filled from the stream: kbps and kHz. */
class StreamFormatTest {
    private val track = Track("t", "Artist", "Title", 60_000)

    @Test
    fun `a stream's figures land in the readouts`() {
        val patched = StreamFormat.patch(track, bitrateBps = 192_000, sampleRateHz = 44_100)

        assertEquals(192, patched.bitrateKbps)
        assertEquals(44, patched.sampleRateKhz)
    }

    @Test
    fun `a container that says nothing leaves the readouts alone`() {
        // Format.NO_VALUE is -1; a stream with no figure must not blank a
        // number the tags gave
        val tagged = track.copy(bitrateKbps = 128, sampleRateKhz = 44)

        val patched = StreamFormat.patch(tagged, bitrateBps = -1, sampleRateHz = -1)

        assertEquals(128, patched.bitrateKbps)
        assertEquals(44, patched.sampleRateKhz)
    }

    @Test
    fun `nothing known stays blank rather than reading zero`() {
        val patched = StreamFormat.patch(track, bitrateBps = 0, sampleRateHz = 0)

        assertNull(patched.bitrateKbps)
        assertNull(patched.sampleRateKhz)
    }

    @Test
    fun `a figure below its own unit reads as blank, not as zero`() {
        // 500 bps is not shown as 0 kbps
        val patched = StreamFormat.patch(track, bitrateBps = 500, sampleRateHz = 800)

        assertNull(patched.bitrateKbps)
        assertNull(patched.sampleRateKhz)
    }

    @Test
    fun `a rate between whole units reads as the nearest one`() {
        val patched = StreamFormat.patch(track, bitrateBps = 192_437, sampleRateHz = 22_050)

        assertEquals(192, patched.bitrateKbps)
        assertEquals(22, patched.sampleRateKhz)
    }

    @Test
    fun `an mp3 average all but on a rung reads as that rung`() {
        // what Media3 can report for a 192k file: a whole-file average
        // slightly off the frame header's rate
        val patched = StreamFormat.patch(track, 193_932, 44_100, StreamFormat.MPEG_AUDIO)

        assertEquals(192, patched.bitrateKbps)
    }

    @Test
    fun `an mp3 average between rungs keeps its own figure`() {
        // a VBR encode: 52 is the file's average, and 48 is the nearest rung
        val patched = StreamFormat.patch(track, 51_965, 44_100, StreamFormat.MPEG_AUDIO)

        assertEquals(52, patched.bitrateKbps)
    }

    @Test
    fun `a stream that is not an mp3 is never snapped to the mp3 ladder`() {
        // 193.9k of AAC reads as 194; the layer III ladder does not apply
        val patched = StreamFormat.patch(track, 193_932, 44_100, "audio/mp4a-latm")

        assertEquals(194, patched.bitrateKbps)
    }
}
