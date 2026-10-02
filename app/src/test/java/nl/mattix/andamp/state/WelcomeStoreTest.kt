// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The welcome screen is shown once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WelcomeStoreTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `a fresh install has not seen it`() {
        assertFalse(WelcomeStore(app.getSharedPreferences("welcome-${System.nanoTime()}", 0)).seen)
    }

    @Test
    fun `having seen it survives a restart`() {
        val prefs = app.getSharedPreferences("welcome-restart", 0)
        WelcomeStore(prefs).seen = true

        assertTrue(WelcomeStore(prefs).seen)
    }

    /** An install updated from a build without the screen has no answer stored, which reads as unseen. */
    @Test
    fun `an install that predates the screen still gets its turn`() {
        val prefs = app.getSharedPreferences("welcome-updated-${System.nanoTime()}", 0)
        // other keys, and no answer for the welcome screen
        prefs
            .edit()
            .putString("skin", "andamp-dark")
            .putInt("version", 3)
            .apply()

        assertFalse(WelcomeStore(prefs).seen)
    }

    @Test
    fun `an answer already given survives an update`() {
        val prefs = app.getSharedPreferences("welcome-answered-${System.nanoTime()}", 0)
        WelcomeStore(prefs).seen = true
        prefs.edit().putInt("version", 4).apply()

        assertTrue(WelcomeStore(prefs).seen)
    }
}
