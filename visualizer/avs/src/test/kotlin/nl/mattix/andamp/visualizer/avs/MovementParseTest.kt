// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Movement's body without the evaluator: which effect, which coordinates,
 * which flags. The renderer needs a device; the parse does not.
 */
class MovementParseTest {
    @Test
    fun `a custom script's coordinates come from the file, polar by default`() {
        val parsed = MovementRenderer.parse(custom("d = d * 0.5;", coordinates = 0))!!

        assertEquals(AvsCoordinates.POLAR, parsed.effect.coordinates)
        assertEquals("d = d * 0.5;", parsed.effect.code)
    }

    @Test
    fun `a custom script marked cartesian in the file is cartesian`() {
        val parsed = MovementRenderer.parse(custom("x = x * 0.5;", coordinates = 1))!!

        assertEquals(AvsCoordinates.CARTESIAN, parsed.effect.coordinates)
    }

    /**
     * The oldest presets store custom code as a fixed 256 bytes and mark
     * cartesian with a "!rect " prefix on the code itself; the prefix is not
     * code, and the settings still start 260 bytes in.
     */
    @Test
    fun `an ancient rect prefix is stripped and the settings stay aligned`() {
        val code = "x = x * 0.5;"
        val block = ("!rect " + code).toByteArray(Charsets.ISO_8859_1).copyOf(256)
        val body =
            int32(MovementEffects.CUSTOM) + block +
                int32(1) + int32(0) + int32(1) + int32(0) + int32(1)

        val parsed = MovementRenderer.parse(body)!!

        assertEquals(code, parsed.effect.code)
        assertEquals(AvsCoordinates.CARTESIAN, parsed.effect.coordinates)
        assertEquals("the blend int right after the code block is read", true, parsed.fiftyFifty)
        assertEquals("the wrap int at the tail is read", true, parsed.wrap)
    }

    @Test
    fun `a built-in by number carries its table's coordinates and script`() {
        val body = int32(3) + int32(0) + int32(0) + int32(0) + int32(0) + int32(0)

        val parsed = MovementRenderer.parse(body)!!

        assertEquals(MovementEffects[3]!!.code, parsed.effect.code)
        assertEquals(AvsCoordinates.POLAR, parsed.effect.coordinates)
    }

    @Test
    fun `a body that runs out of settings is refused`() {
        assertNull(MovementRenderer.parse(int32(3) + int32(0)))
    }

    /** For a length near the top of an Int, an end offset would wrap around. */
    @Test
    fun `a custom script with an impossible length is refused`() {
        listOf(Int.MAX_VALUE, Int.MAX_VALUE - 5, 0x7FFFFFF0, -1).forEach { length ->
            val body = int32(MovementEffects.CUSTOM) + byteArrayOf(1) + int32(length) + ByteArray(64)

            assertNull("length $length", MovementRenderer.parse(body))
        }
    }

    /** As AVS writes it: version marker, length-counted code with its NUL, five setting ints. */
    private fun custom(
        code: String,
        coordinates: Int,
    ): ByteArray {
        val text = code.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0)
        return int32(MovementEffects.CUSTOM) +
            byteArrayOf(1) +
            int32(text.size) +
            text +
            int32(0) + int32(0) + int32(coordinates) + int32(0) + int32(0)
    }

    private fun int32(value: Int) =
        byteArrayOf(
            value.toByte(),
            (value shr 8).toByte(),
            (value shr 16).toByte(),
            (value shr 24).toByte(),
        )
}
