// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.remote

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.security.MessageDigest

/**
 * Who the main window is lent to: this app's own signer, and the key of the releases page.
 *
 * Robolectric answers a comparison of two apps' signatures the same way whoever they are, so
 * the first rule is only checked here for this app itself. `LendToPlayerOnDeviceTest` in
 * `:androidauto` passes through it on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteWindowTrustTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val releasesCertificate = byteArrayOf(1, 2, 3, 4)
    private val trust = RemoteWindowTrust(app, released = sha256(releasesCertificate))

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    /** Puts an app on the phone under [uid], signed with [certificate]. */
    private fun install(
        name: String,
        uid: Int,
        certificate: ByteArray,
    ) {
        val signed = SigningInfo()
        shadowOf(signed).setSignatures(arrayOf(Signature(certificate)))
        val packages = shadowOf(app.packageManager)
        packages.installPackage(
            PackageInfo().apply {
                packageName = name
                signingInfo = signed
            },
        )
        packages.setPackagesForUid(uid, name)
    }

    @Test
    fun `this app may borrow its own window`() {
        assertTrue(trust.trusts(Process.myUid()))
    }

    @Test
    fun `an app signed with the releases page's key may borrow it`() {
        install("nl.mattix.andamp.androidauto", uid = 10_123, certificate = releasesCertificate)

        assertTrue(trust.releasesSigner(10_123))
        assertTrue(trust.trusts(10_123))
    }

    @Test
    fun `an app signed with another key is not taken for it`() {
        install("com.example.other", uid = 10_124, certificate = byteArrayOf(9, 9, 9))

        assertFalse(trust.releasesSigner(10_124))
    }

    @Test
    fun `a caller that is no app on this phone is not taken for it`() {
        assertFalse(trust.releasesSigner(10_999))
    }

    /** The workflow that signs the releases checks them against the same fingerprint. */
    @Test
    fun `the releases page's key is the one the release workflow requires`() {
        val hex = RemoteWindowTrust.RELEASES_KEY.joinToString("") { "%02x".format(it) }

        assertEquals("3ecaa6978a708ed3799433ad000b214ad7c389f8e22ab4bb908e67f80cd7a4ce", hex)
    }
}
