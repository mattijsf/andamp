// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What counts as the stack the playlist hangs under: the sum of every attached window. A
 * window that is open but dragged away does not reserve its height, so the playlist, which
 * follows the stack until it is given a size of its own, does not follow a gap.
 */
class DockedStackTest {
    private val top = 20
    private val mainH = 116
    private val eqH = 116
    private val pluginH = 90

    private fun eq(
        placed: Boolean = false,
        at: Int? = null,
        shown: Boolean = true,
    ) = StackMember(height = eqH, shown = shown, top = at, placed = placed)

    private fun plugin(
        placed: Boolean = false,
        at: Int? = null,
        shown: Boolean = true,
    ) = StackMember(height = pluginH, shown = shown, top = at, placed = placed)

    @Test
    fun `an untouched stack is the sum of what is in it`() {
        val height = dockedStackHeight(top, mainH, listOf(eq(), plugin()))

        assertEquals(mainH + eqH + pluginH, height)
    }

    @Test
    fun `a window that is not open is not in it`() {
        val height = dockedStackHeight(top, mainH, listOf(eq(shown = false), plugin()))

        assertEquals(mainH + pluginH, height)
    }

    @Test
    fun `a window dragged away stops holding a place in the stack`() {
        // the plug-in window is open and somewhere else
        val height = dockedStackHeight(top, mainH, listOf(eq(), plugin(placed = true, at = top + 600)))

        assertEquals("a window that left reserves no height", mainH + eqH, height)
    }

    @Test
    fun `a window dragged back against the stack is in it again`() {
        val snapped = plugin(placed = true, at = top + mainH + eqH)

        val height = dockedStackHeight(top, mainH, listOf(eq(), snapped))

        assertEquals(mainH + eqH + pluginH, height)
    }

    @Test
    fun `the daylight a snap leaves behind still counts as attached`() {
        val nearly = plugin(placed = true, at = top + mainH + eqH + 2)

        val height = dockedStackHeight(top, mainH, listOf(eq(), nearly))

        assertEquals(mainH + eqH + pluginH, height)
    }

    @Test
    fun `what is left closes up behind what left`() {
        // the equalizer is off floating; a plug-in window that never moved sits
        // directly under the player, and the stack is that tall
        val height = dockedStackHeight(top, mainH, listOf(eq(placed = true, at = top + 900), plugin()))

        assertEquals(mainH + pluginH, height)
    }

    @Test
    fun `a window that has never been laid out is trusted to be where it says`() {
        // first frame: rectangles do not exist yet, and a window with a place
        // of its own counts as detached
        val height = dockedStackHeight(top, mainH, listOf(eq(placed = true, at = null)))

        assertEquals(mainH, height)
    }
}
