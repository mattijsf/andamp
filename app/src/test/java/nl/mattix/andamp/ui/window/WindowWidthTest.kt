// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every window's base width is the player's.
 *
 * Winamp's own rule: windows narrower than the main window cannot stack neatly under it,
 * and the edges a snap lines up would not agree.
 */
class WindowWidthTest {
    @Test
    fun `every window is the width of the player`() {
        val widths =
            mapOf(
                "main" to MAIN_W,
                "eq" to EQ_W,
                "playlist" to PL_W,
                "library" to LibraryLayout.WIDTH,
                "skins" to SkinManagerLayout.WIDTH,
                "milkdrop" to MILKDROP_W,
            )

        widths.forEach { (name, width) ->
            assertEquals("$name is the player's width", MAIN_W, width)
        }
    }

    @Test
    fun `the minimum height of a list window still holds a few rows`() {
        assertTrue(SkinManagerLayout.MIN_ROWS >= 3)
        assertTrue(LibraryLayout.MIN_ROWS >= 3)
        assertEquals(2, PlaylistLayout.MIN_SEGMENTS)
    }
}
