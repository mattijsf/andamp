// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.welcome

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The first launch asks which skin to wear and takes only an answer: a press
 * on the scrim does not choose for the listener.
 *
 * Tested with no activity in the context, where
 * [nl.mattix.andamp.ui.menu.AmpDialog] draws the modal into the composition
 * and the press outside is a press on this app's own Box. With an activity the
 * same decision goes to `DialogProperties`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // the card draws a real skin
@Config(sdk = [35], qualifiers = "w411dp-h1600dp-mdpi")
class WelcomeInsistsTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Context = ApplicationProvider.getApplicationContext()

    private var kept = 0
    private var browsed = 0

    private fun show() {
        // no activity in the context, as in the floating player, where the
        // scrim is drawn by the app
        compose.setContent {
            CompositionLocalProvider(LocalContext provides app) {
                WelcomeDialog(WelcomeChoice(onKeep = { kept++ }, onBrowse = { browsed++ }))
            }
        }
    }

    @Test
    fun `a press outside the card does not answer it`() {
        show()

        // the top-left corner is scrim, far from the card in the middle
        compose.onRoot().performTouchInput {
            down(Offset(2f, 2f))
            up()
        }
        compose.waitForIdle()

        compose.onNodeWithTag("welcome.andamp", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("welcome.museum", useUnmergedTree = true).assertIsDisplayed()
        assertEquals("a press outside does not choose to keep", 0, kept)
        assertEquals("a press outside does not choose to browse", 0, browsed)
    }

    @Test
    fun `the keep button answers it`() {
        show()

        compose.onNodeWithTag("welcome.keep", useUnmergedTree = true).performClick()

        assertEquals(1, kept)
        assertEquals(0, browsed)
    }
}
