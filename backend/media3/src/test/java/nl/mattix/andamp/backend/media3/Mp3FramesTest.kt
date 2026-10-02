// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the bitrate off the frames themselves: constant for a CBR encode,
 * changing frame by frame for a VBR one.
 */
class Mp3FramesTest {
    private fun scan(name: String): Mp3Frames? = javaClass.classLoader!!.getResourceAsStream(name)!!.use { Mp3Frames.scan(it) }

    @Test
    fun `a constant bitrate file reads the same rate all the way through`() {
        val frames = requireNotNull(scan("tone-cbr-192.mp3"))

        assertTrue("a 192k encode reads as constant", frames.constant)
        assertEquals(192, frames.kbpsAt(0))
        assertEquals(192, frames.kbpsAt(1_500))
        assertEquals(44_100, frames.sampleRateHz)
    }

    @Test
    fun `a variable bitrate file changes rate as it plays`() {
        val frames = requireNotNull(scan("tone-vbr.mp3"))

        assertTrue("a VBR encode reads as variable", !frames.constant)
        // every rate it reports is a layer III bitrate
        val rungs = setOf(8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 192, 224, 256, 320)
        (0 until frames.frameCount).forEach { at ->
            val kbps = frames.kbpsAt(at.toLong() * frames.frameDurationUs / 1000)
            assertTrue("frame $at reads a layer III bitrate: $kbps", kbps in rungs)
        }
    }

    @Test
    fun `a layer III frame lasts 26 milliseconds at 44 kHz`() {
        val frames = requireNotNull(scan("tone-cbr-192.mp3"))

        // 1152 samples at 44100 Hz; the readout indexes the table by this
        assertEquals(1152 * 1_000_000 / 44_100, frames.frameDurationUs)
    }

    @Test
    fun `the encoder's tag frame is not counted as audio`() {
        // LAME writes a Xing/Info frame in front of the audio
        val cbr = requireNotNull(scan("tone-cbr-192.mp3"))

        assertEquals("the tag frame is not counted", 192, cbr.kbpsAt(0))
    }

    @Test
    fun `a position past the end reads the last frame and a negative one reads nothing`() {
        val frames = requireNotNull(scan("tone-cbr-192.mp3"))

        assertEquals(192, frames.kbpsAt(Long.MAX_VALUE / 2))
        assertNull(frames.kbpsAt(-1))
    }

    @Test
    fun `something that is not an mp3 reads as nothing`() {
        val bytes = ByteArray(4096) { it.toByte() }

        assertNull(Mp3Frames.scan(bytes.inputStream()))
    }

    @Test
    fun `a constant bitrate file is indexed from its first frames`() {
        // LAME writes "Info" for a constant stream and "Xing" for a variable
        // one, so the walk stops after the first audio frame
        val counted = Counting(javaClass.classLoader!!.getResourceAsStream("tone-cbr-192.mp3")!!)

        val frames = counted.use { requireNotNull(Mp3Frames.scan(it)) }

        assertTrue("a CBR file reads as constant", frames.constant)
        assertEquals(192, frames.kbpsAt(0))
        assertTrue("only the first frames are read: ${counted.read} bytes", counted.read < QUICK_READ_BYTES)
    }

    @Test
    fun `a variable bitrate file is walked frame by frame`() {
        val counted = Counting(javaClass.classLoader!!.getResourceAsStream("tone-vbr.mp3")!!)

        val frames = counted.use { requireNotNull(Mp3Frames.scan(it)) }

        assertTrue("a VBR file reads as variable", !frames.constant)
        assertTrue("more than ten frames are walked", frames.frameCount > 10)
    }

    @Test
    fun `a file without an ID3 tag is read from its first frame`() {
        // one 128k frame, then 64k frames, with no tag in front
        val bytes = frame(RUNG_128, BYTES_128) + frame(RUNG_64, BYTES_64) + frame(RUNG_64, BYTES_64)

        val frames = requireNotNull(Mp3Frames.scan(bytes.inputStream()))

        assertEquals(3, frames.frameCount)
        assertEquals(128, frames.kbpsAt(0))
        assertEquals(64, frames.kbpsAt(frames.frameDurationUs / 1000L + 1))
    }

    @Test
    fun `a constant bitrate file without an ID3 tag is indexed from its first frames`() {
        val tagged = javaClass.classLoader!!.getResourceAsStream("tone-cbr-192.mp3")!!.use { it.readBytes() }
        val counted = Counting(tagged.copyOfRange(id3Bytes(tagged), tagged.size).inputStream())

        val frames = counted.use { requireNotNull(Mp3Frames.scan(it)) }

        assertTrue("a CBR file reads as constant", frames.constant)
        assertEquals(192, frames.kbpsAt(0))
        assertTrue("only the first frames are read: ${counted.read} bytes", counted.read < QUICK_READ_BYTES)
    }

    /** An MPEG-1 layer III frame at 44.1 kHz with silent content. */
    private fun frame(
        rung: Int,
        bytes: Int,
    ): ByteArray =
        ByteArray(bytes).also {
            it[0] = 0xFF.toByte()
            it[1] = 0xFB.toByte()
            it[2] = (rung shl 4).toByte()
        }

    /** The length of the ID3v2 tag at the front of [file]: ten header bytes and a syncsafe size. */
    private fun id3Bytes(file: ByteArray): Int = 10 + (6..9).fold(0) { size, at -> size shl 7 or (file[at].toInt() and 0x7F) }

    /** Counts the bytes a scan reads from the stream. */
    private class Counting(
        private val inner: java.io.InputStream,
    ) : java.io.InputStream() {
        var read = 0L
            private set

        override fun read(): Int = inner.read().also { if (it >= 0) read++ }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = inner.read(b, off, len).also { if (it > 0) read += it }

        override fun close() = inner.close()
    }

    private companion object {
        /** An upper bound for reading the tag frame and the first audio frame. */
        const val QUICK_READ_BYTES = 64 * 1024

        /** Bitrate index and frame length of a 128k and a 64k frame at 44.1 kHz. */
        const val RUNG_128 = 9
        const val BYTES_128 = 417
        const val RUNG_64 = 5
        const val BYTES_64 = 208
    }
}
