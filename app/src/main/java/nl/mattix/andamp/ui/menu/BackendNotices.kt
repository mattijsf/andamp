// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import nl.mattix.andamp.core.model.BackendNotice
import nl.mattix.andamp.state.AmpPrompt

/**
 * The words for a backend's notice. A pure function, so tests assert the text.
 *
 * The backend does not say how the notice was reached, so the text names no count of tracks and the
 * remedy it offers is pressing play again. A notice has only its OK.
 */
internal fun promptFor(
    notice: BackendNotice,
    onDismiss: () -> Unit,
): AmpPrompt =
    when (notice) {
        BackendNotice.SourceCannotPlay -> {
            AmpPrompt(
                title = "Nothing will play",
                body =
                    "Nothing from this source would play. It may need a different account or " +
                        "plan, or the connection to it has gone. Pressing play again tries once " +
                        "more. If it keeps happening, check the source's own settings.",
                confirmLabel = "OK",
                dismissLabel = null,
                onConfirm = onDismiss,
            )
        }

        BackendNotice.StationLost -> {
            AmpPrompt(
                title = "Station lost",
                body =
                    "The station stopped and could not be reached again, so the player has " +
                        "stopped too. The station may be off the air, or the connection may " +
                        "still be down. Pressing play tries again.",
                confirmLabel = "OK",
                dismissLabel = null,
                onConfirm = onDismiss,
            )
        }

        BackendNotice.ServerLost -> {
            AmpPrompt(
                title = "Server lost",
                body =
                    "The connection to the music server went, and the server could not be " +
                        "reached again. The server may be down, or the connection may still " +
                        "be gone. Pressing play tries again.",
                confirmLabel = "OK",
                dismissLabel = null,
                onConfirm = onDismiss,
            )
        }

        BackendNotice.NothingPlayableHere -> {
            AmpPrompt(
                title = "Nothing here can play",
                body =
                    "Every track in this playlist comes from a source this phone cannot reach " +
                        "right now: it is not installed, or nobody is signed in to it. Each row " +
                        "says which.",
                confirmLabel = "OK",
                dismissLabel = null,
                onConfirm = onDismiss,
            )
        }
    }
