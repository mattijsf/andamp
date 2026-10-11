// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistLayoutTest {
    @Test
    fun `classic minimum window is 116 tall (top 20 + two 29px segments + bottom 38)`() {
        assertEquals(116, PlaylistLayout.forAvailableHeight(116).height)
    }

    @Test
    fun `height quantizes down to whole 29px segments`() {
        // one pixel short of the next segment stays at the previous height
        assertEquals(116, PlaylistLayout.forAvailableHeight(116 + 28).height)
        assertEquals(116 + 29, PlaylistLayout.forAvailableHeight(116 + 29).height)
    }

    @Test
    fun `a window held at a height between two segments shows the rows that fit it`() {
        // 11 rows more than eight segments: the frame's ninth tile is cut short
        val layout = PlaylistLayout(20 + 8 * 29 + 11 + 38)

        assertEquals(301, layout.height)
        assertEquals(8 * 29 + 11, layout.middleH)
        assertEquals((8 * 29 + 11 - 6) / 13, layout.visibleRows)
        assertEquals(layout.middleH - 18, layout.scrollTrackH)
    }

    @Test
    fun `tiny available space still yields the two-segment minimum`() {
        assertEquals(116, PlaylistLayout.forAvailableHeight(0).height)
        assertEquals(116, PlaylistLayout.forAvailableHeight(-100).height)
    }

    @Test
    fun `a large screen fills with whole segments`() {
        val layout = PlaylistLayout.forAvailableHeight(531) // what a 1080x2424 screen leaves in portrait at 3x
        assertEquals(20 + 16 * 29 + 38, layout.height)
    }

    @Test
    fun `visible rows fit 13px rows in the middle area minus 3px padding top and bottom`() {
        val layout = PlaylistLayout(116) // middle = 58
        assertEquals(58, layout.middleH)
        assertEquals(4, layout.visibleRows)
        assertEquals(23, layout.textTop)
    }

    @Test
    fun `a song already in view leaves the list where it is`() {
        val layout = PlaylistLayout(116) // four rows
        assertEquals(10, layout.scrollShowing(row = 10, scroll = 10, rows = 100))
        assertEquals(10, layout.scrollShowing(row = 13, scroll = 10, rows = 100))
    }

    @Test
    fun `a song out of view is brought to the middle of the list`() {
        val layout = PlaylistLayout(20 + 8 * 29 + 38) // seventeen rows
        assertEquals(17, layout.visibleRows)
        // the row after the last one showing, and one far above
        assertEquals(17 - 8, layout.scrollShowing(row = 17, scroll = 0, rows = 100))
        assertEquals(50 - 8, layout.scrollShowing(row = 50, scroll = 80, rows = 100))
    }

    @Test
    fun `the list does not scroll past its ends to center a song`() {
        val layout = PlaylistLayout(20 + 8 * 29 + 38)
        assertEquals(0, layout.scrollShowing(row = 3, scroll = 40, rows = 100))
        assertEquals(100 - 17, layout.scrollShowing(row = 98, scroll = 0, rows = 100))
        // a list shorter than the window never scrolls
        assertEquals(0, layout.scrollShowing(row = 4, scroll = 0, rows = 5))
    }

    @Test
    fun `no song playing leaves the list where it is`() {
        val layout = PlaylistLayout(116)
        assertEquals(7, layout.scrollShowing(row = -1, scroll = 7, rows = 100))
        assertEquals(0, layout.scrollShowing(row = 0, scroll = 0, rows = 0))
    }

    @Test
    fun `text area spans between the 12px left and 20px right edge tiles`() {
        val layout = PlaylistLayout(116)
        assertEquals(12, layout.textLeft)
        assertEquals(255, layout.textRight)
    }

    @Test
    fun `a width is Winamp's index - 275 plus 25 a step`() {
        assertEquals(275, PlaylistLayout.widthOfCols(0))
        assertEquals(300, PlaylistLayout.widthOfCols(1))
        assertEquals(325, PlaylistLayout.widthOfCols(2))
        assertEquals(275, PlaylistLayout.widthOfCols(-3)) // never narrower than Winamp's own
    }

    @Test
    fun `the title plate stays centered as the window widens`() {
        // webamp: the title's left edge is floor((W - 100) / 2) at every legal width
        listOf(0 to 87, 1 to 100, 2 to 112).forEach { (cols, expected) ->
            val w = PlaylistLayout.widthOfCols(cols)
            assertEquals("the title plate is centered at cols=$cols", expected, (w - 100) / 2)
        }
    }

    @Test
    fun `everything in the bottom bar's right cap moves with it`() {
        val narrow = PlaylistLayout.ofSegments(4)
        val wide = PlaylistLayout.ofSegments(4, PlaylistLayout.widthOfCols(2))

        assertEquals(125, narrow.rightCapX)
        assertEquals(175, wide.rightCapX)
        assertEquals(narrow.width - narrow.rightCapX, wide.width - wide.rightCapX)
    }

    @Test
    fun `taller rows leave the window its size and show fewer of them`() {
        val classic = PlaylistLayout(116)
        val large = PlaylistLayout(116, rowH = 25)

        assertEquals(classic.height, large.height)
        assertEquals(classic.middleH, large.middleH)
        assertEquals(25, large.rowH)
        assertEquals((58 - 6) / 25, large.visibleRows)
        assertEquals(25, PlaylistLayout.ofSegments(4, rowH = 25).rowH)
    }

    @Test
    fun `a row too tall for the window still shows one`() {
        assertEquals(1, PlaylistLayout(116, rowH = 80).visibleRows)
    }
}
