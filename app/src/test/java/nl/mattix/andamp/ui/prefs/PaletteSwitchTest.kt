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
import nl.mattix.andamp.state.PaletteStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The switch moves the store, and is absent where no palette is offered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PaletteSwitchTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `switching it off turns the store off and reports the change`() {
        val prefs = app.getSharedPreferences("palette-switch-${System.nanoTime()}", Context.MODE_PRIVATE)
        lateinit var store: PaletteStore
        var changes = 0
        compose.setContent {
            store = remember { PaletteStore(prefs) }
            PaletteRow(
                PalettePrefs(enabled = store.enabled, offered = true, onEnabled = {
                    store.enabled = it
                    changes++
                }),
            )
        }

        // it starts on
        compose.onNodeWithTag("prefs.palette.switch").assertIsOn()
        compose.onNodeWithTag("prefs.palette.switch").performClick()
        compose.onNodeWithTag("prefs.palette.switch").assertIsOff()
        assertFalse("the store stops following the wallpaper", store.enabled)
        assertTrue("the change is reported once", changes == 1)
        compose.onNodeWithTag("prefs.palette.switch").performClick()
        compose.onNodeWithTag("prefs.palette.switch").assertIsOn()
    }

    @Test
    fun `a phone with no palette to offer shows no switch`() {
        compose.setContent { PaletteRow(PalettePrefs(enabled = true, offered = false)) }

        compose.onNodeWithTag("prefs.palette.switch").assertDoesNotExist()
    }
}
