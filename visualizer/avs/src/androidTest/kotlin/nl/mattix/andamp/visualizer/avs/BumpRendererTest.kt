// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Bump with its code running: where the light falls on a relief. */
@RunWith(AndroidJUnit4::class)
class BumpRendererTest {
    private fun bump(
        perFrame: String,
        settings: BumpRenderer.Settings = BumpRenderer.Settings(percentCoordinates = false, depth = 100),
    ) = BumpRenderer(settings, perFrame, onBeat = "", init = "")

    /** A flat grey sheet with one bright square in the middle: a plateau with four edges. */
    private fun plateau() =
        AvsFrame(SIZE, SIZE).also { frame ->
            frame.pixels.fill(GREY)
            for (y in 12 until 20) for (x in 12 until 20) frame[x, y] = BRIGHT
        }

    private fun state() = AvsRenderState(AvsBuffers(SIZE, SIZE))

    @Test
    fun the_edge_facing_the_light_is_lit_and_the_far_one_is_not() {
        val frame = plateau()

        // the light stands left of the plateau, level with it
        bump("x=0.1; y=0.5;").use { it.render(frame, AvsAudioFrame(), state()) }

        val facing = redOf(frame[11, 16])
        val away = redOf(frame[20, 16])
        assertTrue("the left edge is brighter than the sheet: $facing", facing > redOf(GREY))
        assertTrue("and brighter than the right edge: $facing against $away", facing > away)
    }

    @Test
    fun the_light_moves_with_the_code() {
        val left = plateau()
        val right = plateau()

        bump("x=0.1; y=0.5;").use { it.render(left, AvsAudioFrame(), state()) }
        bump("x=0.9; y=0.5;").use { it.render(right, AvsAudioFrame(), state()) }

        assertTrue(redOf(left[11, 16]) > redOf(right[11, 16]))
        assertTrue(redOf(right[20, 16]) > redOf(left[20, 16]))
    }

    @Test
    fun the_outer_ring_is_black() {
        val frame = plateau()

        bump("x=0.5; y=0.5;").use { it.render(frame, AvsAudioFrame(), state()) }

        assertEquals(AvsFrame.OPAQUE, frame[0, 0])
        assertEquals(AvsFrame.OPAQUE, frame[SIZE - 1, SIZE / 2])
    }

    @Test
    fun bi_scales_the_depth() {
        val full = plateau()
        val none = plateau()

        bump("x=0.1; y=0.5; bi=1;").use { it.render(full, AvsAudioFrame(), state()) }
        bump("x=0.1; y=0.5; bi=0;").use { it.render(none, AvsAudioFrame(), state()) }

        assertTrue(redOf(full[11, 16]) > redOf(none[11, 16]))
        assertEquals("with no depth a pixel keeps its color", redOf(GREY), redOf(none[11, 16]))
    }

    @Test
    fun the_oldest_presets_place_the_light_in_percent() {
        val fraction = plateau()
        val percent = plateau()

        bump("x=0.1; y=0.5;").use { it.render(fraction, AvsAudioFrame(), state()) }
        bump("x=10; y=50;", BumpRenderer.Settings(percentCoordinates = true, depth = 100)).use {
            it.render(percent, AvsAudioFrame(), state())
        }

        assertEquals(fraction.pixels.toList(), percent.pixels.toList())
    }

    @Test
    fun code_that_does_not_compile_is_reported_and_the_rest_runs() {
        BumpRenderer(BumpRenderer.Settings(), perFrame = "this is ( not eel", onBeat = "", init = "x=0.5;").use {
            assertEquals(listOf("per frame"), it.errors)
            it.render(plateau(), AvsAudioFrame(), state())
        }
    }

    @Test
    fun a_bump_read_from_a_body_runs() {
        val code = "x=0.5;y=0.5;".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0)
        val body = ByteArray(28).also { it[0] = 1 } + int32(code.size) + code + int32(0) + int32(0)

        BumpRenderer.read(body, Eel()).use {
            assertEquals(emptyList<String>(), it.errors)
            it.render(plateau(), AvsAudioFrame(), state())
        }
    }

    private fun int32(value: Int) =
        byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    private companion object {
        const val SIZE = 32
        const val GREY = 0xFF404040.toInt()
        const val BRIGHT = 0xFFC0C0C0.toInt()
    }
}
