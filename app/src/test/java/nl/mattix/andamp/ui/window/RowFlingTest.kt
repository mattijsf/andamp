// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The arithmetic of a thrown list: where a throw ends, that it always ends, and that it
 * never overshoots its distance.
 */
class RowFlingTest {
    @Test
    fun `speed is the rows covered over the time they took`() {
        assertEquals(20f, RowFling.speedOf(rowsMoved = 2f, overMs = 100), 0.01f)
        assertEquals(-20f, RowFling.speedOf(rowsMoved = -2f, overMs = 100), 0.01f)
    }

    @Test
    fun `no time means no speed, rather than an infinite one`() {
        assertEquals(0f, RowFling.speedOf(rowsMoved = 5f, overMs = 0), 0f)
    }

    @Test
    fun `a finger lifting is not a throw`() {
        assertFalse(RowFling.worthCarrying(0.4f))
        assertFalse(RowFling.worthCarrying(-0.4f))
        assertTrue(RowFling.worthCarrying(30f))
    }

    @Test
    fun `a throw travels furthest at the start and keeps slowing`() {
        val speed = 40f
        val first = RowFling.travelled(speed, 100) - RowFling.travelled(speed, 0)
        val later = RowFling.travelled(speed, 400) - RowFling.travelled(speed, 300)

        assertTrue("the throw slows: $first then $later", later < first)
    }

    @Test
    fun `it never goes further than the throw was worth`() {
        val speed = 60f
        val ceiling = RowFling.distance(speed)

        for (ms in longArrayOf(0, 50, 200, 1_000, 10_000)) {
            assertTrue("the throw stays within its distance at $ms", RowFling.travelled(speed, ms) <= ceiling + 0.001f)
        }
        assertEquals("the throw arrives at its distance", ceiling, RowFling.travelled(speed, 5_000), 0.5f)
    }

    @Test
    fun `a throw the other way goes the other way, and just as far`() {
        assertEquals(-RowFling.distance(50f), RowFling.distance(-50f), 0.001f)
        assertEquals(-RowFling.travelled(50f, 200), RowFling.travelled(-50f, 200), 0.001f)
    }

    @Test
    fun `every throw comes to rest, and a harder one takes longer`() {
        val gentle = RowFling.restsAfter(5f)
        val hard = RowFling.restsAfter(200f)

        assertTrue("a gentle throw rests within two seconds", gentle in 1..2_000)
        assertTrue("a hard throw rests later than a gentle one", hard > gentle)
        assertTrue("a hard throw rests within three seconds", hard < 3_000)
    }

    @Test
    fun `what is not worth carrying rests at once`() {
        assertEquals(0L, RowFling.restsAfter(0.2f))
    }

    @Test
    fun `by the time it rests it has all but arrived`() {
        // otherwise a list stops visibly short of where it was thrown
        val speed = 90f
        val short = abs(RowFling.distance(speed) - RowFling.travelled(speed, RowFling.restsAfter(speed)))

        assertTrue("the throw rests within a row of its distance: $short rows short", short < 1f)
    }
}
