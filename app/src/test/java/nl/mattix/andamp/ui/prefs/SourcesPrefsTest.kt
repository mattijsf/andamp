// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import nl.mattix.andamp.state.PhoneStats
import nl.mattix.andamp.state.PluginOps
import nl.mattix.andamp.state.ScanProgress
import nl.mattix.andamp.state.SkinEntry
import nl.mattix.andamp.state.SourceUpdates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Preferences > Music sources: a row per source, a page per source, and the
 * way out to what else there is.
 *
 * The second source is made up here, as a pack the listener installed would
 * bring it: its own subtitle, its own page, an update file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class SourcesPrefsTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val opened = mutableListOf<String>()
    private val started = mutableListOf<Intent>()
    private val skinsChosen = mutableListOf<Pair<MusicSource, String?>>()

    private val example = MusicSource("EXAMPLE", "Example")

    private val other =
        object : ExtraSource {
            override val source = example
            override val version = "1.0.0"
            override val updates = "https://example.org/example/update.json"

            override fun signedIn(context: Context) = true

            override fun backend(
                context: Context,
                scope: CoroutineScope,
            ): PlaybackBackend? = null

            override fun browse(context: Context): BrowseSource? = null

            @Composable
            override fun Summary(signedIn: Boolean) = if (signedIn) "Signed in as listener" else "Not signed in"

            @Composable
            override fun Page(
                onSignedIn: () -> Unit,
                onSignedOut: () -> Unit,
            ) = Text("the other source's own page")
        }

    /** The permission as the screen is told it, which a test can change while the screen is up. */
    private val held = mutableStateOf(LibraryAccess.GRANTED)

    private fun show(
        access: LibraryAccess = LibraryAccess.GRANTED,
        phone: () -> PhonePrefs = { PhonePrefs(stats = PhoneStats(1234, 245, 310, 0, 0)) },
        extras: List<ExtraSource> = emptyList(),
        newest: SourceUpdates.Check = SourceUpdates.Check.UpToDate("1.0.0"),
        chosenSkin: String? = null,
    ) {
        held.value = access
        val facade = PlayerFacade(MockBackend(FakeTracks.tracks, scope))
        val dsp = DspOps(app, facade)
        compose.setContent {
            CompositionLocalProvider(
                LocalUriHandler provides
                    object : UriHandler {
                        override fun openUri(uri: String) {
                            opened += uri
                        }
                    },
            ) {
                PreferencesPage(
                    access = held.value,
                    dsp = dsp,
                    plugins = PluginOps(app, facade, dsp, scope),
                    onRequestAccess = {},
                    onPickPlugin = {},
                    onClose = {},
                    sections =
                        PrefsSections(
                            sources =
                                SourcesPrefs(
                                    phone(),
                                    extras,
                                    signedIn = setOf(example),
                                    checkUpdate = { _, _ -> newest },
                                    skins = listOf(SkinEntry("andamp-spot", "AndAmp Spot", 0)),
                                    skinOf = { chosenSkin },
                                    onSkin = { source, skin -> skinsChosen += source to skin },
                                    openSettings = { started += it },
                                ),
                        ),
                )
            }
        }
    }

    private fun tag(value: String) = compose.onNodeWithTag(value, useUnmergedTree = true)

    @Test
    fun `the phone comes first, says how much music there is, and the link to more comes last`() {
        show(extras = listOf(other))

        compose.onNodeWithText("This Phone").assertIsDisplayed()
        compose.onNodeWithText("1,234 tracks · 245 artists · 310 albums").assertIsDisplayed()
        compose.onNodeWithText("Signed in as listener").assertIsDisplayed()
        val phone = tag("prefs.source.phone").getUnclippedBoundsInRoot().top
        val second = tag("prefs.source.example").getUnclippedBoundsInRoot().top
        val more = tag("prefs.source.more").getUnclippedBoundsInRoot().top
        assertTrue("the phone row is above the other source", phone < second)
        assertTrue("the link to more is below both rows", second < more)

        tag("prefs.source.more").performScrollTo().performClick()
        assertEquals(listOf(MORE_SOURCES_URL), opened)
    }

    @Test
    fun `the phone's row says when access is wanted, and its page has the way to give it`() {
        show(access = LibraryAccess.BLOCKED)

        compose.onNodeWithText("Music and audio permission is off").assertIsDisplayed()
        tag("prefs.source.phone").performClick()

        compose.onNodeWithText(LibraryAccess.BLOCKED.summary("Music and audio")).assertIsDisplayed()
        tag("prefs.library.action").assertIsDisplayed()
        // counting and scanning read what the permission guards
        compose.onAllNodesWithTag("prefs.phone.scan", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `access given on the phone's page is counted at once, there and on its row`() {
        var counted = 0
        var stats by mutableStateOf<PhoneStats?>(null)
        show(
            access = LibraryAccess.ASKABLE,
            phone = {
                PhonePrefs(
                    stats = stats,
                    onCount = {
                        counted++
                        // what counting finds without the permission: nothing it may read
                        stats = if (held.value == LibraryAccess.GRANTED) PhoneStats(1234, 245, 310, 0, 0) else null
                    },
                )
            },
        )
        tag("prefs.source.phone").performClick()
        tag("prefs.library.action").assertIsDisplayed()

        // Allow access, and yes: the prompt answers while this page is still open
        held.value = LibraryAccess.GRANTED

        compose.onNodeWithText("1,234").assertIsDisplayed()
        compose.onAllNodesWithText("Counting…").assertCountEquals(0)
        assertEquals("the library is counted once, when access comes", 1, counted)
        tag("prefs.back").performClick()
        compose.onNodeWithText("1,234 tracks · 245 artists · 310 albums").assertIsDisplayed()
    }

    @Test
    fun `a granted library shows what is there, and nothing to grant`() {
        show()
        tag("prefs.source.phone").performClick()

        compose.onAllNodesWithTag("prefs.library.action", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("1,234").assertIsDisplayed()
        tag("prefs.phone.scan").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a scan under way says where it is looking, and cannot be started twice`() {
        show(phone = { PhonePrefs(stats = PhoneStats(10, 1, 1, 0, 0), scanning = ScanProgress("Music", 0, 2)) })
        tag("prefs.source.phone").performClick()

        tag("prefs.phone.scan").performScrollTo().assertIsNotEnabled()
        tag("prefs.phone.progress").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Looking in Music… (0 of 2)").assertIsDisplayed()
    }

    @Test
    fun `another source's page is its own, and the updates come after it`() {
        show(extras = listOf(other))

        tag("prefs.source.example").performClick()

        val page = compose.onNodeWithText("the other source's own page")
        page.assertIsDisplayed()
        val updates = tag("prefs.updates.current").performScrollTo()
        updates.assertIsDisplayed()
        assertTrue(
            "the updates are below the source's own page",
            page.getUnclippedBoundsInRoot().top < updates.getUnclippedBoundsInRoot().top,
        )
    }

    @Test
    fun `a newer version opens its download page in the browser`() {
        val page = "https://example.org/example/download"
        show(extras = listOf(other), newest = SourceUpdates.Check.Available(SourceUpdates.Latest("1.1.0", page)))
        tag("prefs.source.example").performClick()

        tag("prefs.updates.available").performScrollTo().assertIsDisplayed()
        tag("prefs.updates.download").performScrollTo().performClick()

        assertEquals(listOf(page), opened)
    }

    @Test
    fun `a source's page offers a skin, Default until one is chosen`() {
        show(extras = listOf(other))
        tag("prefs.source.example").performClick()

        compose.onNodeWithText(DEFAULT_SKIN).performScrollTo().assertIsDisplayed()
        tag("prefs.skin.row").performClick()
        tag("prefs.skin.andamp-spot").performClick()

        assertEquals(listOf<Pair<MusicSource, String?>>(example to "andamp-spot"), skinsChosen)
    }

    @Test
    fun `each skin on offer is one radio row, and the chosen one reads as selected`() {
        show(extras = listOf(other), chosenSkin = "andamp-spot")
        tag("prefs.source.example").performClick()
        tag("prefs.skin.row").performScrollTo().performClick()

        tag("prefs.skin.andamp-spot")
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        tag("prefs.skin.default").assertIsNotSelected()
        // the dot is part of its row and has no click action of its own
        compose
            .onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("prefs.skin.default")), useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun `a chosen skin that is not installed reads as Default`() {
        show(extras = listOf(other), chosenSkin = "uninstalled")
        tag("prefs.source.example").performClick()

        compose.onNodeWithText(DEFAULT_SKIN).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a source with a settings screen of its own gets a button that opens it`() {
        val asked = Intent("nl.mattix.andamp.source.SETTINGS")
        val apart =
            object : ExtraSource by other {
                override fun settings(context: Context) = asked

                @Composable
                override fun Summary(signedIn: Boolean) = other.Summary(signedIn)
            }
        show(extras = listOf(apart))
        tag("prefs.source.example").performClick()

        compose.onAllNodesWithText("the other source's own page").assertCountEquals(0)
        tag("prefs.source.settings").performScrollTo().performClick()
        assertEquals(listOf(asked), started)
        // the app's own part of the page stays: the skin row is the app's to draw
        tag("prefs.skin.row").assertExists()
    }

    @Test
    fun `a source that says the setting is unsupported has no skin on its page`() {
        val plain =
            object : ExtraSource by other {
                override val skinnable = false

                @Composable
                override fun Summary(signedIn: Boolean) = other.Summary(signedIn)

                @Composable
                override fun Page(
                    onSignedIn: () -> Unit,
                    onSignedOut: () -> Unit,
                ) = other.Page(onSignedIn, onSignedOut)
            }
        show(extras = listOf(plain))
        tag("prefs.source.example").performClick()

        compose.onAllNodesWithTag("prefs.skin.row", useUnmergedTree = true).assertCountEquals(0)
    }
}
