// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.IntRect
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SkinLoader
import nl.mattix.andamp.state.LibraryAccess
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.ui.window.PlaylistMenuActions
import nl.mattix.andamp.ui.window.testViewModel
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Double Size with the phone held sideways: two columns fill the screen, the player over the
 * equalizer on the left and the playlist on the right, from the top to the safe bottom.
 *
 * The screen is 1600 by 900 pixels. The width decides: two columns are 550 virtual pixels,
 * laid out at scale 3 on a surface 1650 wide and drawn at 1600.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // the skin decodes through BitmapFactory
@Config(sdk = [35], qualifiers = "w1600dp-h900dp-mdpi")
class SidewaysDoubleSizeTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var skin: Skin
    private lateinit var vm: WinampViewModel

    @Before
    fun setUp() {
        skin = SkinLoader.loadBase(ApplicationProvider.getApplicationContext<Application>())
        vm = testViewModel()
        vm.state.sideways = true
        vm.doubleSize.on = true
        vm.welcome.seen = true
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
            )
        }
        repeat(8) { compose.mainClock.advanceTimeByFrame() }
    }

    private fun rect(id: String): IntRect = vm.state.windowRects[id] ?: error("$id was not laid out")

    @Test
    fun `two columns fill the screen, the playlist on the right from top to safe bottom`() {
        val main = rect(WindowStore.MAIN)
        val eq = rect(WindowStore.EQ)
        val playlist = rect(WindowStore.PLAYLIST)

        assertEquals("the surface is exactly two players wide", 550, vm.state.screenW)
        assertEquals(0, main.left)
        assertEquals(main.bottom, eq.top)
        assertEquals(main.right, playlist.left)
        assertEquals(0, playlist.top)
        assertEquals(vm.state.safeBottom, playlist.bottom)
    }
}
