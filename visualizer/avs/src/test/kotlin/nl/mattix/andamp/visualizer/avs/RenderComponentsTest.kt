// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The six fixed Render components, tested for the pixels they put down.
 *
 * Each is read from a hand-built body and then rendered. The audio is shaped
 * so the assertions can tell the modes apart: a stepped waveform makes dots
 * countable and a line's join visible, and a full-scale spectrum makes an
 * analyzer fill. The geometry asserted here (baselines, sample windows, speed
 * units, radii) is vis_avs's own (`e_simple.cpp` and the other effect files,
 * BSD; see NOTICE.md). None of these needs the evaluator.
 */
class RenderComponentsTest {
    @Test
    fun `the dots bit wins over the low bits and paints isolated dots`() {
        val scope = SimpleRenderer.read(body((1 shl 6) or 2, 1, WHITE_CONFIG))!!
        val frame = AvsFrame(32, 32)

        scope.render(frame, stepped(), state())

        assertEquals("one dot per column, nothing joining them", frame.width, lit(frame).size)
    }

    @Test
    fun `effect zero is the solid spectrum analyzer`() {
        val analyzer = SimpleRenderer.read(body(0, 1, WHITE_CONFIG))!!
        val frame = AvsFrame(32, 32)

        analyzer.render(frame, brightSpectrum(), state())

        assertTrue("a full-scale spectrum fills columns", lit(frame).size >= frame.width * 4)
    }

    @Test
    fun `effect two is a line scope drawn from the waveform`() {
        val scope = SimpleRenderer.read(body(2, 1, WHITE_CONFIG))!!
        val frame = AvsFrame(32, 32)

        scope.render(frame, stepped(), state())

        assertTrue("a line joins the step in the waveform", lit(frame).size > frame.width)
        assertEquals("a line scope does not fill down to the baseline", AvsFrame.OPAQUE, frame[0, 4])
    }

    @Test
    fun `the position bits keep a top scope in the top half and a bottom one below`() {
        val top = SimpleRenderer.read(body((1 shl 6) or 2, 1, WHITE_CONFIG))!!
        val bottom = SimpleRenderer.read(body((1 shl 6) or 2 or (1 shl 4), 1, WHITE_CONFIG))!!
        val topFrame = AvsFrame(32, 32)
        val bottomFrame = AvsFrame(32, 32)

        top.render(topFrame, flatWaveform(), state())
        bottom.render(bottomFrame, flatWaveform(), state())

        assertTrue(lit(topFrame).isNotEmpty())
        assertTrue("a top scope stays above the middle", lit(topFrame).all { it / topFrame.width < topFrame.height / 2 })
        assertTrue(lit(bottomFrame).isNotEmpty())
        assertTrue(
            "a bottom scope stays below the middle",
            lit(bottomFrame).all { it / bottomFrame.width >= bottomFrame.height / 2 },
        )
    }

    /** e_simple.cpp adds the sample to the band top, so a positive waveform bends the trace down. */
    @Test
    fun `a positive waveform bends a top scope to the bottom of its band`() {
        val scope = SimpleRenderer.read(body((1 shl 6) or 2, 1, WHITE_CONFIG))!!
        val loud = AvsFrame(32, 32)
        val negative = AvsFrame(32, 32)

        scope.render(loud, flatWaveform(), state())
        scope.render(negative, AvsAudioFrame(waveform = FloatArray(AvsAudioFrame.SAMPLES) { -0.9f }), state())

        assertTrue("+0.9 lands at the bottom of the top-half band", lit(loud).all { it / loud.width == 15 })
        assertTrue("-0.9 lands at its top", lit(negative).all { it / negative.width == 0 })
    }

