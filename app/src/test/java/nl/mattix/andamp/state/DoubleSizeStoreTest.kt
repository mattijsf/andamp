// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Whether the player fills the screen, and who starts with it on.
 *
 * A fresh install does. An install that was already in use when this setting arrived has
 * nothing stored either, and keeps the player the size it was. A new install that Android
 * hands an older install's backup is still a new install.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DoubleSizeStoreTest {
    private val app: Context = ApplicationProvider.getApplicationContext()

    private fun prefs(): SharedPreferences =
        app.getSharedPreferences("doublesize-test-${System.nanoTime()}", Context.MODE_PRIVATE)

    @Test
    fun `a fresh install starts with the player filling the screen`() {
        assertTrue(DoubleSizeStore(prefs()) { true }.on)
    }

    @Test
    fun `an install that was already in use keeps its floating player`() {
        assertFalse(DoubleSizeStore(prefs()) { false }.on)
    }

    @Test
    fun `which of the two it is, is asked once and remembered`() {
        val prefs = prefs()
        var asked = 0
        DoubleSizeStore(prefs) {
            asked++
            true
        }

        // by the second launch the install is no longer fresh
        val later =
            DoubleSizeStore(prefs) {
                asked++
                false
            }

        assertTrue("the first answer stands", later.on)
        assertEquals(1, asked)
    }

    @Test
    fun `what the listener switched it to is what comes back`() {
        val prefs = prefs()
        DoubleSizeStore(prefs) { true }.toggle()

        assertFalse(DoubleSizeStore(prefs) { true }.on)
    }

    private fun installedAt(
        first: Long,
        updated: Long,
    ) {
        val installed = shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName)
        installed.firstInstallTime = first
        installed.lastUpdateTime = updated
    }

    @Test
    fun `an app that was installed as this build has never been updated`() {
        installedAt(first = 1_000, updated = 1_000)

        assertTrue(app.neverUpdated())
    }

    @Test
    fun `an app that was updated to this build was already in use`() {
        installedAt(first = 1_000, updated = 9_000)

        assertFalse(app.neverUpdated())
    }

    @Test
    fun `the plug-in window has no height of its own until its grip is dragged`() {
        assertNull(DoubleSizeStore(prefs()) { true }.visSteps)
    }

    @Test
    fun `the plug-in window's height comes back, and can be given up again`() {
        val prefs = prefs()
        DoubleSizeStore(prefs) { true }.visSteps = 21
        assertEquals(21, DoubleSizeStore(prefs) { true }.visSteps)

        DoubleSizeStore(prefs) { true }.visSteps = null
        assertNull(DoubleSizeStore(prefs) { true }.visSteps)
    }
}
