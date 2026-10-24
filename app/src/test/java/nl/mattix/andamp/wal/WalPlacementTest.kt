// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.ui.menu.menuAnchorBounds
import nl.mattix.andamp.ui.window.ClassicStack
import nl.mattix.andamp.ui.window.WindowScreen
import nl.mattix.andamp.ui.window.WindowSnap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Where a modern skin's stack lands on the player's surface.
 *
 * The phone is 1080 by 2400 with a 63 px status bar, so the player's surface is 1080 by 2337 at
 * three pixels to a virtual pixel. The band a handle may stay in runs from 8 to 758 virtual pixels
 * down, which puts the box the skin is fitted into at 24 to 2274.
 */
class WalPlacementTest {
    private val screen = WindowScreen(width = 360, height = 779, safeTop = 8, safeBottom = 758)
    private val box = WalPlacement.box(1080, screen, SCALE)

    private fun layout(
        scale: Float,
        left: Float,
        top: Float,
        stackW: Int,
        stackH: Int,
        fit: WalFit = WalPlacement.fit(box, DENSITY, sideways = false),
    ) = WalLayout(fit, scale, left, top, stackW, stackH, windowOpen = false, animating = false, menuTarget = null)

    @Test
    fun `the box is the band a handle stays in, the surface's width across`() {
        assertEquals(IntRect(0, 24, 1080, 2274), box)
        assertEquals(WalFit(1080, 2250, DENSITY, sideways = false), WalPlacement.fit(box, DENSITY, sideways = false))
    }

    @Test
    fun `Winamp Modern's main window at a whole scale sits where the engine put it, below the box's top`() {
        // its main layout is 354 by 280 at least: three pixels to one fit 1062 of the 1080
        val placed = WalPlacement.of(box, layout(3f, left = 9f, top = 0f, stackW = 354, stackH = 280), SCALE)

        assertEquals(IntRect(9, 24, 9 + 1062, 24 + 840), placed.stack)
        assertEquals(IntRect(3, 8, 357, 288), placed.window)
        assertEquals(IntOffset(100, 50), placed.toSkin(Offset(300f, 150f)))
        assertEquals(IntOffset(100, 50), placed.toSkin(Offset(302.9f, 152.9f)))
    }

    @Test
    fun `below twice the size the scale is fractional, and the stack covers every pixel it draws`() {
        val placed = WalPlacement.of(box, layout(1.7f, left = 31f, top = 0f, stackW = 600, stackH = 250), SCALE)

        assertEquals(IntRect(31, 24, 31 + 1020, 24 + 425), placed.stack)
        assertEquals(IntOffset(100, 100), placed.toSkin(Offset(170.5f, 170.5f)))
        assertEquals(
            "a point a hair short of the next skin pixel is still in this one",
            IntOffset(99, 99),
            placed.toSkin(Offset(169.9f, 169.9f)),
        )
    }

    @Test
    fun `the window rect is rounded outward, to the smallest that holds the stack`() {
        val placed = WalPlacement.of(box, layout(1.7f, left = 31f, top = 1f, stackW = 600, stackH = 250), SCALE)
        val stack = placed.stack
        val window = placed.window

        // 31 / 3 and 1051 / 3 fall inside virtual pixels; rounding would cut the right edge off
        assertEquals(IntRect(10, 8, 351, 150), window)
        assertTrue(window.left * SCALE <= stack.left && stack.left < (window.left + 1) * SCALE)
        assertTrue(window.top * SCALE <= stack.top && stack.top < (window.top + 1) * SCALE)
        assertTrue(window.right * SCALE >= stack.right && stack.right > (window.right - 1) * SCALE)
        assertTrue(window.bottom * SCALE >= stack.bottom && stack.bottom > (window.bottom - 1) * SCALE)
    }

    @Test
    fun `sideways the stack the engine centered sits in the middle of the box both ways`() {
        val sideways = WindowScreen(width = 779, height = 344, safeTop = 0, safeBottom = 344)
        val wide = WalPlacement.box(2337, sideways, SCALE)
        val fit = WalPlacement.fit(wide, DENSITY, sideways = true)
        // the engine fits the stack both ways at a whole scale and centers it, rounded down
        val scale = 3f
        val left = kotlin.math.floor((wide.width - 354 * scale) / 2f)
        val top = kotlin.math.floor((wide.height - 280 * scale) / 2f)

        val placed = WalPlacement.of(wide, layout(scale, left, top, stackW = 354, stackH = 280, fit = fit), SCALE)

        assertEquals(
            wide.center.x.toFloat(),
            placed.stack.center.x
                .toFloat(),
            1f,
        )
        assertEquals(
            wide.center.y.toFloat(),
            placed.stack.center.y
                .toFloat(),
            1f,
        )
        assertTrue(wide.contains(placed.stack.topLeft) && placed.stack.right <= wide.right && placed.stack.bottom <= wide.bottom)
    }

