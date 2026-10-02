// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.state.VolumeModeStore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The switch moves when it is pressed.
 *
 * [VolumeModeStore] is the subject here, not [VolumeRow]: SharedPreferences is
 * not snapshot state, so the store has to hold its value in one for the switch
 * to follow a write.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VolumeSwitchTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `pressing it moves it`() {
        val prefs = app.getSharedPreferences("volume-switch-${System.nanoTime()}", Context.MODE_PRIVATE)
        compose.setContent {
            val store = remember { VolumeModeStore(prefs) }
            VolumeRow(VolumePrefs(mode = store.mode, offered = true, onMode = { store.mode = it }))
        }

        compose.onNodeWithTag("prefs.volume.switch").assertIsOff()
        compose.onNodeWithTag("prefs.volume.switch").performClick()
        compose.onNodeWithTag("prefs.volume.switch").assertIsOn()
        compose.onNodeWithTag("prefs.volume.switch").performClick()
        compose.onNodeWithTag("prefs.volume.switch").assertIsOff()
    }
}
