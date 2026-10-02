// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import nl.mattix.andamp.core.model.Transport
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
class MainWindowInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var vm: WinampViewModel

    private var optionsTaps = 0

    private var exits = 0
    private var minimizes = 0
    private var alwaysOnTop = 0
    private var optionsMenus = 0

    @Before
    fun setUp() {
        vm = testViewModel()
        val widgets =
            mainWindowWidgets(
                vm,
                onExit = { exits++ },
                onMinimize = { minimizes++ },
                onAlwaysOnTop = { alwaysOnTop++ },
                optionsMenu = { anchor ->
                    optionsMenus++
                    nl.mattix.andamp.state
                        .AmpMenu("Options", emptyList(), anchor)
                },
                // the app hands the menu in; a window on its own has none
                visualizerMenu = { anchor ->
                    nl.mattix.andamp.ui.menu
                        .visualizationMenu(vm, anchor)
                },
            ) { optionsTaps++ }
        compose.setContent {
            // draw is a no-op: these tests cover hit-rects and pointer mapping only
            ScaledWindowCanvas(MAIN_W, MAIN_H, SCALE, vm.state, widgets) {}
        }
    }

    @Test
    fun `the clutter bar's O opens the options menu`() {
        // webamp's main-window.css: the bar is at (10,22) and O is its first
        // letter, 8px tall from three pixels in
        compose.onRoot().performTouchInput { tapVirtual(14f, 29f) }

        assertEquals(1, optionsMenus)
        assertEquals("Options", vm.state.activeMenu?.title)
    }

    @Test
    fun `the Winamp logo opens the about box`() {
        // webamp's main-window.css: #about sits at (253,91), 13 by 15
        compose.onRoot().performTouchInput { tapVirtual(259f, 98f) }

        assertEquals(true, vm.state.aboutOpen)
    }

    @Test
    fun `a held clutter letter is the pressed widget`() {
        compose.onRoot().performTouchInput { down(Offset(14f * SCALE, 29f * SCALE)) }

        assertEquals("main.clutter.o", vm.state.pressedWidget)

        compose.onRoot().performTouchInput { up() }
        assertEquals(null, vm.state.pressedWidget)
    }

    @Test
    fun `the clutter bar's A asks for always on top`() {
        compose.onRoot().performTouchInput { tapVirtual(14f, 36f) }

        assertEquals(1, alwaysOnTop)
        assertEquals("the A opens no options menu", 0, optionsMenus)
    }

    @Test
    fun `switching always on top on hands over to the floating player`() {
        // the gate says "not now" while the app is in front, so switching it on
        // and waiting for the app to be left would look like a dead button
        val ops = overlayOpsThatGrant()
        var handovers = 0

        val turningOn = !ops.gate.wanted
        ops.askFor(on = turningOn, prompt = {}, openSettings = {})
        if (turningOn && ops.gate.wanted) handovers++

        assertEquals(1, handovers)
    }

    @Test
    fun `switching it off hands over nothing`() {
        val ops = overlayOpsThatGrant()
        ops.askFor(on = true, prompt = {}, openSettings = {})
        var handovers = 0

        val turningOn = !ops.gate.wanted
        ops.askFor(on = turningOn, prompt = {}, openSettings = {})
        if (turningOn && ops.gate.wanted) handovers++

        assertEquals(0, handovers)
    }

    private fun overlayOpsThatGrant() =
        nl.mattix.andamp.state.overlay
            .OverlayOps(
                nl.mattix.andamp.state.overlay
                    .OverlayStore(
                        androidx.test.core.app.ApplicationProvider
                            .getApplicationContext(),
                    ),
                permitted = { true },
            ).also { it.want(false) }

    @Test
    fun `tapping minimize asks to minimize and not to exit`() {
        compose.onRoot().performTouchInput { tapVirtual(248f, 7f) } // minimize (244,3) 9x9

        assertEquals(1, minimizes)
        assertEquals("minimize does not exit", 0, exits)
    }

    @Test
    fun `tapping the titlebar options button fires the menu opener`() {
        compose.onRoot().performTouchInput { tapVirtual(10f, 7f) } // options (6,3) 9x9
        assertEquals(1, optionsTaps)
    }

    @Test
    fun `a near miss within the hit slop still lands on the options button`() {
        compose.onRoot().performTouchInput { tapVirtual(3f, 1f) } // 3px left, 2px above (6,3)
        assertEquals(1, optionsTaps)
    }

    @Test
    fun `tapping play starts playback and stop resets`() {
        compose.onRoot().performTouchInput { tapVirtual(50f, 97f) } // play (39,88) 23x18
        assertEquals(Transport.Playing, vm.state.transport)

        vm.state.currentTimeSec = 3
        compose.onRoot().performTouchInput { tapVirtual(96f, 97f) } // stop (85,88)
        assertEquals(Transport.Stopped, vm.state.transport)
        assertEquals(0, vm.state.currentTimeSec)
    }

    @Test
    fun `tapping pause toggles between pause and resume`() {
        compose.onRoot().performTouchInput { tapVirtual(50f, 97f) } // play
        compose.onRoot().performTouchInput { tapVirtual(73f, 97f) } // pause (62,88)
        assertEquals(Transport.Paused, vm.state.transport)
        compose.onRoot().performTouchInput { tapVirtual(73f, 97f) }
        assertEquals(Transport.Playing, vm.state.transport)
    }

    @Test
    fun `shuffle and repeat latch on and off`() {
        compose.onRoot().performTouchInput { tapVirtual(187f, 96f) } // shuffle (164,89) 47x15
        assertEquals(true, vm.state.shuffle)
        compose.onRoot().performTouchInput { tapVirtual(187f, 96f) }
        assertEquals(false, vm.state.shuffle)
        compose.onRoot().performTouchInput { tapVirtual(224f, 96f) } // repeat (210,89) 28x15
        assertEquals(true, vm.state.repeat)
    }

    @Test
    fun `eq and playlist toggles collapse the windows`() {
        compose.onRoot().performTouchInput { tapVirtual(230f, 64f) } // EQ toggle (219,58) 23x12
        assertEquals(false, vm.state.eqVisible)
        compose.onRoot().performTouchInput { tapVirtual(253f, 64f) } // PL toggle (242,58)
        assertEquals(false, vm.state.plVisible)
    }

    @Test
    fun `volume drag maps track position to 0-100`() {
        // volume (107,57) 68x13, 14px thumb, 51px effective travel: frac = (x-107-7)/51
        compose.onRoot().performTouchInput { dragVirtual(139.5f, 63f, 170f, 63f) } // past right end -> clamp
        assertEquals(100, vm.state.volume)
        compose.onRoot().performTouchInput { tapVirtual(110f, 63f) } // past left end -> clamp
        assertEquals(0, vm.state.volume)
        compose.onRoot().performTouchInput { tapVirtual(139.5f, 63f) } // mid: ±1 injection tolerance
        assertEquals(true, vm.state.volume in 49..51)
    }

    @Test
    fun `balance snaps to center inside the sticky detent`() {
        // balance (177,57) 38x13, thumb 14, travel 24: frac = (x-177-7)/24
        compose.onRoot().performTouchInput { tapVirtual(196.5f, 63f) } // dead center (detent absorbs jitter)
        assertEquals(0, vm.state.balance)
        compose.onRoot().performTouchInput { tapVirtual(212f, 63f) } // past right end -> clamp
        assertEquals(100, vm.state.balance)
    }

    @Test
    fun `posbar is inert while stopped`() {
        compose.onRoot().performTouchInput { tapVirtual(140f, 77f) } // posbar (16,72) 248x10
        assertNull(vm.state.seekPreview)
        assertEquals(Transport.Stopped, vm.state.transport)
    }

    @Test
    fun `posbar drag previews then commits the seek while playing`() {
        vm.play()
        // frac = (x-16-14.5)/219 -> x = 30.5 + 219 = 249.5 for 1.0
        compose.onRoot().performTouchInput { down(Offset(140f * SCALE, 77f * SCALE)) }
        assertEquals(true, vm.state.seekPreview != null)
        compose.onRoot().performTouchInput { up() }
        assertNull(vm.state.seekPreview)
        assertEquals(true, vm.state.currentTimeSec > 0)
    }

    @Test
    fun `pressed widget is tracked during the gesture and cleared on release`() {
        compose.onRoot().performTouchInput { down(Offset(50f * SCALE, 97f * SCALE)) }
        assertEquals("main.play", vm.state.pressedWidget)
        compose.onRoot().performTouchInput { up() }
        assertNull(vm.state.pressedWidget)
    }

    @Test
    fun `clicking the visualizer cycles analyzer, oscilloscope, off`() {
        assertEquals(nl.mattix.andamp.state.VisMode.Analyzer, vm.state.visMode)
        compose.onRoot().performTouchInput { tapVirtual(62f, 51f) } // vis area (24,43) 76x16
        assertEquals(nl.mattix.andamp.state.VisMode.Oscilloscope, vm.state.visMode)
        compose.onRoot().performTouchInput { tapVirtual(62f, 51f) }
        assertEquals(nl.mattix.andamp.state.VisMode.Off, vm.state.visMode)
        compose.onRoot().performTouchInput { tapVirtual(62f, 51f) }
        assertEquals(nl.mattix.andamp.state.VisMode.Analyzer, vm.state.visMode)
    }

    @Test
    fun `taps outside any widget do nothing`() {
        compose.onRoot().performTouchInput { tapVirtual(5f, 50f) } // empty chrome
        assertEquals(Transport.Stopped, vm.state.transport)
        assertNull(vm.state.pressedWidget)
    }

    @Test
    fun `long pressing the visualizer opens its context menu instead of cycling`() {
        val before = vm.state.visMode
        compose.onRoot().performTouchInput { down(Offset(62f * SCALE, 51f * SCALE)) }
        compose.mainClock.advanceTimeBy(600)
        compose.onRoot().performTouchInput { up() }

        assertEquals("Visualization", vm.state.activeMenu?.title)
        assertEquals("the long press does not cycle the mode", before, vm.state.visMode)
        assertNull(vm.state.pressedWidget)
    }

    @Test
    fun `a quick tap on the visualizer still cycles and opens no menu`() {
        compose.onRoot().performTouchInput { tapVirtual(62f, 51f) }
        assertEquals(nl.mattix.andamp.state.VisMode.Oscilloscope, vm.state.visMode)
        assertNull(vm.state.activeMenu)
    }

    @Test
    fun `a wobbling finger still counts as a long press`() {
        // a finger resting on glass drifts a pixel or two; only a real drag
        // should cancel the context menu
        compose.onRoot().performTouchInput { down(Offset(62f * SCALE, 51f * SCALE)) }
        compose.onRoot().performTouchInput { moveTo(Offset(63f * SCALE, 52f * SCALE)) }
        compose.mainClock.advanceTimeBy(600)
        compose.onRoot().performTouchInput { up() }
        assertEquals("Visualization", vm.state.activeMenu?.title)
    }

    @Test
    fun `dragging away cancels the long press`() {
        compose.onRoot().performTouchInput { down(Offset(62f * SCALE, 51f * SCALE)) }
        compose.onRoot().performTouchInput { moveTo(Offset(90f * SCALE, 51f * SCALE)) }
        compose.mainClock.advanceTimeBy(600)
        compose.onRoot().performTouchInput { up() }
        assertNull(vm.state.activeMenu)
    }

    @Test
    fun `the close button asks the app to exit`() {
        vm.play()

        compose.onRoot().performTouchInput { tapVirtual(268f, 7f) }

        assertEquals("the X asks to exit once", 1, exits)
    }
}
