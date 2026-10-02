// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import nl.mattix.andamp.skin.Dest
import nl.mattix.andamp.state.EqOps
import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.ui.ScaledWindowCanvas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
class EqWindowInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var vm: WinampViewModel

    @Before
    fun setUp() {
        vm = testViewModel()
        val widgets = eqWindowWidgets(vm)
        compose.setContent {
            ScaledWindowCanvas(EQ_W, EQ_H, SCALE, vm.state, widgets) {}
        }
    }

    @Test
    fun `on and auto buttons latch`() {
        compose.onRoot().performTouchInput { tapVirtual(27f, 24f) } // ON (14,18) 26x12
        assertEquals(false, vm.state.eqOn)
        compose.onRoot().performTouchInput { tapVirtual(27f, 24f) }
        assertEquals(true, vm.state.eqOn)
        compose.onRoot().performTouchInput { tapVirtual(56f, 24f) } // AUTO (40,18) 32x12
        assertEquals(true, vm.state.eqAuto)
    }

    @Test
    fun `band slider maps tap height to 0-63`() {
        // band 0 at x=78, y=38, 14x63, 11px thumb, 51px travel: frac = 1 - (y-38-5.5)/51.
        // Taps land past the travel ends (still inside bounds) so clamping makes them exact.
        compose.onRoot().performTouchInput { tapVirtual(85f, 40f) } // above top of travel
        assertEquals(63, vm.state.eqBands[0])
        compose.onRoot().performTouchInput { tapVirtual(85f, 99f) } // below bottom of travel
        assertEquals(0, vm.state.eqBands[0])
        compose.onRoot().performTouchInput { tapVirtual(85f, 69f) } // center: ±1 injection tolerance
        assertEquals(true, vm.state.eqBands[0] in 30..34)
    }

    @Test
    fun `each band hits its own slider at 18px pitch`() {
        compose.onRoot().performTouchInput { tapVirtual(85f + 9 * 18f, 40f) } // band 9 at x=240
        assertEquals(63, vm.state.eqBands[9])
        assertEquals(EqOps.CENTER, vm.state.eqBands[8]) // neighbor untouched
    }

    @Test
    fun `preamp slider is independent of the bands`() {
        compose.onRoot().performTouchInput { tapVirtual(28f, 40f) } // preamp at x=21
        assertEquals(63, vm.state.preamp)
        assertEquals(EqOps.CENTER, vm.state.eqBands[0])
    }

    @Test
    fun `double-tapping the 0db label resets all bands to center`() {
        compose.onRoot().performTouchInput { tapVirtual(85f, 40f) } // band 0 -> 63
        compose.onRoot().performTouchInput { tapVirtual(85f + 9 * 18f, 99f) } // band 9 -> 0
        assertEquals(63, vm.state.eqBands[0])
        assertEquals(0, vm.state.eqBands[9])

        compose.onRoot().performTouchInput { tapVirtual(56f, 68f) } // 0db label (45,64) 22x8
        assertEquals(63, vm.state.eqBands[0]) // single tap does nothing
        compose.onRoot().performTouchInput { tapVirtual(56f, 68f) }
        assertEquals(List(10) { EqOps.CENTER }, vm.state.eqBands.toList())
    }

    @Test
    fun `band drag follows the pointer vertically`() {
        compose.onRoot().performTouchInput { dragVirtual(85f, 99f, 85f, 40f) }
        assertEquals(63, vm.state.eqBands[0])
    }

    @Test
    fun `tapping presets opens the presets menu anchored at the button`() {
        assertEquals(null, vm.state.activeMenu)
        compose.onRoot().performTouchInput { tapVirtual(239f, 24f) } // PRESETS (217,18) 44x12
        assertEquals("Equalizer presets", vm.state.activeMenu?.title)
        // without the anchor the popup degrades to the mid-screen fallback
        assertEquals(MenuAnchor("eq", 217, 18, 44, 12), vm.state.activeMenu?.anchor)
    }

    @Test
    fun `sweeping across bands paints each one it passes, like Winamp`() {
        // hold band 0 near the top and sweep right across bands 1 and 2
        compose.onRoot().performTouchInput {
            down(Offset(85f * SCALE, 40f * SCALE))
            moveTo(Offset((85f + 18f) * SCALE, 45f * SCALE))
            moveTo(Offset((85f + 36f) * SCALE, 99f * SCALE))
            up()
        }
        assertEquals(63, vm.state.eqBands[0]) // the band we grabbed keeps its value
        assertEquals(true, vm.state.eqBands[1] > 40) // painted high on the way past
        assertEquals(0, vm.state.eqBands[2]) // and this one got dragged to the floor
        assertEquals(EqOps.CENTER, vm.state.eqBands[3]) // untouched
    }

    @Test
    fun `the pressed band follows the finger during a sweep`() {
        compose.onRoot().performTouchInput { down(Offset(85f * SCALE, 60f * SCALE)) }
        assertEquals("eq.band0", vm.state.pressedWidget)
        compose.onRoot().performTouchInput { moveTo(Offset((85f + 36f) * SCALE, 60f * SCALE)) }
        assertEquals("eq.band2", vm.state.pressedWidget)
        compose.onRoot().performTouchInput { up() }
        assertNull(vm.state.pressedWidget)
    }

    @Test
    fun `the preamp is not part of the band sweep`() {
        // dragging from the preamp across the bands keeps control of the
        // preamp (it follows the finger's y) and must not paint any band
        compose.onRoot().performTouchInput {
            down(Offset(28f * SCALE, 40f * SCALE))
            moveTo(Offset(85f * SCALE, 99f * SCALE))
            up()
        }
        assertEquals(0, vm.state.preamp) // followed the finger to the bottom
        assertEquals(EqOps.CENTER, vm.state.eqBands[0]) // the band it passed over is untouched
    }

    /** The row a tap has to land on to ask for [value] on the preamp. */
    private fun preampY(value: Int) = Dest.EQ_SLIDER_Y + SliderMath.eqThumbOffset(value) + 5f

    @Test
    fun `the preamp snaps to 0dB just off the middle of its travel`() {
        // aiming for a hair below flat: without the detent this lands on 30
        compose.onRoot().performTouchInput { tapVirtual(28f, preampY(EqOps.CENTER - SliderMath.PREAMP_DETENT)) }

        assertEquals(EqOps.CENTER, vm.state.preamp)
    }

    @Test
    fun `a preamp value just outside the detent is still reachable`() {
        // a couple of pixels below the detent: the thumb's travel is 51px for
        // 63 steps, so a step is not a pixel and the nearest reachable value
        // below the detent is a few steps down
        val reachable = EqOps.CENTER - SliderMath.PREAMP_DETENT - 4

        compose.onRoot().performTouchInput { tapVirtual(28f, preampY(reachable)) }

        assertNotEquals("a value just outside the detent is reachable", EqOps.CENTER, vm.state.preamp)
    }

    @Test
    fun `the close button hides the equalizer`() {
        compose.onRoot().performTouchInput { tapVirtual(268f, 7f) }

        assertEquals(false, vm.state.eqVisible)
    }
}
