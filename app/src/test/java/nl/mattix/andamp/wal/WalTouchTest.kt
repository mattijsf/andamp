// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * One finger on a modern skin, as the skin hears it. Two surface pixels make a skin pixel here, so
 * a press at (20, 40) reaches the skin at (10, 20). A press the engine names as on a move area
 * moves the stack once it travels, which [drags] records.
 */
class WalTouchTest {
    private val surface = FakeTarget()
    private val longPresses = mutableListOf<Pair<TouchPoint, Int>>()
    private val pressed = mutableListOf<TouchPoint>()
    private val drags = mutableListOf<String>()
    private val touch =
        WalTouch(
            surface,
            RULES,
            toSkin = { (it.x / 2).toInt() to (it.y / 2).toInt() },
            onPress = { pressed += it },
            onLongPress = { at, seq -> longPresses += at to seq },
            drag =
                object : WalTouch.Drag {
                    override fun start(
                        from: TouchPoint,
                        at: TouchPoint,
                    ) {
                        drags += "start ${from.x.toInt()},${from.y.toInt()} at ${at.x.toInt()},${at.y.toInt()}"
                    }

                    override fun move(at: TouchPoint) {
                        drags += "move ${at.x.toInt()},${at.y.toInt()}"
                    }

                    override fun end() {
                        drags += "end"
                    }
                },
        )

    /** Every press on a move area or, with [moveAreas] false, none. */
    private fun onMoveAreas(moveAreas: Boolean = true) {
        surface.moveAreaUnderEveryPress = moveAreas
    }

    private fun tap(
        at: TouchPoint,
        downAt: Long,
        upAt: Long = downAt + 80,
    ) {
        touch.down(at, downAt)
        touch.up(at, upAt, consumed = false)
    }

    @Test
    fun `a tap reaches the skin as a press and a release, in skin pixels`() {
        touch.down(TouchPoint(20f, 40f), 0)
        touch.up(TouchPoint(22f, 40f), 90, consumed = false)

        assertEquals(listOf("down 10,20", "up 11,20"), surface.calls)
        assertEquals("the press point is told before the skin hears it", listOf(TouchPoint(20f, 40f)), pressed)
    }

    @Test
    fun `a move reaches the skin as a move`() {
        touch.down(TouchPoint(20f, 40f), 0)
        touch.move(TouchPoint(60f, 40f))
        touch.up(TouchPoint(60f, 40f), 200, consumed = false)

        assertEquals(listOf("down 10,20", "move 30,20", "up 30,20"), surface.calls)
    }

    @Test
    fun `a second tap soon after and near the first is a double tap, and its release is not heard`() {
        tap(TouchPoint(20f, 40f), downAt = 0, upAt = 80)

        touch.down(TouchPoint(26f, 44f), 80 + RULES.doubleTapMs)
        touch.up(TouchPoint(26f, 44f), 80 + RULES.doubleTapMs + 60, consumed = false)

        assertEquals(listOf("down 10,20", "up 10,20", "doubleTap 13,22"), surface.calls)
    }

    @Test
    fun `a second tap after the double-tap timeout is a fresh press`() {
        tap(TouchPoint(20f, 40f), downAt = 0, upAt = 80)

        tap(TouchPoint(20f, 40f), downAt = 80 + RULES.doubleTapMs + 1)

        assertEquals(listOf("down 10,20", "up 10,20", "down 10,20", "up 10,20"), surface.calls)
    }

    @Test
    fun `a second tap beyond the double-tap slop is a fresh press`() {
        tap(TouchPoint(20f, 40f), downAt = 0, upAt = 80)

        tap(TouchPoint(20f + RULES.doubleTapSlop + 1, 40f), downAt = 120)

        assertEquals("down", surface.calls[2].substringBefore(' '))
    }

    @Test
    fun `a tap after a drag is no double tap`() {
        touch.down(TouchPoint(20f, 40f), 0)
        touch.move(TouchPoint(80f, 40f))
        touch.up(TouchPoint(80f, 40f), 80, consumed = false)

        tap(TouchPoint(20f, 40f), downAt = 120)

        assertEquals("down 10,20", surface.calls[3])
    }

