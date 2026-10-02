// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The icon in the app list, and the listener's choice over it: [PackLauncherEntry.restore]
 * runs on every start of the pack and must not bring back an icon the listener hid.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PackLauncherEntryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val component = ComponentName(context, ALIAS)

    private fun entry() = PackLauncherEntry(context, ALIAS)

    private fun state() = context.packageManager.getComponentEnabledSetting(component)

    @Test
    fun `the alias starts off, as its manifest declares`() {
        assertFalse(entry().shown)
    }

    @Test
    fun `a first run puts the icon in the app list`() {
        entry().restore()

        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, state())
        assertTrue(entry().shown)
    }

    @Test
    fun `hiding takes the icon away and showing puts it back`() {
        val entry = entry()
        entry.restore()

        entry.show(false)
        assertFalse(entry.shown)
        assertTrue(entry.hidden)

        entry.show(true)
        assertTrue(entry.shown)
        assertFalse(entry.hidden)
    }

    @Test
    fun `the choice outlives the object that made it`() {
        entry().show(false)

        assertTrue("a new instance reads the same preferences", entry().hidden)
    }

    /** A new start after the listener hid the icon leaves the alias off. */
    @Test
    fun `a run after the listener hid the icon does not bring it back`() {
        entry().restore()
        entry().show(false)

        entry().restore()

        assertFalse(entry().shown)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state())
    }

    private companion object {
        const val ALIAS = "nl.mattix.andamp.pack.common.test.LauncherEntry"
    }
}