    /** The analyzer's baselines: h/2 for top and bottom, 3h/4 centered; bars are up to h/2 long. */
    @Test
    fun `analyzer bars rise from mid-screen, hang below it, or sit on the three-quarter line`() {
        val top = AvsFrame(32, 32)
        val bottom = AvsFrame(32, 32)
        val center = AvsFrame(32, 32)

        SimpleRenderer.read(body(0, 1, WHITE_CONFIG))!!.render(top, brightSpectrum(), state())
        SimpleRenderer.read(body(1 shl 4, 1, WHITE_CONFIG))!!.render(bottom, brightSpectrum(), state())
        SimpleRenderer.read(body(2 shl 4, 1, WHITE_CONFIG))!!.render(center, brightSpectrum(), state())

        // the line drawer leaves a vertical span's larger-y row undrawn (linedraw.cpp),
        // so each bar stops one row short of its lower end
        assertEquals("top bars span from mid-screen to the top", 0..15, litRowRange(top))
        assertEquals("bottom bars hang from mid-screen to the bottom", 15..30, litRowRange(bottom))
        assertEquals("centered bars rise from the three-quarter line", 8..23, litRowRange(center))
    }

    /** e_simple's line scope walks samples 0..287; the back half of the buffer does not appear. */
    @Test
    fun `the scope reads only the first 288 waveform samples`() {
        val one = AvsFrame(32, 32)
        val other = AvsFrame(32, 32)
        val front = FloatArray(AvsAudioFrame.SAMPLES) { if (it < 288) 0.5f else -0.9f }
        val differentBack = FloatArray(AvsAudioFrame.SAMPLES) { if (it < 288) 0.5f else 0.3f }

        SimpleRenderer.read(body(2, 1, WHITE_CONFIG))!!.render(one, AvsAudioFrame(waveform = front), state())
        SimpleRenderer.read(body(2, 1, WHITE_CONFIG))!!.render(other, AvsAudioFrame(waveform = differentBack), state())

        assertEquals(one.pixels.toList(), other.pixels.toList())
    }

    @Test
    fun `the timescope draws one column each frame and moves along`() {
        val timescope = TimescopeRenderer.read(body(1, WHITE_CONFIG, 0, 0, 0, 16))!!
        val frame = AvsFrame(16, 16)

        timescope.render(frame, brightSpectrum(), state())

        assertTrue(lit(frame).isNotEmpty())
        assertTrue("the first frame draws in column one", lit(frame).all { it % frame.width == 1 })
        // e_timescope scales the color by sample/256, so even full scale is a step short of white
        assertEquals(0xFFFEFEFE.toInt(), frame[1, 5])

        timescope.render(frame, brightSpectrum(), state())

        assertTrue("the next frame lands in column two", lit(frame).any { it % frame.width == 2 })
    }

    @Test
    fun `a silent spectrum leaves the timescope's column black`() {
        val timescope = TimescopeRenderer.read(body(1, WHITE_CONFIG, 0, 0, 0, 16))!!
        val frame = AvsFrame(16, 16)

        timescope.render(frame, AvsAudioFrame(), state())

        assertTrue(frame.pixels.all { it == AvsFrame.OPAQUE })
    }

    /** e_timescope indexes the spectrum directly: bands=16 shows only the 16 lowest samples. */
    @Test
    fun `the timescope's bands pick how many low spectrum samples appear`() {
        val timescope = TimescopeRenderer.read(body(1, WHITE_CONFIG, 0, 0, 0, 16))!!
        val frame = AvsFrame(16, 16)
        val energyAboveTheWindow =
            AvsAudioFrame(spectrum = FloatArray(AvsAudioFrame.SAMPLES) { if (it < 16) 0f else 1f })

        timescope.render(frame, energyAboveTheWindow, state())

        assertTrue("everything above sample 15 is out of a 16-band window", frame.pixels.all { it == AvsFrame.OPAQUE })
    }

    @Test
    fun `a dot grid with spacing four puts sixteen dots on a sixteen square frame`() {
        val grid = DotGridRenderer.read(body(1, WHITE_CONFIG, 4, 0, 0, 0))!!
        val frame = AvsFrame(16, 16)

        grid.render(frame, AvsAudioFrame(), state())

        assertEquals(16, lit(frame).size)
    }

