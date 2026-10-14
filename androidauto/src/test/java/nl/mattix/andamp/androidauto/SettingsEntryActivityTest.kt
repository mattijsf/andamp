// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.androidauto

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.core.remotewindow.RemoteWindowLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** The "Additional settings in the app" button on this app's App info page. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsEntryActivityTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val player = RemoteWindowLink.playerOf(app.packageName)

    /** Gives the player on the phone an activity that answers [action] in [category]. */
    private fun playerAnswers(
        action: String,
        category: String,
    ) {
        val screen = ComponentName(player, "Screen$action")
        shadowOf(app.packageManager).apply {
            addActivityIfNotPresent(screen)
            addIntentFilterForActivity(screen, IntentFilter(action).apply { addCategory(category) })
        }
    }

    private fun asked(): Intent? {
        val controller =
            Robolectric
                .buildActivity(
                    SettingsEntryActivity::class.java,
                    Intent(Intent.ACTION_APPLICATION_PREFERENCES),
                ).create()
        return shadowOf(controller.get()).nextStartedActivity
    }

    @Test
    fun `it opens the player's Android Auto settings`() {
        playerAnswers(RemoteWindowLink.SETTINGS, Intent.CATEGORY_DEFAULT)

        val asked = checkNotNull(asked())

        assertEquals(RemoteWindowLink.SETTINGS, asked.action)
        assertEquals(player, asked.`package`)
    }

    /** A player from before the Android Auto settings could be opened from here. */
    @Test
    fun `a player without that page opens as from its icon`() {
        playerAnswers(Intent.ACTION_MAIN, Intent.CATEGORY_LAUNCHER)

        val asked = checkNotNull(asked())

        assertEquals(player, asked.component?.packageName ?: asked.`package`)
    }

    @Test
    fun `without a player it says to install one`() {
        assertNull(asked())
        assertEquals("These settings are in Andamp. Install Andamp on this phone first.", ShadowToast.getTextOfLatestToast())
    }
}
