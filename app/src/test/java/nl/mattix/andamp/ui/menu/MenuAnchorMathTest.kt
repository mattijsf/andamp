// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import androidx.compose.ui.unit.IntRect
import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.WindowStore
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A menu opens against the window it was opened from, wherever that window is.
 * The windows float and publish their own rectangles.
 */
class MenuAnchorMathTest {
    private val scale = 3
    private val width = 1080
    private val height = 2200

    /** Virtual-px rectangles, the way the windows publish them. */
    private val rects =
        mapOf(
            WindowStore.MAIN to IntRect(43, 0, 43 + 275, 116),
            WindowStore.EQ to IntRect(43, 116, 43 + 275, 232),
            WindowStore.PLAYLIST to IntRect(60, 400, 60 + 275, 700),
        )

    private fun bounds(anchor: MenuAnchor?) = menuAnchorBounds(anchor, scale, width, height, rects)

    @Test
    fun `an anchor lands at its window's own corner`() {
        // PRESETS at (217,18) 44x12 inside the equalizer
        val rect = bounds(MenuAnchor(WindowStore.EQ, 217, 18, 44, 12))

        assertEquals((43 + 217) * scale, rect.left)
        assertEquals((116 + 18) * scale, rect.top)
        assertEquals(44 * scale, rect.width)
        assertEquals(12 * scale, rect.height)
    }

    @Test
    fun `a dragged window opens its menu at its own position`() {
        val moved = mapOf(WindowStore.MAIN to IntRect(200, 640, 200 + 275, 640 + 116))

        val rect = menuAnchorBounds(MenuAnchor(WindowStore.MAIN, 6, 3, 9, 9), scale, width, height, moved)

        assertEquals((200 + 6) * scale, rect.left)
        assertEquals((640 + 3) * scale, rect.top)
    }

    @Test
    fun `the playlist anchor uses the playlist's own rectangle`() {
        val rect = bounds(MenuAnchor(WindowStore.PLAYLIST, 0, 0, 10, 10))

        assertEquals(60 * scale, rect.left)
        assertEquals(400 * scale, rect.top)
    }

    @Test
    fun `on a surface drawn smaller than it is laid out, an anchor lands where the widget is drawn`() {
        // a 1080 px screen filled by a player laid out at 4: 1100 px drawn as 1080
        val shrink = 1080f / 1100f
        val filled = mapOf(WindowStore.EQ to IntRect(0, 116, 275, 232))

        val rect = menuAnchorBounds(MenuAnchor(WindowStore.EQ, 217, 18, 44, 12), 4, width, height, filled, shrink)

        assertEquals(852, rect.left) // 217 * 4 * shrink = 852.2
        assertEquals(526, rect.top) // 134 * 4 * shrink = 526.3
        assertEquals(173, rect.width) // 44 * 4 * shrink = 172.8
        assertEquals(47, rect.height) // 12 * 4 * shrink = 47.1
    }

    /** A window still laying out has no rectangle to anchor to. */
    @Test
    fun `an anchor on a window that has not laid out lands mid-screen`() {
        val rect = menuAnchorBounds(MenuAnchor(WindowStore.SKINS, 5, 5, 10, 10), scale, width, height, rects)

        assertEquals(width / 2, rect.left)
        assertEquals(height / 3, rect.top)
        assertEquals(0, rect.width)
    }

    @Test
    fun `null anchor centers mid-screen with zero size`() {
        val rect = bounds(null)

        assertEquals(width / 2, rect.left)
        assertEquals(height / 3, rect.top)
        assertEquals(0, rect.width)
        assertEquals(0, rect.height)
    }
}
