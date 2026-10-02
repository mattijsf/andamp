// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Movement warping a frame, which needs the evaluator and so a device.
 *
 * The warps have known answers: a mesh told to shift everything one way must
 * move a lit block, one told to stand still must leave the frame identical,
 * and one told to read off the edge must clamp or wrap as the preset asks.
 */
@RunWith(AndroidJUnit4::class)
class MovementRendererTest {
    @Test
    fun a_movement_that_reads_where_it_is_leaves_the_frame_alone() {
        val frame = spotted()
        val before = frame.pixels.toList()

        movement(code = "").render(frame, AvsAudioFrame(), state())

        assertEquals(before, frame.pixels.toList())
    }

    @Test
    fun a_shift_moves_what_is_drawn() {
        val frame = spotted()

        // reading from further right puts what was there further left
        movement(code = "x = x + 0.5;").render(frame, AvsAudioFrame(), state())

        assertNotEquals("the shift moves the frame", spotted().pixels.toList(), frame.pixels.toList())
    }

    @Test
    fun wrapping_and_clamping_differ_at_the_edge() {
        val wrapped = edgeLit()
        val clamped = edgeLit()

        movement(code = "x = x + 1.5;", wrap = true).render(wrapped, AvsAudioFrame(), state())
        movement(code = "x = x + 1.5;", wrap = false).render(clamped, AvsAudioFrame(), state())

        assertNotEquals("wrap and clamp differ at the edge", wrapped.pixels.toList(), clamped.pixels.toList())
    }

    @Test
    fun a_built_in_effect_is_its_script() {
        val swirl = MovementEffects[3]!!
        assertEquals("Big Swirl Out", swirl.name)
        assertEquals(AvsCoordinates.POLAR, swirl.coordinates)

        val frame = spotted()
        MovementRenderer(swirl.code, swirl.coordinates, AvsBlendMode.REPLACE, wrap = false, eel = Eel()).use {
            assertTrue("the built-in script compiles: ${swirl.code}", !it.failed)
            it.render(frame, AvsAudioFrame(), state())
        }

        assertNotEquals("the swirl changes the frame", spotted().pixels.toList(), frame.pixels.toList())
    }

    /**
     * The built-in scripts are comma-separated with a trailing comma. This
     * asserts that the evaluator assigns through them.
     */
    @Test
    fun the_built_in_scripts_are_eel_this_evaluator_runs() {
        Eel().use { eel ->
            val r = eel.variable("r")
            val d = eel.variable("d")
            val code = eel.compile(MovementEffects[3]!!.eel)

            assertTrue("the built-in script compiles", code != null)
            // not d = 0.5: the swirl's rotation is (0.1 - 0.2 * d), which is
            // zero there
            r.value = 1.0
            d.value = 0.25
            code!!.run()

            assertNotEquals("the script assigns r", 1.0, r.value, 0.0001)
            assertNotEquals("the script assigns d", 0.25, d.value, 0.0001)
        }
    }

    /** Two of the built-ins have no script in the table. */
    @Test
    fun the_two_effects_without_a_script_say_so() {
        assertTrue(MovementEffects[1]!!.name == "Slight Fuzzify")
        assertTrue("a scriptless effect is not runnable", !MovementEffects[1]!!.runnable)
        assertTrue(!MovementEffects[7]!!.runnable)
        assertTrue("None is runnable", MovementEffects[0]!!.runnable)
    }

    @Test
    fun a_preset_carrying_its_own_script_is_read_as_that_script() {
        val code = "x = x * 0.5;"
        val renderer = MovementRenderer.read(customMovement(code), Eel())

        assertTrue("a custom movement is read", renderer != null)
        renderer!!.use {
            val frame = spotted()
            it.render(frame, AvsAudioFrame(), state())
            assertNotEquals(spotted().pixels.toList(), frame.pixels.toList())
        }
    }

    @Test
    fun a_built_in_by_number_is_read_out_of_the_table() {
        val renderer = MovementRenderer.read(builtInMovement(id = 3), Eel())

        assertTrue(renderer != null)
        renderer!!.close()
    }

    @Test
    fun a_script_that_will_not_compile_is_read_and_marked_failed() {
        val renderer = MovementRenderer.read(customMovement("this is ( not eel"), Eel())

        assertTrue("a script that will not compile is still read", renderer != null)
        assertTrue("the renderer is marked failed", renderer!!.failed)
        renderer.close()
    }

    @Test
    fun an_effect_number_with_no_script_produces_no_renderer() {
        assertNull(MovementRenderer.read(builtInMovement(id = 1), Eel()))
    }

    /**
     * The settings start after the marker byte and the declared code bytes,
     * also when the code has no trailing NUL. The stored blend here is
     * replace.
     */
    @Test
    fun settings_are_read_from_after_the_marker_byte_even_without_a_trailing_nul() {
        val code = "x = x + 0.9;".toByteArray(Charsets.ISO_8859_1)
        val body =
            int32(MovementEffects.CUSTOM) + byteArrayOf(1) + int32(code.size) + code +
                int32(0) + int32(0) + int32(1) + int32(0) + int32(0)

        val renderer = MovementRenderer.read(body, Eel())

        assertTrue(renderer != null)
        renderer!!.use {
            val frame = spotted()
            it.render(frame, AvsAudioFrame(), state())
            // replace, not fifty-fifty: nothing of the block stays at its old spot
            assertEquals(AvsFrame.OPAQUE, frame[SIZE / 4, SIZE / 2])
        }
    }

    private fun movement(
        code: String,
        wrap: Boolean = false,
    ) = MovementRenderer(code, AvsCoordinates.CARTESIAN, AvsBlendMode.REPLACE, wrap, Eel())

    /**
     * A lit block off center, so a move is visible and directional. A small
     * warp moves the sampling position by a pixel or two, which could make a
     * single lit pixel vanish.
     */
    private fun spotted() =
        AvsFrame(SIZE, SIZE).also {
            for (y in SIZE / 2 - 3..SIZE / 2 + 3) {
                for (x in SIZE / 4 - 3..SIZE / 4 + 3) {
                    it[x, y] = WHITE
                }
            }
        }

    private fun edgeLit() = AvsFrame(SIZE, SIZE).also { it[0, SIZE / 2] = WHITE }

    private fun state() = AvsRenderState(AvsBuffers(SIZE, SIZE))

    private fun builtInMovement(id: Int) = int32(id) + int32(0) + int32(0) + int32(1) + int32(0) + int32(0)

    /** As AVS writes it: the stored length counts the trailing NUL. */
    private fun customMovement(code: String): ByteArray {
        val text = code.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0)
        // settings: fifty-fifty, source-mapped, coordinates (1 = cartesian,
        // which is what the plain x/y test scripts are written in), bilinear, wrap
        return int32(MovementEffects.CUSTOM) +
            byteArrayOf(1) +
            int32(text.size) +
            text +
            int32(0) + int32(0) + int32(1) + int32(0) + int32(0)
    }

    private fun int32(value: Int) =
        byteArrayOf(
            value.toByte(),
            (value shr 8).toByte(),
            (value shr 16).toByte(),
            (value shr 24).toByte(),
        )

    private companion object {
        // big enough that a small warp moves a pixel: Big Swirl Out shifts
        // about 0.05 of the frame, which on a 17px frame rounds to nothing
        const val SIZE = 65
        val WHITE = 0xFFFFFFFF.toInt()
    }
}
