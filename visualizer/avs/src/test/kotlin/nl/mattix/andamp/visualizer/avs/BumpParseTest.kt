// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bump's body without the evaluator. The lighting itself needs a device. */
class BumpParseTest {
    @Test
    fun `settings and the three pieces of code are read in file order`() {
        val body =
            int32(1) + int32(1) + int32(20) + int32(40) + int32(90) + int32(0) + int32(1) +
                text("x=0.5;") + text("y=0.1;") + text("t=0;") +
                int32(1) + int32(1) + int32(0) + int32(3)

        val parsed = BumpRenderer.parse(body)

        assertEquals("x=0.5;", parsed.perFrame)
        assertEquals("y=0.1;", parsed.onBeat)
        assertEquals("t=0;", parsed.init)
        with(parsed.settings) {
            assertTrue(enabled)
            assertTrue(onBeat)
            assertEquals(20, beatFrames)
            assertEquals(40, depth)
            assertEquals(90, beatDepth)
            assertEquals(AvsBlendMode.FIFTY_FIFTY, blend)
            assertTrue(showLight)
            assertTrue(invert)
            assertFalse("the light is placed as a fraction", percentCoordinates)
            assertEquals(3, buffer)
        }
    }

    /** A preset from before the coordinates setting gives the light's place in percent. */
    @Test
    fun `a body that ends after the invert setting places the light in percent`() {
        val body =
            int32(1) + int32(0) + int32(15) + int32(30) + int32(100) + int32(1) + int32(1) +
                text("x=50;") + text("") + text("") + int32(0) + int32(0)

        val parsed = BumpRenderer.parse(body)

        assertTrue(parsed.settings.percentCoordinates)
        assertEquals(0, parsed.settings.buffer)
        assertEquals("additive wins over fifty-fifty when both are set", AvsBlendMode.ADDITIVE, parsed.settings.blend)
    }

    @Test
    fun `an empty body is a Bump with no code and the depths AVS starts with`() {
        val parsed = BumpRenderer.parse(ByteArray(0))

        assertEquals("", parsed.perFrame)
        assertEquals(30, parsed.settings.depth)
        assertEquals(100, parsed.settings.beatDepth)
        assertTrue(parsed.settings.enabled)
    }

    @Test
    fun `code with an impossible length reads as no code and the settings after it are still read`() {
        val body = ByteArray(28) + int32(Int.MAX_VALUE) + int32(0) + int32(0) + int32(1)

        val parsed = BumpRenderer.parse(body)

        assertEquals("", parsed.perFrame)
        assertTrue(parsed.settings.showLight)
    }

    private fun text(code: String): ByteArray {
        val bytes = code.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0)
        return int32(bytes.size) + bytes
    }

    private fun int32(value: Int) =
        byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())
}
