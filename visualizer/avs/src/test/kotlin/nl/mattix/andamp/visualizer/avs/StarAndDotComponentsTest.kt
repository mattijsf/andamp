// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Oscilloscope Star, Rotating Stars, Dot Fountain and Dot Plane: where their marks land. */
class StarAndDotComponentsTest {
    private fun state(beat: Boolean = false) = AvsRenderState(AvsBuffers(WIDTH, HEIGHT)).also { it.reset(beat) }

    private fun lit(frame: AvsFrame) = frame.pixels.count { it != AvsFrame.OPAQUE }

    private fun litColumns(frame: AvsFrame) =
        (0 until WIDTH).filter { x -> (0 until HEIGHT).any { frame[x, it] != AvsFrame.OPAQUE } }

    private val white = listOf(AvsFrame.fromConfig(0xFFFFFF))

    @Test
    fun `a silent oscilloscope star is five straight arms from the center`() {
        val frame = AvsFrame(WIDTH, HEIGHT)

        OscilloscopeStarRenderer(AvsPosition.CENTER, white, size = 16, rotation = 0).render(frame, AvsAudioFrame(), state())

        assertNotEquals("the center is drawn", AvsFrame.OPAQUE, frame[WIDTH / 2, HEIGHT / 2])
        assertNotEquals("the first arm points right", AvsFrame.OPAQUE, frame[WIDTH / 2 + 10, HEIGHT / 2])
        assertEquals("nothing points straight left", AvsFrame.OPAQUE, frame[WIDTH / 2 - 10, HEIGHT / 2])
        // half the short side at size 16 of 32
        assertTrue(litColumns(frame).max() <= WIDTH / 2 + HEIGHT / 2)
    }

    @Test
    fun `the star turns by its rotation each frame`() {
        val star = OscilloscopeStarRenderer(AvsPosition.CENTER, white, size = 16, rotation = 16)
        val first = AvsFrame(WIDTH, HEIGHT).also { star.render(it, AvsAudioFrame(), state()) }
        val second = AvsFrame(WIDTH, HEIGHT).also { star.render(it, AvsAudioFrame(), state()) }

        assertNotEquals(first.pixels.toList(), second.pixels.toList())
    }

    @Test
    fun `the waveform bends the arms`() {
        val loud = AvsAudioFrame(waveform = FloatArray(AvsAudioFrame.SAMPLES) { if (it % 2 == 0) 0.9f else -0.9f })
        val still = AvsFrame(WIDTH, HEIGHT)
        val bent = AvsFrame(WIDTH, HEIGHT)

        OscilloscopeStarRenderer(AvsPosition.CENTER, white, size = 24, rotation = 0).render(still, AvsAudioFrame(), state())
        OscilloscopeStarRenderer(AvsPosition.CENTER, white, size = 24, rotation = 0).render(bent, loud, state())

        assertTrue("a bent arm covers more pixels: ${lit(bent)} against ${lit(still)}", lit(bent) > lit(still))
    }

    @Test
    fun `a star placed left sits in the left quarter`() {
        val frame = AvsFrame(WIDTH, HEIGHT)

        OscilloscopeStarRenderer(AvsPosition.LEFT, white, size = 4, rotation = 0).render(frame, AvsAudioFrame(), state())

        assertTrue(litColumns(frame).all { it < WIDTH / 2 })
    }

    @Test
    fun `an oscilloscope star with no settings is the one AVS starts with`() {
        val frame = AvsFrame(WIDTH, HEIGHT)

        OscilloscopeStarRenderer.read(ByteArray(0)).render(frame, AvsAudioFrame(), state())

        assertNotEquals("centered, white, size 8", AvsFrame.OPAQUE, frame[WIDTH / 2, HEIGHT / 2])
    }

    @Test
    fun `an oscilloscope star reads its place, colors, size and rotation`() {
        val body = int32(0) + int32(1) + int32(0x00FF00) + int32(4) + int32(0)
        val frame = AvsFrame(WIDTH, HEIGHT)

        OscilloscopeStarRenderer.read(body).render(frame, AvsAudioFrame(), state())

        assertTrue("placed left", litColumns(frame).all { it < WIDTH / 2 })
        val drawn = frame.pixels.first { it != AvsFrame.OPAQUE }
        assertEquals("green", 0, redOf(drawn) + blueOf(drawn))
    }

    @Test
    fun `rotating stars are two stars on opposite sides of the center`() {
        val frame = AvsFrame(WIDTH, HEIGHT)

        RotatingStarsRenderer(white).render(frame, AvsAudioFrame(), state())

        // the first frame's orbit angle is 0: one star a quarter of the width right, one left
        val columns = litColumns(frame)
        assertTrue("something right of center", columns.any { it > WIDTH / 2 + WIDTH / 8 })
        assertTrue("something left of center", columns.any { it < WIDTH / 2 - WIDTH / 8 })
        assertEquals("nothing in the middle column", AvsFrame.OPAQUE, frame[WIDTH / 2, HEIGHT / 2])
    }

