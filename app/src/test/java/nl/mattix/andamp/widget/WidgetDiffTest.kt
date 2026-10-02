// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.widget

import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.ui.window.MAIN_H
import nl.mattix.andamp.ui.window.MAIN_W
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two decisions that route a widget update.
 *
 * The chrome string decides whole-window against patches, so it must leave out
 * what a tick changes, such as writtenAt, which every save re-stamps. The slide
 * request codes must all differ, because two PendingIntents with one request
 * code are one intent.
 */
class WidgetDiffTest {
    private val layout = WidgetLayout.choose(MAIN_W, MAIN_H)
    private val settings = WidgetSettings()
    private val skin = "base"

    private fun playing(
        at: Int,
        stampedAt: Long,
    ) = WidgetSnapshot(
        title = "Cryogen",
        durationMs = 301_000,
        positionSec = at,
        transport = Transport.Playing,
        writtenAt = stampedAt,
    )

    @Test
    fun `a tick is not a chrome change`() {
        // one second later: new position, new stamp, nothing else. The layers
        // carry that; the chrome must not see it
        val before = WidgetSender.chromeOf(playing(at = 10, stampedAt = 1_000), layout, null, settings, skin)
        val after = WidgetSender.chromeOf(playing(at = 11, stampedAt = 2_000), layout, null, settings, skin)

        assertEquals(before, after)
    }

    @Test
    fun `a changing bitrate is not a chrome change`() {
        // a VBR bitrate changes several times a second. The readout is a
        // layer; the chrome must not see the number
        val a = WidgetSender.chromeOf(playing(at = 10, stampedAt = 1_000).copy(bitrateKbps = 224), layout, null, settings, skin)
        val b = WidgetSender.chromeOf(playing(at = 10, stampedAt = 1_000).copy(bitrateKbps = 320), layout, null, settings, skin)

        assertEquals(a, b)
    }

    /**
     * The widget keys its picture by the skin's cache key, not its id: a live
     * skin keeps its id when the wallpaper changes its colors.
     */
    @Test
    fun `a palette change is a chrome change`() {
        val base = playing(at = 10, stampedAt = 1_000)

        val before = WidgetSender.chromeOf(base, layout, null, settings, "base|1|1")
        val after = WidgetSender.chromeOf(base, layout, null, settings, "base|2|2")

        assertNotEquals(before, after)
    }

    @Test
    fun `what the layers cannot carry is a chrome change`() {
        val base = playing(at = 10, stampedAt = 1_000)
        val chrome = WidgetSender.chromeOf(base, layout, null, settings, skin)

        assertNotEquals(
            "a new track changes the chrome",
            chrome,
            WidgetSender.chromeOf(base.copy(title = "Hexagons"), layout, null, settings, skin),
        )
        assertNotEquals(
            "a flag changes the chrome",
            chrome,
            WidgetSender.chromeOf(base.copy(shuffle = true), layout, null, settings, skin),
        )
        assertNotEquals("a press changes the chrome", chrome, WidgetSender.chromeOf(base, layout, "main.play", settings, skin))
        assertNotEquals(
            "a setting changes the chrome",
            chrome,
            WidgetSender.chromeOf(base, layout, null, settings.copy(refresh = WidgetRefresh.LOW), skin),
        )
    }

    @Test
    fun `every tap target owns its request code alone`() {
        // codes across BOTH sliders plus the transport's ordinals: any two
        // sharing a code are one PendingIntent, and the last one placed wins
        val sliderCodes =
            WidgetSliders
                .steps(layout, seekable = true, volumeControl = true)
                .map { WidgetIntents.slideRequestCode(it.slider, it.fraction) }
        val buttonCodes = WidgetButton.entries.map { it.ordinal }
        val all = sliderCodes + buttonCodes

        assertEquals(
            "every request code is unique; colliding: ${all.groupBy { it }.filterValues { it.size > 1 }.keys}",
            all.size,
            all.distinct().size,
        )
    }

    @Test
    fun `the far end of a slider stays inside its own range`() {
        // seek at fraction 1.0 must not land on volume 0
        val seekEnd = WidgetIntents.slideRequestCode(WidgetSlider.SEEK, 1f)
        val volumeZero = WidgetIntents.slideRequestCode(WidgetSlider.VOLUME, 0f)

        assertTrue("seek 1.0 ($seekEnd) stays below volume 0 ($volumeZero)", seekEnd < volumeZero)
    }

