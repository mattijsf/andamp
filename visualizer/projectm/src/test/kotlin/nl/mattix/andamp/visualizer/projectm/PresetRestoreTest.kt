// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.projectm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Going back to the preset that was playing, after the render loop was rebuilt.
 */
class PresetRestoreTest {
    private val pack =
        listOf(
            "/packs/best/Geiss - Spiral Artifact.milk",
            "/packs/best/Rovastar - Hyperspace.milk",
            "/packs/best/Aderrasi - Agitation.milk",
        )

    @Test
    fun `the preset that was playing is found again by name`() {
        assertEquals(1, PresetRestore.indexOf(pack, "Rovastar - Hyperspace"))
    }

    @Test
    fun `a playlist in another order finds it at its new position`() {
        val reshuffled = listOf(pack[2], pack[1], pack[0])

        assertEquals(2, PresetRestore.indexOf(reshuffled, "Geiss - Spiral Artifact"))
    }

    @Test
    fun `a preset the pack no longer has restores nothing`() {
        assertEquals(-1, PresetRestore.indexOf(pack, "Deleted - Long Gone"))
    }

    @Test
    fun `nothing remembered restores nothing`() {
        assertEquals(-1, PresetRestore.indexOf(pack, null))
        assertEquals(-1, PresetRestore.indexOf(pack, ""))
    }

    @Test
    fun `an empty pack restores nothing`() {
        assertEquals(-1, PresetRestore.indexOf(emptyList(), "Rovastar - Hyperspace"))
    }
}
