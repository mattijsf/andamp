// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.mattix.andamp.core.model.BrowseCapabilities
import nl.mattix.andamp.core.model.LibraryAlbum
import nl.mattix.andamp.core.model.LibraryArtist
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.playback.BrowseSource

/**
 * The phone's music library, browsed through MediaStore's grouped tables.
 *
 * Artists and albums are read from their dedicated tables, with [AlbumMerge] on top
 * because the platform splits an album by the folders its files sit in. Tracks come from
 * the Media table filtered by album, in album order.
 */
class MediaStoreBrowseSource(
    context: Context,
    private val access: MediaStoreAudio = MediaStoreAudio(context),
) : BrowseSource {
    private val resolver: ContentResolver = context.contentResolver

    override val capabilities = BrowseCapabilities(hasArtists = true, hasAlbums = true, canSearch = true)

    override val available: Boolean get() = access.hasPermission()

    override suspend fun artists(): List<LibraryArtist> =
        readable {
            if (!available) return@readable emptyList()
            val rows = mutableListOf<LibraryArtist>()
            resolver
                .query(
                    MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI,
                    arrayOf(
                        MediaStore.Audio.Artists._ID,
                        MediaStore.Audio.Artists.ARTIST,
                        MediaStore.Audio.Artists.NUMBER_OF_ALBUMS,
                        MediaStore.Audio.Artists.NUMBER_OF_TRACKS,
                    ),
                    null,
                    null,
                    "${MediaStore.Audio.Artists.ARTIST} COLLATE NOCASE",
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        rows +=
                            LibraryArtist(
                                id = cursor.getLong(0).toString(),
                                name = cursor.getString(1) ?: UNKNOWN,
                                albumCount = cursor.getInt(2),
                                trackCount = cursor.getInt(3),
                            )
                    }
                }
            // NUMBER_OF_ALBUMS counts MediaStore's folder-split albums; the count shown is of
            // the merged albums the artist's page lists. See [AlbumMerge].
            val merged = albumCountsByArtist()
            rows.map { artist -> artist.copy(albumCount = merged[artist.name.lowercase()] ?: artist.albumCount) }
        }

    private fun albumCountsByArtist(): Map<String, Int> =
        AlbumMerge
            .byTags(queryAlbums(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI))
            .groupingBy { it.artist.lowercase() }
            .eachCount()

    /**
     * An artist's albums, or every album for a null [artistId]. An id that is not a
     * MediaStore row number (another library's id) has no albums here.
     */
    override suspend fun albums(artistId: String?): List<LibraryAlbum> =
        readable {
            if (!available) return@readable emptyList()
            val uri =
                if (artistId == null) {
                    MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI
                } else {
                    val row = artistId.toLongOrNull() ?: return@readable emptyList()
                    // the platform's per-artist albums view
                    MediaStore.Audio.Artists.Albums
                        .getContentUri("external", row)
                }
            AlbumMerge.byTags(queryAlbums(uri))
        }

    private fun queryAlbums(uri: Uri): List<LibraryAlbum> {
        val rows = mutableListOf<LibraryAlbum>()
        resolver
            .query(
                uri,
                arrayOf(
                    MediaStore.Audio.Albums._ID,
                    MediaStore.Audio.Albums.ALBUM,
                    MediaStore.Audio.Albums.ARTIST,
                    MediaStore.Audio.Albums.FIRST_YEAR,
                    MediaStore.Audio.Albums.NUMBER_OF_SONGS,
                ),
                null,
                null,
                "${MediaStore.Audio.Albums.ALBUM} COLLATE NOCASE",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    rows +=
                        LibraryAlbum(
                            id = cursor.getLong(0).toString(),
                            title = cursor.getString(1) ?: UNKNOWN,
                            artist = cursor.getString(2) ?: "",
                            year = cursor.getInt(3).takeIf { it > 0 },
                            trackCount = cursor.getInt(4),
                        )
                }
            }
        return rows
    }

    override suspend fun tracks(albumId: String): List<Track> =
        readable {
            if (!available) return@readable emptyList()
            // one album may stand for several MediaStore rows; see [AlbumMerge]
            val ids = AlbumMerge.idsOf(albumId)
            if (ids.isEmpty()) return@readable emptyList()
            val places = ids.joinToString(",") { "?" }
            val rows = mutableListOf<Track>()
            resolver
                .query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.ARTIST,
                        MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.DURATION,
                    ),
                    "${MediaStore.Audio.Media.ALBUM_ID} IN ($places)",
                    ids.toTypedArray(),
                    // album order, disc breaks included (MediaStore packs those as
                    // disc*1000+track). Files with no track number sort first, and the
                    // title orders them.
                    "${MediaStore.Audio.Media.TRACK}, ${MediaStore.Audio.Media.TITLE} COLLATE NOCASE",
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        rows +=
                            Track(
                                id = "media:$id",
                                artist = cursor.getString(1)?.takeIf { it != UNKNOWN } ?: "",
                                title = cursor.getString(2) ?: UNKNOWN,
                                durationMs = cursor.getLong(3),
                                uri =
                                    ContentUris
                                        .withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                                        .toString(),
                            )
                    }
                }
            rows
        }

    override suspend fun search(
        query: String,
        limit: Int,
    ): List<Track> =
        readable {
            if (!available) return@readable emptyList()
            val rows = mutableListOf<Track>()
            val pattern = LikeEscape.contains(query)
            val clause = "$LIKE_TITLE OR $LIKE_ARTIST OR $LIKE_ALBUM"
            resolver
                .query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.ARTIST,
                        MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.DURATION,
                    ),
                    clause,
                    arrayOf(pattern, pattern, pattern),
                    "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE",
                )?.use { cursor ->
                    // the cap is applied while reading, because a LIMIT in the sort clause
                    // does not work on every platform version
                    while (rows.size < limit && cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        rows +=
                            Track(
                                id = "media:$id",
                                artist = cursor.getString(1)?.takeIf { it != UNKNOWN } ?: "",
                                title = cursor.getString(2) ?: UNKNOWN,
                                durationMs = cursor.getLong(3),
                                uri =
                                    ContentUris
                                        .withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                                        .toString(),
                            )
                    }
                }
            rows
        }

    /**
     * Runs a read of the library on the IO dispatcher and returns an empty list when the
     * provider throws a [SecurityException]. [available] is checked first, but the
     * listener can revoke music access between that check and the query.
     */
    private suspend fun <T> readable(read: suspend () -> List<T>): List<T> =
        withContext(Dispatchers.IO) {
            try {
                read()
            } catch (refused: SecurityException) {
                Log.i(TAG, "music access went away mid-read", refused)
                emptyList()
            }
        }

    private companion object {
        const val TAG = "MediaStoreBrowse"

        /** What MediaStore writes when a tag is missing. */
        const val UNKNOWN = "<unknown>"

        const val LIKE_TITLE = "${MediaStore.Audio.Media.TITLE} LIKE ? ESCAPE '\\'"
        const val LIKE_ARTIST = "${MediaStore.Audio.Media.ARTIST} LIKE ? ESCAPE '\\'"
        const val LIKE_ALBUM = "${MediaStore.Audio.Media.ALBUM} LIKE ? ESCAPE '\\'"
    }
}
