// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The color-only components that need no evaluator ([ColorModifierRenderer]
 * runs ns-eel and is tested on a device), the shared body fields
 * ([shortBlend], [pairBlend], [colourList]) and the color walk
 * ([ColourCycle]).
 *
 * Each renderer is tested for what it does to pixels. Where a value looks
 * arbitrary it is the arithmetic of the vis_avs source.
 */
class ColorComponentsTest {
    @Test
    fun `the short blend maps its three codes and falls back to replace`() {
        assertEquals(AvsBlendMode.REPLACE, shortBlend(0))
        assertEquals(AvsBlendMode.ADDITIVE, shortBlend(1))
        assertEquals(AvsBlendMode.FIFTY_FIFTY, shortBlend(2))
        assertEquals("an unknown code reads as replace", AvsBlendMode.REPLACE, shortBlend(7))
    }

    @Test
    fun `the four-value short blend keeps three for the preset's own mode`() {
        assertNull(shortBlendOrDefault(3))
        assertEquals(AvsBlendMode.ADDITIVE, shortBlendOrDefault(1))
    }

    @Test
    fun `the pair blend maps its two ints to the modes the format documents`() {
        assertEquals(AvsBlendMode.REPLACE, BodyReader(int32(0) + int32(0)).pairBlend())
        assertEquals(AvsBlendMode.ADDITIVE, BodyReader(int32(1) + int32(0)).pairBlend())
        assertEquals(AvsBlendMode.FIFTY_FIFTY, BodyReader(int32(0) + int32(1)).pairBlend())
        assertNull("a low word of two is the preset's default", BodyReader(int32(2) + int32(0)).pairBlend())
    }

