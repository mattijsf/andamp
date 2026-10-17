// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.presets

import org.junit.Assert.assertEquals
import org.junit.Test

class PresetTitleTest {
    @Test
    fun `a named preset keeps its name`() {
        assertEquals("Jc - Crystal Shards", PresetTitle.of("/p/Cream/Dancer/Jc - Crystal Shards.milk"))
    }

    @Test
    fun `a numbered preset takes its folder's name`() {
        assertEquals("Wire Trace 153", PresetTitle.of("/p/Cream/Geometric/Wire Trace/153.milk"))
        assertEquals("Glimmer Mirror 121", PresetTitle.of("/p/Cream/Sparkle/Glimmer Mirror/121.milk"))
    }

    @Test
    fun `numbers are ordered by value, letters regardless of case`() {
        val titles = listOf("Wire Trace 10", "wire trace 2", "Aurora 1", "Wire Trace 002b", "Wire Trace 1")

        assertEquals(
            listOf("Aurora 1", "Wire Trace 1", "wire trace 2", "Wire Trace 002b", "Wire Trace 10"),
            titles.sortedWith(PresetTitle.ORDER),
        )
    }
}
