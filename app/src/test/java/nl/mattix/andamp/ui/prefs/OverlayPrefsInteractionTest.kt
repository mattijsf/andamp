// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The floating player's row. The switch and the permission are separate:
 * asking for the window is not the same as being allowed to draw one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OverlayPrefsInteractionTest {
    @get:Rule val compose = createComposeRule()

    private var wanted = false
    private var grants = 0

    private fun show(
        permitted: Boolean,
        on: Boolean = false,
        cap: Float? = null,
        offered: Boolean = true,
    ) {
        wanted = on
        compose.setContent {
            // in a Column, as the page composes it: the row is a run of
            // siblings, and without one they stack at the same origin
            androidx.compose.foundation.layout.Column {
                OverlayRow(
                    OverlayPrefs(
                        offered = offered,
                        wanted = wanted,
                        permitted = permitted,
                        onWant = { wanted = it },
                        onGrant = { grants++ },
                        summary = "whatever the ops says",
                        systemCap = cap,
                    ),
                )
            }
        }
    }

    private fun switch() = compose.onNodeWithTag("prefs.overlay.switch", useUnmergedTree = true)

    @Test
    fun `switching it on asks for it`() {
        show(permitted = true)

        switch().performClick()

        assertEquals(true, wanted)
        assertEquals("with permission granted, nothing is asked", 0, grants)
    }

    @Test
    fun `switching it on without permission sets the wish and opens no settings`() {
        show(permitted = false)

        switch().performClick()

        // the row only passes the wish on; turning it on or explaining a
        // missing permission belongs to OverlayOps
        assertEquals(true, wanted)
        assertEquals("the row opens no settings", 0, grants)
    }

    @Test
    fun `switching it off never asks for permission`() {
        show(permitted = false, on = true)

        switch().performClick()

        assertEquals(false, wanted)
        assertEquals(0, grants)
    }

    @Test
    fun `a wanted overlay with no permission offers a way to grant it`() {
        show(permitted = false, on = true)

        compose.onNodeWithTag("prefs.overlay.grant", useUnmergedTree = true).assertIsDisplayed().performClick()

        assertEquals(1, grants)
    }

    @Test
    fun `a permitted overlay shows no grant button`() {
        show(permitted = true, on = true)

        compose.onAllNodesWithTag("prefs.overlay.grant", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `a switch that is on shows it`() {
        show(permitted = true, on = true)

        switch().assertIsOn()
    }

    @Test
    fun `a switch that is off shows it`() {
        show(permitted = true, on = false)

        switch().assertIsOff()
    }

    @Test
    fun `a reported system cap earns a note, with its own number`() {
        show(permitted = true, on = true, cap = 0.8f)

        compose
            .onNodeWithTag("prefs.overlay.capnote", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextContains("80%", substring = true)
    }

    @Test
    fun `no established cap, no warning about one`() {
        show(permitted = true, on = true, cap = null)

        compose.onAllNodesWithTag("prefs.overlay.capnote", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `the row is called what Winamp called it`() {
        show(permitted = true, on = true)

        // the clutter bar's A, the permission prompt and this row share one name
        compose.onNodeWithText("Always on top").assertIsDisplayed()
    }

    @Test
    fun `a phone that cannot show a floating player has no row`() {
        show(permitted = true, on = true, cap = 0.8f, offered = false)

        compose.onAllNodesWithText("Always on top").assertCountEquals(0)
        compose.onAllNodesWithTag("prefs.overlay.switch", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag("prefs.overlay.capnote", useUnmergedTree = true).assertCountEquals(0)
    }
}
