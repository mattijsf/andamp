// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Whether the player fills the screen, and what the stack it is then held in remembers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DoubleSizeStoreTest {
    private val app: Context = ApplicationProvider.getApplicationContext()

    private fun prefs(): SharedPreferences =
        app.getSharedPreferences("doublesize-test-${System.nanoTime()}", Context.MODE_PRIVATE)

    @Test
    fun `the player floats until the listener asks for double size`() {
        assertFalse(DoubleSizeStore(prefs()).on)
    }

    @Test
    fun `what the listener switched it to is what comes back`() {
        val prefs = prefs()
        DoubleSizeStore(prefs).toggle()
        assertTrue(DoubleSizeStore(prefs).on)

        DoubleSizeStore(prefs).toggle()
        assertFalse(DoubleSizeStore(prefs).on)
    }

    @Test
    fun `whoever lights the D is told the setting at once and on every change`() {
        val told = mutableListOf<Boolean>()
        val store = DoubleSizeStore(prefs(), mirror = { told += it })

        store.toggle()
        store.on = false

        assertEquals(listOf(false, true, false), told)
    }
}
