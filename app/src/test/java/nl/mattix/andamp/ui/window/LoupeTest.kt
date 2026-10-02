// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import nl.mattix.andamp.skin.Dest
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.ui.widget.Widget
import nl.mattix.andamp.ui.widget.button
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The magnifier's arithmetic: where it aims, how far the finger moves it, and
 * what a release presses.
 */
class LoupeTest {
    private var closed = 0
    private var shaded = 0

    private val buttons =
        listOf(
            Widget(
                "eq.chrome",
                androidx.compose.ui.unit
                    .IntRect(0, 0, 275, 116),
                background = true,
            ),
            button("eq.shade", 254, 3, 9, 9) { shaded++ },
            button("eq.close", 264, 3, 9, 9) { closed++ },
        )

    private fun loupe(start: Offset) =
        Loupe(
            window = "eq",
            roam = Loupe.clusterAround(buttons.last(), buttons),
            paint = {},
            widgets = { buttons },
            start = start,
        )

    @Test
    fun `a control too small for a finger is what it is for`() {
        assertTrue("a 9x9 title-bar button gets the lens", Loupe.fiddly(buttons[1]))
        assertTrue("an 8x8 clutter letter gets the lens", Loupe.fiddly(button("main.clutter.o", 10, 25, 8, 8) {}))
        assertFalse("a 23x18 transport button gets no lens", Loupe.fiddly(button("main.play", 39, 88, 23, 18) {}))
        assertFalse("a slider gets no lens", Loupe.fiddly(button("main.volume", 107, 57, 68, 13) {}))
        // and one that is none of those sizes and asks anyway
        assertTrue(
            "a playlist menu tile that asks gets the lens",
            Loupe.fiddly(button("pl.menu.add", 14, 90, 22, 18, magnify = true) {}),
        )
    }

    /**
     * The lens does not light a switched-off control, and a release over it presses
     * nothing.
     */
    @Test
    fun `the lens passes over a control that has been switched off`() {
        val off =
            listOf(
                button("eq.shade", 254, 3, 9, 9, inert = { true }) { shaded++ },
                button("eq.close", 264, 3, 9, 9) { closed++ },
            )
        val lens =
            Loupe(
                window = "eq",
                roam = Loupe.clusterAround(off.last(), off),
                paint = {},
                widgets = { off },
                start = Offset(258f, 7f),
            )
        val state = WinampState()

        lens.move(Offset.Zero, state)
        assertNull("the lens lights no switched-off control", state.pressedWidget)
        assertNull(lens.target)

        lens.release(state)
        assertEquals(0, shaded)
        assertEquals(0, closed)
    }

    /**
     * It is skipped as a target and kept as geometry.
     *
     * Minimize and close are eleven pixels apart with the shade button between them,
     * further than the lens joins two controls over, so a cluster that left the
     * switched-off one out would cover one button instead of three.
     */
    @Test
    fun `a control that has been switched off still holds its place in the row`() {
        val off =
            listOf(
                button("main.minimize", 244, 3, 9, 9) {},
                button("main.shade", 254, 3, 9, 9, inert = { true }) { shaded++ },
                button("main.close", 264, 3, 9, 9) { closed++ },
            )

        val around = Loupe.clusterAround(off.last(), off)

        assertTrue("the cluster reaches minimize: $around", around.left <= 244)
        assertTrue("the cluster reaches close: $around", around.right >= 273)
    }

    /**
     * Four tiles seven pixels apart that ask for the lens: the cluster covers the row.
     */
    @Test
    fun `the playlist's menu tiles are one cluster`() {
        val bar =
            listOf("add" to 14, "rem" to 43, "sel" to 72, "misc" to 101).map { (name, x) ->
                button("pl.menu.$name", x, 90, 22, 18, magnify = true) {}
            }

        val around = Loupe.clusterAround(bar.first(), bar)

        assertTrue("the cluster reaches MISC: $around", around.right >= 123)
    }

    /**
     * The left edge of the player is one run: the options icon in the title bar and the
     * clutter letters under it, thirteen pixels apart. The lens reaches all of them.
     */
    @Test
    fun `the logo and the clutter bar are one cluster`() {
        val corner =
            listOf(
                button("main.options", Dest.OPTIONS_BUTTON.x, Dest.OPTIONS_BUTTON.y, 9, 9) {},
            ) +
                listOf(Dest.CLUTTER_O, Dest.CLUTTER_A, Dest.CLUTTER_I, Dest.CLUTTER_D, Dest.CLUTTER_V)
                    .mapIndexed { i, at ->
                        button("main.clutter.$i", at.left, at.top, at.width, at.height) {}
                    }

        val around = Loupe.clusterAround(corner.first(), corner)

        assertTrue("the cluster reaches the clutter bar: $around", around.bottom >= Dest.CLUTTER_V.bottom)
    }