    @Test
    fun `a peak in the low spectrum makes the stars larger`() {
        val peaked = FloatArray(AvsAudioFrame.SAMPLES).also { it[8] = 0.45f }
        val quiet = AvsFrame(WIDTH, HEIGHT)
        val loud = AvsFrame(WIDTH, HEIGHT)

        RotatingStarsRenderer(white).render(quiet, AvsAudioFrame(), state())
        RotatingStarsRenderer(white).render(loud, AvsAudioFrame(spectrum = peaked), state())

        assertTrue("${lit(loud)} against ${lit(quiet)}", lit(loud) > lit(quiet))
    }

    @Test
    fun `rotating stars with no colors draw nothing`() {
        val frame = AvsFrame(WIDTH, HEIGHT)

        RotatingStarsRenderer.read(int32(0)).render(frame, AvsAudioFrame(), state())

        assertEquals(0, lit(frame))
    }

    @Test
    fun `the camera looks straight at an unturned, untilted turntable`() {
        val view = DotCamera.view(spin = 0f, tilt = 0f)

        // a point at the origin is 20 above the eye line and 400 away
        assertEquals(0f, view[3], 0.0001f)
        assertEquals(-20f, view[7], 0.0001f)
        assertEquals(400f, view[11], 0.0001f)
        // and x stays x
        assertEquals(1f, view[0], 0.0001f)
    }

    @Test
    fun `the dot colors run from each named color to the next in sixteen steps`() {
        val black = AvsFrame.OPAQUE
        val table = DotCamera.colourTable(listOf(black, pixelOf(160, 0, 0), black, black, pixelOf(0, 0, 160)))

        assertEquals(black, table[0])
        assertEquals(pixelOf(80, 0, 0), table[8])
        assertEquals("the second band starts on the second color", pixelOf(160, 0, 0), table[16])
        assertEquals("and walks back down", pixelOf(80, 0, 0), table[24])
        assertEquals(pixelOf(0, 0, 150), table[63])
    }

    @Test
    fun `a dot fountain throws its first dots from one place and fills the air over time`() {
        val fountain = DotFountainRenderer.read(ByteArray(0))
        val first = AvsFrame(WIDTH, HEIGHT).also { fountain.render(it, AvsAudioFrame(), state()) }
        var later = first
        repeat(40) { later = AvsFrame(WIDTH, HEIGHT).also { fountain.render(it, AvsAudioFrame(), state()) } }

        assertTrue("dots are drawn", lit(first) > 0)
        assertTrue("more of them once they have flown: ${lit(later)} against ${lit(first)}", lit(later) > lit(first))
    }

    @Test
    fun `a beat throws the fountain's dots harder`() {
        fun highestAfter(beat: Boolean): Int {
            val fountain = DotFountainRenderer.read(ByteArray(0))
            var frame = AvsFrame(WIDTH, HEIGHT)
            repeat(25) { step ->
                frame =
                    AvsFrame(WIDTH, HEIGHT).also { fountain.render(it, AvsAudioFrame(), state(beat && step == 0)) }
            }
            return (0 until HEIGHT).first { y -> (0 until WIDTH).any { frame[it, y] != AvsFrame.OPAQUE } }
        }

        assertTrue("the highest dot is higher after a beat", highestAfter(beat = true) < highestAfter(beat = false))
    }

    @Test
    fun `a dot plane is a sheet of dots across the frame`() {
        val frame = AvsFrame(WIDTH, HEIGHT)

        DotPlaneRenderer.read(ByteArray(0)).render(frame, AvsAudioFrame(), state())

        val columns = litColumns(frame)
        assertTrue("it is drawn", columns.isNotEmpty())
        assertTrue("on both sides of the center", columns.min() < WIDTH / 2 && columns.max() > WIDTH / 2)
    }

    @Test
    fun `the spectrum lifts the dot plane, and the lift travels`() {
        val loud = AvsAudioFrame(spectrum = FloatArray(AvsAudioFrame.SAMPLES) { 0.9f })
        val flat = DotPlaneRenderer.read(ByteArray(0))
        val lifted = DotPlaneRenderer.read(ByteArray(0))
        var flatFrame = AvsFrame(WIDTH, HEIGHT)
        var liftedFrame = AvsFrame(WIDTH, HEIGHT)
        repeat(10) {
            flatFrame = AvsFrame(WIDTH, HEIGHT).also { flat.render(it, AvsAudioFrame(), state()) }
            liftedFrame = AvsFrame(WIDTH, HEIGHT).also { lifted.render(it, loud, state()) }
        }

        assertNotEquals(flatFrame.pixels.toList(), liftedFrame.pixels.toList())
    }

    @Test
    fun `dots follow the render mode`() {
        val under = pixelOf(100, 100, 100)
        val additive = state().also { it.setRenderMode(AvsBlendMode.ADDITIVE, 255, 1) }
        val frame = AvsFrame(WIDTH, HEIGHT).also { it.pixels.fill(under) }

        DotPlaneRenderer.read(ByteArray(0)).render(frame, AvsAudioFrame(), additive)

        assertTrue("a dot added to grey is brighter than its own color", frame.pixels.any { greenOf(it) > 107 + 50 })
    }

    private fun int32(value: Int) =
        byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    private companion object {
        const val WIDTH = 96
        const val HEIGHT = 64
    }
}
