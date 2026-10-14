// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.androidauto

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.core.remotewindow.RemoteWindowLink
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What this app tells the player about the interface it speaks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProtocolTest {
    private val app: Context = ApplicationProvider.getApplicationContext()

    /** The player reads the manifest's entry; the code speaks the protocol this module was built with. */
    @Test
    fun `the manifest says the protocol this app speaks`() {
        val info = app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA)

        assertEquals(RemoteWindowLink.PROTOCOL, info.metaData?.getInt(RemoteWindowLink.PROTOCOL_KEY))
    }
}