    @Test
    fun `offering frames advances the batch generation, and clearing an empty batch does not`() {
        val before = WidgetVisFrames.batch.generation

        WidgetVisFrames.offer(listOf())
        WidgetVisFrames.offer(listOf())
        assertNotEquals("each offered batch advances the generation", before, WidgetVisFrames.batch.generation)

        val cleared = WidgetVisFrames.batch.generation
        WidgetVisFrames.clear()
        assertEquals("clearing an empty batch keeps the generation", cleared, WidgetVisFrames.batch.generation)
    }

    @Test
    fun `a new skin is a chrome change`() {
        // without the skin in the chrome, a change would repaint the patches
        // in the new skin over a window still in the old one
        val base = playing(at = 10, stampedAt = 1_000)

        assertNotEquals(
            WidgetSender.chromeOf(base, layout, null, settings, "base"),
            WidgetSender.chromeOf(base, layout, null, settings, "a1b2c3"),
        )
    }

    @Test
    fun `an unlayered widget always needs the whole window`() {
        // the layers are patches of the full player; the shade paints its own
        // clock and thumb into the base picture, so it is never patched
        val shaded = WidgetLayout.choose(MAIN_W * 2, 60)

        assertTrue("a 60px box draws the shade", shaded.shaded)
        assertTrue(
            WidgetSender.needsWholeWindow(
                layered = false,
                whole = false,
                sentBefore = true,
                chromeSame = true,
                patches = 0,
            ),
        )
    }

    @Test
    fun `a settled tick sends a patch, and each reason to stop takes it away`() {
        fun needs(
            layered: Boolean = true,
            whole: Boolean = false,
            sentBefore: Boolean = true,
            chromeSame: Boolean = true,
            patches: Int = 0,
        ) = WidgetSender.needsWholeWindow(layered, whole, sentBefore, chromeSame, patches)

        assertFalse("a settled tick is a patch", needs())
        assertTrue("an unlayered widget needs the whole window", needs(layered = false))
        assertTrue("a broadcast needs the whole window", needs(whole = true))
        assertTrue("a first send needs the whole window", needs(sentBefore = false))
        assertTrue("a chrome change needs the whole window", needs(chromeSame = false))
        assertTrue("60 patches in a row need the whole window", needs(patches = 60))
        assertFalse("59 patches in a row still allow a patch", needs(patches = 59))
    }

    @Test
    fun `the balance thumb moving repaints the window`() {
        // it is painted into the base picture, not into a patch, so only a
        // whole-window send carries it
        val base = playing(at = 10, stampedAt = 1_000)

        assertNotEquals(
            WidgetSender.chromeOf(base, layout, null, settings, skin),
            WidgetSender.chromeOf(base.copy(balance = -40), layout, null, settings, skin),
        )
        assertEquals(-40, base.copy(balance = -40).toState().balance)
    }

    @Test
    fun `nothing on the widget takes a press on the balance`() {
        val steps = WidgetSliders.steps(layout, seekable = true, volumeControl = true)

        assertTrue(
            "only seek and volume take presses",
            steps.map { it.slider }.toSet() == setOf(WidgetSlider.SEEK, WidgetSlider.VOLUME),
        )
    }

    @Test
    fun `a silent frame has no signal`() {
        // a silent moment replayed as a loop reads as a broken strip, so a
        // frame says whether it has any signal
        val silence =
            nl.mattix.andamp.state
                .WinampState()
        assertFalse(VisFrame.of(silence, dropCaps = true).hasSignal)

        val bars =
            nl.mattix.andamp.state
                .WinampState()
                .apply { visLevels[3] = 4f }
        assertTrue("a raised band is signal", VisFrame.of(bars, dropCaps = true).hasSignal)

        val wave =
            nl.mattix.andamp.state
                .WinampState()
                .apply { visWave[5] = 3 }
        assertTrue("a waveform that is not a flat line is signal", VisFrame.of(wave, dropCaps = true).hasSignal)
    }
}
