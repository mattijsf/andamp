// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import android.content.Context
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.state.TapAssistStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The switch that turns holding a small control into a magnified view. It is
 * on by default.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TapAssistSwitchTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Context = ApplicationProvider.getApplicationContext()

    private fun ownPrefs() = app.getSharedPreferences("tapassist-${System.nanoTime()}", Context.MODE_PRIVATE)

    @Test
    fun `it starts on, and switching it off is remembered`() {
        val prefs = ownPrefs()
        lateinit var store: TapAssistStore
        compose.setContent {
            store = remember { TapAssistStore(prefs) }
            TapAssistRow(TapAssistPrefs(enabled = store.enabled, onEnabled = { store.enabled = it }))
        }

        compose.onNodeWithTag("prefs.tapassist.switch").assertIsOn()

        compose.onNodeWithTag("prefs.tapassist.switch").performClick()

        compose.onNodeWithTag("prefs.tapassist.switch").assertIsOff()
        assertFalse("the store turns tap assist off", store.enabled)
        // a second store over the same file is the next launch
        assertFalse("the setting is persisted", TapAssistStore(prefs).enabled)

        compose.onNodeWithTag("prefs.tapassist.switch").performClick()

        compose.onNodeWithTag("prefs.tapassist.switch").assertIsOn()
        assertTrue(TapAssistStore(prefs).enabled)
    }
}
