// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.remote

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import nl.mattix.andamp.core.remotewindow.RemoteWindowLink

/**
 * What the phone can tell about Autoamp, the app Android Auto talks to: whether
 * a car will show it, and whether it speaks the same [protocol] as this Andamp. Preferences >
 * Android Auto shows it, so a listener can see whether the window can be expected to show in
 * the car. This Andamp lends that app the window whatever key signed it; see
 * [RemoteWindowTrust].
 */
data class CompanionCheck(
    /** Whether Google Play installed it. Android Auto shows an app like it in a car only then. */
    val fromPlay: Boolean,
    /** Its version name, which says which release is installed; whether it fits is [match]. */
    val version: String,
    /** This Andamp's version name. */
    val playerVersion: String,
    /** The [RemoteWindowLink.PROTOCOL] it speaks. */
    val protocol: Int,
    /** The one this Andamp speaks. */
    val playerProtocol: Int = RemoteWindowLink.PROTOCOL,
) {
    /** How the two protocols compare. */
    val match: Match
        get() =
            when {
                protocol < playerProtocol -> Match.BEHIND
                protocol > playerProtocol -> Match.AHEAD
                else -> Match.SAME
            }

    /** How the app's protocol compares with this Andamp's. Only a change to the protocol tells; a version alone does not. */
    enum class Match {
        SAME,

        /** It lacks calls this Andamp answers, so some of what Andamp offers in the car is missing. */
        BEHIND,

        /** It makes calls this Andamp drops, so some of what it offers in the car does nothing. */
        AHEAD,
    }

    companion object {
        private const val PLAY = "com.android.vending"
        private const val SHELL = "com.android.shell"

        /**
         * Whether Google Play installed an app, by the installer the phone records for it,
         * [installing], and the app that asked for the install, [initiating]. An install from a
         * computer's command line that names Google Play as its installer is asked for by the
         * shell, and Android Auto does not count it.
         */
        fun fromPlay(
            installing: String?,
            initiating: String?,
        ): Boolean = installing == PLAY && initiating != SHELL

        /** The check of the Autoamp this Andamp belongs with, or null when it is not on the phone. */
        fun of(context: Context): CompanionCheck? {
            val packages = context.packageManager
            val companion = RemoteWindowLink.lenderOf(context.packageName)
            val info = runCatching { packages.getPackageInfo(companion, PackageManager.GET_META_DATA) }.getOrNull() ?: return null
            val (installing, initiating) =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val source = runCatching { packages.getInstallSourceInfo(companion) }.getOrNull()
                    source?.installingPackageName to source?.initiatingPackageName
                } else {
                    // before Android 11 the phone records no app that asked for the install
                    @Suppress("DEPRECATION")
                    runCatching { packages.getInstallerPackageName(companion) }.getOrNull() to null
                }
            val player = runCatching { packages.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
            return CompanionCheck(
                fromPlay = fromPlay(installing, initiating),
                version = info.versionName.orEmpty(),
                playerVersion = player.orEmpty(),
                protocol = RemoteWindowLink.protocolOf(info.applicationInfo),
            )
        }
    }
}