    @Test
    fun `a color list is a count and then that many colors, made opaque`() {
        val colours = BodyReader(int32(2) + int32(0xFF0000) + int32(0x0000FF)).colourList()

        assertEquals(listOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()), colours)
    }

    @Test
    fun `a color list of nothing is empty`() {
        assertTrue(BodyReader(int32(0)).colourList().isEmpty())
    }

    @Test
    fun `a color count past sixteen reads as an empty list`() {
        assertTrue(BodyReader(int32(999)).colourList().isEmpty())
    }

    /** As in AVS, a lone color is dimmed by 63/64 like any other stop. */
    @Test
    fun `a single color renders at sixty-three parts in sixty-four of itself`() {
        val cycle = ColourCycle(listOf(RED))

        // the walk always mixes toward the next stop, and with one color the
        // mix never completes: 255 * 63/64 = 251
        repeat(5) { assertEquals(0xFFFB0000.toInt(), cycle.next()) }
    }

    /** e_superscope.cpp advances color_pos before using it and mixes with (63-r)/64 + r/64. */
    @Test
    fun `the color walk is one step in on its first frame`() {
        val cycle = ColourCycle(listOf(BLACK, WHITE))

        // 255 * 1 / 64 = 3
        assertEquals(0xFF030303.toInt(), cycle.next())
    }

    @Test
    fun `two colors fade through the middle`() {
        val cycle = ColourCycle(listOf(BLACK, WHITE))

        // sixty-four steps per color: a quarter of the way after sixteen
        repeat(15) { cycle.next() }
        assertEquals(0xFF3F3F3F.toInt(), cycle.next())
        repeat(15) { cycle.next() }
        assertEquals("the walk is halfway after thirty-two steps", 0xFF7F7F7F.toInt(), cycle.next())
    }

    @Test
    fun `an empty color list cycles opaque white`() {
        assertEquals(WHITE, ColourCycle(emptyList()).next())
    }

    @Test
    fun `reducing to two levels keeps only the top bit of each channel`() {
        val two = ColorReductionRenderer.read(ByteArray(UNUSED_PATH) + int32(1))!!

        assertEquals("a mid gray has no top bit and collapses to black", BLACK, two.run(0xFF7F7F7F.toInt()))
        assertEquals("a gray one step brighter keeps its top bit", 0xFF808080.toInt(), two.run(0xFF808080.toInt()))
    }

    @Test
    fun `reducing to 256 levels changes nothing`() {
        val full = ColorReductionRenderer.read(ByteArray(UNUSED_PATH) + int32(8))!!

        assertEquals(0xFF7F7F7F.toInt(), full.run(0xFF7F7F7F.toInt()))
    }

    @Test
    fun `color reduction refuses a body without its unused path or a key outside the dial`() {
        assertNull("the 260 unused bytes are still required", ColorReductionRenderer.read(ByteArray(100) + int32(1)))
        assertNull(ColorReductionRenderer.read(ByteArray(UNUSED_PATH) + int32(0)))
        assertNull(ColorReductionRenderer.read(ByteArray(UNUSED_PATH) + int32(9)))
    }

    @Test
    fun `channel shift's BGR mode swaps red and blue`() {
        val bgr = ChannelShiftRenderer.read(int32(1021))!!

        assertEquals(0xFF332211.toInt(), bgr.run(0xFF112233.toInt()))
    }

    @Test
    fun `an unrecognized channel shift leaves the channels alone`() {
        val unknown = ChannelShiftRenderer.read(int32(9999))!!

        assertEquals(0xFF112233.toInt(), unknown.run(0xFF112233.toInt()))
    }

    /** e_channelshift.cpp: a beat rolls rand() % 6 into the mode, which then stays until the next beat. */
    @Test
    fun `a random channel shift rolls a new permutation on the beat and keeps it`() {
        val shift = ChannelShiftRenderer("RGB", onBeatRandom = true, random = FixedRandom(1))

        // mode 1 in enum order is GBR: red takes green, green takes blue, blue takes red
        assertEquals(0xFF223311.toInt(), shift.run(0xFF112233.toInt(), beat = true))
        assertEquals("the rolled mode holds between beats", 0xFF223311.toInt(), shift.run(0xFF112233.toInt()))
    }

    @Test
    fun `a channel shift without the random flag never re-rolls`() {
        val shift = ChannelShiftRenderer.read(int32(1021) + int32(0))!!

        assertEquals(0xFF332211.toInt(), shift.run(0xFF112233.toInt(), beat = true))
    }

    @Test
    fun `grain turned up changes every pixel of a mid gray frame`() {
        val frame = grey()

        GrainRenderer
            .read(grainBody(enabled = 1, amount = 100, static = 0))!!
            .render(frame, AvsAudioFrame(), state())

        assertTrue("every pixel changes", frame.pixels.all { it != GREY })
    }

    /** e_grain.cpp's replace mode keeps only the chosen pixels on black, and amount nothing chooses none. */
    @Test
    fun `replace grain at amount nothing blacks the frame, and switched off leaves it`() {
        val none = grey()
        val off = grey()

        GrainRenderer
            .read(grainBody(enabled = 1, amount = 0, static = 0))!!
            .render(none, AvsAudioFrame(), state())
        GrainRenderer
            .read(grainBody(enabled = 0, amount = 100, static = 0))!!
            .render(off, AvsAudioFrame(), state())

        assertTrue(none.pixels.all { it == AvsFrame.OPAQUE })
        assertTrue(off.pixels.all { it == GREY })
    }

    @Test
    fun `additive grain at amount nothing leaves the frame alone`() {
        val frame = grey()

        GrainRenderer
            .read(int32(1) + int32(1) + int32(0) + int32(0) + int32(0))!!
            .render(frame, AvsAudioFrame(), state())

        assertTrue(frame.pixels.all { it == GREY })
    }

    /** Grain's 50/50 flag only counts when additive is not set, the opposite priority of the shared pair. */
    @Test
    fun `grain's additive flag wins over its fifty-fifty flag`() {
        val frame = grey()

        GrainRenderer
            .read(int32(1) + int32(1) + int32(1) + int32(0) + int32(0))!!
            .render(frame, AvsAudioFrame(), state())

        // additive adds the black candidate: unchanged; had 50/50 won, the frame would have halved
        assertTrue(frame.pixels.all { it == GREY })
    }

    @Test
    fun `static grain is the same grain twice`() {
        val still = GrainRenderer.read(grainBody(enabled = 1, amount = 100, static = 1))!!
        val first = grey()
        val second = grey()

        still.render(first, AvsAudioFrame(), state())
        still.render(second, AvsAudioFrame(), state())

        assertEquals(first.pixels.toList(), second.pixels.toList())
    }

    /** The static table's thresholds run 0..99 against amount*255/100, so from amount 40 up every pixel is chosen. */
    @Test
    fun `static grain from amount forty chooses every pixel`() {
        val forty = grey()
        val hundred = grey()

        GrainRenderer
            .read(grainBody(enabled = 1, amount = 40, static = 1))!!
            .render(forty, AvsAudioFrame(), state())
        GrainRenderer
            .read(grainBody(enabled = 1, amount = 100, static = 1))!!
            .render(hundred, AvsAudioFrame(), state())

        assertEquals("forty and a hundred choose the same pixels", hundred.pixels.toList(), forty.pixels.toList())
    }

    @Test
    fun `moving grain boils between frames`() {
        val boiling = GrainRenderer.read(grainBody(enabled = 1, amount = 100, static = 0))!!
        val first = grey()
        val second = grey()

        boiling.render(first, AvsAudioFrame(), state())
        boiling.render(second, AvsAudioFrame(), state())

        assertNotEquals(first.pixels.toList(), second.pixels.toList())
    }

    @Test
    fun `every one of them refuses a body that runs out`() {
        assertNull(ColorReductionRenderer.read(ByteArray(2)))
        assertNull(ChannelShiftRenderer.read(ByteArray(2)))
        assertNull(GrainRenderer.read(ByteArray(2)))
    }

    @Test
    fun `a reader that runs out clears ok`() {
        val reader = BodyReader(ByteArray(2))
        reader.int32()
        assertFalse(reader.ok)
    }

    /** The two blend ints sit between enabled and amount. */
    private fun grainBody(
        enabled: Int,
        amount: Int,
        static: Int,
    ) = int32(enabled) + int32(0) + int32(0) + int32(amount) + int32(static)

    /** Runs a component over a one-pixel frame and gives back that pixel. */
    private fun AvsComponentRenderer.run(
        pixel: Int,
        beat: Boolean = false,
    ): Int {
        val frame = AvsFrame(1, 1, intArrayOf(pixel))
        render(frame, AvsAudioFrame(), state(beat))
        return frame[0, 0]
    }

    private fun grey() = AvsFrame(8, 8, IntArray(8 * 8) { GREY })

    private fun state(beat: Boolean = false) = AvsRenderState(AvsBuffers(8, 8)).also { it.beat = beat }

    private fun int32(value: Int) =
        byteArrayOf(
            value.toByte(),
            (value shr 8).toByte(),
            (value shr 16).toByte(),
            (value shr 24).toByte(),
        )

    /** Hands out one value regardless of bound, so a dice roll lands where the test says. */
    private class FixedRandom(
        private val value: Int,
    ) : Random() {
        override fun nextInt(bound: Int) = value % bound
    }

    private companion object {
        val WHITE = 0xFFFFFFFF.toInt()
        val BLACK = 0xFF000000.toInt()
        val RED = 0xFFFF0000.toInt()
        val GREY = 0xFF808080.toInt()

        /** The 260 unused bytes a Color Reduction body opens with. */
        const val UNUSED_PATH = 260
    }
}