    /**
     * Guards against a spacing whose 24.8 form overflows to zero, for which
     * the original's "add a period until it is positive" loop would not end.
     */
    @Test(timeout = 5_000)
    fun `a dot grid with an oversized spacing and a backward speed returns`() {
        listOf(1 shl 24, 1 shl 30, Int.MAX_VALUE, Int.MIN_VALUE, -1).forEach { spacing ->
            val grid = DotGridRenderer.read(body(1, WHITE_CONFIG, spacing, -512, -512, 0))!!
            val frame = AvsFrame(16, 16)

            repeat(3) { grid.render(frame, AvsAudioFrame(), state()) }
        }
    }

    /** A position below zero wraps by the grid's period. */
    @Test
    fun `a dot grid moving backward wraps around`() {
        val grid = DotGridRenderer.read(body(1, WHITE_CONFIG, 4, -256, -256, 0))!!

        repeat(6) {
            val frame = AvsFrame(16, 16)
            grid.render(frame, AvsAudioFrame(), state())
            assertEquals(16, lit(frame).size)
        }
    }

    @Test
    fun `a moving dot grid is somewhere else next frame`() {
        val grid = DotGridRenderer.read(body(1, WHITE_CONFIG, 4, 512, 0, 0))!!
        val first = AvsFrame(16, 16)
        val second = AvsFrame(16, 16)

        grid.render(first, AvsAudioFrame(), state())
        grid.render(second, AvsAudioFrame(), state())

        assertTrue(lit(first).isNotEmpty())
        assertNotEquals(lit(first), lit(second))
    }

    /** e_dotgrid accumulates raw speed and shifts by 8: one speed unit is 1/256 px a frame. */
    @Test
    fun `dot grid speed 256 moves the grid one pixel a frame`() {
        val grid = DotGridRenderer.read(body(1, WHITE_CONFIG, 8, 256, 0, 0))!!
        val first = AvsFrame(16, 16)
        val second = AvsFrame(16, 16)

        grid.render(first, AvsAudioFrame(), state())
        grid.render(second, AvsAudioFrame(), state())

        assertTrue(lit(first).all { it % first.width % 8 == 0 })
        assertTrue("one frame later the columns sit one pixel over", lit(second).all { it % second.width % 8 == 1 })
    }

    @Test
    fun `a ring on silence is a circle with nothing at its center`() {
        val ring = RingRenderer.read(body(2 shl 4, 1, WHITE_CONFIG, 16, 0))!!
        val frame = AvsFrame(32, 32)

        ring.render(frame, AvsAudioFrame(), state())

        assertTrue(lit(frame).isNotEmpty())
        assertEquals("the center of a ring is hollow", AvsFrame.OPAQUE, frame[16, 16])
    }

    /** e_ring: radius = size/32 of the short side times 0.1 + 0.9 * value, so audio scales the whole ring. */
    @Test
    fun `the ring's radius runs from a tenth of its size to all of it with the audio`() {
        val quiet = AvsFrame(32, 32)
        val loud = AvsFrame(32, 32)

        RingRenderer.read(body(2 shl 4, 1, WHITE_CONFIG, 16, 1))!!.render(quiet, AvsAudioFrame(), state())
        RingRenderer.read(body(2 shl 4, 1, WHITE_CONFIG, 16, 1))!!.render(loud, brightSpectrum(), state())

        assertTrue(lit(quiet).isNotEmpty())
        assertTrue(
            "a silent spectrum ring stays within a tenth of its size",
            lit(quiet).all { distanceFromCentre(quiet, it) <= 4.0 },
        )
        assertTrue("a full-scale ring reaches its whole size", lit(loud).any { distanceFromCentre(loud, it) >= 14.0 })
    }

    @Test
    fun `a starfield scatters stars and they move between frames`() {
        val warp = java.lang.Float.floatToIntBits(10f)
        val field = StarfieldRenderer.read(body(1, WHITE_CONFIG, 0, 0, warp, 4096, 0, 0, 0))!!
        val first = AvsFrame(32, 32)
        val second = AvsFrame(32, 32)

        field.render(first, AvsAudioFrame(), state())
        field.render(second, AvsAudioFrame(), state())

        assertTrue(lit(first).isNotEmpty())
        assertNotEquals(first.pixels.toList(), second.pixels.toList())
    }

