// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The blend modes: their numberings in the file, and their arithmetic.
 *
 * The numbering is the format's and the arithmetic is vis_avs's own, transcribed
 * (BSD; see NOTICE.md): `blend.cpp` for the pixel forms, `e_effectlist.cpp` and
 * `e_buffersave.cpp` for the decodes. Where a value looks off by one from a
 * true average, that is the original's integer math.
 */
class AvsBlendTest {
    @Test
    fun `the outgoing numbering is the incoming one with adjacent pairs swapped`() {
        // e_effectlist.cpp load_legacy: output_blend_mode = (code & 0b111111) ^ 1
        assertEquals(AvsBlendMode.REPLACE, AvsBlendMode.incoming(1))
        assertEquals(AvsBlendMode.REPLACE, AvsBlendMode.outgoing(0))
        assertEquals(AvsBlendMode.IGNORE, AvsBlendMode.incoming(0))
        assertEquals(AvsBlendMode.IGNORE, AvsBlendMode.outgoing(1))
        assertEquals(AvsBlendMode.BUFFER, AvsBlendMode.incoming(12))
        assertEquals(AvsBlendMode.BUFFER, AvsBlendMode.outgoing(13))
    }

    @Test
    fun `minimum is reachable from every numbering that offers it`() {
        assertEquals(AvsBlendMode.MINIMUM, AvsBlendMode.incoming(13))
        assertEquals(AvsBlendMode.MINIMUM, AvsBlendMode.outgoing(12))
        assertEquals(AvsBlendMode.MINIMUM, AvsBlendMode.rendering(9))
        assertEquals(AvsBlendMode.MINIMUM, AvsBlendMode.buffered(8))
    }

    @Test
    fun `an unlisted code falls back to the original's default case`() {
        // e_effectlist.cpp: both blend switches end in `default: break` - nothing happens
        assertEquals(AvsBlendMode.IGNORE, AvsBlendMode.incoming(99))
        assertEquals(AvsBlendMode.IGNORE, AvsBlendMode.outgoing(14))
        // blend.h blend_default_1px and e_buffersave.cpp both default to replace
        assertEquals(AvsBlendMode.REPLACE, AvsBlendMode.rendering(10))
        assertEquals(AvsBlendMode.REPLACE, AvsBlendMode.buffered(12))
    }

    @Test
    fun `replace takes the source and ignore keeps the destination`() {
        assertEquals(RED, blend(AvsBlendMode.REPLACE, RED, GREEN))
        assertEquals(GREEN, blend(AvsBlendMode.IGNORE, RED, GREEN))
    }

    /** `(a >> 1) + (b >> 1)`: each side loses its low bit first, so 3 and 3 make 2, not 3. */
    @Test
    fun `fifty fifty halves each side before adding`() {
        assertEquals(rgb(127, 127, 0), blend(AvsBlendMode.FIFTY_FIFTY, rgb(255, 0, 0), rgb(1, 255, 0)))
        assertEquals(rgb(2, 0, 0), blend(AvsBlendMode.FIFTY_FIFTY, rgb(3, 0, 0), rgb(3, 0, 0)))
    }

    @Test
    fun `maximum takes the brighter channel from either side`() {
        assertEquals(rgb(255, 128, 9), blend(AvsBlendMode.MAXIMUM, rgb(255, 20, 9), rgb(3, 128, 0)))
    }

    @Test
    fun `minimum takes the darker channel from either side`() {
        assertEquals(rgb(3, 20, 0), blend(AvsBlendMode.MINIMUM, rgb(255, 20, 9), rgb(3, 128, 0)))
    }

    @Test
    fun `additive saturates`() {
        assertEquals(rgb(255, 60, 0), blend(AvsBlendMode.ADDITIVE, rgb(200, 20, 0), rgb(200, 40, 0)))
    }

    @Test
    fun `the two subtractions go opposite ways and both stop at black`() {
        assertEquals(rgb(0, 30, 0), blend(AvsBlendMode.SUB_DEST_SRC, rgb(200, 20, 0), rgb(100, 50, 0)))
        assertEquals(rgb(100, 0, 0), blend(AvsBlendMode.SUB_SRC_DEST, rgb(200, 20, 0), rgb(100, 50, 0)))
    }

    @Test
    fun `multiply scales one side by the other`() {
        assertEquals(rgb(255, 0, 0), blend(AvsBlendMode.MULTIPLY, rgb(255, 255, 255), rgb(255, 0, 0)))
        assertEquals(rgb(0, 0, 0), blend(AvsBlendMode.MULTIPLY, rgb(0, 0, 0), rgb(255, 255, 255)))
        assertEquals(rgb(128, 0, 0), blend(AvsBlendMode.MULTIPLY, rgb(255, 0, 0), rgb(128, 255, 0)))
    }

