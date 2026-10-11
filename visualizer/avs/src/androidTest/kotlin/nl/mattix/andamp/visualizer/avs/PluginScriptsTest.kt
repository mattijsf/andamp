// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The plug-ins that run a preset's code, with the code running, and the functions AVS adds to it. */
@RunWith(AndroidJUnit4::class)
class PluginScriptsTest {
    private fun state(beat: Boolean = false) = AvsRenderState(AvsBuffers(SIZE, SIZE)).also { it.reset(beat) }

    private fun lit(frame: AvsFrame) = frame.pixels.count { it != AvsFrame.OPAQUE }

    @Test
    fun getosc_reads_the_waveform_around_a_place_in_it() {
        val rising = FloatArray(AvsAudioFrame.SAMPLES) { it / (AvsAudioFrame.SAMPLES - 1f) }
        Eel().use { eel ->
            val low = eel.variable("low")
            val high = eel.variable("high")
            val wide = eel.variable("wide")
            val code = eel.compile("low=getosc(0.1,0,0); high=getosc(0.9,0,0); wide=getosc(0.5,1,0);")!!
            Eel.hear(AvsAudioFrame(waveform = rising))
            code.run()

            assertEquals(0.1, low.value, 0.02)
            assertEquals(0.9, high.value, 0.02)
            assertEquals("a full-width read is the mean of all of it", 0.5, wide.value, 0.02)
        }
    }

    @Test
    fun getspec_reads_the_spectrum_at_half_scale_and_an_unknown_channel_is_silent() {
        Eel().use { eel ->
            val full = eel.variable("full")
            val none = eel.variable("none")
            val code = eel.compile("full=getspec(0.5,0,0); none=getspec(0.5,0,7);")!!
            Eel.hear(AvsAudioFrame(spectrum = FloatArray(AvsAudioFrame.SAMPLES) { 1f }))
            code.run()

            assertEquals(1.0, full.value, 0.01)
            assertEquals(0.0, none.value, 0.0)
        }
    }

    @Test
    fun gettime_is_a_clock_in_seconds_and_knows_nothing_of_the_song() {
        Eel().use { eel ->
            val now = eel.variable("now")
            val since = eel.variable("since")
            val position = eel.variable("position")
            val keys = eel.variable("keys")
            eel.compile("now=gettime(0); since=gettime(now); position=gettime(-1); keys=getkbmouse(3);")!!.run()

            assertTrue("a clock that has run: ${now.value}", now.value > 0.0)
            assertEquals("no time passes between two reads", 0.0, since.value, 0.05)
            assertEquals(0.0, position.value, 0.0)
            assertEquals(0.0, keys.value, 0.0)
        }
    }

    @Test
    fun a_triangle_fills_the_shape_its_code_names() {
        val code = "x1=-0.8;y1=-0.8;x2=0.8;y2=-0.8;x3=0;y3=0.8;red1=1;green1=0;blue1=0;"
        val frame = AvsFrame(SIZE, SIZE)

        TriangleRenderer("n=1", "", "", code).use { it.render(frame, AvsAudioFrame(), state()) }

        assertEquals("inside, near the top edge", 0xFFFF0000.toInt(), frame[SIZE / 2, SIZE / 4])
        assertEquals("outside, beside the bottom corner", AvsFrame.OPAQUE, frame[2, SIZE - 4])
        assertTrue("about half the box it spans: ${lit(frame)}", lit(frame) in SIZE * SIZE / 5..SIZE * SIZE / 2)
    }

    @Test
    fun a_nearer_triangle_wins_with_the_depth_buffer_whatever_the_order() {
        // the first triangle is nearer and red, the second farther and blue, over the same place
        val code = "x1=-1;y1=-1;x2=1;y2=-1;x3=0;y3=1;z1=if(equal(i,0),10,5);red1=equal(i,0);green1=0;blue1=1-equal(i,0);"
        val frame = AvsFrame(SIZE, SIZE)

        TriangleRenderer("n=2", "zbuf=1;", "", code).use { it.render(frame, AvsAudioFrame(), state()) }

        assertEquals(0xFFFF0000.toInt(), frame[SIZE / 2, SIZE / 3])
    }

    @Test
    fun texer_two_draws_its_own_dot_where_the_code_puts_it() {
        val frame = AvsFrame(SIZE, SIZE)

        Texer2Renderer(Texer2Renderer.Settings(), "n=1", "", "", "x=0;y=0;").use {
            it.render(frame, AvsAudioFrame(), state())
        }

        assertEquals("the middle of the dot is full white", 0xFFFFFFFF.toInt(), frame[SIZE / 2 - 1, SIZE / 2 - 1])
        assertEquals(AvsFrame.OPAQUE, frame[2, 2])
        assertTrue("a dot 21 across: ${lit(frame)}", lit(frame) in 200..441)
    }

