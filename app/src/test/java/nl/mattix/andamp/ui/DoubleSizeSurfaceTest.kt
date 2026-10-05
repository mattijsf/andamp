// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntRect
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SkinLoader
import nl.mattix.andamp.state.LibraryAccess
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.ui.window.BIG_WINDOW
import nl.mattix.andamp.ui.window.EQ_H
import nl.mattix.andamp.ui.window.MAIN_H
import nl.mattix.andamp.ui.window.MAIN_W
import nl.mattix.andamp.ui.window.PlaylistMenuActions
import nl.mattix.andamp.ui.window.RESIZE_GRIP
import nl.mattix.andamp.ui.window.SHADE_H
import nl.mattix.andamp.ui.window.setShaded
import nl.mattix.andamp.ui.window.testViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Winamp's Double Size on the app's own player: the windows fill the screen as one stack,
 * and the layout they float in is still there when it is switched off.
 *
 * The screen is 900 by 1600 pixels. Filling it, the player is laid out at scale 4 on a
 * surface 1100 wide and drawn at 900, so the screen is exactly one player wide.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // the skin decodes through BitmapFactory
@Config(sdk = [35], qualifiers = BIG_WINDOW)
class DoubleSizeSurfaceTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var skin: Skin
    private lateinit var vm: WinampViewModel

    @Before
    fun setUp() {
        skin = SkinLoader.loadBase(ApplicationProvider.getApplicationContext<Application>())
        // a fresh install: Double Size starts on
        vm = testViewModel()
        // its welcome is answered, so the player is the only window on screen
        vm.welcome.seen = true
    }

    /**
     * The clock is driven by hand: the player draws a visualizer, which is an animation
     * that never finishes, so waiting for the screen to go idle never returns.
     */
    private fun show(fillsScreen: Boolean = true) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PlayerSurface(
                vm,
                skin,
                PlaylistMenuActions(),
                LibraryAccessHandle(LibraryAccess.GRANTED, request = {}),
                onPickSkin = {},
                onPreferences = {},
                onMuseum = {},
                onExit = {},
                onMinimize = {},
                onOverlaySettings = {},
                fillsScreen = fillsScreen,
            )
        }
        settle()
    }

    private fun settle() = repeat(FRAMES) { compose.mainClock.advanceTimeByFrame() }

    private fun rect(id: String): IntRect? = vm.state.windowRects[id]

    /** Where a virtual pixel of the filled screen is drawn. */
    private fun filled(
        x: Int,
        y: Int,
    ) = Offset(x * FILLED_PIXEL + 1f, y * FILLED_PIXEL + 1f)

    /** The floating layout as the store would save it: every window's placement, and their order. */
    private fun floatingLayout() = WindowStore.WINDOWS.map { vm.state.placementOf(it)?.asMemory() } to vm.state.windowOrder

    @Test
    fun `the player, the equalizer and the playlist fill the screen from top to bottom`() {
        show()

        assertTrue(vm.state.stackLocked)
        assertEquals("the screen is one player wide", MAIN_W, vm.state.screenW)
        assertEquals(IntRect(0, 0, MAIN_W, MAIN_H), rect(WindowStore.MAIN))
        assertEquals(IntRect(0, MAIN_H, MAIN_W, MAIN_H + EQ_H), rect(WindowStore.EQ))
        assertEquals(IntRect(0, MAIN_H + EQ_H, MAIN_W, vm.state.safeBottom), rect(WindowStore.PLAYLIST))
    }

    @Test
    fun `the D in the clutter bar switches it off, and the windows float again`() {
        show()

        compose.onRoot().performTouchInput {
            down(filled(14, 51))
            up()
        }
        settle()

        assertFalse(vm.doubleSize.on)
        assertFalse("the D is no longer lit", vm.state.doubleSize)
        assertFalse(vm.state.stackLocked)
        // three screen pixels to a virtual one again, with room beside the player
        assertEquals(900 / 3, vm.state.screenW)
        assertEquals((900 / 3 - MAIN_W) / 2, rect(WindowStore.MAIN)!!.left)
    }

    @Test
    fun `using the stack leaves the layout the windows float in as it was`() {
        show()
        val before = floatingLayout()

        // a drag on the player's title bar, a press that brings a window to the front, and
        // a collapsed equalizer that is left collapsed
        compose.onRoot().performTouchInput { down(filled(100, 5)) }
        settle()
        compose.onRoot().performTouchInput { moveTo(filled(130, 60)) }
        settle()
        compose.onRoot().performTouchInput { up() }
        compose.onRoot().performTouchInput {
            down(filled(100, MAIN_H + 60))
            up()
        }
        vm.state.setShaded(WindowStore.EQ, true, EQ_H)
        settle()
        assertEquals("the stack has the equalizer collapsed", SHADE_H, rect(WindowStore.EQ)!!.height)

        vm.doubleSize.on = false
        settle()

        assertEquals(before, floatingLayout())
        assertEquals("floating, the equalizer stands open", EQ_H, rect(WindowStore.EQ)!!.height)
    }

    @Test
    fun `the stack finds its own collapsed windows when it comes back`() {
        show()
        vm.state.setShaded(WindowStore.EQ, true, EQ_H)
        settle()

        vm.doubleSize.on = false
        settle()
        vm.doubleSize.on = true
        settle()

        assertEquals(SHADE_H, rect(WindowStore.EQ)!!.height)
        assertEquals(IntRect(0, MAIN_H + SHADE_H, MAIN_W, vm.state.safeBottom), rect(WindowStore.PLAYLIST))
    }

    @Test
    fun `a collapsed window gives its rows to the playlist`() {
        show()

        vm.state.setShaded(WindowStore.EQ, true, EQ_H)
        settle()

        val eq = rect(WindowStore.EQ)!!
        assertEquals(MAIN_H, eq.top)
        assertEquals(IntRect(0, eq.bottom, MAIN_W, vm.state.safeBottom), rect(WindowStore.PLAYLIST))
    }

    @Test
    fun `an open library covers everything under the player`() {
        show()

        vm.state.libraryOpen = true
        settle()

        assertEquals(IntRect(0, MAIN_H, MAIN_W, vm.state.safeBottom), rect(WindowStore.LIBRARY))
        assertNull("the equalizer is not shown under it", rect(WindowStore.EQ))
        assertNull("the playlist is not shown under it", rect(WindowStore.PLAYLIST))
        assertNotNull(rect(WindowStore.MAIN))
    }

    @Test
    fun `the plug-in window's grip changes its height in the stack, not the height it floats at`() {
        vm.state.milkdropOn = true
        show()
        val before = rect(WindowStore.MILKDROP)!!

        compose.onRoot().performTouchInput { down(filled(before.right - RESIZE_GRIP / 2, before.bottom - RESIZE_GRIP / 2)) }
        settle()
        // upward: on this screen the playlist's two segments leave it no room to grow
        compose.onRoot().performTouchInput { moveBy(Offset(0f, -40 * FILLED_PIXEL)) }
        settle()
        compose.onRoot().performTouchInput { up() }
        settle()

        val after = rect(WindowStore.MILKDROP)!!
        assertEquals("it stays under the equalizer", before.top, after.top)
        assertEquals("it stays as wide as the screen", MAIN_W, after.width)
        assertTrue("it gets shorter: $before to $after", after.height < before.height)
        assertEquals(
            "the playlist takes the rows it gave up",
            IntRect(0, after.bottom, MAIN_W, vm.state.safeBottom),
            rect(WindowStore.PLAYLIST),
        )
        assertNotNull(vm.doubleSize.visSteps)
        assertNull("the height it floats at is not stored", vm.state.milkdropSteps)
    }

    @Test
    fun `a surface that cannot fill the screen keeps the windows floating, with the D lit from the setting`() {
        show(fillsScreen = false)

        assertTrue("the setting is still on", vm.doubleSize.on)
        assertTrue("and its D is lit", vm.state.doubleSize)
        assertFalse(vm.state.stackLocked)
        assertEquals(900 / 3, vm.state.screenW)
    }

    @Test
    fun `the D there still switches the setting, for when the app has the player again`() {
        show(fillsScreen = false)
        // floating at three screen pixels to a virtual one
        val main = rect(WindowStore.MAIN)!!

        compose.onRoot().performTouchInput {
            down(Offset((main.left + 14) * 3f + 1f, (main.top + 51) * 3f + 1f))
            up()
        }
        settle()

        assertFalse("the setting is switched off", vm.doubleSize.on)
        assertFalse("and the D goes dark", vm.state.doubleSize)
        assertFalse("the windows float as they did", vm.state.stackLocked)
        assertEquals(main, rect(WindowStore.MAIN))
    }

    private companion object {
        const val FRAMES = 8

        /** Scale 4, on a surface 1100 pixels wide that is drawn 900 wide. */
        const val FILLED_PIXEL = 4 * 900f / 1100f
    }
}
