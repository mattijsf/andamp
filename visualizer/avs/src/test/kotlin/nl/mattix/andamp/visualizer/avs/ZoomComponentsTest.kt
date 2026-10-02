// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixed warps and the shift, against numbers worked out from the vis_avs
 * source they were transcribed from. The tests assert pixels, not parses.
 */
class ZoomComponentsTest {
    /**
     * `e_blitterfeedback.cpp`: below 32 the sampling step is (zoom+32)/64,
     * which is one half at zoom 0, so the center half of the frame expands to
     * fill it. A dot four right of center lands eight right of center.
     */
    @Test
    fun `a zoom below 32 spreads the center outward`() {
        val frame = AvsFrame(SIZE, SIZE)
        frame[20, 16] = WHITE

        BlitterFeedbackRenderer(zoom = 0, beatZoom = 0, onBeat = false, blend = AvsBlendMode.REPLACE)
            .render(frame, AvsAudioFrame(), state())

        assertEquals("the dot sits eight from the center", WHITE, frame[24, 16])
        assertEquals("the dot no longer sits four from the center", AvsFrame.OPAQUE, frame[20, 16])
    }

    @Test
    fun `a zoom of 32 is the identity and touches nothing`() {
        val frame = spotted()

        BlitterFeedbackRenderer(zoom = 32, beatZoom = 0, onBeat = false, blend = AvsBlendMode.REPLACE)
            .render(frame, AvsAudioFrame(), state())

        assertEquals(spotted().pixels.toList(), frame.pixels.toList())
    }

    /**
     * Above 32 only a shrunken copy is written, centered, and the border keeps
     * the previous picture. At zoom 255 on a 33px frame the box is 12px,
     * columns and rows 10..21.
     */
    @Test
    fun `a zoom above 32 shrinks into the center and keeps the old frame around it`() {
        val frame = AvsFrame(SIZE, SIZE)
        frame[30, 16] = WHITE

        BlitterFeedbackRenderer(zoom = 255, beatZoom = 0, onBeat = false, blend = AvsBlendMode.REPLACE)
            .render(frame, AvsAudioFrame(), state())

        assertEquals("outside the box the old frame stands", WHITE, frame[30, 16])
        assertEquals("the shrunken copy of the dot lands inside the box", WHITE, frame[21, 16])
    }

    /**
     * A beat snaps the working zoom to the beat value; it then relaxes toward
     * the base zoom by 3 a frame. So one frame after a beat, a (10, beat 40)
     * blitter draws what a fresh zoom-37 blitter draws.
     */
    @Test
    fun `the beat zoom decays back by three a frame`() {
        val decaying = BlitterFeedbackRenderer(zoom = 10, beatZoom = 40, onBeat = true, blend = AvsBlendMode.REPLACE)
        decaying.render(patterned(), AvsAudioFrame(), state(beat = true))
        val afterBeat = patterned()
        decaying.render(afterBeat, AvsAudioFrame(), state())

        val settled = patterned()
        BlitterFeedbackRenderer(zoom = 37, beatZoom = 0, onBeat = false, blend = AvsBlendMode.REPLACE)
            .render(settled, AvsAudioFrame(), state())

        assertEquals(settled.pixels.toList(), afterBeat.pixels.toList())
    }

    /**
     * `e_rotoblitter.cpp`: the file's zoom over 31 is the sampling scale and 32
     * on the rotate field is still. Parked there, the frame stands, except the
     * last row and column, because the sampling tiles with period size-1.
     */
    @Test
    fun `a roto blitter parked at identity only wraps the far edge`() {
        val frame = patterned()
        val before = patterned()

        RotoBlitterRenderer(zoom = 31, rotate = 32, blend = AvsBlendMode.REPLACE)
            .render(frame, AvsAudioFrame(), state())

        for (y in 0 until SIZE - 1) {
            for (x in 0 until SIZE - 1) {
                assertEquals("interior pixel $x,$y stays in place", before[x, y], frame[x, y])
            }
        }
        assertEquals("the far column comes round from the first", before[0, 16], frame[SIZE - 1, 16])
        assertEquals("the far row comes round from the first", before[16, 0], frame[16, SIZE - 1])
    }

