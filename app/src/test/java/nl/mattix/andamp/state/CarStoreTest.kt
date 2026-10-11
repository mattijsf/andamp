// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The skin chosen for the car's screen: kept, and given up when the skin goes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CarStoreTest {
    private val app: Context = ApplicationProvider.getApplicationContext()

    private fun prefs(): SharedPreferences = app.getSharedPreferences("car-${System.nanoTime()}", Context.MODE_PRIVATE)

    @Test
    fun `with nothing chosen the car wears the player's own skin`() {
        val store = CarStore(prefs())

        assertNull(store.skin)
        assertNull(store.worn)
    }

    @Test
    fun `a chosen skin is kept`() {
        val kept = prefs()
        CarStore(kept).skin = "neon"

        assertEquals("neon", CarStore(kept).skin)
        assertEquals("neon", CarStore(kept).worn)
    }

    @Test
    fun `choosing Default again forgets the skin`() {
        val kept = prefs()
        CarStore(kept).skin = "neon"

        CarStore(kept).skin = null

        assertNull(CarStore(kept).skin)
    }

    @Test
    fun `a chosen skin that is no longer installed is not worn`() {
        val store = CarStore(prefs(), installed = { false })
        store.skin = "neon"

        assertNull(store.worn)
        assertEquals("the choice itself is kept", "neon", store.skin)
    }

    @Test
    fun `a skin that leaves the library is given up`() {
        val kept = prefs()
        val store = CarStore(kept)
        store.skin = "neon"

        store.forgetSkin("neon")

        assertNull(store.skin)
        assertNull(CarStore(kept).skin)
    }

    @Test
    fun `another skin leaving the library changes nothing`() {
        val store = CarStore(prefs())
        store.skin = "neon"

        store.forgetSkin("other")

        assertEquals("neon", store.skin)
    }

    @Test
    fun `a phone without the car's app is not offered its settings`() {
        assertEquals(false, CarStore.companionInstalled(app))
    }
}
