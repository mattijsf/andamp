// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.unit.IntOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * A window lands where the model says it does.
 *
 * Docking, snapping and resizing are worked out in virtual pixels; the screen is device
 * pixels, and the two agree only if the centering happens in virtual space. Laying the
 * window out centered (Compose's Alignment.Center) rounds in device pixels instead: the
 * playlist's segment is 29px, which is odd, so every step would flip the parity and move
 * the window a pixel while the model's top edge stays put.
 */
class WindowPlacementTest {
    private val scale = 3

    /** A phone's usable height is whatever is left after the status bar: not a round number. */
    private val screenPx = 2324
    private val screenH = screenPx / scale

    private fun virtualTop(
        offset: IntOffset,
        height: Int,
    ) = (screenH - height) / 2 + offset.y

    /** What laying the window out with Alignment.Center produces. */
    private fun centredPlacement(
        offset: IntOffset,
        height: Int,
    ) = (screenPx - height * scale) / 2 + offset.y * scale

    @Test
    fun `the drawn top is the virtual top, times the scale`() {
        val offset = IntOffset(0, 40)

        val placed = devicePlacementOf(offset, PL_W, 290, 275, screenH, scale)

        assertEquals(virtualTop(offset, 290) * scale, placed.y)
    }

    @Test
    fun `a window whose top edge is held still does not move as it resizes`() {
        // what the grip does: the height steps by a segment and the
        // offset is re-anchored so the top edge stays put
        val start = IntOffset(0, 40)
        val startH = PlaylistLayout.ofSegments(8).height
        val top = virtualTop(start, startH)

        val rows =
            (8 downTo 4).map { segments ->
                val height = PlaylistLayout.ofSegments(segments).height
                val offset = WindowSizing.anchorTop(start, oldH = startH, newH = height, screenH = screenH)
                devicePlacementOf(offset, PL_W, height, 275, screenH, scale).y
            }

        assertEquals("the drawn top stays put while the window resizes", listOf(top * scale), rows.distinct())
    }

    @Test
    fun `centering in device pixels makes it hop`() {
        // the alternative, as the reason for the design: same inputs, and the
        // drawn top moves although the model's top edge does not
        val start = IntOffset(0, 40)
        val startH = PlaylistLayout.ofSegments(8).height

        val rows =
            (8 downTo 4).map { segments ->
                val height = PlaylistLayout.ofSegments(segments).height
                centredPlacement(WindowSizing.anchorTop(start, startH, height, screenH), height)
            }

        assertNotEquals(1, rows.distinct().size)
    }

    @Test
    fun `the drawn position and the rectangle the neighbors dock against are the same place`() {
        val offset = IntOffset(-12, 33)
        val height = PlaylistLayout.ofSegments(6).height

        val placed = devicePlacementOf(offset, PL_W, height, 275, screenH, scale)
        val rect = rectOf(offset, PL_W, height, 275, screenH)

        assertEquals(rect.left * scale, placed.x)
        assertEquals(rect.top * scale, placed.y)
    }
}
