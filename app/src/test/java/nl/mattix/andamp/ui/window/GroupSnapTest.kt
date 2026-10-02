// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A docked group docks as one thing.
 *
 * Dragging the player towards the playlist has to bring the *equalizer's*
 * bottom edge against it - that is the edge that arrives first - and the
 * player is what the finger is holding, so the correction lands on the player.
 */
class GroupSnapTest {
    private val screenW = 300
    private val screenH = 800
    private val width = 275
    private val height = 116

    /** The center-relative offset that puts a window's top edge at [top]. */
    private fun offsetForTop(
        top: Int,
        h: Int = height,
    ) = IntOffset(0, top - (screenH - h) / 2)

    private fun topOf(
        offset: IntOffset,
        h: Int = height,
    ) = (screenH - h) / 2 + offset.y

    private fun rect(
        top: Int,
        h: Int = height,
    ) = IntRect(12, top, 12 + width, top + h)

    private fun settleMain(
        askedTop: Int,
        mainNow: Int,
        eqNow: Int,
        others: List<IntRect>,
    ) = settle(
        offset = offsetForTop(askedTop),
        width = width,
        height = height,
        screenW = screenW,
        screenH = screenH,
        safeTop = 0,
        safeBottom = screenH,
        titleH = 14,
        neighbours = others,
        escaped = true,
        carried = listOf(rect(eqNow)),
        anchorNow = rect(mainNow),
    )

    @Test
    fun `the equalizer's bottom edge docks against the playlist below it`() {
        val playlist = rect(340, h = 260)

        // the group is main 100..216 and eq 216..332; asked to sit 5px lower,
        // which puts the equalizer's bottom 3px short of the playlist
        val landed = settleMain(askedTop = 105, mainNow = 100, eqNow = 216, others = listOf(playlist))

        // the group closed the gap: eq bottom == playlist top, so main sits at 108
        assertEquals(108, topOf(landed))
    }

    @Test
    fun `a player carrying nothing still docks by its own edge`() {
        val playlist = rect(340, h = 260)

        val landed =
            settle(
                offset = offsetForTop(220),
                width = width,
                height = height,
                screenW = screenW,
                screenH = screenH,
                safeTop = 0,
                safeBottom = screenH,
                titleH = 14,
                neighbours = listOf(playlist),
                escaped = true,
            )

        assertEquals(340 - height, topOf(landed))
    }

    @Test
    fun `a short nudge onto a new neighbor still docks`() {
        // the group ends up 3px short of the playlist after a drag too small
        // to count as "escaped"
        val playlist = rect(340, h = 260)

        val landed =
            settle(
                offset = offsetForTop(105),
                width = width,
                height = height,
                screenW = screenW,
                screenH = screenH,
                safeTop = 0,
                safeBottom = screenH,
                titleH = 14,
                neighbours = listOf(playlist),
                escaped = false, // still within the pull of where it started
                carried = listOf(rect(216)),
                anchorNow = rect(100),
            )

        assertEquals("a window arriving at a new neighbor docks", 108, topOf(landed))
    }

    @Test
    fun `the dock a window is leaving lets go`() {
        // flush under a window above it, dragged 4px down: the dock it is
        // leaving must not pull it back
        val above = rect(-116)

        val landed =
            settle(
                offset = offsetForTop(4),
                width = width,
                height = height,
                screenW = screenW,
                screenH = screenH,
                safeTop = 0,
                safeBottom = screenH,
                titleH = 14,
                neighbours = listOf(above),
                escaped = false,
                anchorNow = rect(0),
            )

        assertEquals(4, topOf(landed))
    }

    @Test
    fun `a group too far away is left where the finger put it`() {
        val playlist = rect(600, h = 180)

        val landed = settleMain(askedTop = 105, mainNow = 100, eqNow = 216, others = listOf(playlist))

        assertEquals(105, topOf(landed))
    }
}
