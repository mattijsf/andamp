// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * One finger on a modern skin, as the skin hears it. Two surface pixels make a skin pixel here, so
 * a press at (20, 40) reaches the skin at (10, 20). A press the engine names as on a move area
 * moves the stack once it travels, which [drags] records.
 */
class WalTouchTest {
    private val surface = FakeWalSurface()
    private val longPresses = mutableListOf<Pair<Offset, Int>>()
    private val pressed = mutableListOf<Offset>()
    private val drags = mutableListOf<String>()
    private val touch =
        WalTouch(
            surface,
            RULES,
            toSkin = { IntOffset((it.x / 2).toInt(), (it.y / 2).toInt()) },
            onPress = { pressed += it },
            onLongPress = { at, seq -> longPresses += at to seq },
            drag =
                object : WalTouch.Drag {
                    override fun start(
                        from: Offset,
                        at: Offset,
                    ) {
                        drags += "start ${from.x.toInt()},${from.y.toInt()} at ${at.x.toInt()},${at.y.toInt()}"
                    }

                    override fun move(at: Offset) {
                        drags += "move ${at.x.toInt()},${at.y.toInt()}"
                    }

                    override fun end() {
                        drags += "end"
                    }
                },
        )

    /** The skin laid out, every press on a move area or, with [moveAreas] false, none. */
    private fun onMoveAreas(moveAreas: Boolean = true) {
        surface.layout = stackOf(200, 100, 2f)(WalFit(400, 800, 1f, sideways = false))
        surface.moveAreaUnderEveryPress = moveAreas
    }

    private fun tap(
        at: Offset,
        downAt: Long,
        upAt: Long = downAt + 80,
    ) {
        touch.down(at, downAt)
        touch.up(at, upAt, consumed = false)
    }

    @Test
    fun `a tap reaches the skin as a press and a release, in skin pixels`() {
        touch.down(Offset(20f, 40f), 0)
        touch.up(Offset(22f, 40f), 90, consumed = false)

        assertEquals(listOf("down 10,20", "up 11,20"), surface.calls)
        assertEquals("the press point is told before the skin hears it", listOf(Offset(20f, 40f)), pressed)
    }

    @Test
    fun `a move reaches the skin as a move`() {
        touch.down(Offset(20f, 40f), 0)
        touch.move(Offset(60f, 40f))
        touch.up(Offset(60f, 40f), 200, consumed = false)

        assertEquals(listOf("down 10,20", "move 30,20", "up 30,20"), surface.calls)
    }

    @Test
    fun `a second tap soon after and near the first is a double tap, and its release is not heard`() {
        tap(Offset(20f, 40f), downAt = 0, upAt = 80)

        touch.down(Offset(26f, 44f), 80 + RULES.doubleTapMs)
        touch.up(Offset(26f, 44f), 80 + RULES.doubleTapMs + 60, consumed = false)

        assertEquals(listOf("down 10,20", "up 10,20", "doubleTap 13,22"), surface.calls)
    }

    @Test
    fun `a second tap after the double-tap timeout is a fresh press`() {
        tap(Offset(20f, 40f), downAt = 0, upAt = 80)

        tap(Offset(20f, 40f), downAt = 80 + RULES.doubleTapMs + 1)

        assertEquals(listOf("down 10,20", "up 10,20", "down 10,20", "up 10,20"), surface.calls)
    }

    @Test
    fun `a second tap beyond the double-tap slop is a fresh press`() {
        tap(Offset(20f, 40f), downAt = 0, upAt = 80)

        tap(Offset(20f + RULES.doubleTapSlop + 1, 40f), downAt = 120)

        assertEquals("down", surface.calls[2].substringBefore(' '))
    }

    @Test
    fun `a tap after a drag is no double tap`() {
        touch.down(Offset(20f, 40f), 0)
        touch.move(Offset(80f, 40f))
        touch.up(Offset(80f, 40f), 80, consumed = false)

        tap(Offset(20f, 40f), downAt = 120)

        assertEquals("down 10,20", surface.calls[3])
    }

