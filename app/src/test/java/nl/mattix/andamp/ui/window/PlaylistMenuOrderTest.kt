// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The order the five bottom-bar menus list their entries in.
 *
 * Transcribed from webamp's own components - `AddMenu.tsx`, `RemoveMenu.tsx`,
 * `SelectionMenu.tsx`, `MiscMenu.tsx`, `ListMenu.tsx` - which render their
 * children top to bottom in these orders.
 *
 * The sprite sheet's order differs: REM's art is stacked ALL, CROP, SELECTED, MISC
 * (y=111 up to y=168), and the menu is MISC, ALL, CROP, SELECTED.
 */
class PlaylistMenuOrderTest {
    private fun entriesOf(menu: String): List<String> =
        playlistMenus(width = 275)
            .first { it.id == "pl.menu.$menu" }
            .entries
            .map { it.id.removePrefix("pl.menu.$menu.") }

    @Test
    fun `ADD lists url, dir, file`() {
        assertEquals(listOf("url", "dir", "file"), entriesOf("add"))
    }

    @Test
    fun `REM lists misc, all, crop, selected`() {
        assertEquals(listOf("misc", "all", "crop", "selected"), entriesOf("rem"))
    }

    @Test
    fun `SEL lists invert, zero, all`() {
        assertEquals(listOf("invert", "zero", "all"), entriesOf("sel"))
    }

    @Test
    fun `MISC lists sort, info, opts`() {
        assertEquals(listOf("sort", "info", "opts"), entriesOf("misc"))
    }

    @Test
    fun `LIST lists new, save, load`() {
        assertEquals(listOf("new", "save", "load"), entriesOf("list"))
    }
}