    @Test
    fun `the cluster is grown from the control, whichever way it runs`() {
        // sideways: the two title-bar buttons
        val bar = Loupe.clusterAround(buttons[1], buttons)
        assertTrue("the cluster reaches the button beside it: $bar", bar.right >= 273)

        // and downwards: the clutter bar is a column, and the same growing finds it
        val letters =
            (0..4).map { row -> button("main.clutter.$row", 10, 25 + row * 8, 8, 8) {} }
        val column = Loupe.clusterAround(letters.first(), letters)

        assertTrue("the cluster reaches the bottom of the column: $column", column.bottom >= 65)
        assertTrue("the cluster stays one column wide: $column", column.width <= 8 + 2 * 3)
    }

    @Test
    fun `something on its own is a cluster of one`() {
        val alone = button("gen.close", 264, 3, 9, 9) {}

        val cluster = Loupe.clusterAround(alone, listOf(alone))

        assertEquals(9 + 2 * 3, cluster.width)
    }

    @Test
    fun `the finger moves the crosshair at a fraction of its own speed`() {
        val state = WinampState()
        val loupe = loupe(Offset(258f, 7f))

        loupe.move(Offset(9f, 0f), state)

        assertEquals("the crosshair moves by the gearing", 264f, loupe.focus.x, 0.01f)
    }

    @Test
    fun `the crosshair cannot leave the cluster it was opened on`() {
        val state = WinampState()
        val loupe = loupe(Offset(268f, 7f))

        loupe.move(Offset(0f, 9000f), state)
        loupe.move(Offset(-9000f, 0f), state)

        val roam = Loupe.clusterAround(buttons.last(), buttons)
        assertTrue("the crosshair stays above the cluster's bottom: ${loupe.focus.y}", loupe.focus.y < roam.bottom)
        assertTrue("the crosshair stays inside the cluster's left edge: ${loupe.focus.x}", loupe.focus.x >= roam.left)
    }

    @Test
    fun `the button under the crosshair looks pressed while it is under it`() {
        val state = WinampState()
        val loupe = loupe(Offset(258f, 7f))

        loupe.move(Offset(0f, 0f), state)

        assertEquals("eq.shade", state.pressedWidget)
    }

    @Test
    fun `shoving far past the cluster calls it off`() {
        val state = WinampState()
        val loupe = loupe(Offset(268f, 7f))
        state.loupe = loupe

        // far to the left of the two buttons, and further than a wobble
        loupe.move(Offset(-9000f, 0f), state)
        loupe.release(state)

        assertNull("the lens lets go", state.loupe)
        assertNull("nothing looks pressed", state.pressedWidget)
        assertEquals("a cancel presses nothing", 0, closed)
        assertEquals(0, shaded)
    }

    @Test
    fun `pushing against the edge and coming back is not a cancel`() {
        val state = WinampState()
        val loupe = loupe(Offset(268f, 7f))
        state.loupe = loupe

        // a shove worth less than the escape, then back where it came from
        loupe.move(Offset(-30f, 0f), state)
        loupe.move(Offset(30f, 0f), state)

        assertNotNull("a nudge does not cancel the lens", state.loupe)
        assertEquals("the crosshair returns to where it was", 268f, loupe.focus.x, 0.6f)
    }

    @Test
    fun `a release presses what the crosshair is on`() {
        val state = WinampState()
        val loupe = loupe(Offset(268f, 7f))
        state.loupe = loupe

        loupe.release(state)

        assertEquals(1, closed)
        assertEquals(0, shaded)
        assertNull("the lens closes on release", state.loupe)
        assertNull("nothing looks pressed", state.pressedWidget)
    }

    @Test
    fun `the chrome under everything is never what gets pressed`() {
        // a window whose only widget is its backdrop: with the buttons gone
        // the crosshair is over the handle, and a handle is not a button
        val state = WinampState()
        val bare = buttons.take(1)
        val loupe =
            Loupe(
                window = "eq",
                roam = IntRect(0, 0, 275, 14),
                paint = {},
                widgets = { bare },
                start = Offset(120f, 7f),
            )
        state.loupe = loupe

        loupe.release(state)

        assertEquals(0, closed)
        assertEquals(0, shaded)
        assertNull(state.loupe)
    }
}
