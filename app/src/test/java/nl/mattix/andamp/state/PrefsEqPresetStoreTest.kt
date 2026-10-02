// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrefsEqPresetStoreTest {
    private val store = PrefsEqPresetStore(ApplicationProvider.getApplicationContext<Application>())

    @Test
    fun `save list delete round-trip through shared preferences`() {
        val a = EqPreset("Alpha", 31, List(10) { it })
        val b = EqPreset("beta", 40, List(10) { 63 - it })
        store.save(b)
        store.save(a)

        assertEquals(listOf(a, b), store.userPresets()) // case-insensitive name sort

        store.delete("Alpha")
        assertEquals(listOf(b), store.userPresets())
    }

    @Test
    fun `saving under an existing name overwrites`() {
        store.save(EqPreset("Same", 10, List(10) { 10 }))
        store.save(EqPreset("Same", 20, List(10) { 20 }))
        assertEquals(20, store.userPresets().single().preamp)
    }

    @Test
    fun `default preset is separate from the named list`() {
        assertNull(store.defaultPreset)
        val d = EqPreset("Default", 35, List(10) { 35 })
        store.defaultPreset = d
        assertEquals(d, store.defaultPreset)
        assertEquals(emptyList<EqPreset>(), store.userPresets())
    }
}
