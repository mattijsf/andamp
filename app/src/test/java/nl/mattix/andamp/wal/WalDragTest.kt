// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import nl.mattix.andamp.ui.window.WindowSnap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Winamp Modern's stack moved by its title bar, as on a Pixel 9 Pro: 708 by 328 pixels, put by the
 * engine at the top of a box from y = 27 to 1935, 126 pixels from either side. The classic windows
 * are drawn at three pixels to a virtual pixel there, so the stack snaps within 30 pixels, as they
 * do. Each finger position is turned into a point on the stack as it was last drawn, as the screen
 * hands them over, and the stack is drawn again after each.
 */
class WalDragTest {
    private val upright = WalFit(960, 1908, 2.25f, sideways = false)
    private val box = IntRect(0, 27, 960, 1935)
    private val home = IntRect(126, 27, 834, 355)

    /** Each place kept, with whether it was sideways. */
    private val kept = mutableListOf<Pair<Boolean, IntOffset?>>()

    /** Where the stack rests between drags: the last place kept. */
    private var rest = IntOffset.Zero

    private val drag =
        WalDrag { sideways, move ->
            kept += sideways to move
            rest = move ?: IntOffset.Zero
        }

    private var drawn = home.topLeft

    init {
        frame()
    }

    /** The next frame: the stack drawn where the drag, or the place kept, has it. */
    private fun frame() {
        drawn = home.topLeft + (drag.shift ?: rest)
        drag.placedAt(upright, box, home, drawn, SNAP, KEEP)
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

        assertEquals(listOf(false to IntOffset(-50, 660)), kept)
        assertNull("no drag between drags", drag.shift)
    }

    @Test
    fun `it snaps to an edge, and goes past the edges with a finger's width left in the box`() {
        dragged(Offset(500f, 40f), listOf(Offset(500f, 60f), Offset(394f, 300f)), lift = false)
        assertEquals("20 px from the left edge: against it", IntOffset(-126, 260), drag.shift)

        drag.move(onStack(Offset(100f, 300f)))
        frame()
        drag.move(onStack(Offset(100f, 3000f)))

        assertEquals(
            "pushed further, it goes past the edges, stopping with KEEP px in the box",
            IntOffset(-400, 1764),
            drag.shift,
        )
    }

    @Test
    fun `it snaps back to the place the engine gives it, which keeps no place`() {
        dragged(Offset(500f, 40f), listOf(Offset(500f, 100f), Offset(450f, 700f)))

        dragged(Offset(450f, 700f), listOf(Offset(450f, 680f), Offset(490f, 50f)))

        assertEquals(listOf(false to IntOffset(-50, 660), false to null), kept)
    }

    @Test
    fun `a stack against an edge is pulled off it, not held, until the drag goes further than the snap distance`() {
        dragged(Offset(500f, 40f), listOf(Offset(500f, 60f), Offset(394f, 300f)))
        assertEquals(IntOffset(-126, 260), rest)

        dragged(Offset(200f, 300f), listOf(Offset(215f, 300f)), lift = false)
        assertEquals("15 px off the edge", IntOffset(-111, 260), drag.shift)
        drag.move(onStack(Offset(210f, 300f)))
        frame()
        assertEquals("10 px off: the snap waits", IntOffset(-116, 260), drag.shift)

        drag.move(onStack(Offset(240f, 300f)))
        frame()
        drag.move(onStack(Offset(210f, 300f)))

        assertEquals("once it went further, the edge pulls again", IntOffset(-126, 260), drag.shift)
    }

    @Test
    fun `a drag while the stack has no place in the box moves nothing and keeps nothing`() {
        drag.placedAt(upright, box, home = null, drawn = null, snapPx = SNAP, keepPx = KEEP)

        drag.start(Offset(10f, 10f), Offset(10f, 80f))
        drag.move(Offset(10f, 200f))
        drag.end()

        assertNull(drag.shift)
        assertEquals(emptyList<Pair<Boolean, IntOffset?>>(), kept)
    }

    @Test
    fun `a drag the phone is turned during ends where the stack was and keeps nothing, and the next one is kept sideways`() {
        dragged(Offset(500f, 40f), listOf(Offset(500f, 100f), Offset(450f, 700f)), lift = false)

        // turned: the layout for the new box comes a little later
        val sideways = WalFit(2142, 933, 2.25f, sideways = true)
        val wideBox = IntRect(0, 27, 2142, 960)
        val wideHome = IntRect(717, 27, 1425, 355)
        drag.placedAt(sideways, wideBox, home = null, drawn = null, snapPx = SNAP, keepPx = KEEP)
        assertNull("the drag ends at the turn", drag.shift)
        drag.placedAt(sideways, wideBox, wideHome, wideHome.topLeft, SNAP, KEEP)
        drag.move(Offset(100f, 400f))
        drag.end()
        assertNull(drag.shift)
        assertEquals("nothing kept for either way round", emptyList<Pair<Boolean, IntOffset?>>(), kept)

        drag.start(Offset(100f, 10f), Offset(100f, 60f))
        drag.move(Offset(100f, 210f))
        drag.end()

        assertEquals(listOf(true to IntOffset(0, 200)), kept)
    }

    private companion object {
        /** The classic windows' snap distance at three pixels to a virtual pixel. */
        const val SNAP = WindowSnap.SNAP_DISTANCE * 3
        const val KEEP = 144
    }
}
