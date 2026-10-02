// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.model.Track

/**
 * A song on the phone that cannot play because the audio permission is off, and the prompt
 * that turns it on.
 *
 * Rows from the phone's library are MediaStore addresses (`content://media/…`), which open
 * only while the permission is held. A picked file becomes one when the library knows it
 * ([MediaStoreAudio.libraryUriFor]). Rows that keep their own grant from a folder or file
 * pick play without the permission and are not asked about.
 *
 * The order follows Android's guide to requesting permissions (see `rememberLibraryAccess`):
 * the system's prompt first, [explain] when Android says a rationale should be shown, and
 * [settings] once it will not ask again.
 */
class LibraryPrompt(
    /** Whether the audio permission is held now. */
    private val canRead: () -> Boolean,
) {
    /** What pressing [track] has to ask for, or null when nothing is in its way. */
    fun of(track: Track?): LibraryAsk? = track?.takeIf { needsLibrary(it) && !canRead() }?.let(::LibraryAsk)

    companion object {
        /** A row the phone's library hands out, which opens only with the audio permission. */
        fun needsLibrary(track: Track): Boolean = track.uri?.startsWith("content://media/") == true

        /** Refused before: what the permission is for, with the button that asks again. */
        fun explain(
            onDone: () -> Unit,
            ask: () -> Unit,
        ): AmpPrompt {
            val permission = MusicPermission.current
            return AmpPrompt(
                title = "Allow $permission",
                body = "Andamp needs $permission to play the songs on this phone.",
                confirmLabel = "Allow",
                dismissLabel = "Not now",
                onConfirm = {
                    onDone()
                    ask()
                },
            )
        }

        /** Android will not ask again: leads to the settings screen, where the permission can still be allowed. */
        fun settings(
            onDone: () -> Unit,
            open: () -> Unit,
        ): AmpPrompt {
            val permission = MusicPermission.current
            return AmpPrompt(
                title = "$permission permission is off",
                body = "Allow $permission in Andamp's settings to play the songs on this phone.",
                confirmLabel = "Open settings",
                dismissLabel = "Close",
                onConfirm = {
                    onDone()
                    open()
                },
            )
        }
    }
}

/**
 * The permission is wanted for [then]: the screen that holds the permission launcher asks,
 * and plays the track once it is given. It is a request object because only an activity
 * can ask for a permission.
 */
data class LibraryAsk(
    val then: Track,
)
