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
    fun `left sharp, the window is not shrunk`() {
        val fit = remoteViewport(1280, 720, fill = false)

        assertEquals(4, fit.scale)
        assertEquals(1f, fit.shrink)
    }

    @Test
    fun `filling a wide room, the window is as wide as the room`() {
        val fit = remoteViewport(1280, 720, fill = true)

        assertEquals("laid out at the next whole scale up", 5, fit.scale)
        assertEquals("275 at five to one, drawn 1280 wide", 1280f, 275 * fit.scale * fit.shrink, 0.01f)
        assertTrue("and no taller than the room", 116 * fit.scale * fit.shrink <= 720f)
    }

    @Test
    fun `filling a tall room, the window is as tall as the room`() {
        val fit = remoteViewport(1920, 500, fill = true)

        assertEquals(500f, 116 * fit.scale * fit.shrink, 0.01f)
        assertTrue("and no wider than the room", 275 * fit.scale * fit.shrink <= 1920f)
    }

    @Test
    fun `a room that is a whole multiple of the window is filled without shrinking`() {
        val fit = remoteViewport(1100, 720, fill = true)

        assertEquals(4, fit.scale)
        assertEquals(1f, fit.shrink)
    }

    @Test
    fun `the main window keeps the controls that need no other window`() {
        val ids = mainAlone(vm, onEq = {}, onPl = {}, onFill = {}).map { it.id }.toSet()

        assertEquals(MAIN_ALONE + "main.eqToggle" + "main.plToggle" + "main.clutter.d", ids)
    }

    @Test
    fun `the transport still works from there`() {
        tap(mainAlone(vm, onEq = {}, onPl = {}, onFill = {}).named("main.play"))

        assertEquals(Transport.Playing, vm.state.transport)
    }

    @Test
    fun `the EQ button swaps the screen and opens no window beside it`() {
        var swaps = 0
        val before = vm.state.eqVisible

        tap(mainAlone(vm, onEq = { swaps++ }, onPl = {}, onFill = {}).named("main.eqToggle"))

        assertEquals(1, swaps)
        assertEquals("the phone's equalizer window is left as it was", before, vm.state.eqVisible)
    }

    @Test
    fun `the PL button swaps the screen and opens no window beside it`() {
        var swaps = 0
        val before = vm.state.plVisible

        tap(mainAlone(vm, onEq = {}, onPl = { swaps++ }, onFill = {}).named("main.plToggle"))

        assertEquals(1, swaps)
        assertEquals("the phone's playlist window is left as it was", before, vm.state.plVisible)
    }

    @Test
    fun `the D is this surface's own and leaves the phone's Double Size alone`() {
        var presses = 0
        val before = vm.state.doubleSize

        tap(mainAlone(vm, onEq = {}, onPl = {}, onFill = { presses++ }).named("main.clutter.d"))

        assertEquals(1, presses)
        assertEquals(before, vm.state.doubleSize)
    }

    @Test
    fun `the A does nothing there`() {
        val ids = mainAlone(vm, onEq = {}, onPl = {}, onFill = {}).map { it.id }

        assertFalse("main.clutter.a" in ids)
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
