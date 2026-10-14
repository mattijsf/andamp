// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.remote

import androidx.compose.ui.unit.IntOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the playlist on a lent surface puts its rows, and which row a touch lands on. */
class RemoteListLayoutTest {
    /** A 1280x720 screen at 160 dpi, where the windows are drawn four times their size. */
    private val car = RemoteListLayout(1280, 720, scale = 4, touchPx = 76)

    @Test
    fun `the list is as wide as whole steps of the frame allow`() {
        assertEquals("275, and one step of 25", 300, car.width)
        assertEquals("a room narrower than the frame still gets the frame", 275, RemoteListLayout(800, 480, 3).width)
    }

    @Test
    fun `the list is as tall as the room`() {
        assertEquals(180, car.height)
    }

    @Test
    fun `a row is as high as a finger needs`() {
        assertEquals("76 surface pixels at four to one", 19, car.rowH)
    }

    @Test
    fun `a row is never lower than the playlist window's`() {
        assertEquals(13, RemoteListLayout(1280, 720, scale = 4, touchPx = 20).rowH)
    }

    @Test
    fun `on a coarse screen a row grows to what a finger needs`() {
        val coarse = RemoteListLayout(800, 480, scale = 2, touchPx = 76)

        assertEquals("76 surface pixels at two to one", 38, coarse.rowH)
    }

    @Test
    fun `the text grows with the row`() {
        val coarse = RemoteListLayout(800, 480, scale = 2, touchPx = 76)

        assertTrue(coarse.fontSize > car.fontSize)
        assertTrue("the text fits its row", car.fontSize < car.rowH)
    }

    @Test
    fun `the rows end at a thin border along the bottom`() {
        assertEquals("the frame's 14 pixels", 180 - 14, car.listBottom)
        assertEquals(146, car.listH)
    }

    @Test
    fun `the rows start under the top bar`() {
        assertEquals(0, car.rowAt(20f, offset = 0f))
        assertEquals(1, car.rowAt(39f, offset = 0f))
    }

    @Test
    fun `scrolling moves other rows under the finger`() {
        assertEquals(2, car.rowAt(20f, offset = 38f))
    }

    @Test
    fun `a list shorter than the room does not scroll`() {
        assertEquals(0, car.farthest(rows = 3))
    }

    @Test
    fun `a long list scrolls until its last row is at the bottom`() {
        assertEquals(100 * 19 - 146, car.farthest(rows = 100))
    }

    @Test
    fun `the row that is on opens in the middle`() {
        val offset = car.showing(row = 50, rows = 100)

        // within a pixel: a row of odd height has its middle between two
        assertEquals("the row's middle is the list's middle", 20 + 146 / 2f, 20 + 50 * 19 + 19 / 2f - offset, 1f)
    }

    @Test
    fun `the first rows open at the top and the last at the bottom`() {
        assertEquals(0, car.showing(row = 0, rows = 100))
        assertEquals(car.farthest(100), car.showing(row = 99, rows = 100))
    }

    @Test
    fun `a row begins with a play button as wide as the row is high`() {
        assertTrue("just inside the frame", car.onPlay(12f))
        assertTrue("at the button's far side", car.onPlay(30f))
        assertEquals(12 + 19, car.playRight)
    }

    @Test
    fun `the row's text and its length are not the play button`() {
        assertTrue(!car.onPlay(31f))
        assertTrue(!car.onPlay(250f))
    }

    @Test
    fun `the close button reaches well left of its X and down to the rows`() {
        assertTrue(car.closeReach.contains(IntOffset(265, 10)))
        assertTrue(car.closeReach.contains(IntOffset(289, 19)))
        assertEquals("it ends where the rows begin", car.listTop, car.closeReach.bottom)
    }
}
