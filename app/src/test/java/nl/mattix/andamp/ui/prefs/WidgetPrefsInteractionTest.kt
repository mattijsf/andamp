// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import android.content.Context
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.R
import nl.mattix.andamp.state.SkinEntry
import nl.mattix.andamp.state.SkinKind
import nl.mattix.andamp.state.VisMode
import nl.mattix.andamp.widget.WidgetRefresh
import nl.mattix.andamp.widget.WidgetSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The controls move when they are pressed, and report the change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetPrefsInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `picking a picture selects it and reports it`() {
        var last = WidgetSettings()
        compose.setContent {
            var settings by remember { mutableStateOf(WidgetSettings()) }
            // no Column around it: WidgetRows stacks its own blocks, and a
            // Column here would hide a failure to do so
            WidgetRows(
                WidgetPrefs(settings) {
                    settings = it
                    last = it
                },
            )
        }

        // Off is one of the three choices, not a switch in front of them
        compose.onNodeWithTag("prefs.widget.vis.analyzer").assertIsSelected()
        compose.onNodeWithTag("prefs.widget.vis.oscilloscope").performClick()
        compose.onNodeWithTag("prefs.widget.vis.oscilloscope").assertIsSelected()
        assertEquals(VisMode.Oscilloscope, last.mode)
        compose.onNodeWithTag("prefs.widget.vis.off").performClick()
        assertEquals(VisMode.Off, last.mode)
    }

    @Test
    fun `picking a rate selects it and leaves the picture alone`() {
        var last = WidgetSettings()
        compose.setContent {
            var settings by remember { mutableStateOf(WidgetSettings()) }
            // no Column around it: WidgetRows stacks its own blocks, and a
            // Column here would hide a failure to do so
            WidgetRows(
                WidgetPrefs(settings) {
                    settings = it
                    last = it
                },
            )
        }

        compose.onNodeWithTag("prefs.widget.refresh.medium").assertIsSelected()
        compose.onNodeWithTag("prefs.widget.refresh.low").performClick()
        compose.onNodeWithTag("prefs.widget.refresh.low").assertIsSelected()
        assertEquals(WidgetRefresh.LOW, last.refresh)
        assertEquals(VisMode.Analyzer, last.mode)
    }

    @Test
    fun `switching on open-on-logo and the volume control reports both`() {
        var last = WidgetSettings()
        compose.setContent {
            var settings by remember { mutableStateOf(WidgetSettings()) }
            // scrolling here: these two sit below the three pickers and the
            // page is taller than the window
            androidx.compose.foundation.layout.Column(
                Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
            ) {
                WidgetRows(
                    WidgetPrefs(settings) {
                        settings = it
                        last = it
                    },
                )
            }
        }

        assertEquals(false, last.openOnLogo)
        compose.onNodeWithTag("prefs.widget.logo").performScrollTo().performClick()
        assertEquals(true, last.openOnLogo)

        compose.onNodeWithTag("prefs.widget.volume").performScrollTo().performClick()
        assertEquals(true, last.volumeControl)
        // and the first choice is not undone by the second
        assertEquals(true, last.openOnLogo)
    }

    @Test
    fun `every rate stays offered with the visualizer off, and says only what is left`() {
        // the rate drives the clock as well as the visualizer, and the two
        // faster rates step the clock the same, so with the visualizer off
        // High behaves as Medium
        compose.setContent {
            WidgetRows(WidgetPrefs(WidgetSettings(mode = VisMode.Off, refresh = WidgetRefresh.HIGH)))
        }

        val timeOnly = ApplicationProvider.getApplicationContext<Context>().getString(WidgetRefresh.HIGH.timeOnly)
        compose.onNodeWithText(timeOnly).assertExists()
        compose.onNodeWithTag("prefs.widget.refresh.high").assertIsEnabled()
        assertEquals("High steps the clock at Medium's rate", WidgetRefresh.MEDIUM.windowMs, WidgetRefresh.HIGH.windowMs)
        assertEquals(WidgetRefresh.MEDIUM.timeOnly, WidgetRefresh.HIGH.timeOnly)
    }

    private val classic = listOf(SkinEntry("neon", "Neon Nights", 0), SkinEntry("wood", "Walnut", 0))
    private val modern = SkinEntry("bento", "Bento", 0, SkinKind.MODERN)

    /** Shows the page, taller than the window, with [skins] installed; returns the widget's skin as last reported. */
    private fun showSkins(
        skins: List<SkinEntry>,
        start: String? = null,
    ): () -> String? {
        var chosen by mutableStateOf(start)
        compose.setContent {
            androidx.compose.foundation.layout.Column(
                Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
            ) {
                WidgetRows(WidgetPrefs(skins = skins, skin = chosen, onSkin = { chosen = it }))
            }
        }
        return { chosen }
    }

    /** The tags of the rows in the open skin list, top to bottom. */
    private fun listed(): List<String> =
        compose
            .onAllNodes(
                SemanticsMatcher("in the widget's skin list") { node ->
                    val tag = node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
                    tag.startsWith("prefs.widget.skin.") && tag != "prefs.widget.skin.row"
                },
            ).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.TestTag].removePrefix("prefs.widget.skin.") }

    @Test
    fun `the skin row shows with modern skins off, when only classic skins are listed`() {
        showSkins(classic)

        compose.onNodeWithTag("prefs.widget.skin.row").performScrollTo().assertExists()
        compose.onNodeWithText("Default").assertExists()
    }

    @Test
    fun `the skin list has Default first, then the classic skins only`() {
        showSkins(classic + modern)

        compose.onNodeWithTag("prefs.widget.skin.row").performScrollTo().performClick()

        assertEquals(listOf("default", "neon", "wood"), listed())
    }

    @Test
    fun `picking a skin sets it for the widget`() {
        val chosen = showSkins(classic)

        compose.onNodeWithTag("prefs.widget.skin.row").performScrollTo().performClick()
        compose.onNodeWithTag("prefs.widget.skin.wood").performClick()

        assertEquals("wood", chosen())
        compose.onNodeWithText("Walnut").assertExists()
        compose.onNodeWithTag("prefs.widget.skin.row").performClick()
        compose.onNodeWithTag("prefs.widget.skin.wood").assertIsSelected()
    }

    @Test
    fun `picking Default has the widget follow the player`() {
        val chosen = showSkins(classic, start = "neon")

        compose.onNodeWithTag("prefs.widget.skin.row").performScrollTo().performClick()
        compose.onNodeWithTag("prefs.widget.skin.default").performClick()

        assertEquals(null, chosen())
        val own = ApplicationProvider.getApplicationContext<Context>().getString(R.string.prefs_skin_own)
        compose.onNodeWithText(own).assertExists()
    }
}
