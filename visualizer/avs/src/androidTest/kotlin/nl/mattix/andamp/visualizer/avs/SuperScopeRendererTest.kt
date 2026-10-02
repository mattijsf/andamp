// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A Super Scope drawing, which needs the evaluator and so a device.
 *
 * The tests use shapes with a known answer, not golden images: a scope told to
 * draw a straight line down the middle must put its pixels on the middle
 * column, and one whose points come from the waveform must move when the
 * waveform does.
 */
@RunWith(AndroidJUnit4::class)
class SuperScopeRendererTest {
    @Test
    fun a_scope_draws_where_its_point_code_says() {
        // n points straight down the middle: x is always 0, y walks -1..1
        val frame = render(config(perPoint = "x = 0; y = i * 2 - 1;", init = "n = $HEIGHT"))

        val middle = WIDTH / 2
        val lit = (0 until HEIGHT).count { frame[middle, it] != AvsFrame.OPAQUE }
        assertTrue("the middle column is drawn in more than half of $HEIGHT rows: $lit", lit > HEIGHT / 2)
        assertEquals("nothing is drawn off the line", AvsFrame.OPAQUE, frame[0, 0])
    }

    @Test
    fun the_point_code_sees_the_waveform_through_v() {
        val quiet = render(config(perPoint = "x = i * 2 - 1; y = v;"), waveform = FloatArray(SAMPLES))
        val loud = render(config(perPoint = "x = i * 2 - 1; y = v;"), waveform = FloatArray(SAMPLES) { 0.9f })

        assertNotEquals(
            "the scope draws different pictures for silence and for a loud signal",
            quiet.pixels.toList(),
            loud.pixels.toList(),
        )
    }

    @Test
    fun init_runs_once_and_per_frame_runs_every_frame() {
        SuperScopeRenderer(
            eel = Eel(),
            config = config(init = "count = 0; n = 8;", perFrame = "count = count + 1;", perPoint = "x = 0; y = 0;"),
        ).use { renderer ->
            val frame = AvsFrame(WIDTH, HEIGHT)
            repeat(3) { renderer.render(frame, AvsAudioFrame(), state()) }

            // three frames of a counter that init set to zero once
            assertEquals(3.0, renderer.debugVariable("count"), 0.0)
        }
    }

    @Test
    fun on_beat_code_runs_only_on_a_beat() {
        SuperScopeRenderer(
            eel = Eel(),
            config = config(init = "hits = 0; n = 4;", onBeat = "hits = hits + 1;", perPoint = "x = 0; y = 0;"),
        ).use { renderer ->
            val frame = AvsFrame(WIDTH, HEIGHT)
            // the beat a component sees is the render state's, not the audio's:
            // Custom BPM sits in a preset and rewrites it
            renderer.render(frame, AvsAudioFrame(), state(beat = false))
            renderer.render(frame, AvsAudioFrame(), state(beat = true))
            renderer.render(frame, AvsAudioFrame(), state(beat = false))

            assertEquals(1.0, renderer.debugVariable("hits"), 0.0)
        }
    }

    @Test
    fun the_point_code_can_choose_its_own_colour() {
        val frame = render(config(perPoint = "x = 0; y = 0; red = 1; green = 0; blue = 0;", init = "n = 1"))

        assertEquals(0xFFFF0000.toInt(), frame[WIDTH / 2, HEIGHT / 2])
    }

    @Test
    fun skip_leaves_a_point_undrawn() {
        val drawn = render(config(perPoint = "x = 0; y = 0;", init = "n = 1"))
        val skipped = render(config(perPoint = "x = 0; y = 0; skip = 1;", init = "n = 1"))

        assertNotEquals(AvsFrame.OPAQUE, drawn[WIDTH / 2, HEIGHT / 2])
        assertEquals(AvsFrame.OPAQUE, skipped[WIDTH / 2, HEIGHT / 2])
    }

    @Test
    fun dots_and_lines_are_different_pictures() {
        val code = config(perPoint = "x = i * 2 - 1; y = 0;", init = "n = 4")
        val dots = render(code.copy(drawMode = DOTS))
        val lines = render(code.copy(drawMode = LINES))

        val dotted = dots.pixels.count { it != AvsFrame.OPAQUE }
        val joined = lines.pixels.count { it != AvsFrame.OPAQUE }
        assertTrue("lines ($joined) light more pixels than four dots ($dotted)", joined > dotted)
    }

