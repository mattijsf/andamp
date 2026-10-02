// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.core.model.TrackInfo
import nl.mattix.andamp.state.AmpMenu
import nl.mattix.andamp.state.AmpPrompt
import nl.mattix.andamp.state.BookmarkSheet
import nl.mattix.andamp.state.ChoiceSheet
import nl.mattix.andamp.state.NamePrompt
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.ui.about.AboutBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * Every modal the player can open, shown where there is no activity.
 *
 * The floating player is a view this app hands to the window manager itself, so
 * it has no window token, and Compose's `Dialog` is an `android.app.Dialog`
 * that needs one and throws `BadTokenException` without it. Each modal has a
 * test here that shows it with no activity in the context.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class ModalParityTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Context = ApplicationProvider.getApplicationContext()

    /** Shown with no activity in the context, as in the floating player. */
    private fun floating(content: @Composable () -> Unit) {
        // the about box animates for as long as it is on screen, so the clock never goes idle
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalContext provides app) { content() }
        }
    }

    @Test
    fun `the about box`() {
        floating { AboutBody(onClose = {}) }

        compose.onNodeWithText("Andamp").assertIsDisplayed()
    }

    @Test
    fun `track info`() {
        val info = TrackInfo("Llama Whippin' Intro", listOf(TrackInfo.Line("Path", "/music/llama.mp3")))

        floating { TrackInfoDialog(info) {} }

        compose.onNodeWithText("Llama Whippin' Intro", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a choice of where a verb belongs`() {
        val sheet = ChoiceSheet("Save the playlist", listOf(ChoiceSheet.Choice("Here", "in the app") {}))

        floating { ChoiceSheetDialog(sheet) {} }

        compose.onNodeWithText("Save the playlist").assertIsDisplayed()
    }

    @Test
    fun `the bookmark sheet`() {
        val sheet =
            BookmarkSheet(
                entries = { emptyList() },
                onOpen = {},
                onEnqueue = {},
                onRename = { _, _ -> },
                onRemove = {},
            )

        floating { BookmarkSheetDialog(sheet) {} }

        compose.onNodeWithText("Bookmarks", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a permission being asked for`() {
        val state = WinampState()
        state.prompt =
            AmpPrompt("Draw over other apps", "The floating player needs it.", "Open settings") {}

        floating { AmpPromptHost(state) }

        compose.onNodeWithText("Draw over other apps").assertIsDisplayed()
        compose.onNodeWithText("Open settings").performClick()
        assertNull("an answered prompt is cleared", state.prompt)
    }

    @Test
    fun `a name being asked for`() {
        var given: String? = null
        val state = WinampState()
        state.namePrompt = NamePrompt("Name this playlist", "Untitled", "Save") { given = it }

        floating { AmpModals(state, anchorBounds = { androidx.compose.ui.unit.IntRect.Zero }) }

        compose.onNodeWithText("Name this playlist").assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()
        assertEquals("Untitled", given)
    }

    @Test
    fun `the floating player knows about every one of them`() {
        // The floating window reads `modalShowing` to know a modal is up, so every modal has to set it.
        val modals: List<Pair<String, (WinampState) -> Unit>> =
            listOf(
                "the about box" to { s -> s.aboutOpen = true },
                "a menu" to { s -> s.activeMenu = AmpMenu("Options", emptyList()) },
                "a name prompt" to { s -> s.namePrompt = NamePrompt("Name it", onSubmit = {}) },
                "a permission prompt" to { s -> s.prompt = AmpPrompt("Grant", "why", onConfirm = {}) },
                "track info" to { s -> s.trackInfo = TrackInfo("A track", emptyList()) },
                "a choice sheet" to { s -> s.choiceSheet = ChoiceSheet("Where", emptyList()) },
                "the bookmark sheet" to { s ->
                    s.bookmarkSheet =
                        BookmarkSheet({ emptyList() }, onOpen = {}, onEnqueue = {}, onRename = { _, _ -> }, onRemove = {})
                },
            )

        modals.forEach { (what, open) ->
            val state = WinampState()
            assertEquals("no modal is showing at first", false, state.modalShowing)
            open(state)
            assertEquals("$what sets modalShowing", true, state.modalShowing)
        }
    }

    @Test
    fun `hostActivity finds a wrapped activity and nothing in the application context`() {
        val activity = ContextWrapper(object : Activity() {})

        assertNotNull(activity.hostActivity())
        assertNull("the application context has no host activity", app.hostActivity())
    }
}
