// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.presets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetSearchTest {
    private val text = PresetSearch.text("\$\$\$ Royal - Mashup (189)", "Dancer / Glowsticks Mirror")

    @Test
    fun `every word has to be in the title or the folders, in any order and case`() {
        assertTrue(PresetSearch.matches(text, PresetSearch.words("glow MIRROR")))
        assertTrue(PresetSearch.matches(text, PresetSearch.words("mashup dancer")))
        assertFalse(PresetSearch.matches(text, PresetSearch.words("mashup fractal")))
    }

    @Test
    fun `spaces around and between words do not count`() {
        assertEquals(listOf("glow", "mirror"), PresetSearch.words("  glow   mirror "))
        assertEquals(emptyList<String>(), PresetSearch.words("   "))
    }

    @Test
    fun `a preset with no folders is searched by its title`() {
        assertTrue(PresetSearch.matches(PresetSearch.text("Jc - Crystal Shards", null), PresetSearch.words("crystal")))
    }
}
