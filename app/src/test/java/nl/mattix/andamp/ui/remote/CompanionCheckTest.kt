// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.remote

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.core.remotewindow.RemoteWindowLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** What Preferences > Android Auto checks about Autoamp. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CompanionCheckTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val companion = RemoteWindowLink.lenderOf(app.packageName)

    @Test
    fun `an app Google Play installed counts as from Google Play`() {
        assertTrue(CompanionCheck.fromPlay(installing = "com.android.vending", initiating = "com.android.vending"))
    }

    /** Before Android 11 the phone records only the installer. */
    @Test
    fun `an app with Google Play as its installer and nothing more known counts as from Google Play`() {
        assertTrue(CompanionCheck.fromPlay(installing = "com.android.vending", initiating = null))
    }

    @Test
    fun `an install from a computer's command line does not count, whatever installer it names`() {
        assertFalse(CompanionCheck.fromPlay(installing = "com.android.vending", initiating = "com.android.shell"))
        assertFalse(CompanionCheck.fromPlay(installing = null, initiating = "com.android.shell"))
    }

    @Test
    fun `an app another store or a file installed does not count`() {
        assertFalse(CompanionCheck.fromPlay(installing = "org.fdroid.fdroid", initiating = "org.fdroid.fdroid"))
        assertFalse(
            CompanionCheck.fromPlay(installing = "com.google.android.packageinstaller", initiating = "com.android.chrome"),
        )
    }

    private fun check(protocol: Int) =
        CompanionCheck(fromPlay = true, version = "0.16.0", playerVersion = "0.16.0", protocol = protocol, playerProtocol = 2)

    /** A release that changes nothing between the two apps leaves them matched, whatever its version. */
    @Test
    fun `the two match when they speak the same protocol`() {
        assertEquals(CompanionCheck.Match.SAME, check(protocol = 2).match)
    }

    @Test
    fun `an app that speaks an earlier protocol is behind`() {
        assertEquals(CompanionCheck.Match.BEHIND, check(protocol = 1).match)
    }

    @Test
    fun `an app that speaks a later protocol is ahead`() {
        assertEquals(CompanionCheck.Match.AHEAD, check(protocol = 3).match)
    }

    @Test
    fun `without the app on the phone there is nothing to check`() {
        assertNull(CompanionCheck.of(app))
    }

    /** Puts the app on the phone, saying it speaks [protocol], or saying nothing. */
    private fun install(protocol: Int?) {
        val packages = shadowOf(app.packageManager)
        packages.installPackage(
            PackageInfo().apply {
                packageName = companion
                versionName = "0.14.0"
                applicationInfo =
                    ApplicationInfo().apply {
                        packageName = companion
                        metaData = protocol?.let { Bundle().apply { putInt(RemoteWindowLink.PROTOCOL_KEY, it) } }
                    }
            },
        )
        packages.setInstallSourceInfo(companion, "com.android.vending", "com.android.vending")
    }

    @Test
    fun `the app on the phone is checked as the phone records it`() {
        install(protocol = 2)

        val check = checkNotNull(CompanionCheck.of(app))

        assertTrue(check.fromPlay)
        assertEquals("0.14.0", check.version)
        assertEquals(2, check.protocol)
    }

    /** Releases before the protocol was written down speak the first. */
    @Test
    fun `an app that does not say which protocol it speaks speaks the first`() {
        install(protocol = null)

        assertEquals(RemoteWindowLink.FIRST_PROTOCOL, CompanionCheck.of(app)?.protocol)
    }
}
