// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The phone's palette exists from Android 12. Below that [SystemPalette.of]
 * returns null and does not throw.
 */
@RunWith(RobolectricTestRunner::class)
class SystemPaletteTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    @Config(sdk = [30])
    fun `there is no palette below Android 12`() {
        assertNull(SystemPalette.of(app, dark = true))
        assertNull(SystemPalette.of(app, dark = false))
    }

    @Test
    @Config(sdk = [35])
    fun `dark and light are two schemes from the same palette`() {
        val dark = SystemPalette.of(app, dark = true)
        val light = SystemPalette.of(app, dark = false)

        assertNotNull(dark)
        assertNotNull(light)
        assertNotEquals(dark!!.surface, light!!.surface)
    }
}
