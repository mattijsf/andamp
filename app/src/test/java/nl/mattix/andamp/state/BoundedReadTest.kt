// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream

/**
 * [readAtMost] at its ceiling: a stream of that many bytes is read whole, and one byte
 * more is refused.
 */
class BoundedReadTest {
    @Test
    fun `a stream of exactly the ceiling is read whole`() {
        val bytes = ByteArray(MAX) { it.toByte() }

        assertArrayEquals(bytes, bytes.inputStream().readAtMost(MAX))
    }

    @Test
    fun `one byte past the ceiling throws StreamTooLarge, an IOException`() {
        val refused = assertThrows(IOException::class.java) { ByteArray(MAX + 1).inputStream().readAtMost(MAX) }

        assertTrue("an oversized stream throws StreamTooLarge", refused is StreamTooLarge)
        assertEquals("stream exceeds $MAX bytes", refused.message)
    }

    @Test
    fun `an empty stream reads as no bytes`() {
        assertEquals(0, ByteArray(0).inputStream().readAtMost(MAX).size)
    }

    @Test
    fun `a stream that arrives a byte at a time is counted across every read`() {
        // the ceiling applies to the total across reads
        val fits = Trickle(MAX).readAtMost(MAX)
        assertEquals(MAX, fits.size)

        assertThrows(StreamTooLarge::class.java) { Trickle(MAX + 1).readAtMost(MAX) }
    }

    @Test
    fun `a stream that never ends is refused within a read of the ceiling`() {
        var served = 0L
        val endless =
            object : InputStream() {
                override fun read(): Int {
                    served++
                    return 0
                }

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    served += len
                    return len
                }
            }

        assertThrows(StreamTooLarge::class.java) { endless.readAtMost(MAX) }
        assertTrue("the read stops within one read past the ceiling", served <= MAX + 64 * 1024)
    }

    /** [size] bytes, handed over one per read however many were asked for. */
    private class Trickle(
        private val size: Int,
    ) : InputStream() {
        private var sent = 0

        override fun read(): Int = if (sent < size) (sent++ and 0xFF) else -1

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (len == 0) return 0
            val next = read()
            if (next < 0) return -1
            b[off] = next.toByte()
            return 1
        }
    }

    private companion object {
        /** More than one of the reader's 16 KiB chunks, so reaching it takes several reads. */
        const val MAX = 40_000
    }
}
