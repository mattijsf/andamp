// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import nl.mattix.andamp.state.BookmarkSheet
import nl.mattix.andamp.state.PresetEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Winamp's Preferences > Bookmarks and its four actions: Open and Enqueue send
 * a bookmark somewhere, Remove deletes it, and Edit asks for a name first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class BookmarkSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<String>()
    private val enqueued = mutableListOf<String>()
    private val removed = mutableListOf<String>()
    private val renamed = mutableListOf<Pair<String, String>>()
    private var dismissed = 0

    private fun show(entries: List<PresetEntry> = twoBookmarks) {
        // the list the dialog reads, so removing an entry takes its row away
        val shelf = mutableStateListOf(*entries.toTypedArray())
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                BookmarkSheetBody(
                    BookmarkSheet(
                        entries = { shelf.toList() },
                        onOpen = { opened += it },
                        onEnqueue = { enqueued += it },
                        onRename = { key, name -> renamed += key to name },
                        onRemove = {
                            removed += it
                            shelf.removeAll { row -> row.key == it }
                        },
                    ),
                ) { dismissed++ }
            }
        }
        compose.waitForIdle()
    }

    private fun pickFirst() {
        compose.onNodeWithTag("bookmarks.row.a").performClick()
        compose.waitForIdle()
    }

    @Test
    fun `nothing can be done until a bookmark is picked`() {
        show()

        compose.onNodeWithTag("bookmarks.open").assertIsNotEnabled()
        compose.onNodeWithTag("bookmarks.enqueue").assertIsNotEnabled()
        compose.onNodeWithTag("bookmarks.edit").assertIsNotEnabled()
        compose.onNodeWithTag("bookmarks.remove").assertIsNotEnabled()
    }

    @Test
    fun `picking one turns the four on`() {
        show()

        pickFirst()

        compose.onNodeWithTag("bookmarks.open").assertIsEnabled()
        compose.onNodeWithTag("bookmarks.enqueue").assertIsEnabled()
        compose.onNodeWithTag("bookmarks.edit").assertIsEnabled()
        compose.onNodeWithTag("bookmarks.remove").assertIsEnabled()
    }

    @Test
    fun `open sends the one that was picked, and closes`() {
        show()
        pickFirst()

        compose.onNodeWithTag("bookmarks.open").performClick()

        assertEquals(listOf("a"), opened)
        assertEquals(1, dismissed)
    }

    @Test
    fun `enqueue sends the one that was picked, and closes`() {
        show()
        pickFirst()

        compose.onNodeWithTag("bookmarks.enqueue").performClick()

        assertEquals(listOf("a"), enqueued)
        assertEquals(1, dismissed)
    }

    @Test
    fun `remove takes it off the list, and leaves the dialog open`() {
        // Winamp's page stays open after a removal, so the dialog reads its
        // list each time and does not hold the copy it opened with
        show()
        pickFirst()

        compose.onNodeWithTag("bookmarks.remove").performClick()
        compose.waitForIdle()

        assertEquals(listOf("a"), removed)
        assertEquals(0, dismissed)
        assertEquals(0, compose.onAllNodesWithTag("bookmarks.row.a").fetchSemanticsNodes().size)
        compose.onNodeWithTag("bookmarks.row.b").assertIsDisplayed()
    }

    @Test
    fun `edit renames in the same dialog and returns to the list`() {
        show()
        pickFirst()

        compose.onNodeWithTag("bookmarks.edit").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("bookmarks.name").performTextReplacement("The one with the llama")
        compose.onNodeWithTag("bookmarks.rename.save").performClick()
        compose.waitForIdle()

        assertEquals(listOf("a" to "The one with the llama"), renamed)
        compose.onNodeWithTag("bookmarks.row.a").assertIsDisplayed()
    }

    @Test
    fun `a canceled rename changes nothing`() {
        show()
        pickFirst()

        compose.onNodeWithTag("bookmarks.edit").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("bookmarks.rename.cancel").performClick()
        compose.waitForIdle()

        assertEquals(emptyList<Pair<String, String>>(), renamed)
        compose.onNodeWithTag("bookmarks.row.a").assertIsDisplayed()
    }

    @Test
    fun `an empty shelf has no rows and remove is disabled`() {
        show(entries = emptyList())

        compose.onNodeWithTag("bookmarks.remove").assertIsNotEnabled()
        assertEquals(0, compose.onAllNodesWithTag("bookmarks.row.a").fetchSemanticsNodes().size)
    }

    private companion object {
        val twoBookmarks =
            listOf(
                PresetEntry("a", "DJ Mike Llama - Llama Whippin' Intro"),
                PresetEntry("b", "Ministry - Bad Blood"),
            )
    }
}
