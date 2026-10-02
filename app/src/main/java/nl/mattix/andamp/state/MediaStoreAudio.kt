// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import nl.mattix.andamp.core.model.Track

/**
 * The device's audio library.
 *
 * A document picker hands out uris that work only while a per-item grant lasts; the same
 * file in MediaStore is `content://media/external/audio/media/<id>`, readable for as long
 * as the audio permission is held. An added file therefore gets its library uri where
 * there is one, and keeps the picked document's uri when the file is not indexed.
 */
open class MediaStoreAudio(
    private val context: Context,
) {
    private val resolver: ContentResolver get() = context.contentResolver

    /** Whether the library may be read at all; without it every lookup returns null. */
    open fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, permission()) == PackageManager.PERMISSION_GRANTED

    /**
     * The library uri for a picked document, or null when the file is not in the library
     * (an unscanned download, say) or the match is not certain.
     */
    open fun libraryUriFor(
        documentUri: Uri,
        displayName: String?,
        durationMs: Long,
    ): Uri? {
        if (!hasPermission()) return null
        // from API 29 the platform maps a document to its library entry
        val exact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) mediaUriOf(documentUri) else null
        return when {
            exact != null -> exact

            // Otherwise only the name is known, and a name can match another file of the
            // same name. Without a duration to confirm the match, the picked document is kept.
            durationMs <= 0 -> null

            else -> (displayName ?: context.displayNameOf(documentUri))?.let { findByName(it, durationMs) }
        }
    }

    private fun mediaUriOf(documentUri: Uri): Uri? =
        runCatching { MediaStore.getMediaUri(context, documentUri) }
            .onFailure { Log.i(TAG, "No library entry for $documentUri", it) }
            .getOrNull()

    /** The library entry called [displayName], when [MediaStoreMatch.best] can pick one. */
    open fun findByName(
        displayName: String,
        durationMs: Long,
    ): Uri? {
        if (!hasPermission()) return null
        val candidates = query(displayName)
        val match = MediaStoreMatch.best(candidates, displayName, durationMs) ?: return null
        return MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            .buildUpon()
            .appendPath(match.id.toString())
            .build()
    }

    /** What the library knows about [uri]: artist, title and duration. */
    fun trackFor(
        uri: Uri,
        id: String,
    ): Track? {
        if (!hasPermission()) return null
        return runCatching {
            resolver.query(uri, TRACK_PROJECTION, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val name = cursor.getString(1) ?: ""
                Track(
                    id = id,
                    artist = cursor.getString(2) ?: "",
                    title = cursor.getString(3)?.takeIf { it.isNotBlank() } ?: name,
                    durationMs = cursor.getLong(4),
                    uri = uri.toString(),
                )
            }
        }.onFailure { Log.i(TAG, "Could not read $uri from the library", it) }.getOrNull()
    }

    private fun query(displayName: String): List<MediaStoreMatch.Candidate> =
        runCatching {
            resolver
                .query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    CANDIDATE_PROJECTION,
                    "${MediaStore.Audio.Media.DISPLAY_NAME} = ?",
                    arrayOf(displayName),
                    null,
                )?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                MediaStoreMatch.Candidate(
                                    id = cursor.getLong(0),
                                    displayName = cursor.getString(1) ?: "",
                                    durationMs = cursor.getLong(2),
                                ),
                            )
                        }
                    }
                }.orEmpty()
        }.onFailure { Log.i(TAG, "Library lookup for $displayName failed", it) }.getOrDefault(emptyList())

    companion object {
        private const val TAG = "MediaStoreAudio"

        /** The permission that makes the library readable, by platform version. */
        fun permission(): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                "android.permission.READ_MEDIA_AUDIO"
            } else {
                "android.permission.READ_EXTERNAL_STORAGE"
            }

        private val CANDIDATE_PROJECTION =
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DURATION,
            )

        private val TRACK_PROJECTION =
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.DURATION,
            )
    }
}
