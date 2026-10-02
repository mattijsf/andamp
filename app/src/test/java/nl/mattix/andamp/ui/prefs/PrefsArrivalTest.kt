// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import android.app.Application
import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.playback.BrowseSource
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.core.player.PlayerFacade
import nl.mattix.andamp.state.DspOps
import nl.mattix.andamp.state.ExtraSource
import nl.mattix.andamp.state.FakeTracks
import nl.mattix.andamp.state.LibraryAccess
import nl.mattix.andamp.state.MusicSource
import nl.mattix.andamp.state.PluginOps
import nl.mattix.andamp.state.sourceRoute
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Preferences opened straight onto a page named from outside: a source's page
 * from the library menu when nobody is signed in to it, the widget's page from
 * the widget's own menu.
 *
 * The name is spent whatever it turns out to be, so the next visit opens the
 * list; a name this screen does not know opens the list.
 *
 * The name is held here the way the app holds it: set before the screen opens
 * and cleared when it is spent. Clearing it is a change the screen sees.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class PrefsArrivalTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val example = MusicSource("EXAMPLE", "Example")

    private val other =
        object : ExtraSource {
            override val source = example

            override fun signedIn(context: Context) = false

            override fun backend(
                context: Context,
                scope: CoroutineScope,
            ): PlaybackBackend? = null

            override fun browse(context: Context): BrowseSource? = null

            @Composable
            override fun Summary(signedIn: Boolean) = "Not signed in"

            @Composable
            override fun Page(
                onSignedIn: () -> Unit,
                onSignedOut: () -> Unit,
            ) = Text("the other source's own page")
        }

    private var arrived = 0

    /** The page asked for from outside, as the app keeps it: cleared when it is spent. */
    private var asked by mutableStateOf<String?>(null)

    private fun arriveAt(page: String) {
        asked = page
        val facade = PlayerFacade(MockBackend(FakeTracks.tracks, scope))
        val dsp = DspOps(app, facade)
        compose.setContent {
            PreferencesPage(
                access = LibraryAccess.GRANTED,
                dsp = dsp,
                plugins = PluginOps(app, facade, dsp, scope),
                onRequestAccess = {},
                onPickPlugin = {},
                onClose = {},
                sections = PrefsSections(sources = SourcesPrefs(extras = listOf(other))),
                arrival =
                    PrefsArrival(asked) {
                        arrived++
                        asked = null
                    },
            )
        }
    }

    private fun tag(value: String) = compose.onNodeWithTag(value, useUnmergedTree = true)

    @Test
    fun `a source's page named from outside is where Preferences opens`() {
        arriveAt(sourceRoute(example))

        compose.onNodeWithText("the other source's own page").assertIsDisplayed()
        // the source's name in the bar, and a way back to the list it skipped
        compose.onNodeWithText("Example").assertIsDisplayed()
        tag("prefs.back").assertIsDisplayed()
        assertEquals("the name is spent once", 1, arrived)
    }

    @Test
    fun `the widget's page opens by the name the widget's menu asks for`() {
        arriveAt(WIDGET_PAGE)

        compose.onNodeWithText("Home screen widget").assertIsDisplayed()
        tag("prefs.widget.limits").assertExists()
        tag("prefs.back").assertIsDisplayed()
        assertEquals(1, arrived)
    }

    @Test
    fun `a page this screen does not know opens the list, and is spent all the same`() {
        arriveAt("nope")

        tag("prefs.source.phone").assertIsDisplayed()
        compose.onAllNodesWithTag("prefs.back", useUnmergedTree = true).assertCountEquals(0)
        assertEquals(1, arrived)
    }
}
