// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.BuiltInEffects
import nl.mattix.andamp.core.model.Capabilities
import nl.mattix.andamp.core.model.EffectSpec
import nl.mattix.andamp.core.model.RackSettings
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.core.player.PlayerFacade
import nl.mattix.andamp.state.DspOps
import nl.mattix.andamp.state.FakeTracks
import nl.mattix.andamp.state.LibraryAccess
import nl.mattix.andamp.state.PluginOps
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Preferences on a screen wide enough to keep the list beside the page it
 * opened: a tablet on its side, a desktop.
 *
 * The list stays, one of its rows is marked as the page showing, and a row
 * replaces that page, so only a page opened from inside another (Effects'
 * plug-ins) has a way back. Phones keep one page at a time and are covered by
 * [PreferencesInteractionTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-mdpi")
class PreferencesBesideTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** A backend with a rack, which is what gives the effects page its cards. */
    private class EffectBackend(
        scope: CoroutineScope,
    ) : PlaybackBackend by MockBackend(FakeTracks.tracks, scope) {
        private val delegate = MockBackend(FakeTracks.tracks, scope)
        override val state get() = delegate.state
        override val capabilities: Capabilities get() = delegate.capabilities.copy(hasDsp = true)
        override val effects: List<EffectSpec> get() = BuiltInEffects.all

        override fun setDsp(rack: RackSettings) = Unit
    }

    private fun show(arrival: PrefsArrival = PrefsArrival()) {
        val facade = PlayerFacade(EffectBackend(scope))
        val dsp = DspOps(app, facade)
        compose.setContent {
            PreferencesPage(
                access = LibraryAccess.GRANTED,
                dsp = dsp,
                plugins = PluginOps(app, facade, dsp, scope),
                onRequestAccess = {},
                onPickPlugin = {},
                onClose = {},
                arrival = arrival,
            )
        }
    }

    private fun tag(value: String) = compose.onNodeWithTag(value, useUnmergedTree = true)

    private fun noBack() = compose.onAllNodesWithTag("prefs.back", useUnmergedTree = true).assertCountEquals(0)

    @Test
    fun `the list opens with the first row's page beside it`() {
        show()

        tag("prefs.open.dsp").assertIsDisplayed()
        tag("prefs.phone.scan").assertIsDisplayed()
        tag("prefs.source.phone").assertIsSelected()
        tag("prefs.open.dsp").assertIsNotSelected()
        noBack()
    }

    @Test
    fun `a row replaces the page beside the list, and the list stays`() {
        show()

        tag("prefs.open.dsp").performClick()

        tag("prefs.plugins.manage").performScrollTo().assertIsDisplayed()
        tag("prefs.source.phone").assertIsDisplayed()
        tag("prefs.open.dsp").assertIsSelected()
        tag("prefs.source.phone").assertIsNotSelected()
        // a row is not a step deeper: there is nothing to go back to
        noBack()
    }

    @Test
    fun `a page opened from inside another has a way back to it`() {
        show()
        tag("prefs.open.dsp").performClick()

        tag("prefs.plugins.manage").performScrollTo().performClick()

        tag("prefs.plugins.add").assertIsDisplayed()
        // still Effects' row: the plug-ins are that page's
        tag("prefs.open.dsp").assertIsSelected()
        tag("prefs.back").performClick()
        tag("prefs.plugins.manage").performScrollTo().assertIsDisplayed()
        noBack()
    }

    @Test
    fun `a page named from outside opens beside the list, with nothing to go back to`() {
        var asked: String? = WIDGET_PAGE
        show(PrefsArrival(asked) { asked = null })

        tag("prefs.widget.limits").assertExists()
        tag("prefs.open.widget").assertIsSelected()
        noBack()
    }
}
