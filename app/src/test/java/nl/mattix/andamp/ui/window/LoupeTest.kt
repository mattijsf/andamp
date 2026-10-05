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

    private fun loupe(
        start: Offset,
        reach: Loupe.Reach = Loupe.Reach.UNLIMITED,
    ) = Loupe(
        window = "eq",
        roam = Loupe.clusterAround(buttons.last(), buttons),
        paint = {},
        widgets = { buttons },
        start = start,
        reach = reach,
    )

    /** A fingertip that stays ten pixels from the glass's edge and is aimed within thirty of it. */
    private val edge = Loupe.Edge(reach = 10f, band = 30f)

    /** The room of a finger at [x] in the title bar of a window as wide as the screen. */
    private fun fingerAt(x: Float) = Loupe.Reach.on(Offset(x, 7f), screenW = 275, screenH = 116, edge)

    private fun aimed(
        x: Float,
        screenW: Int = 275,
    ) = Loupe.aimedAcross(x, screenW, edge)

    /** The centers of the two buttons: shade at 254 and close at 264, each 9 wide. */
    private val onShade = Offset(258f, 7f)
    private val closeX = 268f

    @Test
    fun `a finger with room to spare moves the crosshair as it always does`() {
        val state = WinampState()
        // in the middle of a wide screen
        val loupe = loupe(onShade, Loupe.Reach.on(Offset(600f, 300f), screenW = 1200, screenH = 800, edge))

        loupe.move(Offset(9f, 0f), state)

        assertEquals(258f + 9f / Loupe.GEARING, loupe.focus.x, 0.01f)
    }

    @Test
    fun `against the edge of the screen the last of the finger's room reaches the outermost button`() {
        val state = WinampState()
        // 19 pixels from the edge: all but the last ten are the finger's to use
        val loupe = loupe(onShade, fingerAt(256f))

        loupe.move(Offset(9f, 0f), state)

        assertEquals("the crosshair is on the middle of close", closeX, loupe.focus.x, 0.01f)
        assertEquals("eq.close", loupe.target?.id)
    }

    @Test
    fun `a finger already at the edge still reaches it, and no faster than can be held`() {
        val state = WinampState()
        val loupe = loupe(onShade, fingerAt(264f))

        // ten pixels of crosshair for four of finger: the least gearing there is
        loupe.move(Offset(10 * Loupe.MIN_GEARING, 0f), state)
        assertEquals(closeX, loupe.focus.x, 0.01f)

        loupe.release(state)
        assertEquals("the release presses close", 1, closed)
        assertEquals(0, shaded)
    }

    @Test
    fun `away from the edge the same lens moves at its usual speed`() {
        val state = WinampState()
        val loupe = loupe(Offset(closeX, 7f), fingerAt(264f))

        loupe.move(Offset(-9f, 0f), state)

        assertEquals(closeX - 9f / Loupe.GEARING, loupe.focus.x, 0.01f)
    }

    @Test
    fun `the outermost button is in no hurry to go further out`() {
        val state = WinampState()
        // opened on close itself, with the finger at the very edge
        val loupe = loupe(Offset(closeX, 7f), fingerAt(270f))

        loupe.move(Offset(3f, 0f), state)

        assertEquals(closeX + 3f / Loupe.GEARING, loupe.focus.x, 0.01f)
    }

    @Test
    fun `going out and coming back lands where it started`() {
        val state = WinampState()
        val loupe = loupe(onShade, fingerAt(262f))

        loupe.move(Offset(6f, 0f), state)
        loupe.move(Offset(-10f, 0f), state)
        loupe.move(Offset(4f, 0f), state)

        assertEquals(onShade.x, loupe.focus.x, 0.01f)
    }

    @Test
    fun `past the outermost button the lens is as hard to push away as anywhere`() {
        val state = WinampState()
        val loupe = loupe(onShade, fingerAt(262f))
        state.loupe = loupe

        // four pixels reach close; the rest is geared down as usual, and the cluster ends
        // seven pixels past close's middle, so this is thirty past it: short of the escape
        loupe.move(Offset(4f + (7f + 30f) * Loupe.GEARING, 0f), state)

        assertNotNull("the lens is still up", state.loupe)
    }

    @Test
    fun `a press as far out as a finger gets is aimed at the edge of the screen`() {
        assertEquals(275f, aimed(265f), 0.01f)
        assertEquals(0f, aimed(10f), 0.01f)
        // and one that got closer than expected is not aimed past it
        assertEquals(275f, aimed(272f), 0.01f)
    }

    @Test
    fun `a press where the edge's pull ends is aimed where it landed`() {
        assertEquals(245f, aimed(245f), 0.01f)
        assertEquals(30f, aimed(30f), 0.01f)
        assertEquals(137f, aimed(137f), 0.01f)
    }

    @Test
    fun `between the two, a press is aimed further out the nearer the edge it is`() {
        // 15 from the right edge, where the shade button is drawn: aimed at close, 7.5 from it
        assertEquals(267.5f, aimed(260f), 0.01f)
        // 22 from it, still on the shade button's side of minimize: aimed at shade, 18 from it
        assertEquals(257f, aimed(253f), 0.01f)
        // and the same from the left
        assertEquals(7.5f, aimed(15f), 0.01f)
    }

    @Test
    fun `a window in from the side of a wide screen is pressed where the finger is`() {
        assertEquals(700f, aimed(700f, screenW = 1200), 0.01f)
    }

    @Test
    fun `a fingertip is the same size however large the virtual pixels are drawn`() {
        // a phone, where a dp spans about two thirds of a virtual pixel, and a tablet held
        // upright, where it spans a quarter of one
        val phone = Loupe.Edge.of(virtualPerDp = 0.64f)
        val tablet = Loupe.Edge.of(virtualPerDp = 0.26f)

        assertEquals(10.24f, phone.reach, 0.01f)
        assertEquals(30.72f, phone.band, 0.01f)
        // on the tablet the shade button's own pixels, 15 from the edge, are out of the band
        assertEquals(260f, Loupe.aimedAcross(260f, 275, tablet), 0.01f)
        assertEquals(267.9f, Loupe.aimedAcross(260f, 275, phone), 0.1f)
    }

    @Test
    fun `a row of buttons under the top of the screen is not hurried up or down`() {
        val state = WinampState()
        // seven pixels under the top edge, and every button on the same row
        val loupe = loupe(onShade, Loupe.Reach.on(Offset(150f, 7f), screenW = 275, screenH = 600, edge))

        loupe.move(Offset(0f, -3f), state)

        assertEquals(7f - 3f / Loupe.GEARING, loupe.focus.y, 0.01f)
    }

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
