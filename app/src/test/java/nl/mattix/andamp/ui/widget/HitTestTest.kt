// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.widget

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HitTestTest {
    private fun widget(
        id: String,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        enabled: Boolean = true,
    ) = Widget(id, IntRect(x, y, x + w, y + h), enabled = { enabled })

    /**
     * A switched-off control is inert, not disabled: it keeps hit-testing.
     *
     * Winamp's title-bar buttons are one pixel apart, which is well inside
     * [HIT_SLOP], so a shade button that stopped hit-testing would hand its
     * pixels to Close.
     */
    @Test
    fun `a switched-off control keeps its own pixels`() {
        val shade = Widget("shade", IntRect(254, 3, 263, 12), inert = { true })
        val close = widget("close", 264, 3, 9, 9)

        assertEquals("shade", hitTest(listOf(shade, close), IntOffset(258, 7))?.id)
    }

    @Test
    fun `exact hit wins over a nearer neighbor's slop zone`() {
        // pos is inside big and 1px from small: exact containment must win
        val big = widget("big", 0, 0, 50, 50)
        val small = widget("small", 51, 0, 9, 9)
        assertEquals("big", hitTest(listOf(big, small), IntOffset(49, 4))?.id)
    }

    @Test
    fun `overlapping exact hits keep last-widget-wins`() {
        val under = widget("under", 0, 0, 20, 20)
        val over = widget("over", 5, 5, 10, 10)
        assertEquals("over", hitTest(listOf(under, over), IntOffset(7, 7))?.id)
    }

    @Test
    fun `a near miss snaps to the widget within slop`() {
        val button = widget("btn", 6, 3, 9, 9) // the titlebar options button
        assertEquals("btn", hitTest(listOf(button), IntOffset(2, 1))?.id) // dx=4, dy=2
        assertEquals("btn", hitTest(listOf(button), IntOffset(20, 11))?.id) // dx=6 exactly at slop
    }

    @Test
    fun `a miss beyond slop hits nothing`() {
        val button = widget("btn", 6, 3, 9, 9)
        assertNull(hitTest(listOf(button), IntOffset(21, 11))) // dx=7
        assertNull(hitTest(listOf(button), IntOffset(30, 30)))
    }

    @Test
    fun `between two widgets the closer one wins`() {
        // eq band sliders: 14 wide at 18px pitch leaves a 4px gap
        val band0 = widget("band0", 78, 38, 14, 63)
        val band1 = widget("band1", 96, 38, 14, 63)
        assertEquals("band0", hitTest(listOf(band0, band1), IntOffset(92, 60))?.id) // 1px past band0
        assertEquals("band1", hitTest(listOf(band0, band1), IntOffset(94, 60))?.id) // 2px from band1
    }

    @Test
    fun `a miss between two widgets goes to the nearer one`() {
        val a = widget("a", 0, 0, 10, 10)
        val b = widget("b", 14, 0, 10, 10)
        // x=11: 2px from a's last column (9), 3px from b's first (14) -> a
        assertEquals("a", hitTest(listOf(a, b), IntOffset(11, 5))?.id)
        // x=12: 3px from a, 2px from b -> b
        assertEquals("b", hitTest(listOf(a, b), IntOffset(12, 5))?.id)
    }

    @Test
    fun `disabled widgets are transparent to slop too`() {
        val hidden = widget("hidden", 6, 3, 9, 9, enabled = false)
        val other = widget("other", 20, 3, 9, 9)
        assertEquals("other", hitTest(listOf(hidden, other), IntOffset(16, 6))?.id)
        assertNull(hitTest(listOf(hidden), IntOffset(7, 5)))
    }

    @Test
    fun `distance is zero inside and grows from the edges`() {
        val w = widget("w", 10, 10, 10, 10)
        assertEquals(0, w.distanceSquaredTo(IntOffset(10, 10)))
        assertEquals(0, w.distanceSquaredTo(IntOffset(19, 19))) // right/bottom-exclusive bounds
        assertEquals(1, w.distanceSquaredTo(IntOffset(20, 19)))
        assertEquals(8, w.distanceSquaredTo(IntOffset(8, 8)))
    }

    @Test
    fun `group hit test picks the band under the pointer column`() {
        val bands = (0 until 10).map { i -> band("eq.band$i", 78 + i * 18) }
        assertEquals("eq.band0", hitTestGroup(bands, GROUP, 85f)?.id)
        assertEquals("eq.band3", hitTestGroup(bands, GROUP, 78f + 3 * 18f + 5f)?.id)
        assertEquals("eq.band9", hitTestGroup(bands, GROUP, 78f + 9 * 18f)?.id)
    }

    @Test
    fun `group hit test ignores y, so a sweep above the sliders still switches bands`() {
        // Winamp keeps switching bands while the finger is held at max, far
        // above the slider rects
        val bands = (0 until 10).map { i -> band("eq.band$i", 78 + i * 18) }
        assertEquals("eq.band4", hitTestGroup(bands, GROUP, 78f + 4 * 18f + 6f)?.id)
    }

    @Test
    fun `between two bands the nearer column wins`() {
        val bands = listOf(band("eq.band0", 78), band("eq.band1", 96))
        assertEquals("eq.band0", hitTestGroup(bands, GROUP, 93f)?.id) // 1px past band0
        assertEquals("eq.band1", hitTestGroup(bands, GROUP, 95f)?.id) // 1px before band1
    }

    @Test
    fun `a sweep past the ends clamps to the outermost band`() {
        val bands = listOf(band("eq.band0", 78), band("eq.band1", 96))
        assertEquals("eq.band0", hitTestGroup(bands, GROUP, 0f)?.id)
        assertEquals("eq.band1", hitTestGroup(bands, GROUP, 500f)?.id)
    }

    @Test
    fun `widgets outside the group are never targeted`() {
        val mixed = listOf(band("eq.band0", 78), widget("eq.preamp", 21, 38, 14, 63))
        assertEquals("eq.band0", hitTestGroup(mixed, GROUP, 25f)?.id)
        assertNull(hitTestGroup(listOf(widget("eq.preamp", 21, 38, 14, 63)), GROUP, 25f))
    }

    private fun band(
        id: String,
        x: Int,
    ) = Widget(id, IntRect(x, 38, x + 14, 38 + 63), dragGroup = GROUP)

    private companion object {
        const val GROUP = "eq.bands"
    }

    @Test
    fun `a control keeps its slop over a backdrop widget`() {
        // a floating window's drag handle covers the whole window, including
        // the 9x9 close button drawn on its title bar
        val handle = Widget("gen.drag", IntRect(0, 0, 275, 154), background = true)
        val close = Widget("gen.close", IntRect(264, 3, 273, 12))
        val widgets = listOf(handle, close)

        // just outside the close button: inside the backdrop, within slop of the button
        assertEquals("gen.close", hitTest(widgets, IntOffset(261, 14))?.id)
        // dead center of the button still hits it
        assertEquals("gen.close", hitTest(widgets, IntOffset(268, 7))?.id)
        // far from any control, the backdrop takes the press
        assertEquals("gen.drag", hitTest(widgets, IntOffset(100, 100))?.id)
    }

    @Test
    fun `a region its owner claims outright beats whatever is drawn over it`() {
        // the window chrome's resize grip, with a list painted across it: the
        // list is a control and sits later in the list, so both the exact pass
        // and the slop pass would hand it the press
        val chrome =
            Widget(
                "chrome",
                IntRect(0, 0, 275, 100),
                background = true,
                owned = { it.x >= 255 && it.y >= 80 },
            )
        val list = Widget("list", IntRect(0, 20, 275, 90))

        val hit = hitTest(listOf(chrome, list), IntOffset(260, 85))

        assertEquals("chrome", hit?.id)
    }

    @Test
    fun `outside the owned region the control still wins`() {
        val chrome =
            Widget(
                "chrome",
                IntRect(0, 0, 275, 100),
                background = true,
                owned = { it.x >= 255 && it.y >= 80 },
            )
        val list = Widget("list", IntRect(0, 20, 275, 90))

        assertEquals("list", hitTest(listOf(chrome, list), IntOffset(100, 50))?.id)
    }
}
