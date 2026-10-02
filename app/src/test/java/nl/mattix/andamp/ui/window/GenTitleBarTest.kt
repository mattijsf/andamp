// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import nl.mattix.andamp.ui.window.GenTitleBar.Piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generic window's title bar, checked as pure geometry with no skin involved. Every
 * classic skin ships the same seven pieces at the same sizes; only the art differs.
 *
 * Guards against the plate being tiled across the whole bar and the left cap not being drawn.
 */
class GenTitleBarTest {
    private val order =
        listOf(
            Piece.LEFT_CORNER,
            Piece.LEFT_FILL,
            Piece.LEFT_END,
            Piece.PLATE,
            Piece.RIGHT_END,
            Piece.RIGHT_FILL,
            Piece.RIGHT_CORNER,
        )

    @Test
    fun `the pieces run left to right in webamp's order`() {
        assertEquals(order, GenTitleBar.layout(275, 30).map { it.piece })
    }

    @Test
    fun `the bar is gapless and covers exactly the window width`() {
        for (windowW in listOf(150, 200, 275, 400, 1000)) {
            val segments = GenTitleBar.layout(windowW, 30)
            var x = 0
            segments.forEach { segment ->
                assertEquals("${segment.piece} starts where the last piece ends at $windowW px", x, segment.x)
                assertTrue("${segment.piece} has a non-negative width at $windowW px", segment.width >= 0)
                x = segment.right
            }
            assertEquals("the bar ends at the window edge", windowW, x)
        }
    }

    @Test
    fun `the plate is the title plus its padding, and nothing else`() {
        val titleW = 37
        val plate = GenTitleBar.layout(275, titleW).single { it.piece == Piece.PLATE }

        assertEquals(GenTitleBar.PAD_LEFT + titleW + GenTitleBar.PAD_RIGHT, plate.width)
        // the plate does not stretch to fill the bar
        assertTrue("the plate stays narrower than the bar", plate.width < 275 - 4 * GenTitleBar.CAP_W)
    }

    @Test
    fun `the title starts inside the plate, one padding in`() {
        val titleW = 37
        val plate = GenTitleBar.layout(275, titleW).single { it.piece == Piece.PLATE }
        val titleX = GenTitleBar.titleX(275, titleW)

        assertEquals(plate.x + GenTitleBar.PAD_LEFT, titleX)
        assertTrue("the title stays inside its plate", titleX + titleW <= plate.right)
    }

    /**
     * Both end caps are drawn, whole, and touching the plate.
     *
     * A cap is the piece a skin draws the join into, where its corner or its rail meets the
     * plain plate. Without the left one a stub of the corner's bevel shows at the seam.
     */
    @Test
    fun `both caps are drawn, at full width, hugging the plate`() {
        val segments = GenTitleBar.layout(275, 30)
        val leftEnd = segments.single { it.piece == Piece.LEFT_END }
        val plate = segments.single { it.piece == Piece.PLATE }
        val rightEnd = segments.single { it.piece == Piece.RIGHT_END }

        assertEquals(GenTitleBar.CAP_W, leftEnd.width)
        assertEquals(GenTitleBar.CAP_W, rightEnd.width)
        assertEquals("the left cap touches the plate", plate.x, leftEnd.right)
        assertEquals("the right cap touches the plate", plate.right, rightEnd.x)
    }

    @Test
    fun `the right fill takes all the slack`() {
        val segments = GenTitleBar.layout(400, 30)

        assertEquals(0, segments.single { it.piece == Piece.LEFT_FILL }.width)
        assertEquals(
            400 - 4 * GenTitleBar.CAP_W - GenTitleBar.plateWidth(30),
            segments.single { it.piece == Piece.RIGHT_FILL }.width,
        )
    }

    @Test
    fun `a title wider than the window collapses the fills instead of the corners`() {
        val segments = GenTitleBar.layout(120, 400)

        assertEquals(0, segments.single { it.piece == Piece.RIGHT_FILL }.width)
        assertEquals(GenTitleBar.CAP_W, segments.first().width)
        assertEquals(GenTitleBar.CAP_W, segments.last().width)
        assertTrue("no piece has a negative width", segments.all { it.width >= 0 })
    }

    @Test
    fun `an empty title still gets a plate, so the caps never touch`() {
        val plate = GenTitleBar.layout(275, 0).single { it.piece == Piece.PLATE }

        assertEquals(GenTitleBar.PAD_LEFT + GenTitleBar.PAD_RIGHT, plate.width)
    }

    /**
     * `layout()` clamps the plate, but the title text is drawn after it. Guards against a
     * long preset name running off the bar and over the close button.
     */
    @Test
    fun `a title too wide for its window is cut to what fits`() {
        val onePxPerChar = { text: String -> text.length }
        val window = 275
        val max = GenTitleBar.maxTitleWidth(window)

        val long = "x".repeat(max + 40)
        val fitted = GenTitleBar.fit(long, window, onePxPerChar)
        assertEquals(max, fitted.length)
        assertTrue(GenTitleBar.plateWidth(onePxPerChar(fitted)) <= window - GenTitleBar.CAP_W * 4)
    }

    @Test
    fun `a title that already fits is left alone`() {
        val onePxPerChar = { text: String -> text.length }
        assertEquals("Milkdrop", GenTitleBar.fit("Milkdrop", 275, onePxPerChar))
    }

    @Test
    fun `a window too narrow for any title yields an empty one rather than a negative width`() {
        val onePxPerChar = { text: String -> text.length }
        assertEquals(0, GenTitleBar.maxTitleWidth(GenTitleBar.CAP_W * 4))
        assertEquals("", GenTitleBar.fit("Milkdrop", GenTitleBar.CAP_W * 4, onePxPerChar))
    }

    /** Proportional fonts measure per glyph; the cut has to respect that, not character count. */
    @Test
    fun `truncation measures with the caller's font rather than counting characters`() {
        val wideW = { text: String -> text.sumOf { if (it == 'W') 10 else 1 } }
        val fitted = GenTitleBar.fit("WWWWWWWWWWWWWWWWWWWWWWWWWWWWWW", 275, wideW)
        assertEquals(GenTitleBar.maxTitleWidth(275) / 10, fitted.length)
    }
}
