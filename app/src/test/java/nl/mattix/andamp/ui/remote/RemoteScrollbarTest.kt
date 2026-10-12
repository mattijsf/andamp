// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.remote

import androidx.compose.ui.geometry.Offset
import nl.mattix.andamp.skin.Dest
import nl.mattix.andamp.skin.SpriteMap
import nl.mattix.andamp.state.WinampState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The scrollbar of the list on a lent surface, as a control: a touch on it moves the list. */
class RemoteScrollbarTest {
    private val state = WinampState()

    /** A car's screen at four to one: the list is 146 of its own pixels high, under a top bar of 20. */
    private val layout = RemoteListLayout(1280, 720, scale = 4)
    private val handleH = SpriteMap.PLAYLIST_SCROLL_HANDLE.h

    /** The list's scroll with [rows] rows, in the surface's pixels, four to one of the window's. */
    private fun scrollOf(rows: Int) = ListScroll().apply { bound(layout.farthest(rows) * PER_PIXEL) }

    private fun bar(
        scroll: ListScroll,
        rows: Int = ROWS,
    ) = scrollbar(layout, scroll, PER_PIXEL) { rows }

    /** A place on the groove, [along] of the way down the handle's travel. */
    private fun onGroove(along: Float): Offset {
        val travel = layout.listH - handleH
        return Offset(layout.listRight + 10f, layout.listTop + handleH / 2f + along * travel)
    }

    @Test
    fun `it covers the groove beside the rows, from the top bar to the bottom border`() {
        val bounds = bar(scrollOf(ROWS)).bounds

        assertEquals(layout.listRight, bounds.left)
        assertEquals(layout.listRight + Dest.PL_RIGHT_W, bounds.right)
        assertEquals(layout.listTop, bounds.top)
        assertEquals(layout.listBottom, bounds.bottom)
    }

    @Test
    fun `a touch halfway down the groove brings the list halfway`() {
        val scroll = scrollOf(ROWS)

        bar(scroll).down(state, onGroove(0.5f))

        assertEquals(layout.farthest(ROWS) * PER_PIXEL / 2f, scroll.offset, 0.01f)
    }

    @Test
    fun `a touch at either end of the groove brings the list to that end`() {
        val scroll = scrollOf(ROWS)
        val bar = bar(scroll)

        bar.down(state, onGroove(1f))
        assertEquals("the last row", layout.farthest(ROWS) * PER_PIXEL, scroll.offset, 0.01f)

        bar.down(state, onGroove(0f))
        assertEquals("the first row", 0f, scroll.offset, 0.01f)
    }

    @Test
    fun `a drag along the groove takes the list with it`() {
        val scroll = scrollOf(ROWS)
        val bar = bar(scroll)

        bar.down(state, onGroove(0.2f))
        bar.drag(onGroove(0.7f))

        assertEquals(layout.farthest(ROWS) * PER_PIXEL * 0.7f, scroll.offset, 0.01f)
    }

    @Test
    fun `its handle is where the list is scrolled to`() {
        val scroll = scrollOf(ROWS)
        val bar = bar(scroll)

        scroll.moveTo(layout.farthest(ROWS) * PER_PIXEL * 0.25f)

        assertEquals(onGroove(0.25f).y, bar.handle?.invoke()?.y)
    }

    @Test
    fun `a touch stops a list that is still travelling`() {
        val scroll = scrollOf(ROWS).apply { shown = true }
        scroll.throwAt(-2_000f)

        bar(scroll).down(state, onGroove(0.5f))

        assertNull(scroll.thrown)
    }

    @Test
    fun `a list that all fits stays where it is, with its handle at the top`() {
        val scroll = scrollOf(rows = 2)
        val bar = bar(scroll, rows = 2)

        bar.down(state, onGroove(0.8f))

        assertEquals(0f, scroll.offset)
        assertEquals(onGroove(0f).y, bar.handle?.invoke()?.y)
    }

    private companion object {
        const val PER_PIXEL = 4f
        const val ROWS = 20
    }
}
