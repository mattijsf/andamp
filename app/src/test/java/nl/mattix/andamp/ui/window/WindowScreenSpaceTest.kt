// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import android.app.Application
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntOffset
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SkinLoader
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.WindowStore
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every window measures the same screen.
 *
 * The windows dock against each other in one shared space: a rectangle
 * published by one window is read by the next, and a drag carries its
 * neighbors by their offsets in that space. A window that measures a shorter
 * screen than its neighbors, by padding the gesture bar away before measuring,
 * still publishes into the shared space, and the stack comes apart when a drag
 * translates an offset through it.
 *
 * The gesture inset is dispatched here because with no bars there is nothing to
 * disagree about.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // the skin decodes through BitmapFactory
@Config(sdk = [35], qualifiers = BIG_WINDOW)
class WindowScreenSpaceTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var skin: Skin
    private lateinit var vm: WinampViewModel
    private val mainW = 275
    private val mainH = 116

    @Before
    fun setUp() {
        skin = SkinLoader.loadBase(app)
        vm = testViewModel()
        vm.state.skinManagerOpen = true
        var view: View? = null
        compose.setContent {
            view = LocalView.current
            Box(Modifier.fillMaxSize()) {
                FloatingSkinWindow(
                    id = WindowStore.MAIN,
                    state = vm.state,
                    scale = SCALE,
                    width = mainW,
                    height = mainH,
                    offset = vm.state.mainOffset,
                    defaultOffset = IntOffset.Zero,
                    onMove = { vm.state.mainOffset = it },
                    titleH = TITLE_BAR_H,
                    widgets = emptyList(),
                    dragsGroup = true,
                    modifier = Modifier.fillMaxSize(),
                ) {}
                FloatingSkinWindow(
                    id = WindowStore.EQ,
                    state = vm.state,
                    scale = SCALE,
                    width = mainW,
                    height = mainH,
                    offset = vm.state.eqOffset,
                    defaultOffset = IntOffset(0, mainH),
                    onMove = { vm.state.eqOffset = it },
                    titleH = TITLE_BAR_H,
                    widgets = emptyList(),
                    modifier = Modifier.fillMaxSize(),
                ) {}
                // the skin browser publishes last, the way the stack draws it
                SkinManagerWindow(vm, skin, SCALE, Modifier.fillMaxSize())
            }
        }
        compose.waitForIdle()
        // a phone's gesture bar: the strip the windows must stay clear of
        val insets =
            WindowInsetsCompat
                .Builder()
                .setInsets(WindowInsetsCompat.Type.systemGestures(), Insets.of(0, 0, 0, GESTURE_BAR))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, GESTURE_BAR))
                .build()
        compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(view!!.rootView, insets) }
        compose.waitForIdle()

        // the equalizer starts flush under the player; dock the browser under it
        val eqNow = vm.state.windowRects[WindowStore.EQ]!!
        val skins = vm.state.windowRects[WindowStore.SKINS]!!
        vm.state.skinManagerOffset =
            IntOffset(
                vm.state.skinManagerOffset.x,
                vm.state.skinManagerOffset.y + (eqNow.bottom - skins.top),
            )
        compose.waitForIdle()
    }

    private fun rectOf(id: String) = vm.state.windowRects[id]!!

    @Test
    fun `the stack starts flush`() {
        assertEquals(rectOf(WindowStore.MAIN).bottom, rectOf(WindowStore.EQ).top)
        assertEquals(rectOf(WindowStore.EQ).bottom, rectOf(WindowStore.SKINS).top)
    }

    @Test
    fun `dragging the player carries the stack without letting go`() {
        val mainBefore = rectOf(WindowStore.MAIN)
        val eqBefore = rectOf(WindowStore.EQ)
        val skinsBefore = rectOf(WindowStore.SKINS)
        val x = (mainBefore.left + 60) * SCALE + 1f
        val y = (mainBefore.top + TITLE_BAR_H / 2) * SCALE + 1f

        compose.onRoot().performTouchInput {
            down(Offset(x, y))
            moveTo(Offset(x, y + 20 * SCALE))
            moveTo(Offset(x, y + 40 * SCALE))
            up()
        }
        compose.waitForIdle()

        val main = rectOf(WindowStore.MAIN)
        val eq = rectOf(WindowStore.EQ)
        val skins = rectOf(WindowStore.SKINS)
        assertEquals("the player moves with the drag", mainBefore.top + 40, main.top)
        assertEquals("the equalizer stays flush under the player", main.bottom, eq.top)
        assertEquals("the equalizer travels with the player", eqBefore.top + 40, eq.top)
        assertEquals("the browser stays flush under the equalizer", eq.bottom, skins.top)
        assertEquals("the browser travels with the stack", skinsBefore.top + 40, skins.top)
    }
}
