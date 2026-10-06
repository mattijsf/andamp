// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import nl.mattix.andamp.state.Tip
import nl.mattix.andamp.state.TipNote
import nl.mattix.andamp.state.TipShelf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Preferences > Support Andamp: the tips the store sells, and what the page says about one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SupportPageTest {
    @get:Rule
    val compose = createComposeRule()

    private val small = Tip("tip_small", "Small tip", "€2.00", 2_000_000)
    private val large = Tip("tip_large", "Large tip", "€10.00", 10_000_000)

    private fun show(prefs: SupportPrefs) = compose.setContent { Column { SupportPage(prefs) } }

    @Test
    fun `opening the page asks the store, once`() {
        var shown = 0

        show(SupportPrefs(onShown = { shown++ }))
        compose.waitForIdle()

        assertEquals(1, shown)
    }

    @Test
    fun `each tip is a button with the store's name and price, and tapping one gives it`() {
        val given = mutableListOf<Tip>()
        show(SupportPrefs(shelf = TipShelf.Open(listOf(small, large)), onGive = { given += it }))

        compose.onNodeWithText("Small tip").assertIsDisplayed()
        compose.onNodeWithText("€2.00").assertIsDisplayed()
        compose.onNodeWithText("€10.00").assertIsDisplayed()

        compose.onNodeWithTag("prefs.support.tip.tip_large").performClick()

        assertEquals(listOf(large), given)
    }

    @Test
    fun `without a store the page says tips are not available`() {
        show(SupportPrefs(shelf = TipShelf.Closed))

        compose.onNodeWithTag("prefs.support.closed").assertIsDisplayed()
        compose.onNodeWithTag("prefs.support.note").assertDoesNotExist()
    }

    @Test
    fun `while the store has not answered the page waits`() {
        show(SupportPrefs(shelf = TipShelf.Loading))

        compose.onNodeWithTag("prefs.support.loading").assertIsDisplayed()
    }

    @Test
    fun `a tip that arrived is thanked for`() {
        show(SupportPrefs(shelf = TipShelf.Open(listOf(small)), note = TipNote.THANKS, given = true))

        compose.onNodeWithText("Thank you! Your tip arrived.").assertIsDisplayed()
    }

    @Test
    fun `a tip that did not go through is not thanked for`() {
        show(SupportPrefs(shelf = TipShelf.Open(listOf(small)), note = TipNote.FAILED))

        compose.onNodeWithText("did not go through", substring = true).assertIsDisplayed()
    }

    @Test
    fun `someone who gave before is thanked on a later visit`() {
        show(SupportPrefs(shelf = TipShelf.Open(listOf(small)), given = true))

        compose.onNodeWithText("You have supported Andamp. Thank you.").assertIsDisplayed()
        assertEquals("Thank you for your tip", SupportPrefs(given = true).summary())
        assertEquals("Leave a tip", SupportPrefs().summary())
    }
}
