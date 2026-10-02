// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Windows are composed in a fixed order and stacked by depth. Reordering the
 * children instead would rebuild a window as it is raised, and a press that
 * raises a window would never become a drag.
 */
class WindowStackingTest {
    private val windows = listOf("main", "eq", "pl", "library")

    @Test
    fun `the composition order never follows the stack`() {
        val raised = WindowStacking.stack(windows, order = listOf("library", "pl", "eq", "main"))

        assertEquals(windows, raised.map { it.first })
    }

    @Test
    fun `depth comes from the stack, front last`() {
        val stack = WindowStacking.stack(windows, order = listOf("main", "eq", "pl", "library")).toMap()

        assertTrue("the library is in front", stack.getValue("library") > stack.getValue("pl"))
        assertEquals(0f, stack.getValue("main"))
    }

    @Test
    fun `a window the stack forgot sits at the back`() {
        val stack = WindowStacking.stack(windows, order = listOf("pl")).toMap()

        assertEquals(-1f, stack.getValue("main"))
        assertTrue(stack.getValue("pl") > stack.getValue("main"))
    }
}
