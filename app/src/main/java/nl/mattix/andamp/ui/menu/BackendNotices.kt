// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import nl.mattix.andamp.R
import nl.mattix.andamp.core.model.BackendNotice
import nl.mattix.andamp.state.AmpPrompt
import nl.mattix.andamp.state.Words

/**
 * The words for a backend's notice. A pure function, so tests assert the text.
 *
 * The backend does not say how the notice was reached, so the text names no count of tracks and the
 * remedy it offers is pressing play again. A notice has only its OK.
 */
internal fun promptFor(
    notice: BackendNotice,
    words: Words,
    onDismiss: () -> Unit,
): AmpPrompt =
    when (notice) {
        BackendNotice.SourceCannotPlay -> {
            AmpPrompt(
                title = words.get(R.string.notice_source_cannot_play_title),
                body = words.get(R.string.notice_source_cannot_play_body),
                confirmLabel = words.get(R.string.prompt_ok),
                dismissLabel = null,
                onConfirm = onDismiss,
            )
        }

        BackendNotice.StationLost -> {
            AmpPrompt(
                title = words.get(R.string.notice_station_lost_title),
                body = words.get(R.string.notice_station_lost_body),
                confirmLabel = words.get(R.string.prompt_ok),
                dismissLabel = null,
                onConfirm = onDismiss,
            )
        }

        BackendNotice.ServerLost -> {
            AmpPrompt(
                title = words.get(R.string.notice_server_lost_title),
                body = words.get(R.string.notice_server_lost_body),
                confirmLabel = words.get(R.string.prompt_ok),
                dismissLabel = null,
                onConfirm = onDismiss,
            )
        }

        BackendNotice.NothingPlayableHere -> {
            AmpPrompt(
                title = words.get(R.string.notice_nothing_playable_title),
                body = words.get(R.string.notice_nothing_playable_body),
                confirmLabel = words.get(R.string.prompt_ok),
                dismissLabel = null,
                onConfirm = onDismiss,
            )
        }
    }
