// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

        compose.onNodeWithText(WidgetRefresh.HIGH.timeOnly).assertExists()
        compose.onNodeWithTag("prefs.widget.refresh.high").assertIsEnabled()
        assertEquals("High steps the clock at Medium's rate", WidgetRefresh.MEDIUM.windowMs, WidgetRefresh.HIGH.windowMs)
        assertEquals(WidgetRefresh.MEDIUM.timeOnly, WidgetRefresh.HIGH.timeOnly)
    }
}
