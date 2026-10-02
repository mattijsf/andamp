// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.core.model.VolumeMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The one volume setting that outlives a launch. Nothing stored means [VolumeMode.DEVICE]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VolumeModeStoreTest {
    private val app: Context = ApplicationProvider.getApplicationContext()

    private fun store() = VolumeModeStore(app.getSharedPreferences("volume-test-${System.nanoTime()}", Context.MODE_PRIVATE))

    @Test
    fun `nothing stored means the device's volume`() {
        assertEquals(VolumeMode.DEVICE, store().mode)
    }

    @Test
    fun `what was chosen is what comes back`() {
        val prefs = app.getSharedPreferences("volume-kept", Context.MODE_PRIVATE)
        VolumeModeStore(prefs).mode = VolumeMode.APP

        assertEquals("a second launch reads the same file", VolumeMode.APP, VolumeModeStore(prefs).mode)
    }

    @Test
    fun `a name this version does not know falls back to the device's volume`() {
        // a downgrade or an edited file can leave a name that enum valueOf throws on
        val prefs = app.getSharedPreferences("volume-junk", Context.MODE_PRIVATE)
        prefs.edit().putString("mode", "QUADRAPHONIC").apply()

        assertEquals(VolumeMode.DEVICE, VolumeModeStore(prefs).mode)
    }
}
