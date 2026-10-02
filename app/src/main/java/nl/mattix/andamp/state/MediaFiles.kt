// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import nl.mattix.andamp.core.model.Track

/** Media file probes on Android: tag reading for ADD, liveness for REM > MISC. */
class MediaFiles(
    private val app: Application,
    private val library: MediaStoreAudio = MediaStoreAudio(app),
) {
    /**
     * A picked document as a playlist entry. Where the device's audio library knows the
     * file, the entry gets the library's uri, because the document's own uri works only
     * while its grant does; otherwise it keeps the picked uri.
     */
    fun readAdded(uri: Uri): Track {
        val tags = readMetadata(uri)
        val libraryUri = library.libraryUriFor(uri, app.displayNameOf(uri), tags.durationMs)
        return if (libraryUri != null) tags.copy(uri = libraryUri.toString()) else tags
    }

    /**
     * A track from a folder the listener picked, kept under the folder's own uri. The app
     * holds a persisted grant on the tree, and a document uri built under it lasts as long.
     * A library uri here would hide from [GrantScope] that the folder's grant is in use,
     * and it would be released.
     */
    fun readInTree(uri: Uri): Track = readMetadata(uri)

    // MediaMetadataRetriever throws RuntimeException subclasses on unreadable files; the filename is used then
    @Suppress("TooGenericExceptionCaught")
    fun readMetadata(uri: Uri): Track {
        val fallbackTitle = app.displayNameOf(uri) ?: "Unknown"
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(app, uri)
            Track(
                id = "picked-${System.nanoTime()}",
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: fallbackTitle,
                durationMs =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
                bitrateKbps =
                    retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                        ?.toIntOrNull()
                        ?.div(BITS_PER_KBIT),
                sampleRateKhz = retriever.sampleRateKhz(),
                uri = uri.toString(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read metadata for $uri", e)
            Track("picked-${System.nanoTime()}", "", fallbackTitle, 0, uri = uri.toString())
        } finally {
            retriever.release()
        }
    }

    /** The kHz readout. The metadata key exists from API 31; below that the readout is blank. */
    private fun MediaMetadataRetriever.sampleRateKhz(): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()?.div(HZ_PER_KHZ)
        } else {
            null
        }

    // a track is dead only when its uri can be probed and is missing; tracks without a
    // uri, or with a scheme that cannot be probed, count as alive
    // without a uri (or with schemes we can't probe) are left alone
    @Suppress("TooGenericExceptionCaught")
    fun isAlive(track: Track): Boolean {
        val uri = track.uri ?: return true
        return try {
            when {
                uri.startsWith("asset:///") -> {
                    app.assets.open(uri.removePrefix("asset:///")).use { true }
                }

                uri.startsWith("content://") || uri.startsWith("file://") -> {
                    app.contentResolver
                        .openAssetFileDescriptor(Uri.parse(uri), "r")
                        ?.use { true } ?: false
                }

                else -> {
                    true
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "Dead file: ${track.uri} (${e.javaClass.simpleName})")
            false
        }
    }

    private companion object {
        const val TAG = "MediaFiles"
        const val BITS_PER_KBIT = 1000
        const val HZ_PER_KHZ = 1000
    }
}
