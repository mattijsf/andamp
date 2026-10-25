// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.ui.menu.AmpModals
import nl.mattix.andamp.ui.menu.menuAnchorBounds
import nl.mattix.andamp.ui.window.BIG_WINDOW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * When a modern skin's layer asks it for ticks on display frames: while its frames want them, for
 * a while after the listener's input, at no more than sixty a second, and never while nobody sees
 * it. The skin has no engine behind it; the screen is as in [WalLayerTest], 900 by 1600 pixels at
 * three to a virtual pixel, and the clock is driven by hand.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = BIG_WINDOW)
class WalFrameLoopTest {
    @get:Rule
    val compose = createComposeRule()

    private val surface = FakeWalSurface().apply { layoutFor = stackOf(300, 100, 2f) }
    private val state = WinampState()
    private val owner = TestOwner().apply { registry.currentState = Lifecycle.State.RESUMED }

    /** [n] frames; a write the test made is seen first, as the app's snapshot manager makes it seen. */
    private fun frames(n: Int = FRAMES) =
        repeat(n) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
        }

    private fun screen() = compose.onNodeWithTag(SCREEN)

    /** One layer on the whole screen, with the app's menus drawn. */
    private fun layer() {
        val parts = partsFor(surface, state)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                Box(Modifier.fillMaxSize().testTag(SCREEN)) {
                    WalLayer(parts, state, SCALE, Modifier.fillMaxSize()) {}
                    AmpModals(state, anchorBounds = { menuAnchorBounds(it, SCALE, 900, 1600, state.windowRects) })
                }
            }
        }
        frames()
    }

    private fun ticksOver(n: Int): Int {
        val before = surface.ticks
        frames(n)
        return surface.ticks - before
    }

    /** [actual] is [expected], give or take the frame a start falls in. */
    private fun assertNear(
        message: String,
        expected: Int,
        actual: Int,
    ) = assertTrue("$message: $actual, not $expected", kotlin.math.abs(actual - expected) <= 1)

    /** A skin whose frames want no ticks from the screen, as a stopped skin's do. */
    private fun resting() {
        surface.layoutFor = { fit -> stackOf(300, 100, 2f)(fit).copy(wantsFrames = false) }
    }

    @Test
    fun `a skin that wants no frames gets no frame loop`() {
        resting()
        layer()

        assertEquals("a second of display frames asks for no tick", 0, ticksOver(SECOND_OF_FRAMES))

        // the skin starts wanting frames, as when the music plays
        surface.layout = surface.layout!!.copy(wantsFrames = true)
        assertNear("then one tick a frame", FRAMES, ticksOver(FRAMES))
        surface.layout = surface.layout!!.copy(wantsFrames = false)
        frames(1)
        assertEquals("and none once it stops wanting them", 0, ticksOver(SECOND_OF_FRAMES))
    }

    @Test
    fun `a touch gives the skin frames for two seconds`() {
        resting()
        layer()

        screen().performTouchInput {
            down(Offset(450f, 100f))
            up()
        }
        val inFirstSecond = ticksOver(SECOND_OF_FRAMES)
        // up to about 1.9 s after the touch
        val inSecondSecond = ticksOver(SECOND_OF_FRAMES - FRAMES)
        frames(2 * FRAMES)

        assertTrue("a tick a frame after the touch: $inFirstSecond", inFirstSecond >= SECOND_OF_FRAMES - 3)
        assertTrue("and on through the second second: $inSecondSecond", inSecondSecond >= SECOND_OF_FRAMES - FRAMES - 3)
        assertEquals("none once two seconds have passed", 0, ticksOver(SECOND_OF_FRAMES))
    }

    @Test
    fun `a pick from the skin's menu gives the skin frames too`() {
        resting()
        layer()
        surface.menu = skinMenuOf("Play")
        frames()
        assertEquals("no frames while the menu waits", 0, ticksOver(FRAMES))

        compose.onNodeWithText("Play").performClick()

        assertEquals("answered", listOf(1), surface.answers.map { it.second })
        assertTrue("a tick a frame after the pick", ticksOver(FRAMES) >= FRAMES - 1)
    }

    @Test
    fun `the frame loop asks for at most sixty ticks a second`() {
        layer()

        val ticks = ticksOver(SECOND_OF_FRAMES)

        assertTrue("$ticks ticks in a second of 16 ms frames", ticks in 58..61)
    }

    @Test
    fun `on a 60, 90 or 120 Hz display the frame loop ticks sixty times a second`() {
        for (hz in listOf(60, 90, 120)) {
            val pace = WalTickPace()
            val frame = 1_000_000_000L / hz
            val ticks = (0 until hz).count { pace.due(START_NANOS + it * frame) }
            assertTrue("$hz Hz: $ticks ticks", ticks in 59..61)
        }
        // frames 8.3, 11.1 and 16.7 ms apart tick about 16.7 ms apart on average
        for (frameMs in listOf(8.333, 11.111, 16.667)) {
            val pace = WalTickPace()
            val frame = (frameMs * 1_000_000).toLong()
            val at = (0 until 240).map { START_NANOS + it * frame }.filter { pace.due(it) }
            val meanMs = (at.last() - at.first()) / (at.size - 1) / 1e6
            assertTrue("$frameMs ms frames: ticks $meanMs ms apart", meanMs in 16.5..17.0)
        }
    }

    @Test
    fun `after a pause the frame loop ticks on its first frame and does not catch up`() {
        val pace = WalTickPace()
        val frame = 8_333_333L
        assertTrue(pace.due(START_NANOS))
        // ten seconds later, at 120 Hz
        val later = START_NANOS + 10_000_000_000L
        val ticks = (0 until 12).count { pace.due(later + it * frame) }
        assertEquals("every other frame, as before the pause", 6, ticks)
    }

    @Test
    fun `covered, dark or without the turn, the skin is told to rest`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val power = shadowOf(app.getSystemService(PowerManager::class.java))
        layer()
        assertEquals("shown", true, surface.awake)

        state.playerCovered = true
        frames()
        assertEquals("covered", false, surface.awake)
        state.playerCovered = false
        frames()
        assertEquals("uncovered", true, surface.awake)

        power.setIsInteractive(false)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        frames()
        assertEquals("dark", false, surface.awake)
        power.setIsInteractive(true)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        shadowOf(Looper.getMainLooper()).idle()
        frames()
        assertEquals("lit again", true, surface.awake)

        owner.registry.currentState = Lifecycle.State.STARTED
        frames()
        assertEquals("the turn let go", false, surface.awake)
    }

    private companion object {
        const val SCALE = 3
        const val FRAMES = 6
        const val SCREEN = "screen"

        /** A second of the test clock's 16 ms frames. */
        const val SECOND_OF_FRAMES = 62

        /** Where a paced run of display frames starts, in ns. */
        const val START_NANOS = 5_000_000_000L
    }
}
