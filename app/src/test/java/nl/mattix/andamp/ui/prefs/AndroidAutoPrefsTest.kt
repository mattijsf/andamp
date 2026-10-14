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
import nl.mattix.andamp.state.EnglishWords
import nl.mattix.andamp.state.PlaylistFont
import nl.mattix.andamp.state.SkinEntry
import nl.mattix.andamp.ui.remote.CompanionCheck
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Preferences > Android Auto: the skin the main window wears on a car's screen, and the font of the list. */
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

        compose.onNodeWithText("Default").assertExists()
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
    fun `the list's font is chosen apart from the skin`() {
        var font by mutableStateOf(PlaylistFont.REGULAR)
        compose.setContent {
            Column {
                AndroidAutoPage(
                    AndroidAutoPrefs(offered = true, skins = skins, listFont = font, onListFont = { font = it }),
                )
            }
        }

        compose.onNodeWithTag("prefs.androidauto.font.regular").assertIsSelected()
        compose.onNodeWithTag("prefs.androidauto.font.skin").performClick()

        assertEquals(PlaylistFont.SKIN, font)
        compose.onNodeWithTag("prefs.androidauto.font.skin").assertIsSelected()
    }

    private fun showChecked(check: CompanionCheck) {
        compose.setContent { Column { AndroidAutoPage(AndroidAutoPrefs(offered = true, skins = skins, check = { check })) } }
    }

    @Test
    fun `with everything right each check has a tick`() {
        showChecked(
            CompanionCheck(fromPlay = true, version = "0.15.0", protocol = 2, playerProtocol = 2),
        )

        compose.onNodeWithTag("prefs.androidauto.check.play.ok").assertExists()
        compose.onNodeWithTag("prefs.androidauto.check.version.ok").assertExists()
        compose.onNodeWithText("Andamp for Android Auto 0.15.0 is on this phone.").assertExists()
        compose.onNodeWithText("Compatible with this Andamp").assertExists()
    }

    @Test
    fun `each thing that is wrong is a warning that says what it means`() {
        showChecked(
            CompanionCheck(fromPlay = false, version = "0.14.0", protocol = 1, playerProtocol = 2),
        )

        compose.onNodeWithTag("prefs.androidauto.check.play.warning").assertExists()
        compose.onNodeWithText("Android Auto won't show it in a car").assertExists()
        compose.onNodeWithTag("prefs.androidauto.check.version.warning").assertExists()
        compose.onNodeWithText("Update Andamp for Android Auto").assertExists()
    }

    @Test
    fun `an app ahead of this Andamp asks for Andamp to be updated`() {
        showChecked(
            CompanionCheck(fromPlay = true, version = "0.17.0", protocol = 3, playerProtocol = 2),
        )

        compose.onNodeWithTag("prefs.androidauto.check.version.warning").assertExists()
        compose.onNodeWithText("Update Andamp").assertExists()
    }

    @Test
    fun `without the app to check the page shows no status`() {
        show()

        compose.onNodeWithText("Status").assertDoesNotExist()
    }

    @Test
    fun `the row that opens the page names the skin`() {
        assertEquals("Wears Walnut", AndroidAutoPrefs(skins = skins, skin = "wood").summary(EnglishWords))
        assertEquals("Wears the player's own skin", AndroidAutoPrefs(skins = skins).summary(EnglishWords))
    }

    @Test
    fun `a chosen skin that has gone reads as the player's own`() {
        assertEquals("Wears the player's own skin", AndroidAutoPrefs(skins = skins, skin = "gone").summary(EnglishWords))
    }
}
