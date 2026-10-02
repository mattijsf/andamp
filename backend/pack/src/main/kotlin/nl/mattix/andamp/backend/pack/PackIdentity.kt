// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.pack

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import java.security.MessageDigest

/**
 * Who answers for a source, in a form the listener can check.
 *
 * The pack contract is public and the player keeps no list of approved authors, so it shows
 * whose code is playing: the app's label, its package name and the start of the fingerprint of
 * a key that signed it. Two apps can share a label, but an app cannot be signed with a key its
 * author does not hold.
 */
data class PackIdentity(
    /** The app's own label. */
    val label: String,
    val packageName: String,
    /**
     * The first [SHOWN_BYTES] bytes of the SHA-256 of a signing certificate, as colon-separated
     * hex. Short, because it is there to notice a change; the full fingerprint is in Android's
     * app settings and `apksigner`. Empty when the signatures cannot be read.
     */
    val signer: String,
) {
    companion object {
        /**
         * Who [packageName] is, or null when this phone does not have it. A label that cannot
         * be read falls back to the package name, and signatures that cannot be read leave the
         * fingerprint empty.
         */
        fun of(
            context: Context,
            packageName: String,
        ): PackIdentity? {
            val packages = context.packageManager
            val about =
                runCatching {
                    @Suppress("DEPRECATION")
                    packages.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                }.getOrNull() ?: return null
            val label =
                runCatching { packages.getApplicationLabel(about.applicationInfo!!).toString() }
                    .getOrDefault(packageName)
            return PackIdentity(label = label, packageName = packageName, signer = shortPrint(about.signingInfo?.signers()))
        }

        /**
         * The certificates to take the fingerprint from: the APK's signers when there are
         * several, otherwise `signingCertificateHistory`. [shortPrint] uses the first entry,
         * which for a rotated key is the first of that history.
         */
        private fun android.content.pm.SigningInfo.signers(): Array<Signature>? =
            if (hasMultipleSigners()) apkContentsSigners else signingCertificateHistory

        private fun shortPrint(signers: Array<Signature>?): String {
            val first = signers?.firstOrNull() ?: return ""
            val digest = MessageDigest.getInstance("SHA-256").digest(first.toByteArray())
            return digest.take(SHOWN_BYTES).joinToString(":") { "%02X".format(it) }
        }

        /** Four bytes, shown as eight hex digits. */
        private const val SHOWN_BYTES = 4
    }
}
