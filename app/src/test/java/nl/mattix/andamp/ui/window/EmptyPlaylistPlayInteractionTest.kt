// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.ui.ScaledWindowCanvas
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The buttons that open the file browser, pressed through the main window's hit-rects.
 *
 * [PlayIntentTest] covers the rule; this covers the wiring: the Play hit-rect reaches it,
 * and Eject reaches the browser.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = BIG_WINDOW)
class EmptyPlaylistPlayInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var vm: WinampViewModel
    private var opened = 0
    private var openedToPlay = 0

    @Before
    fun setUp() {
        vm = testViewModel()
        val widgets =
            mainWindowWidgets(vm, onOpenFile = { toPlay ->
                opened++
                if (toPlay) openedToPlay++
            }) {}
        compose.setContent {
            ScaledWindowCanvas(MAIN_W, MAIN_H, SCALE, vm.state, widgets) {}
        }
    }

    /** webamp's main-window.css: the play button is (39,88), 23 by 18. */
    private fun pressPlay() = compose.onRoot().performTouchInput { tapVirtual(50f, 97f) }

    /** webamp's main-window.css: eject is (136,89), 22 by 16. */
    private fun pressEject() = compose.onRoot().performTouchInput { tapVirtual(147f, 97f) }

    @Test
    fun `play on an empty playlist opens the file browser`() {
        vm.state.playlist = emptyList()

        pressPlay()

        assertEquals("the file browser opens", 1, opened)
        assertEquals("nothing plays", Transport.Stopped, vm.state.transport)
    }

    @Test
    fun `play with a queue plays instead of opening anything`() {
        // testViewModel starts with tracks loaded
        pressPlay()

        assertEquals("the file browser stays closed", 0, opened)
        assertEquals(Transport.Playing, vm.state.transport)
    }

    @Test
    fun `the browser Play opens is asked to play what it returns`() {
        vm.state.playlist = emptyList()

        pressPlay()

        assertEquals("the browser opened by Play is asked to play", 1, openedToPlay)
    }

    @Test
    fun `the browser Eject opens is not asked to play`() {
        // Eject means open, not play; only Play's own press starts anything
        pressEject()

        assertEquals(1, opened)
        assertEquals(0, openedToPlay)
    }

    @Test
    fun `eject opens the file browser whatever the queue holds`() {
        pressEject()
        assertEquals(1, opened)

        vm.state.playlist = emptyList()
        pressEject()
        assertEquals(2, opened)
    }
}
