// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.widget

import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.skin.Dest
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SkinLoader
import nl.mattix.andamp.state.VisMode
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.ui.window.MAIN_H
import nl.mattix.andamp.ui.window.MAIN_W
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * What the home screen gets.
 *
 * The widget draws through the app's own window functions. The scaling and the
 * choice between the window and its shade are the widget's own, and a golden
 * per layout covers both.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class WidgetGoldenTest {
    private companion object {
        const val SNAPSHOT_DIR = "src/test/snapshots"

        /** The number of analyzer bands. */
        const val BANDS = 19
    }

    private lateinit var skin: Skin

    @Before
    fun loadSkin() {
        skin = SkinLoader.loadBase(ApplicationProvider.getApplicationContext())
    }

    private fun snapshot() =
        WidgetSnapshot(
            title = "Llama Whippin' Intro",
            artist = "DJ Mike Llama",
            durationMs = 5_000,
            positionSec = 2,
            transport = Transport.Playing,
            bitrateKbps = 128,
            sampleRateKhz = 44,
        )

    @Test
    fun `a box with room draws the whole player`() {
        val layout = WidgetLayout.choose(MAIN_W * 2, MAIN_H * 2)

        WidgetRender
            .bitmap(skin, layout, snapshot().toState())
            .captureRoboImage("$SNAPSHOT_DIR/widget_main_full.png")
    }

    @Test
    fun `a box one row tall draws the shade`() {
        val layout = WidgetLayout.choose(MAIN_W * 2, 60)

        assertTrue("a 60px box draws the shade", layout.shaded)
        WidgetRender
            .bitmap(skin, layout, snapshot().toState())
            .captureRoboImage("$SNAPSHOT_DIR/widget_main_shade.png")
    }

    @Test
    fun `the bitmap is the size the layout asked for`() {
        // the launcher's centerInside never has to shrink it, so the art stays
        // on whole pixels
        val layout = WidgetLayout.choose(MAIN_W * 3, MAIN_H * 3)
        val bitmap = WidgetRender.bitmap(skin, layout, snapshot().toState())

        assertEquals(MAIN_W * 3, bitmap.width)
        assertEquals(MAIN_H * 3, bitmap.height)
    }

    @Test
    fun `an empty snapshot draws a player`() {
        // what a launcher gets before the app has ever run
        val layout = WidgetLayout.choose(MAIN_W, MAIN_H)
        val bitmap = WidgetRender.bitmap(skin, layout, WidgetSnapshot().toState())

        assertTrue("an empty snapshot draws pixels", (0 until bitmap.width).any { bitmap.getPixel(it, MAIN_H / 2) != 0 })
    }

    @Test
    fun `the analyzer strip is drawn at the size the window has room for`() {
        val layout = WidgetLayout.choose(MAIN_W * 2, MAIN_H * 2)
        val frames =
            WidgetRender.visualizerFrames(skin, layout, snapshot().toState(), frames(WidgetVisFrames.COUNT), VisMode.Analyzer)

        assertEquals(WidgetVisFrames.COUNT, frames.size)
        frames.forEach {
            assertEquals(WidgetRender.visualizerRect(layout).w, it.width)
            assertEquals(WidgetRender.visualizerRect(layout).h, it.height)
        }
    }

    @Test
    fun `two different signals do not draw the same analyzer`() {
        // a renderer that ignored its levels would animate nothing
        val layout = WidgetLayout.choose(MAIN_W, MAIN_H)
        val quiet =
            WidgetRender.visualizerFrames(
                skin,
                layout,
                snapshot().toState(),
                listOf(frame(FloatArray(BANDS))),
                VisMode.Analyzer,
            )
        val loud =
            WidgetRender.visualizerFrames(
                skin,
                layout,
                snapshot().toState(),
                listOf(frame(FloatArray(BANDS) { 16f })),
                VisMode.Analyzer,
            )

        assertFalse("a silent and a full analyzer draw different pixels", quiet[0].sameAs(loud[0]))
    }

    @Test
    fun `a frame of the analyzer matches its golden`() {
        val layout = WidgetLayout.choose(MAIN_W * 2, MAIN_H * 2)

        WidgetRender
            .visualizerFrames(skin, layout, snapshot().toState(), listOf(frames(1).first()), VisMode.Analyzer)
            .first()
            .captureRoboImage("$SNAPSHOT_DIR/widget_analyzer.png")
    }

    @Test
    fun `a frame of the oscilloscope matches its golden`() {
        val layout = WidgetLayout.choose(MAIN_W * 2, MAIN_H * 2)
        val wave = IntArray(WinampState.WAVE_COLUMNS) { (Dest.VIS_H / 2 + (Dest.VIS_H / 2 - 1) * sin(it / 6.0)).roundToInt() }

        WidgetRender
            .visualizerFrames(skin, layout, snapshot().toState(), listOf(frame(FloatArray(BANDS), wave)), VisMode.Oscilloscope)
            .first()
            .captureRoboImage("$SNAPSHOT_DIR/widget_oscilloscope.png")
    }

    /** A fixed sawtooth across the bands: a picture that cannot change run to run. */
    private fun frames(count: Int) =
        List(count) { at ->
            frame(FloatArray(BANDS) { band -> ((band + at) % BANDS) * Dest.VIS_H / BANDS.toFloat() })
        }

    /** A frame built the way the burst builds one, from a state it has written. */
    private fun frame(
        levels: FloatArray,
        wave: IntArray = IntArray(WinampState.WAVE_COLUMNS) { Dest.VIS_H / 2 },
    ): VisFrame {
        val state = WinampState()
        levels.copyInto(state.visLevels)
        levels.copyInto(state.visPeaks)
        wave.copyInto(state.visWave)
        return VisFrame.of(state)
    }

    @Test
    fun `every control the widget offers has pressed art under the name it uses`() {
        // the flash after a press asks the draw code for a control's pressed
        // sprite by name, and a name that matches nothing draws the resting
        // picture, so every name is checked by drawing with it
        val layout = WidgetLayout.choose(MAIN_W, MAIN_H)
        val resting = WidgetRender.bitmap(skin, layout, snapshot().toState())

        val names =
            WidgetButton.entries.map { WidgetFlash.idOf(it) } + WidgetSlider.entries.map { WidgetFlash.idOf(it) }
        names.forEach { name ->
            val pressed = WidgetRender.bitmap(skin, layout, snapshot().toState().apply { pressedWidget = name })
            assertFalse("$name draws pressed art", resting.sameAs(pressed))
        }
    }
}
