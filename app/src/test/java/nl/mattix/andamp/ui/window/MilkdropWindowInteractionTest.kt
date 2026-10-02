// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.ui.ScaledWindowCanvas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = BIG_WINDOW)
class MilkdropWindowInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var vm: WinampViewModel

    @Before
    fun setUp() {
        vm = testViewModel()
        vm.state.milkdropOn = true
        val widgets = milkdropWidgets(vm, GenFrame)
        compose.setContent {
            ScaledWindowCanvas(MILKDROP_W, milkdropHeight(GenFrame), SCALE, vm.state, widgets) {}
        }
    }

    @Test
    fun `the close button shuts the plug-in window`() {
        // close sits at (264,3) 9x9, like every other window's X
        compose.onRoot().performTouchInput { tapVirtual(268f, 7f) }
        assertEquals(false, vm.state.milkdropOn)
    }

    @Test
    fun `holding the close button marks it pressed`() {
        compose.onRoot().performTouchInput { down(Offset(268f * SCALE, 7f * SCALE)) }
        assertEquals("milkdrop.close", vm.state.pressedWidget)
        compose.onRoot().performTouchInput { up() }
        assertNull(vm.state.pressedWidget)
    }

    @Test
    fun `releasing off the button cancels the close, as Winamp does`() {
        compose.onRoot().performTouchInput {
            down(Offset(268f * SCALE, 7f * SCALE))
            moveTo(Offset(120f * SCALE, 80f * SCALE))
            up()
        }
        assertEquals(true, vm.state.milkdropOn)
    }
}
