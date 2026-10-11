// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the list on a lent surface follows a finger that is only reported as a distance and a speed. */
class ListScrollTest {
    private fun scrolledTo(
        offset: Float,
        farthest: Float = 1_000f,
    ) = ListScroll().apply {
        bound(farthest)
        moveTo(offset)
    }

    @Test
    fun `a finger moving up the screen scrolls the list down`() {
        val scroll = scrolledTo(100f)

        scroll.dragBy(-40f)

        assertEquals(140f, scroll.offset)
    }

    @Test
    fun `a finger moving down the screen scrolls the list back`() {
        val scroll = scrolledTo(100f)

        scroll.dragBy(30f)

        assertEquals(70f, scroll.offset)
    }

    @Test
    fun `the list stops at its top`() {
        val scroll = scrolledTo(20f)

        scroll.dragBy(500f)

        assertEquals(0f, scroll.offset)
        assertTrue(scroll.atAnEnd)
    }

    @Test
    fun `the list stops at its last row`() {
        val scroll = scrolledTo(900f)

        scroll.dragBy(-500f)

        assertEquals(1_000f, scroll.offset)
        assertTrue(scroll.atAnEnd)
    }

    @Test
    fun `a list that all fits does not scroll`() {
        val scroll = scrolledTo(0f, farthest = 0f)

        scroll.dragBy(-200f)

        assertEquals(0f, scroll.offset)
    }

    @Test
    fun `a list that got shorter is brought back inside`() {
        val scroll = scrolledTo(900f)

        scroll.bound(300f)

        assertEquals(300f, scroll.offset)
    }

    @Test
    fun `a finger flung up the screen sends the list down`() {
        val scroll = scrolledTo(100f)

        scroll.throwAt(-1_500f)

        assertEquals(1_500f, scroll.thrown?.velocity)
    }

    @Test
    fun `a drag takes hold of a list that was flung`() {
        val scroll = scrolledTo(100f)
        scroll.throwAt(-1_500f)

        scroll.dragBy(-10f)

        assertNull(scroll.thrown)
    }

    @Test
    fun `in the middle the list is at neither end`() {
        assertFalse(scrolledTo(500f).atAnEnd)
    }
}
