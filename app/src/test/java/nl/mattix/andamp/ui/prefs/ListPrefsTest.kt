// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.R
import nl.mattix.andamp.state.EnglishWords
import nl.mattix.andamp.state.ListFont
import nl.mattix.andamp.state.ListStyleStore
import nl.mattix.andamp.state.RowHeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Preferences > Player > Lists: the playlist's font and the height of a row. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ListPrefsTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Context = ApplicationProvider.getApplicationContext()

    private fun ownPrefs() = app.getSharedPreferences("liststyle-${System.nanoTime()}", Context.MODE_PRIVATE)

    private fun show(prefs: android.content.SharedPreferences): () -> ListStyleStore {
        lateinit var store: ListStyleStore
        compose.setContent {
            store = remember { ListStyleStore(prefs) }
            val lists =
                ListPrefs(
                    font = store.font,
                    onFont = { store.font = it },
                    rowHeight = store.rowHeight,
                    onRowHeight = { store.rowHeight = it },
                )
            Column {
                ListFontRow(lists)
                RowHeightRow(lists)
            }
        }
        return { store }
    }

    @Test
    fun `the font starts as the skin's, and picking the regular one is remembered`() {
        val prefs = ownPrefs()
        val store = show(prefs)

        compose.onNodeWithTag("prefs.lists.font.skin").assertIsSelected()
        compose.onNodeWithTag("prefs.lists.font.regular").assertIsNotSelected()

        compose.onNodeWithTag("prefs.lists.font.regular").performClick()

        compose.onNodeWithTag("prefs.lists.font.regular").assertIsSelected()
        assertEquals(ListFont.REGULAR, store().font)
        // a second store over the same file is the next launch
        assertEquals(ListFont.REGULAR, ListStyleStore(prefs).font)

        compose.onNodeWithTag("prefs.lists.font.skin").performClick()

        compose.onNodeWithTag("prefs.lists.font.skin").assertIsSelected()
        assertEquals(ListFont.SKIN, ListStyleStore(prefs).font)
    }

    @Test
    fun `rows start classic, and a taller pick is remembered`() {
        val prefs = ownPrefs()
        val store = show(prefs)

        compose.onNodeWithTag("prefs.lists.rows.classic").assertIsSelected()

        compose.onNodeWithTag("prefs.lists.rows.large").performClick()

        compose.onNodeWithTag("prefs.lists.rows.large").assertIsSelected()
        compose.onNodeWithTag("prefs.lists.rows.classic").assertIsNotSelected()
        assertEquals(RowHeight.LARGE, store().rowHeight)
        assertEquals(RowHeight.LARGE, ListStyleStore(prefs).rowHeight)

        compose.onNodeWithTag("prefs.lists.rows.roomy").performClick()

        assertEquals(RowHeight.ROOMY, ListStyleStore(prefs).rowHeight)
        // the font is its own choice
        assertEquals(ListFont.SKIN, ListStyleStore(prefs).font)
    }

    @Test
    fun `the player row names a choice only while it is not the one the app starts with`() {
        val plain = PrefsSections().playerSummary(EnglishWords)
        assertFalse("font" in plain)
        assertFalse("rows" in plain)

        val picked =
            PrefsSections(lists = ListPrefs(font = ListFont.REGULAR, rowHeight = RowHeight.ROOMY)).playerSummary(EnglishWords)
        assertTrue("Regular font in the lists" in picked)
        assertTrue("Roomy rows" in picked)
    }

    /** Sections whose line names nothing, so it holds only what a test sets; tap assist, on by default, is named while on. */
    private val unnamed = PrefsSections(tapAssist = TapAssistPrefs(enabled = false))

    private fun line(id: Int) = EnglishWords.get(id)

    @Test
    fun `the player row names modern skins while they are on, and sharp pixels only with them`() {
        fun summary(modern: ModernSkinPrefs) = unnamed.copy(modern = modern).playerSummary(EnglishWords)
        val modernSkins = line(R.string.prefs_player_summary_modern_skins)
        val sharpPixels = line(R.string.prefs_player_summary_sharp_pixels)

        val plain = summary(ModernSkinPrefs(offered = true))
        assertFalse(modernSkins in plain)
        assertFalse(sharpPixels in plain)

        assertEquals(modernSkins, summary(ModernSkinPrefs(offered = true, enabled = true)))
        assertEquals("$modernSkins · $sharpPixels", summary(ModernSkinPrefs(offered = true, enabled = true, sharp = true)))
        assertFalse(
            "sharp pixels wait for modern skins",
            sharpPixels in summary(ModernSkinPrefs(offered = true, enabled = false, sharp = true)),
        )
        val notOffered = summary(ModernSkinPrefs(offered = false, enabled = true, sharp = true))
        assertFalse("a build that does not offer them names neither", modernSkins in notOffered || sharpPixels in notOffered)
    }

    @Test
    fun `with nothing changed, the player row names skins only in a build that offers modern skins`() {
        val offered = ModernSkinPrefs(offered = true)
        val overlay = OverlayPrefs(offered = true)
        assertEquals(line(R.string.prefs_player_summary_all_skins), unnamed.copy(modern = offered).playerSummary(EnglishWords))
        assertEquals(
            line(R.string.prefs_player_summary_all_with_overlay_skins),
            unnamed.copy(overlay = overlay, modern = offered).playerSummary(EnglishWords),
        )
        assertEquals(line(R.string.prefs_player_summary_all), unnamed.playerSummary(EnglishWords))
        assertEquals(
            line(R.string.prefs_player_summary_all_with_overlay),
            unnamed.copy(overlay = overlay).playerSummary(EnglishWords),
        )
    }
}