    @Test
    fun `a third tap after a double tap is a press again`() {
        tap(TouchPoint(20f, 40f), downAt = 0, upAt = 80)
        tap(TouchPoint(20f, 40f), downAt = 150, upAt = 220)

        tap(TouchPoint(20f, 40f), downAt = 300)

        assertEquals(listOf("down 10,20", "up 10,20", "doubleTap 10,20", "down 10,20", "up 10,20"), surface.calls)
    }

    @Test
    fun `a long press fires while the finger is down, cancels the press, and swallows the rest`() {
        touch.down(TouchPoint(20f, 40f), 1_000)
        assertEquals(RULES.longPressMs, touch.longPressIn(1_000))
        assertEquals(RULES.longPressMs - 100, touch.longPressIn(1_100))

        touch.longPressDue()

        assertEquals(listOf("down 10,20", "cancel"), surface.calls)
        assertEquals("the menu opens at the press, in its gesture", listOf(TouchPoint(20f, 40f) to 1), longPresses)
        assertNull("it fires once", touch.longPressIn(1_500))

        touch.move(TouchPoint(24f, 40f))
        touch.up(TouchPoint(24f, 40f), 1_900, consumed = false)
        touch.cancel()

        assertEquals("nothing more of that finger reaches the skin", listOf("down 10,20", "cancel"), surface.calls)
    }

    @Test
    fun `a finger that wanders within the touch slop still long-presses`() {
        touch.down(TouchPoint(20f, 40f), 0)
        touch.move(TouchPoint(20f + RULES.touchSlop, 40f))

        touch.longPressDue()

        assertEquals(1, longPresses.size)
    }

    @Test
    fun `a move past the touch slop disarms the long press`() {
        touch.down(TouchPoint(20f, 40f), 0)
        touch.move(TouchPoint(20f + RULES.touchSlop + 1, 40f))

        assertNull(touch.longPressIn(100))
        touch.longPressDue()

        assertEquals(emptyList<Pair<TouchPoint, Int>>(), longPresses)
        assertEquals("the drag goes on reaching the skin", listOf("down 10,20", "move 14,20"), surface.calls)
    }

    @Test
    fun `a skin menu opened during the press rules the long press out`() {
        touch.down(TouchPoint(20f, 40f), 0)
        touch.menuShown()

        assertNull(touch.longPressIn(100))
        touch.longPressDue()

        assertEquals(emptyList<Pair<TouchPoint, Int>>(), longPresses)
        assertEquals(listOf("down 10,20"), surface.calls)
    }

    @Test
    fun `a double tap held down long-presses in the double tap's gesture`() {
        tap(TouchPoint(20f, 40f), downAt = 0, upAt = 80)
        touch.down(TouchPoint(20f, 40f), 150)

        touch.longPressDue()

        assertEquals("the second gesture's seq", listOf(TouchPoint(20f, 40f) to 2), longPresses)
    }

    @Test
    fun `a release that arrives consumed cancels the press`() {
        touch.down(TouchPoint(20f, 40f), 0)

        touch.up(TouchPoint(20f, 40f), 80, consumed = true)

        assertEquals(listOf("down 10,20", "cancel"), surface.calls)
    }

    @Test
    fun `a press whose change vanished, or whose loop ended without a release, is cancelled once`() {
        touch.down(TouchPoint(20f, 40f), 0)

        touch.cancel()
        touch.cancel()

        assertEquals(listOf("down 10,20", "cancel"), surface.calls)
    }

    @Test
    fun `a cancelled press starts no double tap`() {
        touch.down(TouchPoint(20f, 40f), 0)
        touch.cancel()

        tap(TouchPoint(20f, 40f), downAt = 100)

        assertEquals("down 10,20", surface.calls[2])
    }

    @Test
    fun `the end of the loop after a release cancels nothing`() {
        tap(TouchPoint(20f, 40f), downAt = 0)

        touch.cancel()

        assertEquals(listOf("down 10,20", "up 10,20"), surface.calls)
    }