    /** The draw branch reads the variable, not the config: scripts switch modes by assigning drawmode. */
    @Test
    fun the_code_can_switch_a_dots_scope_to_lines_by_writing_drawmode() {
        val code = config(perPoint = "x = i * 2 - 1; y = 0;", init = "n = 4", drawMode = DOTS)
        val dots = render(code)
        val switched = render(code.copy(perFrame = "drawmode = 1;"))

        val dotted = dots.pixels.count { it != AvsFrame.OPAQUE }
        val joined = switched.pixels.count { it != AvsFrame.OPAQUE }
        assertTrue("drawmode = 1 in code joins the dots ($joined vs $dotted)", joined > dotted)
    }

    /** AVS's lines branch has no dot fallback: point zero only seeds the line's start. */
    @Test
    fun the_first_point_of_a_lines_scope_draws_nothing() {
        val frame = render(config(perPoint = "x = 0; y = 0;", init = "n = 1", drawMode = LINES))

        assertTrue("a single-point lines scope stays blank", frame.pixels.all { it == AvsFrame.OPAQUE })
    }

    /** linesize applies to lines only; a dot is one pixel. */
    @Test
    fun dots_are_one_pixel_no_matter_what_linesize_says() {
        val frame = render(config(perPoint = "x = 0; y = 0;", init = "n = 1; linesize = 9;", drawMode = DOTS))

        assertEquals(1, frame.pixels.count { it != AvsFrame.OPAQUE })
    }

    /** The host sets n once; after that it is the preset's own variable. */
    @Test
    fun n_belongs_to_the_code_and_accumulates_across_frames() {
        SuperScopeRenderer(
            eel = Eel(),
            config = config(init = "n = 4;", perFrame = "n = n + 1;", perPoint = "x = 0; y = 0;"),
        ).use { renderer ->
            val frame = AvsFrame(WIDTH, HEIGHT)
            repeat(3) { renderer.render(frame, AvsAudioFrame(), state()) }

            // frame one: init makes it 4, perFrame 5; then 6; then 7
            assertEquals(7.0, renderer.debugVariable("n"), 0.0)
        }
    }

    @Test
    fun n_of_zero_hides_the_scope() {
        val frame = render(config(perPoint = "x = 0; y = 0;", init = "n = 0"))

        assertTrue("n = 0 hides the scope", frame.pixels.all { it == AvsFrame.OPAQUE })
    }

    /** AVS returns before any section runs when the palette is empty. */
    @Test
    fun a_scope_with_no_colours_neither_draws_nor_runs_its_code() {
        SuperScopeRenderer(
            eel = Eel(),
            config = config(perFrame = "ran = 1;", perPoint = "x = 0; y = 0;").copy(colours = emptyList()),
        ).use { renderer ->
            val frame = AvsFrame(WIDTH, HEIGHT)
            renderer.render(frame, AvsAudioFrame(), state())

            assertTrue(frame.pixels.all { it == AvsFrame.OPAQUE })
            assertEquals("the frame section does not run", 0.0, renderer.debugVariable("ran"), 0.0)
        }
    }

    /** Only the point loop is gated on the point code; the other sections still run. */
    @Test
    fun a_scope_without_point_code_runs_its_frame_code_but_draws_nothing() {
        SuperScopeRenderer(
            eel = Eel(),
            config = config(perFrame = "ticks = ticks + 1;"),
        ).use { renderer ->
            val frame = AvsFrame(WIDTH, HEIGHT)
            renderer.render(frame, AvsAudioFrame(), state())

            assertTrue("stale x/y are not painted", frame.pixels.all { it == AvsFrame.OPAQUE })
            assertEquals(1.0, renderer.debugVariable("ticks"), 0.0)
        }
    }

    /** linesize is read back per point, so point code tapers a stroke along its length. */
    @Test
    fun point_code_can_vary_linesize_along_the_scope() {
        val code = config(init = "n = 8;", perPoint = "x = i * 2 - 1; y = 0;", drawMode = LINES)
        val uniform = render(code)
        val tapered = render(code.copy(perPoint = "x = i * 2 - 1; y = 0; linesize = 1 + i * 8;"))

        val thin = uniform.pixels.count { it != AvsFrame.OPAQUE }
        val grown = tapered.pixels.count { it != AvsFrame.OPAQUE }
        assertTrue("a linesize written per point widens the stroke ($grown vs $thin)", grown > thin)
    }

