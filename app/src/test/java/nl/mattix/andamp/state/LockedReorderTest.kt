// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Test

/** A window dragged through the locked layout goes before the first window whose middle is below it. */
class LockedReorderTest {
    private val main = WindowStore.MAIN
    private val eq = WindowStore.EQ
    private val vis = WindowStore.MILKDROP
    private val pl = WindowStore.PLAYLIST

    /** One column: the player 0..116, the equalizer 116..232, the playlist 232..584. */
    private val upright = listOf(IntRect(0, 0, 275, 584))
    private val uprightRects =
        mapOf(main to IntRect(0, 0, 275, 116), eq to IntRect(0, 116, 275, 232), pl to IntRect(0, 232, 275, 584))

    private fun moved(
        id: String,
        center: IntOffset,
        columns: List<List<String>>,
        bands: List<IntRect> = upright,
        rects: Map<String, IntRect> = uprightRects - id,
    ) = LockedReorder.moved(id, center, columns, bands, rects)

    @Test
    fun `a window stays put while its middle is above the next window's middle`() {
        val columns = listOf(listOf(main, eq, vis, pl))

        assertEquals(columns, moved(eq, IntOffset(137, 300), columns))
    }

    @Test
    fun `past the playlist's middle the equalizer goes under it`() {
        val columns = listOf(listOf(main, eq, vis, pl))

        assertEquals(listOf(listOf(main, vis, pl, eq)), moved(eq, IntOffset(137, 420), columns))
    }

    @Test
    fun `a closed window keeps its place in the list, before or after, without being counted`() {
        // the plug-in window is closed, so it has no rectangle; it stays right after the equalizer
        val columns = listOf(listOf(main, eq, vis, pl))

        assertEquals(listOf(listOf(pl, main, eq, vis)), moved(pl, IntOffset(137, 20), columns))
    }

    @Test
    fun `dragged over the player's top half the window goes first`() {
        val columns = listOf(listOf(main, eq, pl))

        assertEquals(listOf(listOf(eq, main, pl)), moved(eq, IntOffset(137, 40), columns))
    }

    @Test
    fun `sideways, the band under the finger decides the column`() {
        val bands = listOf(IntRect(0, 0, 275, 232), IntRect(275, 0, 550, 232))
        val rects = mapOf(main to IntRect(0, 0, 275, 116), eq to IntRect(0, 116, 275, 232))
        val columns = listOf(listOf(main, eq, vis), listOf(pl))

        assertEquals(
            "the playlist dragged under the equalizer, before the closed plug-in window",
            listOf(listOf(main, eq, pl, vis), emptyList()),
            moved(pl, IntOffset(137, 300), columns, bands, rects),
        )
        assertEquals(
            "the equalizer dragged into the right column, above the playlist",
            listOf(listOf(main, vis), listOf(eq, pl)),
            moved(eq, IntOffset(400, 60), columns, bands, rects + (pl to IntRect(275, 0, 550, 232))),
        )
    }

    @Test
    fun `a window that is not in the columns leaves them alone`() {
        val columns = listOf(listOf(main, eq, vis, pl))

        assertEquals(columns, moved(WindowStore.LIBRARY, IntOffset(137, 300), columns))
    }
}