    /** e_starfield scales the stored count by w*h/(512*384): it is a density, not a count. */
    @Test
    fun `a star count of 64 scales to none on a 16 by 12 frame`() {
        val field = StarfieldRenderer(true, WHITE, null, 10f, 64, false, 0f, 0)
        val frame = AvsFrame(16, 12)

        repeat(3) { field.render(frame, AvsAudioFrame(), state()) }

        assertTrue("64 stars at 512x384 scale to zero at 16x12", frame.pixels.all { it == AvsFrame.OPAQUE })
    }

    /** On a beat the speed jumps to the beat speed and ramps linearly back to the warp speed. */
    @Test
    fun `a beat kicks the stars and the ramp settles them back to the warp speed`() {
        val field = StarfieldRenderer(true, WHITE, null, 0f, 4096, true, 8f, 2)

        fun draw(beat: Boolean): AvsFrame {
            val frame = AvsFrame(32, 32)
            field.render(frame, AvsAudioFrame(), state().also { it.beat = beat })
            return frame
        }

        draw(false) // first placement, far-plane respawns settle
        val still = draw(false)
        assertEquals("at warp speed zero the field is frozen", still.pixels.toList(), draw(false).pixels.toList())

        draw(true) // the kick: draws the frozen state once more, then moves
        assertNotEquals("the beat sets the field moving", still.pixels.toList(), draw(false).pixels.toList())

        draw(false) // the ramp's last frame, back at warp speed zero
        val settled = draw(false)
        assertEquals("after the ramp the field freezes again", settled.pixels.toList(), draw(false).pixels.toList())
    }

    /** The staircase color mix keeps the gray component, so even a black color shows stars. */
    @Test
    fun `a black starfield still shows gray stars`() {
        val field = StarfieldRenderer(true, AvsFrame.OPAQUE, null, 10f, 4096, false, 0f, 0)
        val frame = AvsFrame(32, 32)

        repeat(2) {
            frame.clear()
            field.render(frame, AvsAudioFrame(), state())
        }

        assertTrue(lit(frame).isNotEmpty())
    }

    @Test
    fun `bass spin draws both arms and keeps turning`() {
        val spin = BassSpinRenderer.read(body(3, WHITE_CONFIG, WHITE_CONFIG, 0))!!
        val first = AvsFrame(64, 64)
        val second = AvsFrame(64, 64)

        spin.render(first, brightSpectrum(), state())
        spin.render(second, brightSpectrum(), state())

        assertTrue(lit(first).isNotEmpty())
        assertNotEquals(lit(first), lit(second))
    }

    @Test
    fun `a left arm and a right arm are different pixels in different halves`() {
        val leftOnly = BassSpinRenderer.read(body(1, WHITE_CONFIG, WHITE_CONFIG, 0))!!
        val rightOnly = BassSpinRenderer.read(body(2, WHITE_CONFIG, WHITE_CONFIG, 0))!!
        val left = AvsFrame(32, 32)
        val right = AvsFrame(32, 32)

        leftOnly.render(left, quietSpectrum(), state())
        rightOnly.render(right, quietSpectrum(), state())

        assertTrue(lit(left).isNotEmpty())
        assertTrue(lit(right).isNotEmpty())
        assertNotEquals(lit(left), lit(right))
        assertTrue("the left arm stays out of the rightmost quarter", lit(left).all { it % left.width < left.width * 3 / 4 })
    }

    /** e_bassspin: reach = min(h/2, 3w/8) * loudness/256, centers at w/2 -/+ half that span. */
    @Test
    fun `a quiet spectrum keeps each arm within a few pixels of its own center`() {
        val leftOnly = BassSpinRenderer.read(body(1, WHITE_CONFIG, WHITE_CONFIG, 0))!!
        val frame = AvsFrame(32, 32)

        repeat(3) { leftOnly.render(frame, quietSpectrum(), state()) }

        // ss = min(16, 12) = 12, so the left center is 16 - 6 = 10, on the middle row
        assertTrue(lit(frame).isNotEmpty())
        assertTrue(
            "quiet bass keeps the arm near its center",
            lit(frame).all {
                val dx = it % frame.width - 10
                val dy = it / frame.width - 16
                dx * dx + dy * dy <= 16
            },
        )
    }

