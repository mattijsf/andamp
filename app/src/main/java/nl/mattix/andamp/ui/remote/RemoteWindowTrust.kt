// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.remote

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import java.security.MessageDigest

/**
 * Who may borrow the main window.
 *
 * The window shows what is playing and takes taps, so it is lent to two kinds of caller only.
 * One is signed with the key this app is signed with, which includes this app itself. The
 * other is signed with [released], the key of the APKs on the releases page: an Andamp from
 * Google Play carries a key only Google holds, which the app that brings it the car's screen
 * cannot carry.
 *
 * The second kind is recognized from Android 9; before that, only the first.
 */
internal class RemoteWindowTrust(
    private val context: Context,
    /** The SHA-256 of the certificate of the releases page's key. */
    private val released: ByteArray = RELEASES_KEY,
) {
    /** Whether the app running as [uid] may borrow the window. */
    fun trusts(uid: Int): Boolean = ownSigner(uid) || releasesSigner(uid)

    /** Whether the app running as [uid] is signed with the key this app is signed with. */
    internal fun ownSigner(uid: Int): Boolean =
        context.packageManager.checkSignatures(Process.myUid(), uid) == PackageManager.SIGNATURE_MATCH

    /** Whether an app running as [uid] is signed with the releases page's key. */
    internal fun releasesSigner(uid: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val packages = context.packageManager
        return packages.getPackagesForUid(uid).orEmpty().any { name ->
            val signed =
                runCatching { packages.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo }
                    .getOrNull()
            certificatesOf(signed).any { sha256(it.toByteArray()).contentEquals(released) }
        }
    }

    /**
     * The certificates an app is signed with: its signers when there are several, otherwise
     * its key and the keys that key was rotated from.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun certificatesOf(signed: SigningInfo?): List<Signature> =
        when {
            signed == null -> emptyList()
            signed.hasMultipleSigners() -> signed.apkContentsSigners.orEmpty().toList()
            else -> signed.signingCertificateHistory.orEmpty().toList()
        }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    companion object {
        /**
         * The certificate `.github/workflows/apk.yml` requires of every APK it attaches to a
         * release, as bytes.
         */
        val RELEASES_KEY: ByteArray =
            "3ecaa6978a708ed3799433ad000b214ad7c389f8e22ab4bb908e67f80cd7a4ce"
                .chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
    }
}