    /**
     * The palette color is written to red/green/blue once per frame, before
     * the frame sections, and not again per point.
     */
    @Test
    fun a_frame_sections_colour_survives_to_the_points() {
        SuperScopeRenderer(
            eel = Eel(),
            config =
                config(
                    init = "n = 4;",
                    perFrame = "red = 0; green = 1; blue = 0;",
                    perPoint = "x = i * 2 - 1; y = 0;",
                    drawMode = DOTS,
                ),
        ).use { renderer ->
            val frame = AvsFrame(WIDTH, HEIGHT)
            renderer.render(frame, AvsAudioFrame(), state())

            assertTrue(
                "the frame section's green is not overridden by the palette",
                frame.pixels.any { it == 0xFF00FF00.toInt() },
            )
        }
    }

    @Test
    fun code_that_will_not_compile_is_reported_and_the_rest_still_runs() {
        SuperScopeRenderer(
            eel = Eel(),
            config = config(perFrame = "this is ( not eel", perPoint = "x = 0; y = 0;", init = "n = 1"),
        ).use { renderer ->
            assertEquals(listOf("per frame"), renderer.errors)

            val frame = AvsFrame(WIDTH, HEIGHT)
            renderer.render(frame, AvsAudioFrame(), state())

            assertNotEquals("the point code still draws", AvsFrame.OPAQUE, frame[WIDTH / 2, HEIGHT / 2])
        }
    }

    @Test
    fun linesize_makes_the_line_thicker() {
        val code = config(perPoint = "x = i * 2 - 1; y = 0;", drawMode = LINES)
        val thin = render(code.copy(init = "n = 4; linesize = 1;"))
        val thick = render(code.copy(init = "n = 4; linesize = 3;"))

        val thinPixels = thin.pixels.count { it != AvsFrame.OPAQUE }
        val thickPixels = thick.pixels.count { it != AvsFrame.OPAQUE }
        assertTrue(
            "linesize 3 lights more than twice what linesize 1 does: $thickPixels vs $thinPixels",
            thickPixels > thinPixels * 2,
        )
    }

    @Test
    fun a_linesize_set_in_frame_code_applies_to_that_frame() {
        SuperScopeRenderer(
            eel = Eel(),
            config =
                config(
                    init = "n = 4; linesize = 1;",
                    perFrame = "linesize = 4;",
                    perPoint = "x = i * 2 - 1; y = 0;",
                    drawMode = LINES,
                ),
        ).use { renderer ->
            val first = AvsFrame(WIDTH, HEIGHT)
            renderer.render(first, AvsAudioFrame(), state())

            // perFrame ran before the points were drawn, so even frame one is thick
            assertTrue(first.pixels.count { it != AvsFrame.OPAQUE } > WIDTH)
        }
    }

    @Test
    fun a_damaged_linesize_cannot_paint_the_whole_frame() {
        val frame =
            render(
                config(
                    init = "n = 2; linesize = 9999;",
                    perPoint = "x = i * 2 - 1; y = 0;",
                    drawMode = LINES,
                ),
            )

        assertTrue("a runaway linesize leaves part of the frame unpainted", frame.pixels.any { it == AvsFrame.OPAQUE })
    }

    @Test
    fun a_point_off_the_edge_is_clipped() {
        val frame = render(config(perPoint = "x = 5; y = 5;", init = "n = 4"))

        assertTrue("nothing is drawn", frame.pixels.all { it == AvsFrame.OPAQUE })
    }

    private fun render(
        config: SuperScopeConfig,
        waveform: FloatArray = FloatArray(SAMPLES),
    ): AvsFrame {
        val frame = AvsFrame(WIDTH, HEIGHT)
        SuperScopeRenderer(config, Eel()).use { it.render(frame, AvsAudioFrame(waveform = waveform), state()) }
        return frame
    }

    private fun state(beat: Boolean = false) = AvsRenderState(AvsBuffers(WIDTH, HEIGHT)).also { it.beat = beat }

    private fun config(
        init: String = "",
        perFrame: String = "",
        onBeat: String = "",
        perPoint: String = "",
        drawMode: Int = DOTS,
    ) = SuperScopeConfig(
        init = init,
        perFrame = perFrame,
        onBeat = onBeat,
        perPoint = perPoint,
        drawMode = drawMode,
    )

    private companion object {
        const val WIDTH = 33
        const val HEIGHT = 33
        const val SAMPLES = 64

        /** The stored draw-mode int: zero is dots, anything else is lines. */
        const val DOTS = 0
        const val LINES = 1
    }
}