    /** Filled mode sweeps from the previous frame's tips, so the first frame has nothing to sweep. */
    @Test
    fun `filled bass spin needs a previous frame to sweep from`() {
        val spin = BassSpinRenderer.read(body(3, WHITE_CONFIG, WHITE_CONFIG, 1))!!
        val first = AvsFrame(64, 64)
        val second = AvsFrame(64, 64)

        spin.render(first, brightSpectrum(), state())
        spin.render(second, brightSpectrum(), state())

        assertTrue("the first frame draws nothing", lit(first).isEmpty())
        assertTrue("the second frame draws the swept slices", lit(second).isNotEmpty())
    }

    @Test
    fun `short blend decodes its three modes and leaves the fourth to the preset`() {
        assertEquals(AvsBlendMode.REPLACE, shortBlendOrDefault(0))
        assertEquals(AvsBlendMode.ADDITIVE, shortBlendOrDefault(1))
        assertEquals(AvsBlendMode.FIFTY_FIFTY, shortBlendOrDefault(2))
        assertNull(shortBlendOrDefault(3))
    }

    @Test
    fun `every one of them refuses a body that runs out`() {
        assertNull(SimpleRenderer.read(ByteArray(2)))
        assertNull(TimescopeRenderer.read(ByteArray(2)))
        assertNull(DotGridRenderer.read(ByteArray(2)))
        assertNull(RingRenderer.read(ByteArray(2)))
        assertNull(StarfieldRenderer.read(ByteArray(2)))
        assertNull(BassSpinRenderer.read(ByteArray(2)))
    }

    /** The indices of every pixel a render touched. */
    private fun lit(frame: AvsFrame) = frame.pixels.indices.filter { frame.pixels[it] != AvsFrame.OPAQUE }

    /** The rows a render touched, lowest to highest. */
    private fun litRowRange(frame: AvsFrame): IntRange {
        val rows = lit(frame).map { it / frame.width }
        return rows.min()..rows.max()
    }

    private fun distanceFromCentre(
        frame: AvsFrame,
        index: Int,
    ): Double {
        val dx = index % frame.width - frame.width / 2
        val dy = index / frame.width - frame.height / 2
        return kotlin.math.sqrt((dx * dx + dy * dy).toDouble())
    }

    private fun state() = AvsRenderState(AvsBuffers(8, 8))

    /** A loud flat waveform: every scope column sees the same value. */
    private fun flatWaveform() = AvsAudioFrame(waveform = FloatArray(AvsAudioFrame.SAMPLES) { 0.9f })

    /**
     * Loud positive then loud negative, stepping at sample 144, inside the 288
     * samples a scope reads, so line modes get one visible join.
     */
    private fun stepped() =
        AvsAudioFrame(
            waveform = FloatArray(AvsAudioFrame.SAMPLES) { if (it < 144) 0.9f else -0.9f },
        )

    private fun brightSpectrum() = AvsAudioFrame(spectrum = FloatArray(AvsAudioFrame.SAMPLES) { 1f })

    /**
     * Just audible: a silent spectrum collapses Bass Spin's reach to zero and a
     * zero-length line draws nothing (linedraw.cpp), so the center tests need
     * bass quiet enough to stay put but loud enough to draw.
     */
    private fun quietSpectrum() = AvsAudioFrame(spectrum = FloatArray(AvsAudioFrame.SAMPLES) { 0.05f })

    private fun body(vararg fields: Int): ByteArray {
        val out = ByteArray(fields.size * Int.SIZE_BYTES)
        fields.forEachIndexed { index, value -> int32(value).copyInto(out, index * Int.SIZE_BYTES) }
        return out
    }

    private fun int32(value: Int) =
        byteArrayOf(
            value.toByte(),
            (value shr 8).toByte(),
            (value shr 16).toByte(),
            (value shr 24).toByte(),
        )

    private companion object {
        /** A color as a preset stores it: `0x00RRGGBB`. */
        const val WHITE_CONFIG = 0xFFFFFF
        val WHITE = 0xFFFFFFFF.toInt()
    }
}
