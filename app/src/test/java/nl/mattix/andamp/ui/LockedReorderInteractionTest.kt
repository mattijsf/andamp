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
import nl.mattix.andamp.state.LockedColumns
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.ui.window.BIG_WINDOW
import nl.mattix.andamp.ui.window.EQ_H
import nl.mattix.andamp.ui.window.MAIN_H
import nl.mattix.andamp.ui.window.PlaylistMenuActions
import nl.mattix.andamp.ui.window.testViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Dragging a window in Double Size reorders the stack: the window follows the finger, the
 * others make room where it would land, and it drops into that slot.
 *
 * The screen is 900 by 1600 pixels, filled at scale 4 and drawn at 900 wide.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // the skin decodes through BitmapFactory
@Config(sdk = [35], qualifiers = BIG_WINDOW)
class LockedReorderInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var skin: Skin
    private lateinit var vm: WinampViewModel

    @Before
    fun setUp() {
        skin = SkinLoader.loadBase(ApplicationProvider.getApplicationContext<Application>())
        vm = testViewModel()
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
        settle()
    }

    private fun settle() = repeat(8) { compose.mainClock.advanceTimeByFrame() }

    private fun rect(id: String): IntRect = vm.state.windowRects[id] ?: error("$id was not laid out")

    /** Where a virtual pixel of the filled screen is drawn. */
    private fun filled(
        x: Int,
        y: Int,
    ) = Offset(x * FILLED_PIXEL + 1f, y * FILLED_PIXEL + 1f)

    @Test
    fun `the equalizer dragged past the playlist's middle lands under it`() {
        val playlist = rect(WindowStore.PLAYLIST)
        assertEquals("the stack to begin with", MAIN_H, rect(WindowStore.EQ).top)

        // grab the equalizer by its title bar and take it past the playlist's middle
        compose.onRoot().performTouchInput { down(filled(100, MAIN_H + 7)) }
        settle()
        compose.onRoot().performTouchInput { moveTo(filled(100, playlist.center.y + 60)) }
        settle()
        assertEquals("while it is held, the equalizer follows the finger", WindowStore.EQ, vm.state.lifted?.id)
        assertEquals(
            listOf(listOf(WindowStore.MAIN, WindowStore.MILKDROP, WindowStore.PLAYLIST, WindowStore.EQ)),
            vm.state.lockedColumns,
        )
        compose.onRoot().performTouchInput { up() }
        settle()

        assertNull(vm.state.lifted)
        val pl = rect(WindowStore.PLAYLIST)
        val eq = rect(WindowStore.EQ)
        assertEquals("the playlist moved up under the player", MAIN_H, pl.top)
        assertEquals("and the equalizer dropped in under it", pl.bottom, eq.top)
        assertEquals(EQ_H, eq.height)
    }

    @Test
    fun `a short drag leaves the order alone`() {
        compose.onRoot().performTouchInput { down(filled(100, MAIN_H + 7)) }
        settle()
        compose.onRoot().performTouchInput { moveTo(filled(100, MAIN_H + 30)) }
        settle()
        compose.onRoot().performTouchInput { up() }
        settle()

        assertEquals(LockedColumns.defaultFor(sideways = false), vm.state.lockedColumns)
        assertEquals(MAIN_H, rect(WindowStore.EQ).top)
    }

    private companion object {
        const val FILLED_PIXEL = 4 * 900f / 1100f
    }
}
