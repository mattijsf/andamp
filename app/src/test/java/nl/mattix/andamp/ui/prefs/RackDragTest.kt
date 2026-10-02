// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where a dragged card belongs, worked out from the heights the cards reported.
 *
 * The rack's cards differ in height, so a drag cannot count rows.
 */
class RackDragTest {
    private val order = listOf("a", "b", "c")

    private fun drag(): RackDrag =
        RackDrag().apply {
            measured("a", 100)
            measured("b", 400)
            measured("c", 200)
        }

    @Test
    fun `a small drag stays where it is`() {
        val drag = drag().apply { start("a") }

        assertNull("a small drag passes no neighbor", drag.drag(delta = 50f, order = order))
    }

    @Test
    fun `passing the middle of the neighbor below takes its place`() {
        val drag = drag().apply { start("a") }

        // b is 400 tall, so its middle is 200 away
        assertNull(drag.drag(150f, order))
        assertEquals(1, drag.drag(100f, order))
    }

    @Test
    fun `the card stays under the finger after it moves`() {
        val drag = drag().apply { start("a") }

        drag.drag(250f, order)

        // it traveled 250 and swapped past a 400-tall neighbor, so it is 150
        // short of where the finger is
        assertEquals(-150f, drag.offset, 0.01f)
    }

    @Test
    fun `dragging up passes the neighbor above`() {
        val drag = drag().apply { start("c") }

        // b again, from below
        assertEquals(1, drag.drag(-250f, listOf("a", "b", "c")))
    }

    @Test
    fun `a drag off either end does nothing`() {
        val top = drag().apply { start("a") }
        val bottom = drag().apply { start("c") }

        assertNull(top.drag(-500f, order))
        assertNull(bottom.drag(500f, order))
    }

    @Test
    fun `a neighbor nobody has measured is not passed`() {
        val drag = RackDrag().apply { start("a") }

        assertNull("an unmeasured neighbor is not passed", drag.drag(500f, order))
    }

    @Test
    fun `a second move in one drag is measured against the current order`() {
        // a drag outlives several reorders
        val drag = drag().apply { start("a") }

        assertEquals("a passes b", 1, drag.drag(250f, listOf("a", "b", "c")))
        // the rack is b, a, c, and a is at 1 with c below it at 200 tall
        assertEquals("a passes c", 2, drag.drag(250f, listOf("b", "a", "c")))
    }

    @Test
    fun `a card at the end of the rack is clamped half a card past the end`() {
        // dragging past the last card has nothing to pass, so the offset is clamped
        val drag = drag().apply { start("c") }

        assertNull(drag.drag(1000f, listOf("a", "b", "c")))
        assertEquals("the card stops half a card past the end", 100f, drag.offset, 0.5f)
        // back up costs half of b from the clamp, not the 1000 it was dragged
        assertEquals(1, drag.drag(-300f, listOf("a", "b", "c")))
    }

    @Test
    fun `letting go forgets where it was`() {
        val drag = drag().apply { start("a") }
        drag.drag(150f, order)

        drag.stop()

        assertNull(drag.carried)
        assertEquals(0f, drag.offset, 0f)
    }
}