    @Test
    fun `a press made into an anchor comes back from the menu's anchor math at the press point`() {
        val placed = WalPlacement.of(box, layout(1.7f, left = 31f, top = 1f, stackW = 600, stackH = 250), SCALE)

        assertPopsAtPress(placed, published = placed.window)
    }

    @Test
    fun `a press comes back at the press point while the published rect still waits for a sliding stack`() {
        val before = WalPlacement.of(box, layout(1.7f, left = 31f, top = 40f, stackW = 600, stackH = 250), SCALE)
        // the stack has grown and moved up since that rect was published
        val placed = WalPlacement.of(box, layout(1.7f, left = 31f, top = 1f, stackW = 600, stackH = 275), SCALE)

        assertPopsAtPress(placed, published = before.window)
        assertPopsAtPress(placed, published = null)
    }

    @Test
    fun `the classic stack holds the main window down to the skin's stack, leaving room for the plug-in window`() {
        val placed = WalPlacement.of(box, layout(3f, left = 9f, top = 0f, stackW = 354, stackH = 280), SCALE)

        assertEquals("no rect, no height", null, WalPlacement.dockedMainH(null, screen, visH = 120))
        assertEquals("to the stack's bottom", 288 - 8, WalPlacement.dockedMainH(placed.window, screen, visH = 120))
        assertEquals(
            "a stack that fills the band leaves the plug-in window room above the safe bottom",
            758 - 120 - 8,
            WalPlacement.dockedMainH(IntRect(0, 8, 360, 758), screen, visH = 120),
        )
    }

    @Test
    fun `a stack moved too low for the plug-in window under it has it docked over it`() {
        assertEquals(
            "moved down, still room under it",
            410 - 8,
            WalPlacement.dockedMainH(IntRect(0, 300, 360, 410), screen, visH = 120),
        )
        assertEquals(
            "against the bottom edge: over it",
            648 - 120 - 8,
            WalPlacement.dockedMainH(IntRect(0, 648, 360, 758), screen, visH = 120),
        )
        assertEquals(
            "no room over it either: as low as leaves the plug-in window its height",
            758 - 120 - 8,
            WalPlacement.dockedMainH(IntRect(0, 100, 360, 700), screen, visH = 120),
        )
    }

    @Test
    fun `with a modern skin worn the plug-in window docks under its stack, and no room is kept for the equalizer`() {
        val stack = IntRect(3, 8, 357, 288)
        val ask =
            ClassicStack.Ask(
                screen,
                sideways = false,
                mainH = 116,
                eq = ClassicStack.Member(116, top = null, placed = false),
                vis = ClassicStack.Member(120, top = null, placed = false),
            )

        val docked = WalPlacement.dockedStack(ask, stack, dock = null, visH = 120)

        assertEquals(ClassicStack.layout(ask.copy(mainH = 288 - 8, eq = null)), docked)
        assertEquals("the plug-in window under the stack", 288, docked.vis.y)
        assertEquals(
            "no rect yet: the classic stack's heights",
            ClassicStack.layout(ask.copy(eq = null)),
            WalPlacement.dockedStack(ask, null, null, 120),
        )
    }

    @Test
    fun `a stack in two columns has the plug-in window docked under its left column`() {
        val ask = ClassicStack.Ask(screen, sideways = true, mainH = 116, eq = null, vis = ClassicStack.Member(120, null, false))
        // a 400 by 300 stack drawn as 200 by 150 virtual pixels, whose left column ends 200 skin px down
        val stack = IntRect(10, 8, 210, 158)
        val dock = WalDock.of(layout(2f, left = 0f, top = 0f, stackW = 400, stackH = 300).copy(dock = IntRect(0, 0, 177, 200)))

        assertEquals(IntRect(10, 8, 99, 108), dock!!.within(stack))
        assertEquals(108, WalPlacement.dockedStack(ask, stack, dock, visH = 120).vis.y)
        assertEquals("one column has nothing to dock against but the stack", null, WalDock.of(layout(2f, 0f, 0f, 400, 300)))
    }

    @Test
    fun `a moved stack is drawn that far from where the engine put it, and its rect goes with it`() {
        val layout = layout(2f, left = 186f, top = 0f, stackW = 354, stackH = 164)

        val placed = WalPlacement.of(box, layout, SCALE, shift = IntOffset(-186, 900))

        assertEquals(IntRect(0, 924, 708, 1252), placed.stack)
        assertEquals(IntRect(0, 308, 236, 418), placed.window)
        assertEquals("touches count from the moved stack", IntOffset(100, 50), placed.toSkin(Offset(200f, 100f)))
    }

    @Test
    fun `a moved stack stays wholly in the box, and keeps the engine's place on an axis it fills`() {
        val home = IntRect(186, 24, 894, 352)

        assertEquals(IntOffset(0, 24), WalPlacement.clamp(IntOffset(-50, -10), home, box))
        assertEquals(IntOffset(372, 2274 - 328), WalPlacement.clamp(IntOffset(500, 3000), home, box))
        assertEquals(IntOffset(100, 500), WalPlacement.clamp(IntOffset(100, 500), home, box))

        // wider than the box: it stays where the engine put it across, and moves up and down
        val wide = IntRect(-43, 24, 1123, 456)
        assertEquals(IntOffset(-43, 800), WalPlacement.clamp(IntOffset(0, 800), wide, box))
    }

