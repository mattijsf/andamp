// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntOffset
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SkinLoader
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.WindowStore
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
 * Tap assist, switched off, arms nothing.
 *
 * The gesture is decided per window, not inside the lens, so this asks a real window:
 * hold the close button, which is nine pixels square, and see whether anything opens.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // the skin decodes through BitmapFactory
@Config(sdk = [35], qualifiers = BIG_WINDOW)
class TapAssistGateTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var skin: Skin
    private lateinit var vm: WinampViewModel

    @Before
    fun setUp() {
        skin = SkinLoader.loadBase(app)
        vm = testViewModel()
    }

    private fun show() {
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                MainFloatWindow(
                    vm,
                    skin,
                    SCALE,
                    widgets = mainWindowWidgets(vm, onExit = {}) {},
                    height = MAIN_H,
                    defaultOffset = IntOffset.Zero,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.waitForIdle()
    }

    /** A hold on the close button, three pixels short of it - inside the hit slop. */
    private fun holdTheCloseButton() {
        val at = vm.state.windowRects[WindowStore.MAIN]!!
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput {
            down(Offset((at.left + 261) * SCALE + 1f, (at.top + 7) * SCALE + 1f))
        }
        compose.mainClock.advanceTimeBy(600)
    }

    @Test
    fun `on, a hold on a small button magnifies it`() {
        vm.state.tapAssist = true
        show()

        holdTheCloseButton()

        assertNotNull("a hold on the close button opens the magnifier", vm.state.loupe)
    }

    @Test
    fun `off, the same hold magnifies nothing`() {
        vm.state.tapAssist = false
        show()

        holdTheCloseButton()

        assertNull("no magnifier opens with tap assist off", vm.state.loupe)
    }
}
