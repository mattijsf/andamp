// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import android.net.Uri
import androidx.media3.common.MediaMetadata
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.displayName

/**
 * What the notification, the lock screen and a car say about a row.
 *
 * Shared by the two players that build media items from rows: ExoPlayer's, in
 * [Media3Backend], and [BackendPlayer]. The artist is set even when it is
 * empty. A row with no title yet, whose tags are still being read, goes by the
 * name the playlist window shows for it.
 */
internal fun Track.toMediaMetadata(): MediaMetadata =
    MediaMetadata
        .Builder()
        .setTitle(title.ifBlank { displayName })
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