    @Test
    fun `a third tap after a double tap is a press again`() {
        tap(Offset(20f, 40f), downAt = 0, upAt = 80)
        tap(Offset(20f, 40f), downAt = 150, upAt = 220)

        tap(Offset(20f, 40f), downAt = 300)

        assertEquals(listOf("down 10,20", "up 10,20", "doubleTap 10,20", "down 10,20", "up 10,20"), surface.calls)
    }

    @Test
    fun `a long press fires while the finger is down, cancels the press, and swallows the rest`() {
        touch.down(Offset(20f, 40f), 1_000)
        assertEquals(RULES.longPressMs, touch.longPressIn(1_000))
        assertEquals(RULES.longPressMs - 100, touch.longPressIn(1_100))

        touch.longPressDue()

        assertEquals(listOf("down 10,20", "cancel"), surface.calls)
        assertEquals("the menu opens at the press, in its gesture", listOf(Offset(20f, 40f) to 1), longPresses)
        assertNull("it fires once", touch.longPressIn(1_500))

        touch.move(Offset(24f, 40f))
        touch.up(Offset(24f, 40f), 1_900, consumed = false)
        touch.cancel()

        assertEquals("nothing more of that finger reaches the skin", listOf("down 10,20", "cancel"), surface.calls)
    }

    @Test
    fun `a finger that wanders within the touch slop still long-presses`() {
        touch.down(Offset(20f, 40f), 0)
        touch.move(Offset(20f + RULES.touchSlop, 40f))

        touch.longPressDue()

        assertEquals(1, longPresses.size)
    }

    @Test
    fun `a move past the touch slop disarms the long press`() {
        touch.down(Offset(20f, 40f), 0)
        touch.move(Offset(20f + RULES.touchSlop + 1, 40f))

        assertNull(touch.longPressIn(100))
        touch.longPressDue()

        assertEquals(emptyList<Pair<Offset, Int>>(), longPresses)
        assertEquals("the drag goes on reaching the skin", listOf("down 10,20", "move 14,20"), surface.calls)
    }

    @Test
    fun `a skin menu opened during the press rules the long press out`() {
        touch.down(Offset(20f, 40f), 0)
        touch.menuShown()

        assertNull(touch.longPressIn(100))
        touch.longPressDue()

        assertEquals(emptyList<Pair<Offset, Int>>(), longPresses)
        assertEquals(listOf("down 10,20"), surface.calls)
    }

    @Test
    fun `a double tap held down long-presses in the double tap's gesture`() {
        tap(Offset(20f, 40f), downAt = 0, upAt = 80)
        touch.down(Offset(20f, 40f), 150)

        touch.longPressDue()

        assertEquals("the second gesture's seq", listOf(Offset(20f, 40f) to 2), longPresses)
    }

    @Test
    fun `a release that arrives consumed cancels the press`() {
        touch.down(Offset(20f, 40f), 0)

        touch.up(Offset(20f, 40f), 80, consumed = true)

        assertEquals(listOf("down 10,20", "cancel"), surface.calls)
    }

    @Test
    fun `a press whose change vanished, or whose loop ended without a release, is cancelled once`() {
        touch.down(Offset(20f, 40f), 0)

        touch.cancel()
        touch.cancel()

        assertEquals(listOf("down 10,20", "cancel"), surface.calls)
    }

    @Test
    fun `a cancelled press starts no double tap`() {
        touch.down(Offset(20f, 40f), 0)
        touch.cancel()

        tap(Offset(20f, 40f), downAt = 100)

        assertEquals("down 10,20", surface.calls[2])
    }

    @Test
    fun `the end of the loop after a release cancels nothing`() {
        tap(Offset(20f, 40f), downAt = 0)

        touch.cancel()

        assertEquals(listOf("down 10,20", "up 10,20"), surface.calls)
    }

