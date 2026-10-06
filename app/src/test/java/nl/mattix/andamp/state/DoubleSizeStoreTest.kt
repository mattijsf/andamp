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

    @Test
    fun `the windows collapsed in the stack come back, and none are to begin with`() {
        val prefs = prefs()
        assertEquals(emptySet<String>(), DoubleSizeStore(prefs).shaded)

        DoubleSizeStore(prefs).shaded = setOf("eq", "pl")

        assertEquals(setOf("eq", "pl"), DoubleSizeStore(prefs).shaded)
    }

    @Test
    fun `the plug-in window has no height of its own until its grip is dragged`() {
        assertNull(DoubleSizeStore(prefs()).visSteps)
    }

    @Test
    fun `the plug-in window's height comes back, and can be given up again`() {
        val prefs = prefs()
        DoubleSizeStore(prefs).visSteps = 21
        assertEquals(21, DoubleSizeStore(prefs).visSteps)

        DoubleSizeStore(prefs).visSteps = null
        assertNull(DoubleSizeStore(prefs).visSteps)
    }
}
