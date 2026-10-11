// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.unit.IntOffset
import nl.mattix.andamp.ui.SIDEWAYS_STACK_H
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the windows start, and what counts as the stack the playlist hangs under: every
 * attached window. A window that is open but dragged away reserves no place, so the
 * playlist, which follows the stack until it is given a size of its own, does not follow a
 * gap.
 */
class ClassicStackTest {
    private val top = 20
    private val mainH = 116
    private val eqH = 116
    private val pluginH = 90
    private val screen = WindowScreen(width = 275, height = 800, safeTop = top, safeBottom = 780)

    private fun eq(
        placed: Boolean = false,
        at: Int? = null,
    ) = ClassicStack.Member(height = eqH, top = at, placed = placed)

    private fun plugin(
        placed: Boolean = false,
        at: Int? = null,
    ) = ClassicStack.Member(height = pluginH, top = at, placed = placed)

    private fun layout(
        eq: ClassicStack.Member? = eq(),
        vis: ClassicStack.Member? = plugin(),
        sideways: Boolean = false,
    ) = ClassicStack.layout(ClassicStack.Ask(screen, sideways, mainH, eq, vis))

    @Test
    fun `an untouched stack is one column, the playlist under the sum of what is in it`() {
        val places = layout()

        assertEquals(IntOffset(0, top), places.main)
        assertEquals(IntOffset(0, top + mainH), places.eq)
        assertEquals(IntOffset(0, top + mainH + eqH), places.vis)
        assertEquals(IntOffset(0, top + mainH + eqH + pluginH), places.playlist)
        assertEquals(PlaylistLayout.segmentsThatFit(780 - (top + mainH + eqH + pluginH)), places.playlistSegments)
    }

    @Test
    fun `a window that is not open is not in it`() {
        val places = layout(eq = null)

        assertEquals("the plug-in window moves up", IntOffset(0, top + mainH), places.vis)
        assertEquals(IntOffset(0, top + mainH + pluginH), places.playlist)
    }

    @Test
    fun `a window dragged away stops holding a place in the stack`() {
        val places = layout(vis = plugin(placed = true, at = top + 600))

        assertEquals("a window that left reserves no height", IntOffset(0, top + mainH + eqH), places.playlist)
    }

    @Test
    fun `a window dragged back against the stack is in it again`() {
        val places = layout(vis = plugin(placed = true, at = top + mainH + eqH))

        assertEquals(IntOffset(0, top + mainH + eqH + pluginH), places.playlist)
    }

    @Test
    fun `the daylight a snap leaves behind still counts as attached`() {
        val places = layout(vis = plugin(placed = true, at = top + mainH + eqH + 2))

        assertEquals(IntOffset(0, top + mainH + eqH + pluginH), places.playlist)
    }

    @Test
    fun `what is left closes up behind what left`() {
        // the equalizer is off floating; a plug-in window that never moved sits
        // directly under the player
        val places = layout(eq = eq(placed = true, at = top + 900))

        assertEquals(IntOffset(0, top + mainH), places.vis)
        assertEquals(IntOffset(0, top + mainH + pluginH), places.playlist)
    }

    @Test
    fun `a window that has never been laid out is trusted to be where it says`() {
        // first frame: rectangles do not exist yet, and a window with a place
        // of its own counts as detached
        val places = layout(eq = eq(placed = true, at = null), vis = null)

        assertEquals(IntOffset(0, top + mainH), places.playlist)
    }

    @Test
    fun `sideways, the playlist stands beside the stack, as tall as the player and the equalizer`() {
        val wide = WindowScreen(width = 600, height = 300, safeTop = top, safeBottom = 280)

        val places = ClassicStack.layout(ClassicStack.Ask(wide, sideways = true, mainH, eq(), vis = null))

        assertEquals(IntOffset(25, top), places.main)
        assertEquals(IntOffset(25, top + mainH), places.eq)
        assertEquals(IntOffset(25 + MAIN_W, top), places.playlist)
        assertEquals(PlaylistLayout.segmentsThatFit(SIDEWAYS_STACK_H), places.playlistSegments)
    }
}
