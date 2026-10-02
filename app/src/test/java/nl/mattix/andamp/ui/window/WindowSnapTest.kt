// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Winamp's docking, as pure geometry. */
class WindowSnapTest {
    private val screen = IntRect(0, 0, 360, 800)

    private fun rect(
        x: Int,
        y: Int,
        w: Int = 275,
        h: Int = 116,
    ) = IntRect(x, y, x + w, y + h)

    private fun snap(
        target: IntRect,
        vararg others: IntRect,
    ) = WindowSnap.snap(target, others.toList(), screen)

    @Test
    fun `a top edge near another's bottom docks flush under it`() {
        val main = rect(40, 0)

        val landed = snap(rect(40, 120), main)

        assertEquals(IntOffset(40, 116), landed)
    }

    @Test
    fun `a bottom edge near another's top docks flush above it`() {
        val eq = rect(40, 300)

        val landed = snap(rect(40, 189), eq)

        assertEquals(IntOffset(40, 184), landed)
    }

    @Test
    fun `aligned lefts snap while the windows overlap vertically`() {
        val main = rect(40, 0)

        // side by side, sharing rows 50..116: the lefts line up
        val landed = snap(rect(45, 50, w = 100, h = 300), main)

        assertEquals(40, landed.x)
    }

    @Test
    fun `the docking slack lets a window just below dock and align at once`() {
        val main = rect(40, 0)

        // 4px under main's bottom and 5px off its left: no true vertical
        // overlap, but the slack is what makes a stack line up
        val landed = snap(rect(45, 120, h = 300), main)

        assertEquals(IntOffset(40, 116), landed)
    }

    @Test
    fun `no perpendicular overlap means no horizontal snap`() {
        val main = rect(40, 0, h = 50)

        // 3px from main's left, but 400px below it: nothing to dock against
        val landed = snap(rect(43, 400), main)

        assertEquals(43, landed.x)
    }

    @Test
    fun `a corner approach snaps both axes in one move`() {
        val main = rect(40, 0)

        val landed = snap(rect(45, 121), main)

        assertEquals(IntOffset(40, 116), landed)
    }

    @Test
    fun `the nearest candidate wins per axis`() {
        val main = rect(40, 0)

        // 3px below main's bottom edge: the nearest edge takes it
        val landed = snap(rect(40, 119), main)

        assertEquals(116, landed.y)
    }

    @Test
    fun `nine px snaps and ten does not`() {
        val main = rect(40, 0)

        assertEquals(116, snap(rect(40, 125), main).y)
        assertEquals(126, snap(rect(40, 126), main).y)
    }

    @Test
    fun `dragging on past the threshold releases the snap`() {
        val main = rect(40, 0)

        assertEquals(116, snap(rect(40, 121), main).y) // still caught
        assertEquals(130, snap(rect(40, 130), main).y) // let go
    }

    @Test
    fun `the axes may take their corrections from different windows`() {
        val main = rect(40, 0)
        val other = rect(200, 400, w = 100, h = 100)

        // x from `other`'s left edge, y from main's bottom
        val landed = snap(rect(196, 120, w = 100, h = 300), main, other)

        assertEquals(IntOffset(200, 116), landed)
    }

    @Test
    fun `every screen edge attracts from the inside`() {
        assertEquals(0, snap(rect(4, 400)).x)
        assertEquals(screen.right - 275, snap(rect(screen.right - 275 - 5, 400)).x)
        assertEquals(0, snap(rect(40, 6)).y)
        assertEquals(screen.bottom - 116, snap(rect(40, screen.bottom - 116 - 3)).y)
    }

    @Test
    fun `a window beats the screen edge on an exact tie`() {
        // both the screen's left edge and the window's left are 5px away
        val other = rect(10, 400, w = 100, h = 100)

        val landed = snap(rect(5, 420, w = 100, h = 60), other)

        assertEquals(10, landed.x)
    }

    @Test
    fun `the correction never exceeds the snap distance`() {
        val main = rect(40, 0)
        for (dy in -40..40) {
            for (dx in -40..40 step 3) {
                val target = rect(40 + dx, 116 + dy)
                val landed = snap(target, main)
                assertTrue(
                    "the correction stays under the snap distance: " +
                        "moved ${abs(landed.x - target.left)},${abs(landed.y - target.top)} for $dx/$dy",
                    abs(landed.x - target.left) < WindowSnap.SNAP_DISTANCE &&
                        abs(landed.y - target.top) < WindowSnap.SNAP_DISTANCE,
                )
            }
        }
    }
}
