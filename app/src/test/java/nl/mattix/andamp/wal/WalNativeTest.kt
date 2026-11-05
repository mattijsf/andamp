// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The native engine switch: off unless switched on, a change stored, and a stored value read once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WalNativeTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("wal_skins", Context.MODE_PRIVATE)

    @Before
    fun clear() {
        WalNative.forget()
        prefs.edit().clear().commit()
    }

    @After
    fun forget() = WalNative.forget()

    @Test
    fun `off by default`() {
        WalNative.read(app)
        assertFalse(WalNative.on)
    }

    @Test
    fun `a change is stored, and read back`() {
        WalNative.read(app)
        WalNative.change(app, true)
        assertTrue(WalNative.on)
        assertTrue(prefs.getBoolean("native_engine", false))

        WalNative.forget()
        WalNative.read(app)
        assertTrue("the stored value", WalNative.on)
    }

    @Test
    fun `a read after a change keeps the change`() {
        WalNative.change(app, true)
        prefs.edit().putBoolean("native_engine", false).commit()
        WalNative.read(app)
        assertTrue(WalNative.on)
    }
}
