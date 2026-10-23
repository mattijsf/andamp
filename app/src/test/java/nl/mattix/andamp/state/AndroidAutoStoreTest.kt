// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The skin chosen for the car's screen: kept, and given up when the skin goes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidAutoStoreTest {
    private val app: Context = ApplicationProvider.getApplicationContext()

    private fun prefs(): SharedPreferences = app.getSharedPreferences("android-auto-${System.nanoTime()}", Context.MODE_PRIVATE)

    @Test
    fun `with nothing chosen the car wears the player's own skin`() {
        val store = AndroidAutoStore(prefs())

        assertNull(store.skin)
        assertNull(store.worn)
    }

    @Test
    fun `a chosen skin is kept`() {
        val kept = prefs()
        AndroidAutoStore(kept).skin = "neon"

        assertEquals("neon", AndroidAutoStore(kept).skin)
        assertEquals("neon", AndroidAutoStore(kept).worn)
    }

    @Test
    fun `choosing Default again forgets the skin`() {
        val kept = prefs()
        AndroidAutoStore(kept).skin = "neon"

        AndroidAutoStore(kept).skin = null

        assertNull(AndroidAutoStore(kept).skin)
    }

    @Test
    fun `a chosen skin that is no longer installed is not worn`() {
        val store = AndroidAutoStore(prefs(), installed = { false })
        store.skin = "neon"

        assertNull(store.worn)
        assertEquals("the choice itself is kept", "neon", store.skin)
    }

    /** Through the library, as the app builds it: a modern skin is never worn in the car. */
    @Test
    fun `a modern skin chosen for the car is not worn and the car follows the player`() {
        val library = SkinLibrary(app)
        val modern = library.addModern(app, "Bento")
        val classic = library.save(zipOf("main.bmp"), "Classic.wsz").id
        val store = AndroidAutoStore(app)

        store.skin = modern
        assertNull(store.worn)

        store.skin = classic
        assertEquals(classic, store.worn)
    }

    @Test
    fun `a skin that leaves the library is given up`() {
        val kept = prefs()
        val store = AndroidAutoStore(kept)
        store.skin = "neon"

        store.forgetSkin("neon")

        assertNull(store.skin)
        assertNull(AndroidAutoStore(kept).skin)
    }

    @Test
    fun `another skin leaving the library changes nothing`() {
        val store = AndroidAutoStore(prefs())
        store.skin = "neon"

        store.forgetSkin("other")

        assertEquals("neon", store.skin)
    }

    @Test
    fun `the window fills the screen until it is told not to`() {
        assertEquals(true, AndroidAutoStore(prefs()).fill)
    }

    @Test
    fun `filling the screen is kept`() {
        val kept = prefs()
        AndroidAutoStore(kept).fill = false

        assertEquals(false, AndroidAutoStore(kept).fill)
    }

    @Test
    fun `without the Android Auto app on the phone the settings are not offered`() {
        assertEquals(false, AndroidAutoStore.companionInstalled(app))
    }

    /** The app is the one whose id is this player's with a word after it. */
    @Test
    fun `with the Android Auto app on the phone the settings are offered`() {
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = "${app.packageName}.androidauto" })

        assertEquals(true, AndroidAutoStore.companionInstalled(app))
    }

    @Test
    fun `the list in the car keeps the regular font until the skin's is chosen`() {
        val kept = prefs()

        assertEquals(ListFont.REGULAR, AndroidAutoStore(kept).listFont)
        AndroidAutoStore(kept).listFont = ListFont.SKIN

        assertEquals(ListFont.SKIN, AndroidAutoStore(kept).listFont)
    }
}
