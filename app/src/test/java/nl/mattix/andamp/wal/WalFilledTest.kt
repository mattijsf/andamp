// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A Double Size stored for the app's windows is read as a classic skin filling the screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WalFilledTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun old() = app.getSharedPreferences(WalFilled.OLD_PREFS, Context.MODE_PRIVATE)

    private fun mine() = app.getSharedPreferences(WalSettings.PREFS, Context.MODE_PRIVATE)

    @Before
    fun clear() {
        old().edit().clear().commit()
        mine().edit().clear().commit()
        WalFilled.forget(app)
    }

    @After
    fun forget() {
        WalFilled.forget()
    }

    @Test
    fun `a Double Size stored on fills the screen, and is then removed`() {
        old().edit().putBoolean(WalFilled.OLD_ON, true).commit()

        WalFilled.read(app)

        assertTrue(WalFilled.on)
        assertTrue(mine().getBoolean(WalFilled.KEY, false))
        assertFalse(old().contains(WalFilled.OLD_ON))
    }

    @Test
    fun `a Double Size stored off leaves the windows apart`() {
        old().edit().putBoolean(WalFilled.OLD_ON, false).commit()

        WalFilled.read(app)

        assertFalse(WalFilled.on)
        assertFalse(old().contains(WalFilled.OLD_ON))
    }

    @Test
    fun `a fill already set wins over the stored Double Size`() {
        mine().edit().putBoolean(WalFilled.KEY, false).commit()
        old().edit().putBoolean(WalFilled.OLD_ON, true).commit()

        WalFilled.read(app)

        assertEquals(false, WalFilled.on)
    }

    @Test
    fun `with nothing stored the windows are apart`() {
        WalFilled.read(app)

        assertFalse(WalFilled.on)
        assertFalse(mine().contains(WalFilled.KEY))
    }
}
