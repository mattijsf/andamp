// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Winamp Modern's stack moved by its title bar, as on a Pixel 9 Pro: 708 by 328 pixels, put by the
 * engine across the top of a box from y = 24 to 2274, 186 pixels from either side. 8 dp make 18
 * pixels. Each finger position is turned into a point on the stack as it was last drawn, as the
 * screen hands them over, and the stack is drawn again after each.
 */
class WalDragTest {
    private val box = IntRect(0, 24, 1080, 2274)
    private val home = IntRect(186, 24, 894, 352)
    private val kept = mutableListOf<WalMove?>()

    /** Where the stack rests between drags: the last place kept. */
    private var rest = IntOffset.Zero

    private val drag =
        WalDrag { move ->
            kept += move
            rest = move?.let { IntOffset(it.dx, it.dy) } ?: IntOffset.Zero
        }

    private var drawn = home.topLeft

    init {
        frame()
    }

    /** The next frame: the stack drawn where the drag, or the place kept, has it. */
    private fun frame() {
        drawn = home.topLeft + (drag.shift ?: rest)
        drag.placedAt(box, home, drawn, SNAP)
    }

    /** [at], a point on the surface, as a point on the stack drawn now. */
    private fun onStack(at: Offset) = Offset(at.x - drawn.x, at.y - drawn.y)

    /**
     * A press at [from] on the surface, past the touch slop to the first of [path], then along the
     * rest; unless [lift], the finger stays down.
     */
    private fun dragged(
        from: Offset,
        path: List<Offset>,
        lift: Boolean = true,
    ) {
        drag.start(onStack(from), onStack(path.first()))
        frame()
        for (at in path.drop(1)) {
            drag.move(onStack(at))
            frame()
        }
        if (lift) {
            drag.end()
            frame()
        }
    }

    @Test
    fun `the stack follows the finger from where it was pressed, and its place is kept when the finger lifts`() {
        dragged(Offset(500f, 40f), listOf(Offset(500f, 100f), Offset(450f, 700f)), lift = false)
        assertEquals(IntOffset(-50, 660), drag.shift)

        drag.end()

        assertEquals(listOf(WalMove(-50, 660, 708, 328)), kept)
        assertNull("no drag between drags", drag.shift)
    }

    @Test
    fun `it snaps to an edge, and stays wholly in the box`() {
        dragged(Offset(500f, 40f), listOf(Offset(500f, 60f), Offset(325f, 300f)), lift = false)
        assertEquals("11 px from the left edge: against it", IntOffset(-186, 260), drag.shift)

        drag.move(onStack(Offset(100f, 300f)))
        frame()
        drag.move(onStack(Offset(100f, 3000f)))

        assertEquals("pushed further, it stops at the edges", IntOffset(-186, 1922), drag.shift)
    }

    @Test
    fun `it snaps back to the place the engine gives it, which keeps no place`() {
        dragged(Offset(500f, 40f), listOf(Offset(500f, 100f), Offset(450f, 700f)))

        dragged(Offset(450f, 700f), listOf(Offset(450f, 680f), Offset(490f, 50f)))

        assertEquals(listOf(WalMove(-50, 660, 708, 328), null), kept)
    }

    @Test
    fun `a stack against an edge is pulled off it, not held, until the drag goes further than the snap distance`() {
        dragged(Offset(500f, 40f), listOf(Offset(500f, 60f), Offset(325f, 300f)))
        assertEquals(IntOffset(-186, 260), rest)

        dragged(Offset(200f, 300f), listOf(Offset(215f, 300f)), lift = false)
        assertEquals("15 px off the edge", IntOffset(-171, 260), drag.shift)
        drag.move(onStack(Offset(210f, 300f)))
        frame()
        assertEquals("10 px off: the snap waits", IntOffset(-176, 260), drag.shift)

        drag.move(onStack(Offset(240f, 300f)))
        frame()
        drag.move(onStack(Offset(210f, 300f)))

        assertEquals("once it went further, the edge pulls again", IntOffset(-186, 260), drag.shift)
    }

    @Test
    fun `a drag while the stack has no place in the box moves nothing and keeps nothing`() {
        drag.placedAt(box, home = null, drawn = null, snapPx = SNAP)

        drag.start(Offset(10f, 10f), Offset(10f, 80f))
        drag.move(Offset(10f, 200f))
        drag.end()

        assertNull(drag.shift)
        assertEquals(emptyList<WalMove?>(), kept)
    }

    private companion object {
        const val SNAP = 18
    }
}
