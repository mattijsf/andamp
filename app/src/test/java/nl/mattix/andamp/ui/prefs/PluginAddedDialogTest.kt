// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import android.app.Application
import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.player.PlayerFacade
import nl.mattix.andamp.state.DspOps
import nl.mattix.andamp.state.FakeTracks
import nl.mattix.andamp.state.PluginOps
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * What adding a plug-in says, on the page every install lands on - from the
 * picker, or a .lua another app handed over: added, updated or already there,
 * with the plug-in's name, version and author, until it has been read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PluginAddedDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun clean() {
        listOf("dsp", "plugins").forEach {
            app
                .getSharedPreferences(it, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
        java.io.File(app.filesDir, "plugins").deleteRecursively()
    }

    private fun page(): PluginOps {
        val facade = PlayerFacade(MockBackend(FakeTracks.tracks, scope))
        val dsp = DspOps(app, facade)
        val plugins = PluginOps(app, facade, dsp, scope, Dispatchers.Unconfined) { GAIN }
        compose.setContent {
            InstalledPlugins(plugins, dsp, SnackbarHostState(), rememberCoroutineScope(), onPick = {})
        }
        return plugins
    }

    private fun install(
        plugins: PluginOps,
        source: String,
    ) {
        plugins.install(source.byteInputStream())
        ShadowLooper.idleMainLooper()
        compose.waitForIdle()
    }

    @Test
    fun `a new plug-in is said to be added, by name, version and author`() {
        val plugins = page()

        install(plugins, GAIN)

        compose.onNodeWithText("Plug-in added").assertIsDisplayed()
        compose.onNodeWithTag("prefs.plugins.added.name").assertTextEquals("Gain")
        compose.onNodeWithTag("prefs.plugins.added.credit").assertTextEquals("version 2.1, by Somebody")
        compose.onNodeWithText("It is at the end of the rack in Effects, switched off.").assertIsDisplayed()
    }

    @Test
    fun `a newer version is said to be an update, from the version it replaces`() {
        val plugins = page()
        install(plugins, GAIN)
        compose.onNodeWithTag("prefs.plugins.added.ok").performClick()
        install(plugins, GAIN.replace("2.1", "3.0"))

        compose.onNodeWithTag("prefs.plugins.replace").performClick()
        ShadowLooper.idleMainLooper()
        compose.waitForIdle()

        compose.onNodeWithText("Plug-in updated").assertIsDisplayed()
        compose.onNodeWithTag("prefs.plugins.added.credit").assertTextEquals("version 3.0, by Somebody")
        compose.onNodeWithText("Replaces version 2.1. It keeps its place in the rack and its settings.").assertIsDisplayed()
    }

    @Test
    fun `the same file again is said to be there already`() {
        val plugins = page()
        install(plugins, GAIN)
        compose.onNodeWithTag("prefs.plugins.added.ok").performClick()

        install(plugins, GAIN)

        compose.onNodeWithText("Already added").assertIsDisplayed()
    }

    @Test
    fun `OK puts it away`() {
        val plugins = page()
        install(plugins, GAIN)

        compose.onNodeWithTag("prefs.plugins.added.ok").performClick()

        compose.onNodeWithTag("prefs.plugins.added").assertDoesNotExist()
        assertNull(plugins.added)
    }

    /** A second identical refusal is shown after the first was dismissed. */
    @Test
    fun `a repeated refusal is shown again`() {
        val plugins = page()
        install(plugins, "not a plug-in")
        compose.onNodeWithText("Can't add that plug-in").assertIsDisplayed()
        compose.onNodeWithText("OK").performClick()

        install(plugins, "not a plug-in")

        compose.onNodeWithText("Can't add that plug-in").assertIsDisplayed()
    }

    /** A plug-in link asks first, by name and source, then says "added" as a picked file does. */
    @Test
    fun `a plug-in link asks before adding, and says it was added after`() {
        val plugins = page()

        plugins.offerFrom("https://mattix.nl/andamp/extensions/plugins/gain.lua")
        ShadowLooper.idleMainLooper()
        compose.waitForIdle()

        compose.onNodeWithText("Add this plug-in?").assertIsDisplayed()
        compose.onNodeWithTag("prefs.plugins.offer.credit").assertTextEquals("version 2.1, by Somebody")
        compose.onNodeWithText("From mattix.nl").assertIsDisplayed()

        compose.onNodeWithTag("prefs.plugins.offer.add").performClick()
        ShadowLooper.idleMainLooper()
        compose.waitForIdle()

        compose.onNodeWithText("Plug-in added").assertIsDisplayed()
    }

    private companion object {
        val GAIN =
            """
            plugin {
              id      = "com.example.gain",
              name    = "Gain",
              version = "2.1",
              author  = "Somebody",
              about   = "Turns it up.",
            }

            local gain = param.number { id = "gain", name = "Gain", min = 0, max = 1, default = 0.5 }

            function build(g, ctx)
              local out = {}
              for ch = 0, ctx.channels - 1 do
                out[ch] = g.mul(g.input(ch), gain)
              end
              return out
            end
            """.trimIndent()
    }
}
