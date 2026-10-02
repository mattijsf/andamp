// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import nl.mattix.andamp.state.PresetImport
import nl.mattix.andamp.state.VisPlugin
import nl.mattix.andamp.state.presets.InstalledPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The visualizer page: what it shows, and that touching it reaches the ops. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class VisualizerSettingsTest {
    @get:Rule
    val compose = createComposeRule()

    private val packs =
        listOf(
            InstalledPack("Cream of the Crop", File("/tmp/cotc"), 9_795, emptyList()),
            InstalledPack("Tiny", File("/tmp/tiny"), 3, emptyList()),
        )

    @Test
    fun `the root row says which engine is running and what it has`() {
        assertEquals(
            "Milkdrop · 2 packs, 9798 presets",
            VisualizerPrefs(plugin = VisPlugin.Milkdrop, packs = packs).summary(),
        )
        // the count follows the engine's own kind
        assertEquals(
            "AVS · 1 pack, 155 presets",
            VisualizerPrefs(
                plugin = VisPlugin.Avs,
                packs = listOf(InstalledPack("Winamp AVS", File("/tmp/avs"), 0, emptyList(), avsCount = 155)),
            ).summary(),
        )
        assertEquals(
            "AVS · no preset packs yet",
            VisualizerPrefs().summary(),
        )
    }

    @Test
    fun `choosing an engine reports it`() {
        var chosen: VisPlugin? = null
        show(VisualizerPrefs(onPlugin = { chosen = it }))

        compose.onNodeWithTag("prefs.vis.plugin.milkdrop").performScrollTo().performClick()
        assertEquals(VisPlugin.Milkdrop, chosen)
    }

    @Test
    fun `the idle preset is an entry of its own, and is what is chosen with no pack`() {
        var selected: String? = "something"
        show(VisualizerPrefs(packs = packs, activePack = null, onSelectPack = { selected = it }))

        compose.onNodeWithTag("prefs.vis.pack.idle").performScrollTo().assertIsSelected()

        compose.onNodeWithTag("prefs.vis.pack.Tiny").performScrollTo().performClick()
        assertEquals("Tiny", selected)

        compose.onNodeWithTag("prefs.vis.pack.idle").performClick()
        assertNull(selected)
    }

    @Test
    fun `a pack can be removed, and the idle preset cannot`() {
        var removed: InstalledPack? = null
        show(VisualizerPrefs(packs = packs, onDeletePack = { removed = it }))

        compose.onNodeWithTag("prefs.vis.pack.idle.delete").assertDoesNotExist()
        compose.onNodeWithTag("prefs.vis.pack.Tiny.delete", useUnmergedTree = true).performScrollTo().performClick()
        assertEquals(packs[1], removed)
    }

    @Test
    fun `switching shuffle on reports it`() {
        var shuffle: Boolean? = null
        show(VisualizerPrefs(shuffle = false, onShuffle = { shuffle = it }))

        compose
            .onNodeWithTag("prefs.vis.shuffle")
            .performScrollTo()
            .assertIsOff()
            .performClick()
        assertEquals(true, shuffle)
    }

    @Test
    fun `an import in flight is counted`() {
        show(VisualizerPrefs(importing = PresetImport("CotC", 4_200)))
        compose.onNodeWithText("Importing CotC... 4200 files").assertExists()
    }

    @Test
    fun `an import that found no presets says so`() {
        show(VisualizerPrefs(importing = PresetImport("holiday photos", 0, failed = true)))
        compose.onNodeWithText("\"holiday photos\" held no presets.").assertExists()
    }

    @Test
    fun `a shuffled pack shows as shuffled`() {
        show(VisualizerPrefs(packs = packs, shuffle = true))
        compose.onNodeWithTag("prefs.vis.shuffle").performScrollTo().assertIsOn()
    }

    /** The real page is inside a scroller, and performScrollTo needs one. */
    private fun show(prefs: VisualizerPrefs) {
        compose.setContent {
            androidx.compose.foundation.layout.Column(
                androidx.compose.ui.Modifier
                    .verticalScroll(rememberScrollState()),
            ) { VisualizerSettings(prefs) }
        }
    }
}
