// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import nl.mattix.andamp.state.presets.PresetDetails
import nl.mattix.andamp.visualizer.core.PresetGaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The preset selector: the visual on top, the preset showing with its buttons, and a lazy list
 * of the source's presets with their stars and problems; the settings on a second tab.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class MilkdropScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun paths(count: Int) = (1..count).map { "/presets/pack/preset $it.milk" }

    @Test
    fun `picking a preset plays its file`() {
        var played: String? = null
        show(selector = PresetSelector(paths = paths(5), onPlay = { played = it }))

        // the row may start off screen and a lazy list only composes what shows
        compose.onNodeWithTag("milkdrop.presets").performScrollToNode(hasTagOf("milkdrop.preset.preset 4"))
        compose.onNodeWithTag("milkdrop.preset.preset 4").performClick()

        assertEquals("/presets/pack/preset 4.milk", played)
    }

    @Test
    fun `the list says how many there are and which pack they came from`() {
        show(VisualizerPrefs(activePack = "Cream of the Crop"), PresetSelector(paths = paths(120)))

        compose
            .onNodeWithText("120 presets in Cream of the Crop. Tap a star to add a preset to your favorites.")
            .assertIsDisplayed()
    }

    /** A list of thousands must not compose all of them; only what is on screen. */
    @Test
    fun `a big pack is not composed all at once`() {
        show(selector = PresetSelector(paths = paths(5_000)))

        compose.onNodeWithTag("milkdrop.preset.preset 1").assertIsDisplayed()
        compose.onNodeWithTag("milkdrop.preset.preset 4000").assertDoesNotExist()
    }

    @Test
    fun `a long list opens on the preset that is playing`() {
        show(selector = PresetSelector(paths = paths(500), current = "/presets/pack/preset 300.milk", currentName = "preset 300"))

        compose.onNodeWithTag("milkdrop.preset.preset 300").assertIsDisplayed()
        compose.onNodeWithTag("milkdrop.current").assertIsDisplayed()
    }

    @Test
    fun `a star marks a favorite, and toggles it`() {
        val favorite = "/presets/pack/preset 2.milk"
        var toggled: String? = null
        show(
            selector =
                PresetSelector(
                    paths = paths(3),
                    isFavorite = { it == favorite },
                    onToggleFavorite = { toggled = it },
                ),
        )

        compose.onNodeWithTag("milkdrop.preset.preset 2.favorite").assertIsOn()
        compose.onNodeWithTag("milkdrop.preset.preset 3.favorite").assertIsOff().performClick()

        assertEquals("/presets/pack/preset 3.milk", toggled)
    }

    @Test
    fun `the idle preset cannot be a favorite`() {
        show()

        compose.onNodeWithTag("milkdrop.current.favorite").assertIsNotEnabled()
    }

    @Test
    fun `a preset says why it did not play, and what it left out`() {
        val gaps =
            mapOf(
                "/presets/pack/preset 1.milk" to PresetGaps(unreadable = true, reason = "unexpected end of file"),
                "/presets/pack/preset 2.milk" to PresetGaps(missing = listOf("Texer II")),
            )
        show(selector = PresetSelector(paths = paths(3), gapsOf = { gaps[it] }))

        compose.onNodeWithText("This preset could not be read: unexpected end of file").assertIsDisplayed()
        compose.onNodeWithText("Not shown in this preset: Texer II").assertIsDisplayed()
        compose.onNodeWithTag("milkdrop.preset.preset 3.problem").assertDoesNotExist()
    }

    @Test
    fun `the preset showing says what is wrong with it`() {
        show(
            selector =
                PresetSelector(
                    current = "/presets/pack/x.avs",
                    currentName = "x",
                    currentGaps = PresetGaps(missing = listOf("Acko.net: Texer II", "Picture")),
                ),
        )

        compose.onNodeWithTag("milkdrop.current.problem").assertIsDisplayed()
        compose.onNodeWithText("Not shown in this preset: Texer II, Picture").assertIsDisplayed()
    }

    @Test
    fun `the favorites are a source, which plays as one list`() {
        var chosen = false
        show(
            VisualizerPrefs(activePack = "pack", favoritesOn = true, favoriteCount = 2, onSelectFavorites = { chosen = true }),
            PresetSelector(paths = paths(2)),
        )

        compose.onNodeWithText("2 favorites, from any pack. Next and Shuffle stay among them.").assertIsDisplayed()
        compose.onNodeWithTag("milkdrop.source.favorites").performClick()

        assertTrue(chosen)
    }

    @Test
    fun `with no favorites their source cannot be picked`() {
        show(VisualizerPrefs(activePack = "pack"), PresetSelector(paths = paths(2)))

        compose.onNodeWithTag("milkdrop.source.favorites").assertIsNotEnabled()
    }

    @Test
    fun `with no pack it says what to do`() {
        show()

        // the copy follows the engine, and the default prefs are the default engine's
        compose
            .onNodeWithText("The AVS idle preset is playing. Pick a pack above, or import one under Settings.")
            .assertIsDisplayed()
    }

    @Test
    fun `the buttons under the visual reach the engine`() {
        var steps = 0
        var shuffle: Boolean? = null
        show(
            VisualizerPrefs(onShuffle = { shuffle = it }),
            PresetSelector(onNext = { steps++ }, onPrevious = { steps-- }),
        )

        compose.onNodeWithTag("milkdrop.next").performClick()
        compose.onNodeWithTag("milkdrop.next").performClick()
        compose.onNodeWithTag("milkdrop.previous").performClick()
        compose.onNodeWithTag("milkdrop.shuffle").performClick()

        assertEquals(1, steps)
        assertEquals(true, shuffle)
    }

    @Test
    fun `the list is handed on to be checked ahead`() {
        var shown: List<String>? = null
        show(selector = PresetSelector(paths = paths(4), onShown = { shown = it }))

        compose.waitForIdle()
        assertEquals(paths(4), shown)
    }

    @Test
    fun `the pack settings are on the second tab`() {
        show(VisualizerPrefs(activePack = "pack"), PresetSelector(paths = paths(3)))

        compose.onNodeWithTag("prefs.vis.import").assertDoesNotExist()
        compose.onNodeWithTag("milkdrop.tab.settings").performClick()
        compose.onNodeWithTag("prefs.vis.import").assertIsDisplayed()
    }

    /** This screen is where browsing happens. */
    @Test
    fun `it does not offer a button to itself`() {
        show(VisualizerPrefs(activePack = "pack"), PresetSelector(paths = paths(3)))

        compose.onNodeWithTag("milkdrop.tab.settings").performClick()
        compose.onNodeWithTag("prefs.vis.manage").assertDoesNotExist()
    }

    @Test
    fun `a preset's page opens from its row, and back returns to the list`() {
        var forgotten: String? = null
        val path = "/presets/pack/preset 2.milk"
        show(
            selector =
                PresetSelector(
                    paths = paths(3),
                    gapsOf = { if (it == path) PresetGaps(blank = true) else null },
                    describe = {
                        PresetDetails(
                            path = it,
                            name = "preset 2",
                            author = null,
                            pack = "pack",
                            folder = null,
                            fingerprint = null,
                        )
                    },
                    onForget = { forgotten = it },
                ),
        )

        compose.onNodeWithTag("milkdrop.preset.preset 2.about").performClick()
        compose.waitUntil { compose.onAllNodesWithTag("preset.details.forget").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("preset.details.problem").assertIsDisplayed()
        compose.onNodeWithTag("preset.details.forget").performClick()
        compose.onNodeWithTag("preset.details.back").performClick()

        assertEquals(path, forgotten)
        compose.onNodeWithTag("milkdrop.presets").assertIsDisplayed()
    }

    @Test
    fun `done leaves`() {
        var closed = false
        show(onClose = { closed = true })

        compose.onNodeWithTag("milkdrop.close").performClick()

        assertTrue(closed)
    }

    private fun hasTagOf(tag: String) =
        androidx.compose.ui.test.SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.TestTag,
            tag,
        )

    private fun show(
        prefs: VisualizerPrefs = VisualizerPrefs(),
        selector: PresetSelector = PresetSelector(),
        onClose: () -> Unit = {},
    ) {
        compose.setContent { MilkdropScreen(prefs, selector, onClose) }
    }
}
