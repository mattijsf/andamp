// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

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
    fun summary(permission: String): String =
        when (this) {
            GRANTED -> {
                "Andamp has the $permission permission. Tracks you add keep playing after a restart."
            }

            ASKABLE -> {
                "Without the $permission permission, Andamp can still play files you pick, " +
                    "but they may stop working after a restart."
            }

            BLOCKED -> {
                "The $permission permission is off. Files you pick still play for now, " +
                    "but they may stop working after a restart."
            }
        }

    /** The button to show, or null when nothing is left to offer. */
    val action: String?
        get() =
            when (this) {
                GRANTED -> null
                ASKABLE -> "Allow"
                BLOCKED -> "Open settings"
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
