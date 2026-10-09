// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A docked group stays docked at the edge of the screen.
 *
 * Each window keeps itself reachable on its own when it is laid out, so the group's travel
 * has to stop where the first member would be held back. Otherwise the player goes on, the
 * member stays, and the two come apart.
 */
class GroupClampTest {
    private val screenW = 400
    private val screenH = 800
    private val width = 275
    private val height = 116
    private val titleH = 14

    /** The player at [left], carrying [carried], asked to move by [dx] and [dy]. */
    private fun settleGroup(
        left: Int,
        dx: Int,
        dy: Int = 0,
        carried: List<IntRect>,
        handles: List<Int> = carried.map { titleH },
    ): IntRect {
        val now = IntRect(left, 100, left + width, 100 + height)
        val landed =
            settle(
                offset = offsetOfTopLeft(IntOffset(now.left + dx, now.top + dy), width, height, screenW, screenH),
                width = width,
                height = height,
                screenW = screenW,
                screenH = screenH,
                safeTop = 0,
                safeBottom = screenH,
                titleH = titleH,
                neighbours = emptyList(),
                escaped = true,
                carried = carried,
                carriedHandles = handles,
                anchorNow = now,
            )
        return rectOf(landed, width, height, screenW, screenH)
    }

    /** Whether [rect], moved along with the player, is where it may be on its own. */
    private fun staysPut(
        rect: IntRect,
        travel: IntOffset,
        handle: Int = titleH,
    ): Boolean {
        val moved = IntOffset(rect.left + travel.x, rect.top + travel.y)
        val own = offsetOfTopLeft(moved, rect.width, rect.height, screenW, screenH)
        val clamped =
            WindowBounds.clamp(own, rect.width, rect.height, screenW, screenH, handle, safeTop = 0, safeBottom = screenH)
        return own == clamped
    }

    @Test
    fun `a window docked beside the player stops the group at the right edge`() {
        val beside = IntRect(width, 100, 2 * width, 100 + height)

        val landed = settleGroup(left = 0, dx = 1000, carried = listOf(beside))

        // the player alone could hang half off the screen; with a window on its right it
        // stops where that window's own limit is
        val travel = IntOffset(landed.left, 0)
        assertEquals(WindowBounds.leftRange(width, screenW).last - width, landed.left)
        assertEquals(true, staysPut(beside, travel))
    }

    @Test
    fun `a wider window under the player stops the group before the player's own limit`() {
        val wider = IntRect(0, 100 + height, width + 100, 100 + height + 200)

        val landed = settleGroup(left = 0, dx = 1000, carried = listOf(wider))

        assertEquals(WindowBounds.leftRange(wider.width, screenW).last, landed.left)
        assertEquals(true, staysPut(wider, IntOffset(landed.left, 0)))
    }

    @Test
    fun `the player's own limit still holds when its members have room`() {
        val under = IntRect(0, 100 + height, width, 100 + height + 100)

        val landed = settleGroup(left = 0, dx = -1000, carried = listOf(under))

        assertEquals(WindowBounds.leftRange(width, screenW).first, landed.left)
        assertEquals(true, staysPut(under, IntOffset(landed.left, 0)))
    }

    @Test
    fun `a member with a taller handle stops the group at the bottom`() {
        val playlist = IntRect(0, 100 + height, width, 100 + height + 200)
        val plHandle = 20

        val landed = settleGroup(left = 0, dx = 0, dy = 10_000, carried = listOf(playlist), handles = listOf(plHandle))

        val travel = IntOffset(0, landed.top - 100)
        assertEquals(screenH - plHandle - playlist.top, travel.y)
        assertEquals(true, staysPut(playlist, travel, handle = plHandle))
    }
}
