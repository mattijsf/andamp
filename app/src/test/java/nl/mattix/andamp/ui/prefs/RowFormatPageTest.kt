// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.state.ListStyleStore
import nl.mattix.andamp.state.RowFormat
import nl.mattix.andamp.state.TrackField
import nl.mattix.andamp.state.TrackInfo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Preferences > Player > Song rows: blocks added, typed between, taken out, dragged and reset. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RowFormatPageTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Context = ApplicationProvider.getApplicationContext()

    private fun show(known: Set<TrackField> = TrackField.entries.toSet()): ListStyleStore {
        val store = ListStyleStore(app.getSharedPreferences("liststyle-${System.nanoTime()}", Context.MODE_PRIVATE))
        compose.setContent {
            val kept = remember { store }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                RowFormatPage(
                    ListPrefs(
                        rowFormat = kept.rowFormat,
                        onRowFormat = { kept.rowFormat = it },
                        knownFields = { known },
                        playing = { Track("t1", "Portishead", "Roads", 300_000) to TrackInfo(album = "Dummy", year = 1994) },
                    ),
                )
            }
        }
        return store
    }

    private fun tag(name: String) = compose.onNodeWithTag(name, useUnmergedTree = true)

    @Test
    fun `a block added shows in the preview, with the song playing written in it`() {
        val store = show()

        tag("prefs.rows.add.album").performScrollTo().performClick()

        assertEquals("%artist% - %title% - %album%", store.rowFormat.encode())
        compose.onNodeWithText("1. Artist - Title - Album").assertExists()
        compose.onNodeWithText("2. Portishead - Roads - Dummy").assertExists()
    }

    @Test
    fun `only the fields the library has are offered`() {
        show(known = setOf(TrackField.ARTIST, TrackField.TITLE, TrackField.YEAR))

        tag("prefs.rows.add.year").assertExists()
        tag("prefs.rows.add.genre").assertDoesNotExist()
    }

    @Test
    fun `text typed between blocks is kept, and a block taken out takes its text with it`() {
        val store = show()

        tag("prefs.rows.gap.0").performTextReplacement(" · ")
        assertEquals("%artist% · %title%", store.rowFormat.encode())

        tag("prefs.rows.remove.artist").performClick()
        assertEquals("%title%", store.rowFormat.encode())
        tag("prefs.rows.remove.title").assertDoesNotExist()
    }

    @Test
    fun `a block held and dragged down takes the place below it`() {
        val store = show()

        tag("prefs.rows.block.artist").performTouchInput {
            down(center)
            advanceEventTime(LONG_PRESS_MS)
            moveBy(Offset(0f, 1f))
            repeat(STEPS) {
                advanceEventTime(16)
                moveBy(Offset(0f, STEP_PX))
            }
            up()
        }
        compose.waitForIdle()

        assertEquals("the text between stays where it was", "%title% - %artist%", store.rowFormat.encode())
    }

    @Test
    fun `reset goes back to artist and title`() {
        val store = show()
        tag("prefs.rows.add.genre").performScrollTo().performClick()

        tag("prefs.rows.reset").performScrollTo().performClick()

        assertEquals(RowFormat.DEFAULT, store.rowFormat)
        tag("prefs.rows.reset").assertIsNotEnabled()
    }

    private companion object {
        const val LONG_PRESS_MS = 700L
        const val STEPS = 8
        const val STEP_PX = 40f
    }
}
