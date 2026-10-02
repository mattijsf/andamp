// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.widget

import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SkinLoader
import nl.mattix.andamp.ui.window.MAIN_H
import nl.mattix.andamp.ui.window.MAIN_W
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * What a tick of the widget costs.
 *
 * The moving parts are pictures of their own: they are a small fraction of the
 * window, and each one can tell when it has nothing to send. The tests compare
 * pixel counts, not milliseconds, because a stopwatch in a test measures the
 * machine it runs on.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class WidgetLayerTest {
    private lateinit var skin: Skin

    @Before
    fun loadSkin() {
        skin = SkinLoader.loadBase(ApplicationProvider.getApplicationContext())
    }

    private fun playing(at: Int) =
        WidgetSnapshot(
            title = "Cryogen",
            artist = "Muse",
            durationMs = 301_000,
            positionSec = at,
            transport = Transport.Playing,
        )

    @Test
    fun `a tick redraws a fraction of what the window is`() {
        val layout = WidgetLayout.choose(MAIN_W * 3, MAIN_H * 3)
        val whole = layout.widthPx.toLong() * layout.heightPx

        fun area(part: WidgetLayer) = layout.px(part.width).toLong() * layout.px(part.height)

        // a second moves the clock and the title; the bar only when its thumb
        // reaches the next pixel
        val everySecond = area(WidgetLayer.CLOCK) + area(WidgetLayer.MARQUEE)
        val allLayers = WidgetLayer.entries.sumOf(::area)

        assertTrue("a second redraws under a fifteenth of the window: $everySecond of $whole pixels", everySecond * 15 < whole)
        assertTrue("all layers cover under a fifth of the window: $allLayers of $whole pixels", allLayers * 5 < whole)
    }

    @Test
    fun `each layer draws a bitmap the size of its rectangle`() {
        val layout = WidgetLayout.choose(MAIN_W, MAIN_H)

        WidgetLayer.entries.forEach { part ->
            val bitmap = WidgetRender.layer(skin, layout, playing(at = 10).toState(), part)
            val at = layout.rect(part.left, part.top, part.width, part.height)
            assertEquals("${part.name} width matches its rectangle", at.w, bitmap.width)
            assertEquals("${part.name} height matches its rectangle", at.h, bitmap.height)
        }
    }

    @Test
    fun `a second that changes nothing asks for nothing to be sent`() {
        // a stopped player ticks no clock, so its widget has nothing to send
        val still = playing(at = 10).copy(transport = Transport.Stopped).toState()
        val later = playing(at = 10).copy(transport = Transport.Stopped).toState()

        WidgetLayer.entries.forEach { part ->
            assertEquals("${part.name} keeps its signature", part.signature(still), part.signature(later))
        }
    }

    @Test
    fun `a second of playing moves the clock and the title`() {
        val before = playing(at = 10).toState()
        val after = playing(at = 11).toState()

        assertTrue("the clock's signature changes", WidgetLayer.CLOCK.signature(before) != WidgetLayer.CLOCK.signature(after))
        assertTrue("the title's signature changes", WidgetLayer.MARQUEE.signature(before) != WidgetLayer.MARQUEE.signature(after))
    }

    @Test
    fun `the bar's signature holds until its thumb moves a pixel`() {
        // on an hour-long track a second does not move the thumb a pixel, so
        // the bar is keyed on the thumb and not on the position
        val hour = playing(at = 10).copy(durationMs = 3_600_000)
        val a = hour.toState()
        val b = hour.copy(positionSec = 11).toState()

        assertEquals(
            "the bar's signature holds while the thumb stays put",
            WidgetLayer.POSBAR.signature(a),
            WidgetLayer.POSBAR.signature(b),
        )
        assertTrue("the clock's signature changes", WidgetLayer.CLOCK.signature(a) != WidgetLayer.CLOCK.signature(b))
    }

    @Test
    fun `a playing snapshot reads as stopped once it is stale`() {
        // the widget is only sent something when something changes, so a player
        // killed mid-song sends no correction and its snapshot has to expire
        val written = 10_000L
        val fresh = playing(at = 30).copy(writtenAt = written)

        assertEquals(Transport.Playing, fresh.asKnown(written + 5_000).transport)
        assertEquals(Transport.Stopped, fresh.asKnown(written + WidgetSnapshot.STALE_AFTER_MS + 1).transport)
    }

    @Test
    fun `a stale snapshot keeps its title and flags`() {
        // only the claim to be playing expires
        val fresh = playing(at = 30).copy(writtenAt = 10_000, shuffle = true)
        val stale = fresh.asKnown(10_000 + WidgetSnapshot.STALE_AFTER_MS + 1)

        assertEquals(fresh.title, stale.title)
        assertEquals(fresh.shuffle, stale.shuffle)
    }

    @Test
    fun `a snapshot written later than the clock reads as stopped`() {
        // elapsed time restarts at zero on a reboot, which leaves a snapshot
        // that reads as written in the future
        val fresh = playing(at = 30).copy(writtenAt = 500_000)

        assertEquals(Transport.Stopped, fresh.asKnown(now = 1_000).transport)
    }

    /** Force-stopped a few seconds ago: the picture is young, and nothing is playing it. */
    @Test
    fun `a fresh snapshot with no player in this process reads as stopped`() {
        val fresh = WidgetSnapshot(title = "A Song", transport = Transport.Playing, writtenAt = 10_000)

        val read = fresh.asKnown(now = 12_000, playerHere = false)

        assertEquals(Transport.Stopped, read.transport)
        assertEquals("the stopped snapshot keeps its title", "A Song", read.title)
    }

    @Test
    fun `a layer's patch is the window's own pixels for that rectangle`() {
        // a layer that drew a pixel differently from the window would flicker
        // on every partial update
        val layout = WidgetLayout.choose(MAIN_W, MAIN_H)
        val state = playing(at = 10).toState()
        val window = WidgetRender.bitmap(skin, layout, state)

        WidgetLayer.entries.forEach { part ->
            val patch = WidgetRender.layer(skin, layout, playing(at = 10).toState(), part)
            val crop =
                android.graphics.Bitmap.createBitmap(
                    window,
                    layout.px(part.left),
                    layout.px(part.top),
                    layout.span(part.left, part.width),
                    layout.span(part.top, part.height),
                )
            assertTrue("${part.name} matches the window it patches", patch.sameAs(crop))
        }
    }

    @Test
    fun `every count of views agrees with the count of things put in them`() {
        // the slots live in the XML, the arrays in the sender and the counts
        // in the geometry, and getOrNull would hide a drift between them
        assertEquals(WidgetButton.entries.size, WidgetSender.BUTTON_SLOTS.size)
        assertEquals(WidgetSliders.SEEK_STEPS, WidgetSender.SEEK_SLOTS.size)
        assertEquals(WidgetSliders.VOLUME_STEPS, WidgetSender.VOLUME_SLOTS.size)
        assertEquals(WidgetVisFrames.COUNT, WidgetSender.VIS_SLOTS.size)
    }

    @Test
    fun `no peak cap is frozen into the loop`() {
        // a cap holds and then falls, and a replayed loop has no fall: a
        // frozen cap reads as a stuck stripe, so the loop carries none
        val state = playing(at = 10).toState()
        state.visLevels[3] = 8f
        state.visPeaks[3] = 12f
        state.visPeaks[15] = 12f

        val frame = VisFrame.of(state, dropCaps = true)
        val replayed = playing(at = 10).toState().also { it.visPeaks[7] = 5f }
        frame.applyTo(replayed)

        assertEquals("the bars survive the replay", 8f, replayed.visLevels[3])
        assertTrue("no cap survives the replay", replayed.visPeaks.all { it == 0f })
    }

    @Test
    fun `the widget's marquee counts the song at the player's own number`() {
        // a queue of one would say "1." while the player says "9."
        val state = playing(at = 10).copy(queueNumber = 9).toState()

        assertEquals(8, state.currentIndex)
        assertEquals("Cryogen", state.playlist[state.currentIndex].title)
    }

    @Test
    fun `a patch at a fractional scale lands on the pixels it covers`() {
        // rounding happens once, in WidgetLayout: the window and the patch
        // over it are rasterized separately, and at a fractional scale a patch
        // that rounded its own origin differently would sit up to a pixel off
        // the art underneath
        val layout = WidgetLayout.choose(MAIN_W * 3 + 24, MAIN_H * 4, fill = true)
        assertTrue("this box has a fractional scale: ${layout.factor}", layout.factor % 1f != 0f)
        val state = playing(at = 10).toState()
        val whole = WidgetRender.bitmap(skin, layout, state)

        fun channels(
            pixel: Int,
            other: Int,
        ) = (0..2).maxOf { at -> abs(((pixel shr (at * 8)) and 0xFF) - ((other shr (at * 8)) and 0xFF)) }

        WidgetLayer.entries.forEach { part ->
            val at = layout.rect(part.left, part.top, part.width, part.height)
            val patch = WidgetRender.layer(skin, layout, state, part)
            val points = (0 until at.h).flatMap { y -> (0 until at.w).map { x -> x to y } }
            val worst = points.maxOf { (x, y) -> channels(patch.getPixel(x, y), whole.getPixel(at.x + x, at.y + y)) }
            val shifted = points.count { (x, y) -> patch.getPixel(x, y) != whole.getPixel(at.x + x + 1, at.y + y) }
            // what a sideways shift could disturb: a skin may leave a layer on
            // a flat field that reads the same a pixel over
            val varied = points.count { (x, y) -> whole.getPixel(at.x + x, at.y + y) != whole.getPixel(at.x + x + 1, at.y + y) }

            // one step in 255 is Skia rounding a blended edge
            assertTrue("${part.name} is within 1 of 255: $worst", worst <= 1)
            // the same patch one pixel over has to differ, so the check above can fail
            assertTrue("${part.name} has sideways detail to test with", varied > 0)
            assertTrue("${part.name} differs a pixel over", shifted >= varied / 2)
        }
    }

    @Test
    fun `switching to time remaining changes the clock's signature`() {
        // the snapshot carries the player's readout preference, and the
        // clock's signature sees it, so the change repaints at once
        val up = playing(at = 10).toState()
        val down = playing(at = 10).copy(timeRemaining = true).toState()

        assertTrue("the snapshot carries time remaining", down.timeRemaining)
        assertNotEquals(
            WidgetLayer.CLOCK.signature(up),
            WidgetLayer.CLOCK.signature(down),
        )
    }
}
