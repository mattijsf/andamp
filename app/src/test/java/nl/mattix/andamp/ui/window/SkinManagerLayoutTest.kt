// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import org.junit.Assert.assertEquals
import org.junit.Test

/** The skin browser's geometry when the layout holds it at a height of its own choosing. */
class SkinManagerLayoutTest {
    private val frame = GenFrame

    @Test
    fun `a window held at a height between two rows is exactly that tall, the rest left under the last row`() {
        val whole = SkinManagerLayout(10).height(frame)

        val held = SkinManagerLayout.filling(whole + 5, SkinManagerLayout.WIDTH, frame)

        assertEquals(10, held.visibleRows)
        assertEquals(5, held.slack)
        assertEquals(whole + 5, held.height(frame))
    }

    @Test
    fun `a window held at a whole number of rows has no slack`() {
        val held = SkinManagerLayout.filling(SkinManagerLayout(10).height(frame), SkinManagerLayout.WIDTH, frame)

        assertEquals(10, held.visibleRows)
        assertEquals(0, held.slack)
    }
}
