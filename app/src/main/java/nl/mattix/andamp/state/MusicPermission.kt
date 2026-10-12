// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.os.Build
import nl.mattix.andamp.R

/**
 * The audio permission by the name Android's settings give it, so the listener can find it
 * under App info > Permissions. Android 13 split audio off as its own permission; before
 * that it came with the other files.
 */
object MusicPermission {
    /** The name on a phone running [sdk]. */
    fun name(
        sdk: Int,
        words: Words,
    ): String =
        words.get(
            when {
                sdk >= Build.VERSION_CODES.TIRAMISU -> R.string.permission_music_and_audio
                sdk >= Build.VERSION_CODES.R -> R.string.permission_files_and_media
                else -> R.string.permission_storage
            },
        )

    /** The name on this phone. */
    fun current(words: Words): String = name(Build.VERSION.SDK_INT, words)
}
