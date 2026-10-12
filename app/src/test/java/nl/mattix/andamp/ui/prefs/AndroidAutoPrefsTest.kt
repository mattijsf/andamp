// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import nl.mattix.andamp.state.SkinEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Preferences > Android Auto: the skin the main window wears on a car's screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class AndroidAutoPrefsTest {
    @get:Rule
    val compose = createComposeRule()

    private val skins = listOf(SkinEntry("neon", "Neon Nights", 0), SkinEntry("wood", "Walnut", 0))

    private fun show(start: String? = null): () -> String? {
        var chosen by mutableStateOf(start)
        compose.setContent {
            Column { AndroidAutoPage(AndroidAutoPrefs(offered = true, skins = skins, skin = chosen, onSkin = { chosen = it })) }
        }
        return { chosen }
    }

    @Test
    fun `with nothing chosen the row says Default`() {
        show()

        compose.onNodeWithText(DEFAULT_SKIN).assertExists()
    }

    @Test
    fun `picking a skin from the list chooses it for the car`() {
        val chosen = show()

        compose.onNodeWithTag("prefs.androidauto.skin.row").performClick()
        compose.onNodeWithTag("prefs.androidauto.skin.wood").performClick()

        assertEquals("wood", chosen())
        compose.onNodeWithText("Walnut").assertExists()
    }

    @Test
    fun `the list shows which skin the car wears`() {
        show(start = "neon")

        compose.onNodeWithTag("prefs.androidauto.skin.row").performClick()

        compose.onNodeWithTag("prefs.androidauto.skin.neon").assertIsSelected()
    }

    @Test
    fun `picking Default goes back to the player's own skin`() {
        val chosen = show(start = "neon")

        compose.onNodeWithTag("prefs.androidauto.skin.row").performClick()
        compose.onNodeWithTag("prefs.androidauto.skin.default").performClick()

        assertEquals(null, chosen())
    }

    @Test
    fun `the row that opens the page names the skin`() {
        assertEquals("Wears Walnut", AndroidAutoPrefs(skins = skins, skin = "wood").summary())
        assertEquals("Wears the player's own skin", AndroidAutoPrefs(skins = skins).summary())
    }

    @Test
    fun `a chosen skin that has gone reads as the player's own`() {
        assertEquals("Wears the player's own skin", AndroidAutoPrefs(skins = skins, skin = "gone").summary())
    }
}
