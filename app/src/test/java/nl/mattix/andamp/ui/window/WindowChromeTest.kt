// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import nl.mattix.andamp.ui.widget.Widget
import nl.mattix.andamp.ui.widget.button
import nl.mattix.andamp.ui.widget.hitTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a window's chrome answers for, and what it hands to the buttons drawn
 * in it.
 *
 * The chrome is a backdrop over the whole window so any bare pixel drags it, and it owns
 * its title bar so a control below the bar cannot reach up into it with hit slop. That
 * ownership stops at the buttons in the bar: without its slop, a tap a hair wide of the
 * 9x9 close button would move the window instead of closing it. The bar here is 20px,
 * taller than the player's 14.
 */
class WindowChromeTest {
    private val width = 275
    private val height = 116
    private val titleH = 20
    private val closeX = width - 11

    private fun close() = button("close", closeX, 3, 9, 9) {}

    private fun chrome(siblings: List<Widget>): List<Widget> {
        val chrome =
            windowChromeWidget(
                id = "chrome",
                bounds = IntRect(0, 0, width, height),
                grip = IntRect(width - RESIZE_GRIP, height - RESIZE_GRIP, width, height),
                drag = WindowDrag(),
                resize = WindowResize(),
                offsetNow = { IntOffset.Zero },
                heightNow = { height },
                onMove = { _, _ -> },
                onResize = {},
                titleH = titleH,
                siblings = { siblings },
            )
        return listOf(chrome) + siblings
    }

    private fun hit(
        x: Int,
        y: Int,
        siblings: List<Widget> = listOf(close()),
    ) = hitTest(chrome(siblings), IntOffset(x, y))?.id

    @Test
    fun `a tap just wide of the close button still closes`() {
        assertEquals("close", hit(closeX - 3, 8))
    }

    @Test
    fun `a tap just under the close button still closes`() {
        assertEquals("close", hit(closeX + 4, 15))
    }

    @Test
    fun `a tap on the bare bar drags the window`() {
        assertEquals("chrome", hit(100, 8))
    }

    @Test
    fun `a control below the bar cannot reach up into it`() {
        // the equalizer's ON button sits four pixels under the bar; a press
        // meant for the handle must not switch the equalizer off
        val on = button("eq.on", 14, titleH + 4, 26, 12) {}
        assertEquals("chrome", hit(20, titleH - 2, siblings = listOf(on)))
    }

    @Test
    fun `the grip is the chrome's own corner`() {
        assertEquals("chrome", hit(width - 3, height - 3))
    }
}
