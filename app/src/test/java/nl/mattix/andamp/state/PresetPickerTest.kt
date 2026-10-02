// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The list dialog's own rules: what it shows, and what its button says. */
class PresetPickerTest {
    private fun picker(
        count: Int,
        multi: Boolean = true,
    ) = PresetPicker(
        title = "Delete auto-load presets",
        entries = (1..count).map { PresetEntry("k$it", "Song $it", detail = "Artist $it") },
        confirmLabel = "Delete",
        multiSelect = multi,
        destructive = true,
    ) {}

    @Test
    fun `a blank search shows everything`() {
        assertEquals(12, picker(12).matching("  ").size)
    }

    @Test
    fun `a search matches the name and what is under it`() {
        val found = picker(12).matching("artist 7")

        assertEquals(listOf("Song 7"), found.map { it.label })
    }

    @Test
    fun `searching ignores case and stray spaces`() {
        assertEquals(1, picker(12).matching("  SONG 3 ").size)
    }

    @Test
    fun `a short list needs no search field, a long one does`() {
        assertTrue(!picker(5).searchable)
        assertTrue(picker(40).searchable)
    }

    @Test
    fun `the button counts what will be thrown away`() {
        val delete = picker(9)

        assertEquals("Delete", delete.confirmLabelFor(0))
        assertEquals("Delete", delete.confirmLabelFor(1))
        assertEquals("Delete (4)", delete.confirmLabelFor(4))
    }

    @Test
    fun `a single-pick dialog never counts`() {
        assertEquals("Load", picker(9, multi = false).copy(confirmLabel = "Load").confirmLabelFor(1))
    }

    @Test
    fun `the line under the title counts what is there and what is picked`() {
        val nine = picker(9)

        assertEquals("9 saved", nine.countLabel(0))
        assertEquals("3 of 9 selected", nine.countLabel(3))
        assertEquals("1 saved", picker(1).countLabel(0))
        assertEquals("Nothing saved", picker(0).countLabel(0))
    }
}
