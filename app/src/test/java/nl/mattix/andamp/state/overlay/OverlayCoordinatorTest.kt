// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Handing the player between the activity and the floating window: [OverlayCoordinator] shows
 * or hides the window to match the gate, and finishes the activity on a hand-over.
 */
class OverlayCoordinatorTest {
    private var shown = false
    private var shows = 0
    private var hides = 0
    private var finishes = 0

    private val coordinator =
        OverlayCoordinator(
            show = {
                shows++
                shown = true
            },
            hide = {
                hides++
                shown = false
            },
            finish = { finishes++ },
            shown = { shown },
        )

    /** Wanted, permitted, out of the app, not dismissed. */
    private fun floating() = OverlayGate(wanted = true, permitted = true, appInFront = false)

    @Test
    fun `launching with the preference on goes straight to the floating player`() {
        coordinator.handOver(floating())

        assertTrue(shown)
        assertEquals("the activity finishes on the hand-over", 1, finishes)
    }

    /** The clutter bar's A, switched on while the app is in front. */
    @Test
    fun `switching always on top on hands the player over and finishes the activity`() {
        val gate = OverlayGate(wanted = true, permitted = true, appInFront = true)

        coordinator.handOver(gate.leftApp())

        assertTrue(shown)
        assertEquals(1, finishes)
    }

    /** The same switch, off: the gate stops wanting it, so the window goes. */
    @Test
    fun `switching always on top off takes the floating player down`() {
        coordinator.settle(floating())

        coordinator.settle(floating().copy(wanted = false))

        assertFalse(shown)
        assertEquals(1, hides)
    }

    @Test
    fun `withdrawing the permission takes it down too`() {
        coordinator.settle(floating())

        coordinator.settle(floating().copy(permitted = false))

        assertFalse(shown)
    }

    @Test
    fun `entering the app takes the floating player down`() {
        coordinator.settle(floating())

        coordinator.settle(floating().returnedToApp())

        assertFalse(shown)
    }

    @Test
    fun `leaving the app puts it back up`() {
        coordinator.settle(floating().returnedToApp())

        coordinator.settle(floating())

        assertTrue(shown)
        assertEquals("leaving by the home button finishes nothing", 0, finishes)
    }

    /** The floating player's X: hidden until the listener opens the app again. */
    @Test
    fun `dismissing takes it down, and returning to the app arms it again`() {
        coordinator.settle(floating())

        coordinator.settle(floating().dismiss())
        assertFalse(shown)

        coordinator.settle(floating().dismiss().returnedToApp().leftApp())
        assertTrue("the floating player shows again after a return to the app", shown)
    }

    @Test
    fun `settling twice on the same answer shows once`() {
        coordinator.settle(floating())
        coordinator.settle(floating())

        assertEquals(1, shows)
        assertEquals(0, hides)
    }

    /** Preferences, the museum, a file picker: an activity is arriving. */
    @Test
    fun `stepping aside takes the window down without the gate changing`() {
        val gate = floating()
        coordinator.settle(gate)

        coordinator.stepAside()

        assertFalse(shown)
        assertTrue("the gate still shows the player", gate.showing)
    }

    @Test
    fun `stepping aside with nothing up does nothing`() {
        coordinator.stepAside()

        assertEquals(0, hides)
    }

    /** The window can also take itself down, so what is on screen is asked each time. */
    @Test
    fun `a window that took itself down is shown again when the gate says so`() {
        coordinator.settle(floating())
        shown = false // the overlay hid itself; nobody told the coordinator

        coordinator.settle(floating())

        assertTrue(shown)
        assertEquals(2, shows)
    }

    @Test
    fun `handing over when the gate says no finishes nothing`() {
        coordinator.handOver(floating().copy(permitted = false))

        assertFalse(shown)
        assertEquals("the activity stays when there is no floating player", 0, finishes)
    }
}
