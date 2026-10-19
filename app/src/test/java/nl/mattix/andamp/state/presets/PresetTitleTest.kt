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

    private val cream =
        InstalledPack(
            name = "Cream",
            dir = java.io.File("/p/Cream"),
            presetCount = 2,
            textureDirs = emptyList(),
            wrapper = "presets-cream-of-the-crop-master",
        )

    @Test
    fun `the line under a title is its folders, without the zip's own`() {
        assertEquals(
            "Dancer / Glowsticks Mirror",
            PresetTitle.placeOf(
                "/p/Cream/presets-cream-of-the-crop-master/Dancer/Glowsticks Mirror/x - Mashup (189).milk",
                cream,
            ),
        )
    }

    @Test
    fun `a numbered preset's line leaves out the folder its title names`() {
        assertEquals(
            "Geometric",
            PresetTitle.placeOf("/p/Cream/presets-cream-of-the-crop-master/Geometric/Wire Trace/153.milk", cream),
        )
    }

    @Test
    fun `a preset at the top of its pack has no line`() {
        assertEquals(null, PresetTitle.placeOf("/p/Cream/presets-cream-of-the-crop-master/top.milk", cream))
        assertEquals(null, PresetTitle.placeOf("/elsewhere/a.milk", null))
    }
}
