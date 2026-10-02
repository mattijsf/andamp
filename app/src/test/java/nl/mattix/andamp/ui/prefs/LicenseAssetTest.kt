// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** LGPL-2.1 and the OFL ask that their text accompany what they cover, so both are in the packaged assets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LicenseAssetTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `projectM's LGPL text is packaged with the app`() {
        val text =
            app.assets
                .open(Notices.PROJECTM_LICENSE_ASSET)
                .bufferedReader()
                .use { it.readText() }

        assertTrue("the text is the LGPL", text.contains("GNU LESSER GENERAL PUBLIC LICENSE"))
        assertTrue("the text is version 2.1", text.contains("Version 2.1, February 1999"))
        assertTrue("the text is complete", text.contains("END OF TERMS AND CONDITIONS"))
    }

    @Test
    fun `Liberation Sans's OFL text is packaged with the app`() {
        val text =
            app.assets
                .open(Notices.LIBERATION_SANS_LICENSE_ASSET)
                .bufferedReader()
                .use { it.readText() }

        assertTrue("the text is the OFL", text.contains("SIL OPEN FONT LICENSE Version 1.1"))
    }
}