    /** File zoom 62 is a sampling scale of 2: a dot four right of center lands two right. */
    @Test
    fun `the roto zoom scale is the file value over 31`() {
        val frame = AvsFrame(SIZE, SIZE)
        frame[20, 16] = WHITE

        RotoBlitterRenderer(zoom = 62, rotate = 32, blend = AvsBlendMode.REPLACE)
            .render(frame, AvsAudioFrame(), state())

        assertEquals(WHITE, frame[18, 16])
        assertEquals(AvsFrame.OPAQUE, frame[20, 16])
    }

    @Test
    fun `roto blitter turns as well as zooms`() {
        val frame = spotted()

        RotoBlitterRenderer(zoom = 32, rotate = 48, blend = AvsBlendMode.REPLACE)
            .render(frame, AvsAudioFrame(), state())

        assertNotEquals("the rotation changes the frame", spotted().pixels.toList(), frame.pixels.toList())
    }

    /**
     * On-beat reverse eases the direction at 1/(1 + 4*speed) per frame, so the
     * reversal speed field changes what the frame of the beat looks like.
     */
    @Test
    fun `the reversal speed changes the frame of the beat`() {
        val instant = rotoReverse(speed = 0)
        val eased = rotoReverse(speed = 8)

        assertNotEquals("speed 8 reverses less sharply than speed 0", instant, eased)
    }

    private fun rotoReverse(speed: Int): List<Int> {
        val renderer =
            RotoBlitterRenderer(
                zoom = 31,
                rotate = 48,
                blend = AvsBlendMode.REPLACE,
                onBeatReverse = true,
                reversalSpeed = speed,
            )
        val first = patterned()
        renderer.render(first, AvsAudioFrame(), state())
        val second = patterned()
        renderer.render(second, AvsAudioFrame(), state(beat = true))
        return second.pixels.toList()
    }

    /** `e_dynamicshift.cpp`'s replace path: rows move, vacated space goes black. */
    @Test
    fun `a replace shift moves the frame and blacks what it uncovered`() {
        val source = patterned()
        val frame = patterned()

        DynamicShiftRenderer.shift(frame, source, byX = 2, byY = 1, fiftyFifty = false, alpha = 0.5)

        assertEquals(source[14, 15], frame[16, 16])
        assertEquals("the uncovered edge is black", AvsFrame.OPAQUE, frame[1, 16])
        assertEquals("the uncovered top row is black", AvsFrame.OPAQUE, frame[16, 0])
    }

    /** With blend on, the shifted image mixes over the unshifted frame at the script's alpha. */
    @Test
    fun `a blended shift mixes at the script's alpha`() {
        val source = patterned()
        val frame = patterned()
        val alpha = 0.25

        DynamicShiftRenderer.shift(frame, source, byX = 0, byY = 2, fiftyFifty = true, alpha = alpha)

        val level = (alpha * 255).toInt()
        assertEquals(
            AvsBlend.pixel(AvsBlendMode.ADJUSTABLE, source[16, 14], source[16, 16], level),
            frame[16, 16],
        )
        assertEquals(
            "the vacated edge blends black at the same alpha",
            AvsBlend.pixel(AvsBlendMode.ADJUSTABLE, AvsFrame.OPAQUE, source[16, 0], level),
            frame[16, 0],
        )
    }

    @Test
    fun `alpha at or below zero makes the shift a no-op`() {
        val frame = patterned()

        DynamicShiftRenderer.shift(frame, patterned(), byX = 5, byY = 5, fiftyFifty = true, alpha = 0.0)

        assertEquals(patterned().pixels.toList(), frame.pixels.toList())
    }

    @Test
    fun `alpha at or above one degrades to a plain replace`() {
        val blended = patterned()
        val replaced = patterned()

        DynamicShiftRenderer.shift(blended, patterned(), byX = 3, byY = 0, fiftyFifty = true, alpha = 1.0)
        DynamicShiftRenderer.shift(replaced, patterned(), byX = 3, byY = 0, fiftyFifty = false, alpha = 0.5)

        assertEquals(replaced.pixels.toList(), blended.pixels.toList())
    }