    @Test
    fun `xor combines the color channels and keeps the pixel opaque`() {
        val mixed = blend(AvsBlendMode.XOR, rgb(255, 0, 15), rgb(255, 0, 9))
        assertEquals(rgb(0, 0, 6), mixed)
    }

    @Test
    fun `adjustable weighs the two sides by the adjust value`() {
        assertEquals(rgb(255, 0, 0), blend(AvsBlendMode.ADJUSTABLE, RED, GREEN, adjust = 255))
        assertEquals(rgb(0, 255, 0), blend(AvsBlendMode.ADJUSTABLE, RED, GREEN, adjust = 0))
        // s*a/255 + d*(255-a)/255: the halfway weight is 128/127, not 128/128
        assertEquals(rgb(128, 127, 0), blend(AvsBlendMode.ADJUSTABLE, RED, GREEN, adjust = 128))
    }

    /** The original's two LUT reads floor separately, so two dim halves can sum to zero. */
    @Test
    fun `adjustable floors each side's share on its own`() {
        // 1*128/255 + 1*127/255 = 0 + 0, where a summed-then-floored form would give 1
        assertEquals(rgb(0, 0, 0), blend(AvsBlendMode.ADJUSTABLE, rgb(1, 1, 1), rgb(1, 1, 1), adjust = 128))
    }

    @Test
    fun `every other line takes the source on even rows only`() {
        val source = filled(2, 2, RED)
        val destination = filled(2, 2, GREEN)

        AvsBlend.blend(AvsBlendMode.EVERY_OTHER_LINE, source, destination)

        assertEquals(listOf(RED, RED, GREEN, GREEN), destination.pixels.toList())
    }

    /** blend.cpp: "The top-left pixel is from src", and the column shifts each row. */
    @Test
    fun `every other pixel is a checkerboard`() {
        val source = filled(3, 3, RED)
        val destination = filled(3, 3, GREEN)

        AvsBlend.blend(AvsBlendMode.EVERY_OTHER_PIXEL, source, destination)

        assertEquals(
            listOf(
                RED,
                GREEN,
                RED,
                GREEN,
                RED,
                GREEN,
                RED,
                GREEN,
                RED,
            ),
            destination.pixels.toList(),
        )
    }

    /** blend_buffer: the buffer's brightest channel is a per-pixel adjustable weight. */
    @Test
    fun `buffer blend weighs source against destination by the mask's brightest channel`() {
        // v = max(60, 0, 0) = 60: out = src*60/255 + dest*195/255
        assertEquals(
            rgb(60, 195, 0),
            AvsBlend.bufferPixel(rgb(255, 0, 0), rgb(0, 255, 0), mask = rgb(60, 0, 0)),
        )
        // a black mask keeps the destination, a white one takes the source
        assertEquals(GREEN, AvsBlend.bufferPixel(RED, GREEN, mask = rgb(0, 0, 0)))
        assertEquals(RED, AvsBlend.bufferPixel(RED, GREEN, mask = rgb(255, 255, 255)))
    }

    @Test
    fun `inverting the buffer blend swaps the two weights`() {
        assertEquals(
            rgb(195, 60, 0),
            AvsBlend.bufferPixel(rgb(255, 0, 0), rgb(0, 255, 0), mask = rgb(60, 0, 0), invert = true),
        )
        assertEquals(RED, AvsBlend.bufferPixel(RED, GREEN, mask = rgb(0, 0, 0), invert = true))
    }

    @Test
    fun `buffer blend runs over whole frames, each pixel weighed by its own mask pixel`() {
        val source = filled(2, 1, RED)
        val destination = filled(2, 1, GREEN)
        val mask = AvsFrame(2, 1, intArrayOf(rgb(0, 0, 0), rgb(255, 255, 255)))

        AvsBlend.bufferBlend(source, destination, mask)

        assertEquals(listOf(GREEN, RED), destination.pixels.toList())
    }

    @Test
    fun `blending frames of different sizes is refused`() {
        val thrown =
            runCatching { AvsBlend.blend(AvsBlendMode.REPLACE, filled(2, 2, RED), filled(3, 3, GREEN)) }
                .exceptionOrNull()

        assertEquals(IllegalArgumentException::class, thrown!!::class)
    }

    private fun blend(
        mode: AvsBlendMode,
        source: Int,
        destination: Int,
        adjust: Int = 255,
    ) = AvsBlend.pixel(mode, source, destination, adjust)

    private fun filled(
        width: Int,
        height: Int,
        colour: Int,
    ) = AvsFrame(width, height, IntArray(width * height) { colour })

    private fun rgb(
        r: Int,
        g: Int,
        b: Int,
    ) = AvsFrame.OPAQUE or (r shl 16) or (g shl 8) or b

    private companion object {
        val RED = 0xFFFF0000.toInt()
        val GREEN = 0xFF00FF00.toInt()
    }
}
