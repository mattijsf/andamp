// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.unit.IntOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The grip's arithmetic: steps, caps, and the corner that must not move. */
class WindowSizingTest {
    private val playlistStep = 29

    @Test
    fun `travel rounds to the nearest step`() {
        assertEquals(4, WindowSizing.stepsFor(4, 14f, playlistStep, min = 2, max = 20))
        assertEquals(5, WindowSizing.stepsFor(4, 15f, playlistStep, min = 2, max = 20))
        assertEquals(6, WindowSizing.stepsFor(4, 58f, playlistStep, min = 2, max = 20))
        assertEquals(3, WindowSizing.stepsFor(4, -20f, playlistStep, min = 2, max = 20))
    }

    @Test
    fun `steps clamp at the minimum and at what fits`() {
        assertEquals(2, WindowSizing.stepsFor(4, -500f, playlistStep, min = 2, max = 20))
        assertEquals(20, WindowSizing.stepsFor(4, 5000f, playlistStep, min = 2, max = 20))
        // a screen too small for the minimum still gets the minimum
        assertEquals(2, WindowSizing.stepsFor(2, 0f, playlistStep, min = 2, max = 1))
    }

    @Test
    fun `what fits comes off the available height, never below the minimum`() {
        assertEquals(6, WindowSizing.stepsThatFit(availableH = 232, chromeH = 58, stepPx = playlistStep, min = 2))
        assertEquals(2, WindowSizing.stepsThatFit(availableH = 60, chromeH = 58, stepPx = playlistStep, min = 2))
    }

    @Test
    fun `a window dragged to its full size is remembered as auto`() {
        assertNull(WindowSizing.rememberedSize(count = 8, fits = 8))
        assertEquals(5, WindowSizing.rememberedSize(count = 5, fits = 8))
    }

    @Test
    fun `the chosen size is capped by what fits without being forgotten`() {
        assertEquals(5, WindowSizing.effectiveSteps(remembered = 5, fits = 8, min = 2))
        // the keyboard is up: shrink for now, the stored 12 is untouched
        assertEquals(4, WindowSizing.effectiveSteps(remembered = 12, fits = 4, min = 2))
        assertEquals(8, WindowSizing.effectiveSteps(remembered = null, fits = 8, min = 2))
    }

    @Test
    fun `a resize keeps the top-left corner still`() {
        val screenH = 800
        val offset = IntOffset(0, 100)

        // top edge before: (800-116)/2 + 100 = 442
        val grown = WindowSizing.anchorTop(offset, oldH = 116, newH = 174, screenH = screenH)

        assertEquals((screenH - 116) / 2 + offset.y, (screenH - 174) / 2 + grown.y)
        assertEquals(offset.x, grown.x)
    }

    @Test
    fun `the corner stays still across odd heights too`() {
        val screenH = 791
        val offset = IntOffset(3, -47)

        val shrunk = WindowSizing.anchorTop(offset, oldH = 187, newH = 116, screenH = screenH)

        assertEquals((screenH - 187) / 2 + offset.y, (screenH - 116) / 2 + shrunk.y)
    }

    @Test
    fun `a finger parked on a step boundary does not flip the window back and forth`() {
        // half a pixel of wobble on glass, right where round() changes its mind,
        // must not swap a whole 29px segment in and out
        val onTheEdge = PlaylistLayout.FURNITURE_H + (8 * 29) + 15 // just past the half step

        val steps =
            (-2..2).map { wobble ->
                WindowSizing.stepsForHeight(
                    rawHeight = onTheEdge + wobble,
                    furnitureH = PlaylistLayout.FURNITURE_H,
                    stepPx = 29,
                    min = 2,
                    max = 30,
                    current = 8,
                )
            }

        assertEquals("the step count holds steady under a wobble: $steps", 1, steps.distinct().size)
    }

    @Test
    fun `a decisive pull still steps`() {
        val current = 8
        val pulled = PlaylistLayout.FURNITURE_H + (current + 1) * 29

        assertEquals(
            current + 1,
            WindowSizing.stepsForHeight(pulled, PlaylistLayout.FURNITURE_H, 29, 2, 30, current = current),
        )
        assertEquals(
            current - 1,
            WindowSizing.stepsForHeight(
                PlaylistLayout.FURNITURE_H + (current - 1) * 29,
                PlaylistLayout.FURNITURE_H,
                29,
                2,
                30,
                current = current,
            ),
        )
    }

    @Test
    fun `the cap and the floor still win over what the window already is`() {
        assertEquals(4, WindowSizing.stepsForHeight(10_000, 58, 29, min = 2, max = 4, current = 3))
        assertEquals(2, WindowSizing.stepsForHeight(0, 58, 29, min = 2, max = 30, current = 8))
    }
}