    /**
     * For a leftward blended shift `e_dynamicshift.cpp` zeroes its column
     * offset after the first row (`else xa = 0;`), so only the first row
     * moves and the rest blend the frame over itself.
     */
    @Test
    fun `a leftward blended shift only moves its first row`() {
        val source = patterned().also { it[7, 0] = WHITE }
        val frame = patterned().also { it[7, 0] = WHITE }

        DynamicShiftRenderer.shift(frame, source, byX = -2, byY = 0, fiftyFifty = true, alpha = 0.5)

        assertNotEquals("the first row shifts", source[5, 0], frame[5, 0])
        assertEquals(
            "the second row blends the frame over itself, unmoved",
            AvsBlend.pixel(AvsBlendMode.ADJUSTABLE, source[5, 1], source[5, 1], 127),
            frame[5, 1],
        )
    }

    /**
     * `e_dynamicdistancemodifier.cpp` scales each pixel's offset from the
     * center by a per-radius table entry in 8.8 fixed point: 256 is identity.
     */
    @Test
    fun `a distance table of 256 is the identity`() {
        val source = patterned()
        val destination = AvsFrame(SIZE, SIZE)

        DynamicDistanceModifierRenderer.applyTable(source, destination, IntArray(64) { 256 }, fiftyFifty = false)

        assertEquals(source.pixels.toList(), destination.pixels.toList())
    }

    /** A multiplier of one half halves every sampling offset: a dot four from center shows up seven from center. */
    @Test
    fun `a table under 256 magnifies toward the center`() {
        val source = AvsFrame(SIZE, SIZE)
        source[20, 16] = WHITE
        val destination = AvsFrame(SIZE, SIZE)

        DynamicDistanceModifierRenderer.applyTable(source, destination, IntArray(64) { 128 }, fiftyFifty = false)

        assertEquals(WHITE, destination[23, 16])
        assertEquals(AvsFrame.OPAQUE, destination[20, 16])
    }

    @Test
    fun `the distance modifier's fifty-fifty blends warped over original`() {
        val source = AvsFrame(SIZE, SIZE)
        source[20, 16] = WHITE
        val destination = AvsFrame(SIZE, SIZE)

        DynamicDistanceModifierRenderer.applyTable(source, destination, IntArray(64) { 128 }, fiftyFifty = true)

        assertEquals(0xFF7F7F7F.toInt(), destination[23, 16])
    }

    /** The table-driven integer square root, spot-checked against real roots. */
    @Test
    fun `the integer square root approximation lands on its squares`() {
        assertEquals(16, DynamicDistanceModifierRenderer.isqrt(256))
        assertEquals(28, DynamicDistanceModifierRenderer.isqrt(784))
        assertEquals(256, DynamicDistanceModifierRenderer.isqrt(65536))
        assertTrue(DynamicDistanceModifierRenderer.isqrt(300) in 16..18)
    }

    /**
     * The parse alone; the full renderer needs the evaluator. The tail is two
     * ints, and the sections are stored in a Super Scope's order.
     */
    @Test
    fun `a dynamic distance modifier body parses with its own two-int tail`() {
        val body = byteArrayOf(1) + sized("p") + sized("f") + sized("b") + sized("i") + int32(1) + int32(0)

        val config = DynamicDistanceModifierRenderer.readConfig(body)!!

        assertEquals(true, config.fiftyFifty)
        assertEquals("i", config.init)
        assertEquals("f", config.perFrame)
        assertEquals("b", config.onBeat)
        assertEquals("p", config.perPoint)
    }

    /** A lit block off center, so a move is visible and directional. */
    private fun spotted() =
        AvsFrame(SIZE, SIZE).also {
            for (y in 12..20) {
                for (x in 4..12) {
                    it[x, y] = WHITE
                }
            }
        }

    /** Every pixel its own color, so any resample shows. */
    private fun patterned() =
        AvsFrame(SIZE, SIZE).also {
            for (y in 0 until SIZE) {
                for (x in 0 until SIZE) {
                    it[x, y] = AvsFrame.OPAQUE or (x shl 16) or (y shl 8) or ((x + y) and 0xFF)
                }
            }
        }

    private fun state(beat: Boolean = false) = AvsRenderState(AvsBuffers(8, 8)).also { it.beat = beat }

    private fun int32(value: Int) =
        byteArrayOf(
            value.toByte(),
            (value shr 8).toByte(),
            (value shr 16).toByte(),
            (value shr 24).toByte(),
        )

    private fun sized(text: String) = int32(text.length) + text.toByteArray(Charsets.ISO_8859_1)

    private companion object {
        const val SIZE = 33
        val WHITE = 0xFFFFFFFF.toInt()
    }
}