    @Test
    fun `a press on a move area that travels past the touch slop moves the stack, and the skin's press is cancelled`() {
        onMoveAreas()
        touch.down(Offset(20f, 40f), 0)
        touch.move(Offset(20f + RULES.touchSlop, 40f))

        touch.move(Offset(40f, 44f))
        touch.move(Offset(90f, 140f))
        // the release comes a little further on than the last move
        touch.up(Offset(96f, 150f), 300, consumed = false)

        assertEquals(
            "the skin heard the moves within the slop, then let go",
            listOf("down 10,20", "move 14,20", "cancel"),
            surface.calls,
        )
        assertEquals(
            "the stack lands where the finger lifted",
            listOf("start 20,40 at 40,44", "move 90,140", "move 96,150", "end"),
            drags,
        )
        assertNull("no long press after a drag", touch.longPressIn(400))
    }

    @Test
    fun `a press the engine names a move area only after the slop moves the stack from the next move on`() {
        onMoveAreas(moveAreas = false)
        touch.down(Offset(20f, 40f), 0)
        touch.move(Offset(40f, 40f))
        assertEquals("not named yet: the skin hears the move", listOf("down 10,20", "move 20,20"), surface.calls)

        // the engine has handled the press, and names it
        surface.layout = surface.layout!!.copy(moveTarget = 1)
        touch.move(Offset(50f, 40f))
        touch.up(Offset(50f, 40f), 200, consumed = false)

        assertEquals(listOf("down 10,20", "move 20,20", "cancel"), surface.calls)
        assertEquals("measured from where the finger went down", listOf("start 20,40 at 50,40", "move 50,40", "end"), drags)
    }

    @Test
    fun `a drag from anywhere else still reaches the skin`() {
        onMoveAreas(moveAreas = false)
        surface.layout = surface.layout!!.copy(moveTarget = 99)

        touch.down(Offset(20f, 40f), 0)
        touch.move(Offset(80f, 40f))
        touch.up(Offset(80f, 40f), 200, consumed = false)

        assertEquals(listOf("down 10,20", "move 40,20", "up 40,20"), surface.calls)
        assertEquals(emptyList<String>(), drags)
    }

    @Test
    fun `a tap and a double tap on a move area reach the skin as before`() {
        onMoveAreas()

        tap(Offset(20f, 40f), downAt = 0, upAt = 80)
        touch.down(Offset(22f, 40f), 150)
        // the second tap of a double tap moves nothing: the engine names none
        touch.move(Offset(60f, 40f))
        touch.up(Offset(60f, 40f), 260, consumed = false)

        assertEquals(listOf("down 10,20", "up 10,20", "doubleTap 11,20", "move 30,20"), surface.calls)
        assertEquals(emptyList<String>(), drags)
    }

    @Test
    fun `a long press on a move area still opens the main menu`() {
        onMoveAreas()
        touch.down(Offset(20f, 40f), 0)
        touch.move(Offset(20f + RULES.touchSlop, 40f))

        touch.longPressDue()

        assertEquals(listOf(Offset(20f, 40f) to 1), longPresses)
        assertEquals(emptyList<String>(), drags)
    }

    @Test
    fun `a gesture cut off mid-drag, or a release that arrives consumed, ends the drag and cancels the skin once`() {
        onMoveAreas()
        touch.down(Offset(20f, 40f), 0)
        touch.move(Offset(60f, 40f))
        touch.cancel()

        touch.down(Offset(20f, 40f), 500)
        touch.move(Offset(60f, 40f))
        touch.up(Offset(60f, 40f), 600, consumed = true)

        assertEquals(listOf("down 10,20", "cancel", "down 10,20", "cancel"), surface.calls)
        assertEquals(
            "neither moves the stack further",
            listOf("start 20,40 at 60,40", "end", "start 20,40 at 60,40", "end"),
            drags,
        )
    }

    private companion object {
        val RULES = WalTouch.Rules(longPressMs = 400, touchSlop = 8f, doubleTapMs = 300, doubleTapSlop = 100f)
    }
}
