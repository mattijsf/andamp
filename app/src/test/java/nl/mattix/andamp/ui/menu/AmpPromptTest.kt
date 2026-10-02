// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import nl.mattix.andamp.core.model.BackendNotice
import nl.mattix.andamp.state.AmpPrompt
import nl.mattix.andamp.state.WinampState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [AmpPromptHost] draws the prompt held in `WinampState.prompt` and clears it
 * when it is answered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AmpPromptTest {
    @get:Rule val compose = createComposeRule()

    private val state = WinampState()

    @Test
    fun `an asked question is drawn`() {
        state.prompt =
            AmpPrompt(title = "Always on top", body = "why", onConfirm = {})
        compose.setContent { AmpPromptHost(state) }

        compose.onNodeWithText("Always on top").assertIsDisplayed()
    }

    @Test
    fun `nothing asked draws nothing`() {
        compose.setContent { AmpPromptHost(state) }

        compose.onAllNodesWithTag("prompt.confirm").assertCountEquals(0)
    }

    @Test
    fun `confirming does the thing and clears the question`() {
        var confirmed = 0
        state.prompt =
            AmpPrompt(title = "Always on top", body = "why", onConfirm = { confirmed++ })
        compose.setContent { AmpPromptHost(state) }

        compose.onNodeWithTag("prompt.confirm").performClick()
        compose.waitForIdle()

        assertEquals(1, confirmed)
        assertNull("an answered question is cleared", state.prompt)
    }

    @Test
    fun `a question can be put off`() {
        state.prompt =
            AmpPrompt(title = "Always on top", body = "why", onConfirm = {})
        compose.setContent { AmpPromptHost(state) }

        compose.onNodeWithText("Not now").performClick()
        compose.waitForIdle()

        assertNull("putting the question off clears it", state.prompt)
    }

    /**
     * A notice asks nothing, so it has no second button.
     */
    @Test
    fun `a notice has only its OK`() {
        state.prompt = promptFor(BackendNotice.SourceCannotPlay) { state.prompt = null }
        compose.setContent { AmpPromptHost(state) }

        compose.onNodeWithText("OK").assertIsDisplayed()
        compose.onAllNodesWithText("Not now").assertCountEquals(0)

        compose.onNodeWithTag("prompt.confirm").performClick()
        compose.waitForIdle()
        assertNull("OK clears the notice", state.prompt)
    }
}
