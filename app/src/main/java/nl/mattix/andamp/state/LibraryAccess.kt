// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.R

/**
 * What the preferences screen tells the listener about the audio library permission, and
 * what it offers. "Not asked yet" can be asked again; "refused for good" only leads to
 * system settings.
 */
enum class LibraryAccess {
    /** The library is readable; added tracks survive restarts. */
    GRANTED,

    /** Not asked yet, or asked and dismissed: asking again will show the prompt. */
    ASKABLE,

    /** Refused for good; only the system settings screen can undo it. */
    BLOCKED,
    ;

    /** What this means, naming the permission as Android does ([MusicPermission]). */
    fun summary(
        permission: String,
        words: Words,
    ): String =
        words.get(
            when (this) {
                GRANTED -> R.string.access_granted
                ASKABLE -> R.string.access_askable
                BLOCKED -> R.string.access_blocked
            },
            permission,
        )

    /** The button to show, or null when nothing is left to offer. */
    fun action(words: Words): String? =
        when (this) {
            GRANTED -> null
            ASKABLE -> words.get(R.string.prompt_allow)
            BLOCKED -> words.get(R.string.prompt_open_settings)
        }

    companion object {
        /**
         * [canAsk] is false once a request was refused and
         * shouldShowRequestPermissionRationale is false after it: Android will
         * not show its prompt again, and only the settings screen is left.
         */
        fun of(
            granted: Boolean,
            canAsk: Boolean,
        ): LibraryAccess =
            when {
                granted -> GRANTED
                canAsk -> ASKABLE
                else -> BLOCKED
            }
    }
}
