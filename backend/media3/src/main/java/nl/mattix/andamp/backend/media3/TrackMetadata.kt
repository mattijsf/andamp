// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import android.net.Uri
import androidx.media3.common.MediaMetadata
import nl.mattix.andamp.core.model.Track

/**
 * What the notification and the lock screen say about a row.
 *
 * Shared by the two players that build media items from rows: ExoPlayer's, in
 * [Media3Backend], and [BackendPlayer]. The artist is set even when it is
 * empty.
 */
internal fun Track.toMediaMetadata(): MediaMetadata =
    MediaMetadata
        .Builder()
        .setTitle(title)
        .setArtist(artist.trim())
        // loaded by the session's bitmap loader; see CoverBitmapLoader
        .setArtworkUri(cover()?.let(Uri::parse))
        .build()

/**
 * Where to look for this row's cover: the artwork address when the row has
 * one, or else the row's own address for a song on the phone, whose cover
 * [CoverBitmapLoader] reads from its tags or the library. A station has none
 * of its own.
 */
private fun Track.cover(): String? =
    artworkUri ?: uri?.takeIf { !isStream && (it.startsWith("content://") || it.startsWith("file://")) }
