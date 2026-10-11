// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import android.net.Uri
import android.os.Bundle
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
 *
 * A picture's address is passed on as the row's artwork, for whatever shows
 * the row to fetch. A song on the phone has no such address: its cover is in
 * its own file, which other apps cannot open and which is no picture. That
 * file is named under [COVER_IN] instead, for [CoverBitmapLoader] to read, and
 * what shows the row is handed the picture.
 */
internal fun Track.toMediaMetadata(): MediaMetadata =
    MediaMetadata
        .Builder()
        .setTitle(title.ifBlank { displayName })
        .setArtist(artist.trim())
        .setArtworkUri(artworkUri?.let(Uri::parse))
        .setExtras(ownFile()?.let { file -> Bundle().apply { putString(COVER_IN, file) } })
        .build()

/** The key, in a row's metadata, of the file its cover is read from. */
internal const val COVER_IN = "nl.mattix.andamp.cover.in"

/**
 * The row's own address, when its cover is to be read from there: a song on
 * the phone that names no picture. A station has no cover of its own.
 */
private fun Track.ownFile(): String? =
    uri?.takeIf { artworkUri == null && !isStream && (it.startsWith("content://") || it.startsWith("file://")) }