    @Test
    fun `a press on a move area that travels past the touch slop moves the stack, and the skin's press is cancelled`() {
        onMoveAreas()
        touch.down(TouchPoint(20f, 40f), 0)
        touch.move(TouchPoint(20f + RULES.touchSlop, 40f))

        touch.move(TouchPoint(40f, 44f))
        touch.move(TouchPoint(90f, 140f))
        // the release comes a little further on than the last move
        touch.up(TouchPoint(96f, 150f), 300, consumed = false)

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
        touch.down(TouchPoint(20f, 40f), 0)
        touch.move(TouchPoint(40f, 40f))
        assertEquals("not named yet: the skin hears the move", listOf("down 10,20", "move 20,20"), surface.calls)

        // the engine has handled the press, and names it
        surface.moveTarget = 1
        touch.move(TouchPoint(50f, 40f))
        touch.up(TouchPoint(50f, 40f), 200, consumed = false)

        assertEquals(listOf("down 10,20", "move 20,20", "cancel"), surface.calls)
        assertEquals("measured from where the finger went down", listOf("start 20,40 at 50,40", "move 50,40", "end"), drags)
    }

    @Test
    fun `a drag from anywhere else still reaches the skin`() {
        onMoveAreas(moveAreas = false)
        surface.moveTarget = 99

        touch.down(TouchPoint(20f, 40f), 0)
        touch.move(TouchPoint(80f, 40f))
        touch.up(TouchPoint(80f, 40f), 200, consumed = false)

        assertEquals(listOf("down 10,20", "move 40,20", "up 40,20"), surface.calls)
        assertEquals(emptyList<String>(), drags)
    }

    @Test
    fun `a tap and a double tap on a move area reach the skin as before`() {
        onMoveAreas()

        tap(TouchPoint(20f, 40f), downAt = 0, upAt = 80)
        touch.down(TouchPoint(22f, 40f), 150)
        // the second tap of a double tap moves nothing: the engine names none
        touch.move(TouchPoint(60f, 40f))
        touch.up(TouchPoint(60f, 40f), 260, consumed = false)

        assertEquals(listOf("down 10,20", "up 10,20", "doubleTap 11,20", "move 30,20"), surface.calls)
        assertEquals(emptyList<String>(), drags)
    }

    @Test
    fun `a long press on a move area still opens the main menu`() {
        onMoveAreas()
        touch.down(TouchPoint(20f, 40f), 0)
        touch.move(TouchPoint(20f + RULES.touchSlop, 40f))

        touch.longPressDue()

        assertEquals(listOf(TouchPoint(20f, 40f) to 1), longPresses)
        assertEquals(emptyList<String>(), drags)
    }

    @Test
    fun `a gesture cut off mid-drag, or a release that arrives consumed, ends the drag and cancels the skin once`() {
        onMoveAreas()
        touch.down(TouchPoint(20f, 40f), 0)
        touch.move(TouchPoint(60f, 40f))
        touch.cancel()

        touch.down(TouchPoint(20f, 40f), 500)
        touch.move(TouchPoint(60f, 40f))
        touch.up(TouchPoint(60f, 40f), 600, consumed = true)

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

/** The skin's pointer calls as short lines; a press on a move area when [moveAreaUnderEveryPress] says so. */
private class FakeTarget : WalTouch.Target {
    val calls = mutableListOf<String>()
    var moveAreaUnderEveryPress = false
    override var moveTarget: Int? = null
    private var seq = 0

    override fun pointerDown(
        x: Int,
        y: Int,
    ): Int = press("down $x,$y")

    override fun pointerMove(
        x: Int,
        y: Int,
    ) {
        calls += "move $x,$y"
    }

    override fun pointerUp(
        x: Int,
        y: Int,
    ) {
        calls += "up $x,$y"
    }

    override fun pointerCancel() {
        calls += "cancel"
    }

    override fun doubleTap(
        x: Int,
        y: Int,
    ): Int = press("doubleTap $x,$y")

    private fun press(call: String): Int {
        calls += call
        seq++
        // the engine names no double tap as a move
        if (moveAreaUnderEveryPress) moveTarget = seq.takeIf { call.startsWith("down") }
        return seq
    }
}
