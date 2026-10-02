// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs.author

import nl.mattix.andamp.visualizer.avs.BodyReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The primitives, each checked against the bytes the module's readers expect
 * or read back through `BodyReader`.
 */
class BodyWriterTest {
    @Test
    fun `an int32 is four bytes, least significant first`() {
        val bytes = BodyWriter().int32(0x04030201).toByteArray()

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), bytes)
    }

    @Test
    fun `a negative int32 carries its sign through all four bytes`() {
        val written = BodyWriter().int32(-2).toByteArray()

        assertEquals(-2, BodyReader(written).int32())
    }

    @Test
    fun `a float is its IEEE bits, and BodyReader reads it back`() {
        val written = BodyWriter().float(6.5f).toByteArray()

        assertEquals(6.5f, BodyReader(written).float(), 0f)
    }

    @Test
    fun `a color keeps its channels and zeroes the byte every reader ignores`() {
        val bytes = BodyWriter().colour(0x7F123456).toByteArray()

        // on-disk order BB GG RR 00
        assertArrayEquals(byteArrayOf(0x56, 0x34, 0x12, 0x00), bytes)
    }

    @Test
    fun `a color list is a count and then each color`() {
        val bytes = BodyWriter().colourList(listOf(0xFF0000, 0x00FF00)).toByteArray()

        val expected =
            BodyWriter()
                .int32(2)
                .int32(0xFF0000)
                .int32(0x00FF00)
                .toByteArray()
        assertArrayEquals(expected, bytes)
    }

    @Test
    fun `a seventeenth color is refused`() {
        val tooMany = List(17) { 0xFFFFFF }

        assertThrows(IllegalArgumentException::class.java) { BodyWriter().colourList(tooMany) }
    }

    @Test
    fun `a sized string counts the trailing null it writes`() {
        val bytes = BodyWriter().sizedString("n=0;").toByteArray()

        val expected = BodyWriter().int32(5).toByteArray() + "n=0;".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0)
        assertArrayEquals(expected, bytes)
    }

    @Test
    fun `a fixed string pads its block with nulls`() {
        val bytes = BodyWriter().fixedString("Channel Shift", 16).toByteArray()

        val expected = "Channel Shift".toByteArray(Charsets.ISO_8859_1) + ByteArray(3)
        assertArrayEquals(expected, bytes)
    }

    @Test
    fun `text longer than its fixed block is refused`() {
        assertThrows(IllegalArgumentException::class.java) { BodyWriter().fixedString("Channel Shift", 4) }
    }

    @Test
    fun `fields land in the order they were written`() {
        val written =
            BodyWriter()
                .byte(1)
                .int32(576)
                .float(0.5f)
                .toByteArray()

        val reader = BodyReader(written)
        assertEquals(1, reader.byte())
        assertEquals(576, reader.int32())
        assertEquals(0.5f, reader.float(), 0f)
    }
}
