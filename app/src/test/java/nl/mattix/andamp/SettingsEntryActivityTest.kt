// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.core.remotewindow.RemoteWindowLink
import nl.mattix.andamp.ui.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The way into Preferences from the phone's App info page, and from the app Android Auto talks to. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsEntryActivityTest {
    private val app: Context = ApplicationProvider.getApplicationContext()

    /** What the activity asks for when started with [action]. */
    private fun opened(action: String): Intent {
        val controller = Robolectric.buildActivity(SettingsEntryActivity::class.java, Intent(action)).create()
        assertTrue("it finishes at once", controller.get().isFinishing)
        return checkNotNull(shadowOf(controller.get()).nextStartedActivity)
    }

    @Test
    fun `the App info page finds it`() {
        val found =
            app.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_APPLICATION_PREFERENCES).setPackage(app.packageName),
                0,
            )

        assertEquals(SettingsEntryActivity::class.java.name, found.single().activityInfo.name)
    }

    @Test
    fun `from the App info page it opens Preferences`() {
        val asked = opened(Intent.ACTION_APPLICATION_PREFERENCES)

        assertEquals(MainActivity::class.java.name, asked.component?.className)
        assertTrue(asked.getBooleanExtra(MainActivity.SHOW_THE_APP, false))
        assertEquals(Screen.PREFERENCES, asked.getStringExtra(MainActivity.GO_TO))
        assertNull(asked.getStringExtra(MainActivity.GO_TO_PAGE))
    }

    @Test
    fun `for the app Android Auto talks to it opens the Android Auto page`() {
        val asked = opened(RemoteWindowLink.SETTINGS)

        assertEquals(Screen.PREFERENCES, asked.getStringExtra(MainActivity.GO_TO))
        assertEquals("android-auto", asked.getStringExtra(MainActivity.GO_TO_PAGE))
    }
}
