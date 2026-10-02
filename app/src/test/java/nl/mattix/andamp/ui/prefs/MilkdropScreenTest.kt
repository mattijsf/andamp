// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The preset manager: a lazy list of a pack's presets, opened on the one playing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class MilkdropScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun presets(count: Int) = (1..count).map { "preset $it" }

    @Test
    fun `picking a preset plays the one at that index`() {
        var played = -1
        show(VisualizerPrefs(activePack = "pack", presets = presets(5), onSelectPreset = { played = it }))

        // the row starts off screen and a lazy list only composes what shows
        compose.onNodeWithTag("milkdrop.presets").performScrollToNode(hasTagOf("milkdrop.preset.preset 4"))
        compose.onNodeWithTag("milkdrop.preset.preset 4").performClick()

        assertEquals(3, played)
    }

    @Test
    fun `the list says how many there are and which pack they came from`() {
        show(VisualizerPrefs(activePack = "Cream of the Crop", presets = presets(120)))

        compose
            .onNodeWithText("120 in Cream of the Crop, in the order the engine plays them.")
            .assertIsDisplayed()
    }

    /** A list of thousands must not compose all of them; only what is on screen. */
    @Test
    fun `a big pack is not composed all at once`() {
        show(VisualizerPrefs(activePack = "big", presets = presets(5_000)))

        compose.onNodeWithTag("milkdrop.preset.preset 1").assertIsDisplayed()
        compose.onNodeWithTag("milkdrop.preset.preset 4000").assertDoesNotExist()
    }

    @Test
    fun `a long list opens on the preset that is playing`() {
        show(VisualizerPrefs(activePack = "big", presets = presets(500), current = "preset 300"))

        compose.onNodeWithTag("milkdrop.preset.preset 300").assertIsDisplayed()
    }

    /** The pack settings sit above the list. */
    @Test
    fun `a short list opens at the top, with the pack settings still in view`() {
        show(VisualizerPrefs(activePack = "small", presets = presets(6), current = "preset 5"))

        compose.onNodeWithTag("prefs.vis.import").assertIsDisplayed()
    }

    @Test
    fun `with no pack it says what to do`() {
        show(VisualizerPrefs())

        // the copy follows the engine, and the default prefs are the default
        // engine's; the line starts off screen
        compose
            .onNodeWithTag("milkdrop.presets")
            .performScrollToNode(hasText("Nothing is loaded but the AVS idle preset. Import a pack above."))
        compose
            .onNodeWithText("Nothing is loaded but the AVS idle preset. Import a pack above.")
            .assertIsDisplayed()
    }

    @Test
    fun `the pack settings are on this screen`() {
        show(VisualizerPrefs(activePack = "pack", presets = presets(3)))

        compose.onNodeWithTag("milkdrop.presets").performScrollToNode(hasTagOf("prefs.vis.import"))
        compose.onNodeWithTag("prefs.vis.import").assertIsDisplayed()
    }

    /** This screen is where browsing happens. */
    @Test
    fun `it does not offer a button to itself`() {
        show(VisualizerPrefs(activePack = "pack", presets = presets(3)))

        compose.onNodeWithTag("prefs.vis.manage").assertDoesNotExist()
    }

    @Test
    fun `done leaves`() {
        var closed = false
        show(VisualizerPrefs(), onClose = { closed = true })

        compose.onNodeWithTag("milkdrop.close").performClick()

        assertTrue(closed)
    }

    private fun hasTagOf(tag: String) =
        androidx.compose.ui.test.SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.TestTag,
            tag,
        )

    private fun show(
        prefs: VisualizerPrefs,
        onClose: () -> Unit = {},
    ) {
        compose.setContent { MilkdropScreen(prefs, onClose) }
    }
}
