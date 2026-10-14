// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.remote

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.core.remotewindow.RemoteWindowLink
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the player tells Autoamp about the window's interface it speaks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerProtocolTest {
    private val app: Context = ApplicationProvider.getApplicationContext()

    /** Autoamp reads the manifest's entry; the code speaks the protocol this build was made with. */
    @Test
    fun `the manifest says the protocol the player speaks`() {
        val info = app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA)

        assertEquals(RemoteWindowLink.PROTOCOL, RemoteWindowLink.protocolOf(info))
    }
}
