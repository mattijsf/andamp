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
 * Dynamic Movement with the evaluator: the four sections run when they should,
 * and the per-frame code moves the warp between frames. The warping itself is
 * covered by WarpMeshTest on the JVM. Dynamic Shift and the Dynamic Distance
 * Modifier are covered here too.
 */
@RunWith(AndroidJUnit4::class)
class DynamicMovementRendererTest {
    @Test
    fun a_per_point_warp_moves_the_frame() {
        val frame = spotted()

        renderer(perPoint = "x = x + 0.5;").use { it.render(frame, AvsAudioFrame(), state()) }

        assertNotEquals(spotted().pixels.toList(), frame.pixels.toList())
    }

    @Test
    fun the_per_frame_code_moves_the_warp_between_frames() {
        val first = spotted()
        val second = spotted()

        renderer(init = "shift = 0;", perFrame = "shift = shift + 0.4;", perPoint = "x = x + shift;").use {
            it.render(first, AvsAudioFrame(), state())
            it.render(second, AvsAudioFrame(), state())
        }

        assertNotEquals("the warp moves between frames", first.pixels.toList(), second.pixels.toList())
    }

    @Test
    fun on_beat_code_runs_only_on_a_beat() {
        renderer(init = "hits = 0;", onBeat = "hits = hits + 1;", perPoint = "x = x;").use { renderer ->
            val frame = spotted()
            renderer.render(frame, AvsAudioFrame(), state(beat = false))
            renderer.render(frame, AvsAudioFrame(), state(beat = true))
            renderer.render(frame, AvsAudioFrame(), state(beat = false))

            assertEquals(1.0, renderer.debugVariable("hits"), 0.0)
        }
    }

    @Test
    fun polar_code_warps_through_d_and_r() {
        val frame = spotted()

        renderer(perPoint = "d = d * 0.8;", coordinates = AvsCoordinates.POLAR)
            .use { it.render(frame, AvsAudioFrame(), state()) }

        assertNotEquals(spotted().pixels.toList(), frame.pixels.toList())
    }

    @Test
    fun a_section_that_will_not_compile_is_named() {
        renderer(perFrame = "this is ( not eel", perPoint = "x = x;").use {
            assertEquals(listOf("per frame"), it.errors)
        }
    }

    /** AVS's comma-separated style, the same one the built-in Movements use. */
    @Test
    fun avs_comma_separated_code_runs_here_too() {
        renderer(perPoint = "x = x * 0.9, y = y * 0.9,").use {
            assertTrue("comma-separated code compiles", it.errors.isEmpty())
            val frame = spotted()
            it.render(frame, AvsAudioFrame(), state())
            assertNotEquals(spotted().pixels.toList(), frame.pixels.toList())
        }
    }

    @Test
    fun a_body_is_read_into_a_config() {
        val config = DynamicMovementRenderer.read(body(perPoint = "x = x;", gridWidth = 24, gridHeight = 12))

        assertEquals("x = x;", config!!.perPoint)
        assertEquals(24, config.gridWidth)
        assertEquals(12, config.gridHeight)
    }

    /** Three sections, not four: the shift is the same everywhere, so there is no per-point code. */
    @Test
    fun a_dynamic_shift_slides_the_whole_frame() {
        val frame = spotted()

        DynamicShiftRenderer("", "x = 8; y = 0;", "", AvsBlendMode.REPLACE, Eel()).use {
            assertTrue(it.errors.isEmpty())
            it.render(frame, AvsAudioFrame(), state())
        }

        assertNotEquals(spotted().pixels.toList(), frame.pixels.toList())
    }

    @Test
    fun a_shift_of_nothing_leaves_the_frame_alone() {
        val frame = spotted()

        DynamicShiftRenderer("", "x = 0; y = 0;", "", AvsBlendMode.REPLACE, Eel())
            .use { it.render(frame, AvsAudioFrame(), state()) }

        assertEquals(spotted().pixels.toList(), frame.pixels.toList())
    }

