// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.overlay

import androidx.compose.ui.unit.IntRect
import nl.mattix.andamp.ui.overlay.TouchWindowPlan.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the floating player's touch window may move: not under a finger, not
 * before a release has been delivered, and not while a modal has it parked.
 */
class TouchWindowPlanTest {
    private val here = IntRect(10, 20, 200, 300)
    private val there = IntRect(10, 400, 200, 680)

    private fun moves(commands: List<Command>) = commands.filterIsInstance<Command.Move>().map { it.to }

    @Test
    fun `the feeler follows the windows when no finger is down`() {
        val plan = TouchWindowPlan()

        assertEquals(listOf(here), moves(plan.wants(here)))
        assertEquals(here, plan.applied)
    }

    @Test
    fun `asking for the geometry it already has moves nothing`() {
        val plan = TouchWindowPlan()
        plan.wants(here)

        assertTrue(plan.wants(here).isEmpty())
    }

    /** A window that moves under a finger reports that finger elsewhere. */
    @Test
    fun `a move asked for under a finger waits for the finger to leave`() {
        val plan = TouchWindowPlan()
        plan.wants(here)

        plan.touching(true)
        val duringDrag = plan.wants(there)
        val onRelease = plan.touching(false)

        assertTrue("the window holds still under the finger", duringDrag.isEmpty())
        assertEquals(listOf(there), moves(onRelease))
    }

    /** Nothing may move before the release has been delivered. */
    @Test
    fun `a press and release with nothing to follow moves nothing at all`() {
        val plan = TouchWindowPlan()
        plan.wants(here)

        val down = plan.touching(true)
        val up = plan.touching(false)

        assertTrue(down.isEmpty())
        assertTrue(up.isEmpty())
        assertEquals(here, plan.applied)
    }

    @Test
    fun `only the last place asked for during a gesture is where it lands`() {
        val plan = TouchWindowPlan()
        plan.wants(here)
        plan.touching(true)

        plan.wants(there)
        plan.wants(IntRect(0, 0, 100, 100))
        val settled = moves(plan.touching(false))

        assertEquals(listOf(IntRect(0, 0, 100, 100)), settled)
    }

    /** A popup lives in the picture, which takes no touches until this. */
    @Test
    fun `a modal hands the touches to the picture and parks the feeler`() {
        val plan = TouchWindowPlan()
        plan.wants(here)

        val commands = plan.modal(true)

        assertEquals(listOf<Command>(Command.PictureTakesTouches(true), Command.Move(TouchWindowPlan.PARKED)), commands)
    }

    @Test
    fun `closing the modal puts the feeler back where it was`() {
        val plan = TouchWindowPlan()
        plan.wants(here)
        plan.modal(true)

        val commands = plan.modal(false)

        assertEquals(listOf<Command>(Command.PictureTakesTouches(false), Command.Move(here)), commands)
        assertEquals(here, plan.applied)
    }

    @Test
    fun `a modal that is already up is not put up again`() {
        val plan = TouchWindowPlan()
        plan.wants(here)
        plan.modal(true)

        assertTrue("a second modal does not park the feeler again", plan.modal(true).isEmpty())
    }

    @Test
    fun `the windows may move while a modal is up, and the feeler follows after`() {
        val plan = TouchWindowPlan()
        plan.wants(here)
        plan.modal(true)

        val duringModal = plan.wants(there)
        val afterModal = plan.modal(false)

        assertTrue("the feeler stays parked while the menu is up", duringModal.isEmpty())
        assertEquals(listOf(there), moves(afterModal))
    }

    /** Two reasons to hold still at once: neither may release the other's hold. */
    @Test
    fun `a finger down under a modal still defers`() {
        val plan = TouchWindowPlan()
        plan.wants(here)
        plan.modal(true)
        plan.touching(true)

        plan.wants(there)
        val onRelease = plan.touching(false)

        assertTrue("a release under a menu leaves the feeler parked", onRelease.isEmpty())
        assertEquals(listOf(there), moves(plan.modal(false)))
    }
}
