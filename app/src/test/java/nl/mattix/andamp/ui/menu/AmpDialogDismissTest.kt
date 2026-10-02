// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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
 * [AmpDialog] with and without a dismiss.
 *
 * Most modals close on a press beside them. A caller passes `null` for the
 * dismiss when the modal must be answered with its own buttons.
 *
 * Tested with no activity in the context, where the scrim is this app's own
 * Box and the press outside is handled in [AmpDialog]. With an activity the
 * same decision goes to `DialogProperties`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class AmpDialogDismissTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Context = ApplicationProvider.getApplicationContext()

    /** No activity in the context, as in the floating player. */
    private fun floating(content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalContext provides app) { content() }
        }
    }

    private fun pressBesideIt() =
        compose.onRoot().performTouchInput {
            down(Offset(2f, 2f))
            up()
        }

    @Test
    fun `a modal that can be dismissed closes on a press beside it`() {
        var dismissed = 0
        floating { AmpDialog(onDismiss = { dismissed++ }) { Text("body") } }

        pressBesideIt()
        compose.waitForIdle()

        assertEquals("a press beside the modal dismisses it once", 1, dismissed)
    }

    @Test
    fun `a modal with no dismiss stays up on a press beside it`() {
        floating { AmpDialog(onDismiss = null) { Text("body") } }

        pressBesideIt()
        compose.waitForIdle()

        compose.onNodeWithText("body").assertIsDisplayed()
        assertEquals("only the card is selectable", CARD_ONLY, selectables())
    }

    @Test
    fun `the scrim is selectable only when it dismisses`() {
        floating { AmpDialog(onDismiss = {}) { Text("body") } }

        assertEquals(CARD_AND_SCRIM, selectables())
    }

    /**
     * How many nodes on screen are selectable.
     *
     * The card carries a selectable that does nothing, so a press on the card
     * does not fall through to the scrim behind it. The scrim is the second.
     */
    private fun selectables() = compose.onAllNodes(isSelectable()).fetchSemanticsNodes().size

    private companion object {
        const val CARD_ONLY = 1
        const val CARD_AND_SCRIM = 2
    }
}
