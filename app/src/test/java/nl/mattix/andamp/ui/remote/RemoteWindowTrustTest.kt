// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.remote

import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.core.remotewindow.RemoteWindowLink
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Who the main window is lent to: this app's signer, and the app Android Auto talks to
 * that belongs with this player, whatever key signed it.
 *
 * Robolectric answers a comparison of two apps' signatures the same way whoever they are, so
 * the first rule is only checked here for this app itself. `LendToPlayerOnDeviceTest` in
 * `:androidauto` passes through it on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteWindowTrustTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val trust = RemoteWindowTrust(app)

    @Test
    fun `this app may borrow its own window`() {
        assertTrue(trust.trusts(Process.myUid()))
    }

    @Test
    fun `the app Android Auto talks to that belongs with this player may borrow it`() {
        shadowOf(app.packageManager).setPackagesForUid(10_123, RemoteWindowLink.lenderOf(app.packageName))

        assertTrue(trust.companion(10_123))
        assertTrue(trust.trusts(10_123))
    }

    @Test
    fun `another app is not taken for it`() {
        shadowOf(app.packageManager).setPackagesForUid(10_124, "com.example.other")

        assertFalse(trust.companion(10_124))
    }

    /** A build with a second id has a separate companion, which lends to that build alone. */
    @Test
    fun `the app that belongs with another player is not taken for it`() {
        shadowOf(app.packageManager).setPackagesForUid(10_125, RemoteWindowLink.lenderOf(app.packageName + ".other"))

        assertFalse(trust.companion(10_125))
    }

    @Test
    fun `a caller that is no app on this phone is not taken for it`() {
        assertFalse(trust.companion(10_999))
    }
}
