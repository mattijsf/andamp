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
import nl.mattix.andamp.state.ShadeStore
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The switch is the opposite of what it stores: on means windows stay open,
 * which means shading is off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShadeSwitchTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `switching it on turns shading off in the store`() {
        val prefs = app.getSharedPreferences("shade-switch-${System.nanoTime()}", Context.MODE_PRIVATE)
        lateinit var store: ShadeStore
        compose.setContent {
            store = remember { ShadeStore(prefs) }
            ShadeRow(ShadePrefs(shadeEnabled = store.enabled, onShadeEnabled = { store.enabled = it }))
        }

        // Winamp's behavior is the default, so the switch starts off
        compose.onNodeWithTag("prefs.shade.switch").assertIsOff()
        compose.onNodeWithTag("prefs.shade.switch").performClick()
        compose.onNodeWithTag("prefs.shade.switch").assertIsOn()
        assertFalse("the store turns shading off", store.enabled)
        compose.onNodeWithTag("prefs.shade.switch").performClick()
        compose.onNodeWithTag("prefs.shade.switch").assertIsOff()
    }
}