    /** The point code sees only `d`. */
    @Test
    fun a_distance_modifier_pulls_along_the_radius() {
        val frame = spotted()
        // the tail is a blend and a bilinear flag
        val renderer = DynamicDistanceModifierRenderer.read(ddmBody(perPoint = "d = d * 0.7;"), Eel())

        assertTrue(renderer != null)
        renderer!!.use { it.render(frame, AvsAudioFrame(), state()) }

        assertNotEquals(spotted().pixels.toList(), frame.pixels.toList())
    }

    /** Stored init, per-frame, on-beat: the decoder's CodeIFB, not a Super Scope's PFBI. */
    @Test
    fun a_dynamic_shift_reads_its_sections_in_ifb_order() {
        // init shifts once; on-beat puts the shift back to nothing
        val body =
            byteArrayOf(1) +
                sized("x = 8;") + sized("") + sized("x = 0;") +
                int32(0) + int32(0)
        val renderer = DynamicShiftRenderer.read(body, Eel())

        assertTrue(renderer != null)
        renderer!!.use {
            val quiet = spotted()
            it.render(quiet, AvsAudioFrame(), state(beat = false))
            assertNotEquals("the init section shifts the frame", spotted().pixels.toList(), quiet.pixels.toList())

            val onBeat = spotted()
            it.render(onBeat, AvsAudioFrame(), state(beat = true))
            assertEquals("the on-beat section stops the shift", spotted().pixels.toList(), onBeat.pixels.toList())
        }
    }

    /** ns-eel variables persist between frames; an init-set shift stays set. */
    @Test
    fun a_shift_set_in_init_survives_to_the_next_frame() {
        DynamicShiftRenderer("x = 8; y = 0;", "", "", AvsBlendMode.REPLACE, Eel()).use {
            val first = spotted()
            val second = spotted()
            it.render(first, AvsAudioFrame(), state())
            it.render(second, AvsAudioFrame(), state())

            assertNotEquals("the shift persists between frames", spotted().pixels.toList(), second.pixels.toList())
        }
    }

    @Test
    fun a_body_that_runs_out_is_null() {
        assertNull(DynamicMovementRenderer.read(byteArrayOf(1, 0, 0)))
        assertNull(DynamicMovementRenderer.read(ByteArray(0)))
    }

    private fun renderer(
        init: String = "",
        perFrame: String = "",
        onBeat: String = "",
        perPoint: String = "",
        coordinates: AvsCoordinates = AvsCoordinates.CARTESIAN,
    ) = DynamicMovementRenderer(
        eel = Eel(),
        config =
            DynamicMovementConfig(
                init = init,
                perFrame = perFrame,
                onBeat = onBeat,
                perPoint = perPoint,
                coordinates = coordinates,
                gridWidth = GRID,
                gridHeight = GRID,
            ),
    )

    private fun body(
        perPoint: String,
        gridWidth: Int,
        gridHeight: Int,
    ): ByteArray {
        var out = byteArrayOf(1)
        listOf(perPoint, "", "", "").forEach { out += sized(it) }
        return out + int32(0) + int32(1) + int32(gridWidth) + int32(gridHeight) + int32(0) + int32(0) + int32(0) + int32(0)
    }

    /** A Dynamic Distance Modifier's body: the same four sections, then blend and bilinear. */
    private fun ddmBody(perPoint: String): ByteArray {
        var out = byteArrayOf(1)
        listOf(perPoint, "", "", "").forEach { out += sized(it) }
        return out + int32(0) + int32(0)
    }

    private fun sized(text: String) = int32(text.length) + text.toByteArray(Charsets.ISO_8859_1)

    private fun spotted() =
        AvsFrame(SIZE, SIZE).also {
            for (y in SIZE / 2 - 3..SIZE / 2 + 3) {
                for (x in SIZE / 4 - 3..SIZE / 4 + 3) {
                    it[x, y] = WHITE
                }
            }
        }

    private fun state(beat: Boolean = false) = AvsRenderState(AvsBuffers(SIZE, SIZE)).also { it.beat = beat }

    private fun int32(value: Int) =
        byteArrayOf(
            value.toByte(),
            (value shr 8).toByte(),
            (value shr 16).toByte(),
            (value shr 24).toByte(),
        )

    private companion object {
        const val SIZE = 65
        const val GRID = 16
        val WHITE = 0xFFFFFFFF.toInt()
    }
}
