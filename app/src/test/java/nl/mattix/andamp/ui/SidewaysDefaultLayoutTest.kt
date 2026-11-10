// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SkinLoader
import nl.mattix.andamp.state.LibraryAccess
import nl.mattix.andamp.state.WinampViewModel
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
 * The phone held sideways with nothing moved yet: the player over the equalizer on the left,
 * and the playlist beside them, drawn at the scale that fits the two columns.
 *
 * The screen is 1600 by 900 pixels, so the player is drawn at scale 2 and the two columns
 * stand in the middle of an 800 pixel wide surface.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // the skin decodes through BitmapFactory
@Config(sdk = [35], qualifiers = "w1600dp-h900dp-mdpi")
class SidewaysDefaultLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var skin: Skin
    private lateinit var vm: WinampViewModel

    @Before
    fun setUp() {
        skin = SkinLoader.loadBase(ApplicationProvider.getApplicationContext<Application>())
        vm = testViewModel()
        vm.state.sideways = true
        // its welcome is answered, so the player is the only window on screen
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
        // a few frames: the windows publish their rectangles a frame after they are placed
        repeat(8) { compose.mainClock.advanceTimeByFrame() }
    }

    @Test
    fun `the arrangement is drawn at the scale that fits it`() {
        assertEquals(800, vm.state.screenW)
    }
}