    @Test
    fun `a stack snaps to the box's edges and back to its place when less than the distance away, as a classic window snaps`() {
        val home = IntRect(186, 24, 894, 352)

        assertEquals("to the left and bottom edges", IntOffset(0, 1946), WalPlacement.snap(IntOffset(17, 1930), home, box, SNAP))
        assertEquals("to the right edge", IntOffset(372, 600), WalPlacement.snap(IntOffset(360, 600), home, box, SNAP))
        assertEquals(
            "back to its place across, wherever it is up and down",
            IntOffset(186, 600),
            WalPlacement.snap(IntOffset(195, 600), home, box, SNAP),
        )
        assertEquals("and to its place at the top", IntOffset(186, 24), WalPlacement.snap(IntOffset(170, 40), home, box, SNAP))
        assertEquals(
            "exactly the distance away does not snap",
            IntOffset(30, 54),
            WalPlacement.snap(IntOffset(30, 54), home, box, SNAP),
        )
    }

    @Test
    fun `the nearest snap wins, and the stack's place wins a tie with an edge`() {
        // a stack whose place is 20 px from the left edge
        val home = IntRect(20, 24, 1060, 352)

        assertEquals("nearer to the edge", IntOffset(0, 300), WalPlacement.snap(IntOffset(8, 300), home, box, SNAP))
        assertEquals("nearer to its place", IntOffset(20, 300), WalPlacement.snap(IntOffset(12, 300), home, box, SNAP))
        assertEquals("as near to both", IntOffset(20, 300), WalPlacement.snap(IntOffset(10, 300), home, box, SNAP))
    }

    @Test
    fun `a kept move comes back as it was, held in a box drawn a little smaller`() {
        val home = IntRect(186, 24, 894, 352)
        val atBottom = IntOffset(-186, 1946 - 24)

        assertEquals(IntOffset(-186, 1922), WalPlacement.held(atBottom, home, box))
        // the same stack where another surface's box ends 9 px higher
        val smaller = IntRect(0, 24, 1080, 2265)
        assertEquals(IntOffset(-186, 1913), WalPlacement.held(atBottom, home, smaller))
    }

    @Test
    fun `a stack that grew past the box's edge is held inside it, and goes back where it was moved once it shrinks`() {
        val home = IntRect(186, 24, 894, 352)
        val atMiddle = IntOffset(-186, 600)
        val atBottom = IntOffset(-186, 1922)
        // a drawer slid out under the main window
        val grown = IntRect(186, 24, 894, 680)

        assertEquals("it still fits where it was moved", atMiddle, WalPlacement.held(atMiddle, grown, box))
        assertEquals("its bottom held at the box's", IntOffset(-186, 2274 - 656 - 24), WalPlacement.held(atBottom, grown, box))
        assertEquals("the drawer closed", atBottom, WalPlacement.held(atBottom, home, box))
    }

    @Test
    fun `on an axis the stack fills, a kept move gives way to where the engine puts it`() {
        // moved down on the floating player, where the stack is compact; the activity fills the box with it
        val compactMove = IntOffset(0, 900)
        val filling = IntRect(0, 24, 1081, 2274)

        assertEquals(IntOffset(0, 0), WalPlacement.held(compactMove, filling, box))
    }

    /** Presses across [placed]'s stack each pop a menu at the press, with [published] in `windowRects[MAIN]`. */
    private fun assertPopsAtPress(
        placed: WalPlacement,
        published: IntRect?,
    ) {
        val rects = mapOf(WindowStore.MAIN to (published ?: placed.window))
        for (at in listOf(Offset(0f, 0f), Offset(1f, 2f), Offset(500.4f, 211.7f), Offset(1019f, 424f))) {
            val anchor = placed.anchorAt(placed.pointAt(at), published)
            val bounds = menuAnchorBounds(anchor, SCALE, 1080, 2337, rects, shrink = 1f)
            val pressX = placed.stack.left + at.x
            val pressY = placed.stack.top + at.y

            assertEquals(WindowStore.MAIN, anchor.window)
            // the nearest virtual pixel: no further than half of one
            assertTrue("x for $at: ${bounds.left} against $pressX", abs(bounds.left - pressX) <= SCALE / 2f)
            assertTrue("y for $at: ${bounds.top} against $pressY", abs(bounds.top - pressY) <= SCALE / 2f)
        }
    }

    private companion object {
        const val SCALE = 3
        const val DENSITY = 2.625f

        /** The classic windows' snap distance at [SCALE]. */
        const val SNAP = WindowSnap.SNAP_DISTANCE * SCALE
    }
}
