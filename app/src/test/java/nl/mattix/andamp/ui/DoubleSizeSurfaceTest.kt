// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import android.app.Application
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.IntRect
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SkinLoader
import nl.mattix.andamp.state.LibraryAccess
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.ui.window.BIG_WINDOW
import nl.mattix.andamp.ui.window.MAIN_H
import nl.mattix.andamp.ui.window.MAIN_W
import nl.mattix.andamp.ui.window.PlaylistMenuActions
import nl.mattix.andamp.ui.window.testViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        vm = testViewModel()
        vm.doubleSize.on = true
        // its welcome is answered, so the player is the only window on screen
        vm.welcome.seen = true
    }

    /**
     * The clock is driven by hand: the player draws a visualizer, which is an animation
     * that never finishes, so waiting for the screen to go idle never returns.
     */
    private fun show() {
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
                onFloatingChanged = {},
            )
        }
        settle()
    }

    /** [FRAMES] frames; a write the test made is seen first, as the app's snapshot manager makes it seen. */
    private fun settle() =
        repeat(FRAMES) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
        }

    private fun rect(id: String): IntRect? = vm.state.windowRects[id]

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

    private companion object {
        const val FRAMES = 8
    }
}
