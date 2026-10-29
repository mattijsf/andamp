// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import nl.mattix.andamp.modernskin.skin.SkinOptions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Skin Settings dialog: the skin's groups as headings, its options as boxes to tick. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SkinSettingsDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private fun toggle(
        name: String,
        on: Boolean,
    ) = SkinOptions.Toggle(SkinOptions.Key("{item}", name.lowercase()), name, on)

    private val options =
        SkinOptions(
            1,
            listOf(
                SkinOptions.Submenu(
                    "Winamp Modern",
                    listOf(
                        SkinOptions.Submenu("Drawers", listOf(toggle("Animate Config Drawer", false))),
                        SkinOptions.Separator,
                        toggle("Show Songticker", true),
                    ),
                ),
            ),
        )

    @Test
    fun `it shows the skin's groups under its name, ticked as the skin has them, and a tap asks for the flip`() {
        val flipped = ArrayList<SkinOptions.Key>()
        compose.setContent { SkinSettingsBody(options, { flipped += it }, {}) }

        compose.onNodeWithTag("settings.subtitle").assertExists()
        compose.onNodeWithText("Winamp Modern").assertExists()
        compose.onNodeWithTag("settings.heading.Drawers").assertExists()
        compose.onNodeWithTag("settings.option.Animate Config Drawer").assertIsOff()
        compose.onNodeWithTag("settings.option.Show Songticker").assertIsOn()

        compose.onNodeWithTag("settings.option.Animate Config Drawer").performClick()

        assertEquals(listOf(SkinOptions.Key("{item}", "animate config drawer")), flipped)
    }

    @Test
    fun `a radio option shows as picked or not, and a tap asks for the flip only when it is not picked`() {
        val flipped = ArrayList<SkinOptions.Key>()
        val radios =
            SkinOptions(
                1,
                listOf(
                    SkinOptions.Toggle(SkinOptions.Key("{item}", "top"), "Top", checked = true, radio = true),
                    SkinOptions.Toggle(SkinOptions.Key("{item}", "bottom"), "Bottom", checked = false, radio = true),
                ),
            )
        compose.setContent { SkinSettingsBody(radios, { flipped += it }, {}) }

        compose.onNodeWithTag("settings.option.Top").assertIsSelected()
        compose.onNodeWithTag("settings.option.Bottom").assertIsNotSelected()
        compose.onNodeWithTag("settings.option.Top").performClick()
        compose.onNodeWithTag("settings.option.Bottom").performClick()

        assertEquals(listOf(SkinOptions.Key("{item}", "bottom")), flipped)
    }

    @Test
    fun `a separator shows between options only, never at the ends`() {
        val rows =
            settingRows(
                SkinOptions(
                    1,
                    listOf(
                        SkinOptions.Separator,
                        toggle("A", false),
                        SkinOptions.Separator,
                        SkinOptions.Separator,
                        toggle("B", false),
                        SkinOptions.Separator,
                    ),
                ),
            ).second

        assertEquals(listOf("A", "-", "B"), rows.map { (it as? SettingRow.Option)?.toggle?.text ?: "-" })
    }
}
