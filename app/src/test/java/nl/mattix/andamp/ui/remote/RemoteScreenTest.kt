// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.remote

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.ui.widget.Widget
import nl.mattix.andamp.ui.window.testViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a surface that shows one window at a time offers of each window, and how large it draws
 * them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteScreenTest {
    private lateinit var vm: WinampViewModel

    @Before
    fun setUp() {
        vm = testViewModel()
    }

    /** A press and a release in the middle of [widget]. */
    private fun tap(widget: Widget) {
        val middle =
            Offset(
                widget.bounds.center.x
                    .toFloat(),
                widget.bounds.center.y
                    .toFloat(),
            )
        widget.down(vm.state, middle)
        widget.up(vm.state, middle)
    }

    private fun List<Widget>.named(id: String) = first { it.id == id }

    @Test
    fun `the window is as large as whole pixels allow`() {
        assertEquals("a 1280x720 screen", 4, wholeScale(1280, 720))
        assertEquals("an 800x480 screen", 2, wholeScale(800, 480))
        assertEquals("a wide 1920x720 screen", 6, wholeScale(1920, 720))
    }

    @Test
    fun `a room smaller than the window still gets the window`() {
        assertEquals(1, wholeScale(200, 100))
    }

    @Test
    fun `the main window keeps the controls that need no other window`() {
        val ids = mainAlone(vm, onEq = {}, onPl = {}).map { it.id }.toSet()

        assertEquals(MAIN_ALONE + "main.eqToggle" + "main.plToggle", ids)
    }

    @Test
    fun `the transport still works from there`() {
        tap(mainAlone(vm, onEq = {}, onPl = {}).named("main.play"))

        assertEquals(Transport.Playing, vm.state.transport)
    }

    @Test
    fun `the EQ button swaps the screen and opens no window beside it`() {
        var swaps = 0
        val before = vm.state.eqVisible

        tap(mainAlone(vm, onEq = { swaps++ }, onPl = {}).named("main.eqToggle"))

        assertEquals(1, swaps)
        assertEquals("the phone's equalizer window is left as it was", before, vm.state.eqVisible)
    }

    @Test
    fun `the PL button swaps the screen and opens no window beside it`() {
        var swaps = 0
        val before = vm.state.plVisible

        tap(mainAlone(vm, onEq = {}, onPl = { swaps++ }).named("main.plToggle"))

        assertEquals(1, swaps)
        assertEquals("the phone's playlist window is left as it was", before, vm.state.plVisible)
    }

    @Test
    fun `the equalizer's close button swaps back and closes no window`() {
        var swaps = 0
        vm.state.eqVisible = true

        tap(eqAlone(vm, onClose = { swaps++ }).named("eq.close"))

        assertEquals(1, swaps)
        assertTrue("the phone's equalizer window stays open", vm.state.eqVisible)
    }

    @Test
    fun `the close button takes a touch well beside the X`() {
        val close = eqAlone(vm, onClose = {}).named("eq.close")

        assertTrue("left of the shade button", close.bounds.contains(IntOffset(240, 8)))
        assertTrue("under the title bar", close.bounds.contains(IntOffset(268, 15)))
    }

    @Test
    fun `the close button's reach stops above the row of buttons`() {
        val buttonsTop = 18

        assertTrue(EQ_CLOSE_REACH.bottom <= buttonsTop)
    }

    @Test
    fun `the equalizer keeps its sliders and switches`() {
        val ids = eqAlone(vm, onClose = {}).map { it.id }

        assertTrue("the preamp", "eq.preamp" in ids)
        assertTrue("every band", (0 until 10).all { "eq.band$it" in ids })
        assertTrue("the on switch", "eq.on" in ids)
    }

    @Test
    fun `the preset menu and the shade button are left out`() {
        val ids = eqAlone(vm, onClose = {}).map { it.id }

        assertFalse("eq.presets" in ids)
        assertFalse("eq.shade" in ids)
        assertEquals("one close button", 1, ids.count { it == "eq.close" })
    }
}
