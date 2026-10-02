// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.os.Build

/**
 * The audio permission by the name Android's settings give it, so the listener can find it
 * under App info > Permissions. Android 13 split audio off as its own permission; before
 * that it came with the other files.
 */
object MusicPermission {
    /** The name on a phone running [sdk]. */
    fun name(sdk: Int): String =
        when {
            sdk >= Build.VERSION_CODES.TIRAMISU -> "Music and audio"
            sdk >= Build.VERSION_CODES.R -> "Files and media"
            else -> "Storage"
        }

    /** The name on this phone. */
    val current: String get() = name(Build.VERSION.SDK_INT)
}
