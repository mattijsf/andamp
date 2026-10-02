// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.online

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which screenshots are worth fetching before they are looked at.
 *
 * The window reaches further ahead than behind, because a grid is read
 * downwards, and it stops at the ends of the list.
 */
class ThumbPrefetchTest {
    @Test
    fun `a screenful ahead and a little behind`() {
        assertEquals(96..131, nextUp(100..119, total = 1_000))
    }

    @Test
    fun `nothing before the first skin`() {
        assertEquals(0..26, nextUp(0..14, total = 1_000))
    }

    @Test
    fun `nothing past the last skin`() {
        assertEquals(76..99, nextUp(80..99, total = 100))
    }

    @Test
    fun `an empty museum is nothing to fetch`() {
        assertEquals(IntRange.EMPTY, nextUp(0..10, total = 0))
    }
}
