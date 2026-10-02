// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Test

/** What comes along when the player is dragged. */
class DockGroupTest {
    private fun rect(
        top: Int,
        height: Int,
        left: Int = 0,
        width: Int = 275,
    ) = IntRect(left, top, left + width, top + height)

    @Test
    fun `a stack travels together, however long the chain`() {
        val rects =
            mapOf(
                "main" to rect(0, 116),
                "eq" to rect(116, 116),
                "pl" to rect(232, 290),
            )

        assertEquals(setOf("main", "eq", "pl"), DockGroup.of("main", rects))
    }

    @Test
    fun `a pixel of rounding daylight is still docked`() {
        // what a snapped stack reads back as after integer division
        val rects =
            mapOf(
                "main" to rect(0, 116),
                "eq" to rect(117, 116),
                "pl" to rect(234, 290),
            )

        assertEquals(setOf("main", "eq", "pl"), DockGroup.of("main", rects))
    }

    @Test
    fun `a window merely near the stack stays behind`() {
        val rects =
            mapOf(
                "main" to rect(0, 116),
                "eq" to rect(116, 116),
                // three px of daylight: near is not attached
                "pl" to rect(235, 290),
            )

        assertEquals(setOf("main", "eq"), DockGroup.of("main", rects))
    }

    @Test
    fun `a break in the chain leaves everything below it behind`() {
        val rects =
            mapOf(
                "main" to rect(0, 116),
                "eq" to rect(120, 116), // detached
                "pl" to rect(236, 290), // flush under the equalizer, not under main
            )

        assertEquals(setOf("main"), DockGroup.of("main", rects))
        assertEquals(setOf("eq", "pl"), DockGroup.of("eq", rects))
    }

    @Test
    fun `windows side by side count as docked`() {
        val rects =
            mapOf(
                "main" to rect(0, 116),
                "library" to rect(0, 116, left = 275, width = 275),
            )

        assertEquals(setOf("main", "library"), DockGroup.of("main", rects))
    }

    @Test
    fun `a group's bounds cover every window it carries, where they will be`() {
        val main = rect(0, 116)
        val eq = rect(116, 116)

        // the player asked to sit 40px lower: the group's bottom is the
        // equalizer's, and it moves with it
        val bounds = DockGroup.bounds(anchorAt = rect(40, 116), anchorNow = main, carried = listOf(eq))

        assertEquals(IntRect(0, 40, 275, 272), bounds)
    }

    @Test
    fun `a group of one is just the window`() {
        val main = rect(0, 116)

        assertEquals(rect(40, 116), DockGroup.bounds(rect(40, 116), main, emptyList()))
    }

    @Test
    fun `windows that only touch at a corner are not docked`() {
        val rects =
            mapOf(
                "main" to rect(0, 116),
                // starts where main ends, both across and down
                "pl" to rect(116, 290, left = 275),
            )

        assertEquals(setOf("main"), DockGroup.of("main", rects))
    }
}
