// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.ui.ScaledWindowCanvas
import nl.mattix.andamp.ui.widget.Widget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A shaded window is still a window: the player's transport and seek bar, the
 * equalizer's volume and balance, and the buttons that put each of them back.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = BIG_WINDOW)
class ShadeInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var vm: WinampViewModel

    @Before
    fun setUp() {
        vm = testViewModel()
    }

    private fun show(widgets: List<Widget>) {
        compose.setContent { ScaledWindowCanvas(MAIN_W, SHADE_H, SCALE, vm.state, widgets) {} }
        compose.waitForIdle()
    }

    private fun tap(
        x: Float,
        y: Float,
    ) = compose.onRoot().performTouchInput { tapVirtual(x, y) }

    private fun drag(
        y: Float,
        fromX: Float,
        toX: Float,
    ) = compose.onRoot().performTouchInput {
        down(Offset(fromX * SCALE, y * SCALE))
        moveTo(Offset(((fromX + toX) / 2f) * SCALE, y * SCALE))
        moveTo(Offset(toX * SCALE, y * SCALE))
        up()
    }

    // --- the player -------------------------------------------------------

    @Test
    fun `the shaded player still plays, pauses and stops`() {
        show(mainShadeWidgets(vm, onOptions = {}, onExit = {}))

        tap(180f, 7f) // play
        assertEquals(Transport.Playing, vm.state.transport)
        tap(190f, 7f) // pause
        assertEquals(Transport.Paused, vm.state.transport)
        tap(199f, 7f) // stop
        assertEquals(Transport.Stopped, vm.state.transport)
    }

    /**
     * What the strip and the clock do here is what the visualizer and the clock do in the
     * big window, on the same state.
     */
    @Test
    fun `tapping the strip cycles the visualizer style, as the big one does`() {
        show(mainShadeWidgets(vm, onOptions = {}, onExit = {}))
        val before = vm.state.visMode

        tap(90f, 7f) // the strip: 38x5 at (79,5)

        assertEquals(before.next(), vm.state.visMode)
    }

    @Test
    fun `tapping the clock turns the time round, as the big one does`() {
        show(mainShadeWidgets(vm, onOptions = {}, onExit = {}))
        val before = vm.state.timeRemaining

        tap(135f, 7f) // the clock: "00:00" at (127,4)

        assertEquals(!before, vm.state.timeRemaining)
    }

    @Test
    fun `the strip and the clock keep to themselves`() {
        show(mainShadeWidgets(vm, onOptions = {}, onExit = {}))
        val mode = vm.state.visMode

        tap(160f, 7f) // between the clock and the transport: nothing lives here

        assertEquals("a tap in the gap changes nothing", mode, vm.state.visMode)
        assertEquals(Transport.Stopped, vm.state.transport)
    }

    @Test
    fun `the shaded player seeks`() {
        vm.play()
        show(mainShadeWidgets(vm, onOptions = {}, onExit = {}))

        // the seek bar is 17px wide at x=226: drag it to the far end
        drag(y = 7f, fromX = 228f, toX = 243f)

        assertTrue("the shaded seek bar seeks", vm.state.currentTimeSec > 0)
    }

    @Test
    fun `the shade button in the bar puts the player back`() {
        vm.state.mainShaded = true
        show(mainShadeWidgets(vm, onOptions = {}, onExit = {}))

        tap(258f, 7f)

        assertEquals(false, vm.state.mainShaded)
    }

    @Test
    fun `the X still quits from the shaded player`() {
        var quits = 0
        show(mainShadeWidgets(vm, onOptions = {}, onExit = { quits++ }))

        tap(268f, 7f)

        assertEquals(1, quits)
    }

    // --- the equalizer ----------------------------------------------------

    @Test
    fun `the shaded equalizer carries the volume slider`() {
        vm.setVolume(0.2f)
        show(eqShadeWidgets(vm))

        // the volume track runs 97px from x=61
        drag(y = 7f, fromX = 62f, toX = 157f)

        assertTrue("the volume follows the shaded slider: ${vm.state.volume}", vm.state.volume > 90)
    }

    @Test
    fun `the shaded equalizer carries the balance slider`() {
        show(eqShadeWidgets(vm))

        // the balance track runs 43px from x=164; drag it hard left
        drag(y = 7f, fromX = 200f, toX = 164f)

        assertTrue("the balance follows the shaded slider: ${vm.state.balance}", vm.state.balance < -50)
    }

    @Test
    fun `the shaded equalizer's balance still sticks to the center`() {
        show(eqShadeWidgets(vm))

        drag(y = 7f, fromX = 200f, toX = 186f) // a hair off the middle of the track

        assertEquals(0, vm.state.balance)
    }

    @Test
    fun `the equalizer's shade button puts it back, and its X closes it`() {
        vm.state.eqShaded = true
        show(eqShadeWidgets(vm))

        tap(258f, 7f)
        assertEquals(false, vm.state.eqShaded)

        tap(268f, 7f)
        assertEquals(false, vm.state.eqVisible)
    }

    // --- the playlist -----------------------------------------------------

    @Test
    fun `the shaded playlist unshades and closes`() {
        vm.state.plShaded = true
        show(playlistShadeWidgets(vm, expandedH = 290))

        tap(258f, 7f)
        assertEquals(false, vm.state.plShaded)

        tap(268f, 7f)
        assertEquals(false, vm.state.plVisible)
    }

    // --- what the bars say ------------------------------------------------

    @Test
    fun `the shaded clock pads its minutes`() {
        val state = WinampState().apply { currentTimeSec = 64 }

        assertEquals("01:04", shadeTime(state))
    }

    @Test
    fun `each slider thumb art follows which third of the travel it is in`() {
        assertEquals("low", segmentOf(0.1f, "low", "mid", "high"))
        assertEquals("mid", segmentOf(0.5f, "low", "mid", "high"))
        assertEquals("high", segmentOf(0.9f, "low", "mid", "high"))
    }

    @Test
    fun `shading a window takes its height out of the stack`() {
        fun stack(
            playerH: Int,
            eqShown: Boolean,
        ) = dockedStackHeight(top = 0, anchor = playerH, under = listOf(StackMember(EQ_H, eqShown, top = null, placed = false)))

        assertEquals(MAIN_H + EQ_H, stack(MAIN_H, eqShown = true))
        assertEquals(SHADE_H + EQ_H, stack(heightOf(MAIN_H, true), eqShown = true))
        assertEquals(
            "an equalizer that is not shown takes no room",
            SHADE_H,
            stack(heightOf(MAIN_H, true), eqShown = false),
        )
    }

    @Test
    fun `a docked window keeps its row when it collapses`() {
        // the row is the stack's; the offset that lands a window on it depends
        // on that window's own height, or half the collapse shows up as a gap
        val screen = 533
        val row = 60

        val full = dockedOffset(row, MAIN_H, screen)
        val shaded = dockedOffset(row, SHADE_H, screen)

        assertEquals(row, (screen - MAIN_H) / 2 + full.y)
        assertEquals(row, (screen - SHADE_H) / 2 + shaded.y)
    }
}