    @Test
    fun texer_two_tints_and_resizes_a_stamp() {
        val small = AvsFrame(SIZE, SIZE)
        val large = AvsFrame(SIZE, SIZE)
        val settings = Texer2Renderer.Settings(resize = true)

        Texer2Renderer(settings, "n=1", "", "", "x=0;y=0;sizex=0.5;sizey=0.5;red=1;green=0;blue=0;").use {
            it.render(small, AvsAudioFrame(), state())
        }
        Texer2Renderer(settings, "n=1", "", "", "x=0;y=0;sizex=2;sizey=2;").use {
            it.render(large, AvsAudioFrame(), state())
        }

        assertTrue("${lit(large)} against ${lit(small)}", lit(large) > lit(small) * 4)
        val middle = small[SIZE / 2 - 1, SIZE / 2 - 1]
        assertTrue("tinted red", redOf(middle) > 200 && greenOf(middle) == 0 && blueOf(middle) == 0)
    }

    @Test
    fun texer_two_wraps_a_stamp_that_crosses_an_edge() {
        val wrapped = AvsFrame(SIZE, SIZE)
        val settings = Texer2Renderer.Settings(wrap = true)

        Texer2Renderer(settings, "n=1", "", "", "x=1;y=0;").use { it.render(wrapped, AvsAudioFrame(), state()) }

        assertTrue("it shows at the right edge", (0 until SIZE).any { wrapped[SIZE - 1, it] != AvsFrame.OPAQUE })
        assertTrue("and again at the left", (0 until SIZE).any { wrapped[0, it] != AvsFrame.OPAQUE })
    }

    @Test
    fun global_variables_set_values_another_component_reads() {
        GlobalVariablesRenderer("reg42=7;", "reg43=reg43+1;", "").use { globals ->
            Eel().use { other ->
                val seen = other.variable("seen")
                val counted = other.variable("counted")
                val code = other.compile("seen=reg42; counted=reg43;")!!
                other.compile("reg43=0;")!!.run()

                repeat(3) { globals.render(AvsFrame(SIZE, SIZE), AvsAudioFrame(), state()) }
                code.run()

                assertEquals(7.0, seen.value, 0.0)
                assertEquals(3.0, counted.value, 0.0)
            }
        }
    }

    @Test
    fun a_preset_with_avstrans_runs_code_written_with_its_macros() {
        val trans =
            AvsComponent.Ape(
                "Misc: AVSTrans Automation",
                int32(1) + int32(0) + int32(0) + int32(1) + "#define HALF 0.5\u0000".toByteArray(),
            )
        val scope =
            AvsComponent.Builtin(
                SUPER_SCOPE,
                AvsComponents[SUPER_SCOPE]!!,
                scopeBody(init = "n=40", perPoint = "x=i*2-1; y=HALF;"),
            )

        AvsEngine(SIZE, SIZE).use { engine ->
            engine.load(AvsPreset(clearEveryFrame = true, components = listOf(trans, scope)))

            assertEquals("the macro is known to the scope's code", emptyList<String>(), engine.unimplemented)
            val frame = engine.render(AvsAudioFrame())
            val row = (0 until SIZE).single { y -> (0 until SIZE).any { frame[it, y] != AvsFrame.OPAQUE } }
            assertTrue("y = 0.5 is three quarters of the way down: $row", row in SIZE * 3 / 4 - 1..SIZE * 3 / 4 + 1)
        }
    }

    @Test
    fun the_same_code_without_avstrans_does_not_compile() {
        val scope =
            AvsComponent.Builtin(
                SUPER_SCOPE,
                AvsComponents[SUPER_SCOPE]!!,
                scopeBody(init = "n=40", perPoint = "x=i*2-1; y=HALF(;"),
            )

        AvsEngine(SIZE, SIZE).use { engine ->
            engine.load(AvsPreset(clearEveryFrame = true, components = listOf(scope)))

            assertEquals(1, engine.unimplemented.size)
        }
    }

    @Test
    fun text_is_drawn_with_the_phones_fonts() {
        val mask = AndroidTextPainter().paint("AVS", AvsFont(height = 20, bold = true))

        assertNotNull(mask)
        assertTrue("about as tall as the font: ${mask!!.height}", mask.height in 15..30)
        assertTrue("three letters wide: ${mask.width}", mask.width in 20..70)
        assertTrue("some of it is ink", mask.covered.count { it } > 50)
        assertTrue("and some is not", mask.covered.count { !it } > 50)
    }

    @Test
    fun a_text_component_puts_its_first_word_in_the_middle() {
        val text = TextRenderer(TextRenderer.Settings(text = "HI;there", font = AvsFont(height = 24)))
        val state = state().also { it.text = AndroidTextPainter() }
        val frame = AvsFrame(SIZE, SIZE)

        text.render(frame, AvsAudioFrame(), state)

        val rows = (0 until SIZE).filter { y -> (0 until SIZE).any { frame[it, y] != AvsFrame.OPAQUE } }
        assertTrue("the word is drawn", rows.isNotEmpty())
        assertTrue("around the middle row: $rows", rows.min() < SIZE / 2 && rows.max() > SIZE / 2 - 6)
    }

    private fun scopeBody(
        init: String,
        perPoint: String,
    ): ByteArray {
        var body = byteArrayOf(1)
        listOf(perPoint, "", "", init).forEach { body += int32(it.length) + it.toByteArray(Charsets.ISO_8859_1) }
        return body + int32(0) + int32(1) + int32(0xFFFFFF) + int32(0)
    }

    private fun int32(value: Int) =
        byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    private companion object {
        const val SIZE = 64
        const val SUPER_SCOPE = 0x24
    }
}
