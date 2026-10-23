// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * One finger on a modern skin, as the skin hears it. Two surface pixels make a skin pixel here, so
 * a press at (20, 40) reaches the skin at (10, 20).
 */
class WalTouchTest {
    private val surface = FakeWalSurface()
    private val longPresses = mutableListOf<Pair<Offset, Int>>()
    private val pressed = mutableListOf<Offset>()
    private val touch =
        WalTouch(
            surface,
            RULES,
            toSkin = { IntOffset((it.x / 2).toInt(), (it.y / 2).toInt()) },
            onPress = { pressed += it },
            onLongPress = { at, seq -> longPresses += at to seq },
        )

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

    private companion object {
        val RULES = WalTouch.Rules(longPressMs = 400, touchSlop = 8f, doubleTapMs = 300, doubleTapSlop = 100f)
    }
}
