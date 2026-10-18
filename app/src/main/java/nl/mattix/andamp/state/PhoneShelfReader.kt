// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.os.Build
import android.provider.MediaStore
import android.util.Log

/**
 * Reads the phone's audio files from its media index, all of them in one pass, for the
 * library's own index ([LibraryIndex]).
 *
 * [stamp] is what tells whether that read is needed: it changes when a music file is added,
 * removed or changed, and costs one small query.
 */
open class PhoneShelfReader(
    private val resolver: ContentResolver,
) {
    /**
     * How the music on the phone stands: how many files, the newest id and the latest change.
     * Equal stamps mean nothing was added, removed or changed since.
     */
    open fun stamp(): String =
        resolver
            .query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATE_MODIFIED),
                MUSIC,
                null,
                null,
            )?.use { c ->
                var newest = 0L
                var changed = 0L
                while (c.moveToNext()) {
                    newest = maxOf(newest, c.getLong(0))
                    changed = maxOf(changed, c.getLong(1))
                }
                "${c.count}:$newest:$changed"
            } ?: ""

    /** Every music file, as [ShelfBuilder] takes them. */
    open fun read(): List<PhoneFile> {
        val columns = columns()
        val cursor =
            try {
                resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, columns.toTypedArray(), MUSIC, null, null)
            } catch (refused: IllegalArgumentException) {
                // a phone whose index lacks one of the newer columns: read without them
                Log.i(TAG, "reading with the basic columns", refused)
                resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, BASIC.toTypedArray(), MUSIC, null, null)
            }
        return cursor?.use(::files).orEmpty()
    }

    /** The columns to ask for: the basic ones and those this version of Android has. */
    private fun columns(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BASIC +
                listOf(MediaStore.Audio.Media.ALBUM_ARTIST, MediaStore.Audio.Media.GENRE, MediaStore.Audio.Media.RELATIVE_PATH)
        } else {
            BASIC + ALBUM_ARTIST
        }

    private fun files(c: Cursor): List<PhoneFile> {
        fun at(column: String) = c.getColumnIndex(column)
        val id = at(MediaStore.Audio.Media._ID)
        val title = at(MediaStore.Audio.Media.TITLE)
        val artist = at(MediaStore.Audio.Media.ARTIST)
        val album = at(MediaStore.Audio.Media.ALBUM)
        val albumId = at(MediaStore.Audio.Media.ALBUM_ID)
        val track = at(MediaStore.Audio.Media.TRACK)
        val year = at(MediaStore.Audio.Media.YEAR)
        val duration = at(MediaStore.Audio.Media.DURATION)
        val added = at(MediaStore.Audio.Media.DATE_ADDED)
        val data = at(DATA)
        val albumArtist = at(ALBUM_ARTIST)
        val genre = at(GENRE)
        val relativePath = at(RELATIVE_PATH)
        val out = ArrayList<PhoneFile>(c.count.coerceAtLeast(0))
        while (c.moveToNext()) {
            val mediaId = c.getLong(id)
            // MediaStore packs a disc into the track number as disc * 1000 + track
            val packed = c.intOrNull(track)
            out +=
                PhoneFile(
                    mediaId = mediaId,
                    uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId).toString(),
                    title = c.stringOrNull(title) ?: "",
                    artist = c.stringOrNull(artist)?.takeIf { it != UNKNOWN } ?: "",
                    album = c.stringOrNull(album)?.takeIf { it != UNKNOWN } ?: "",
                    albumArtist = c.stringOrNull(albumArtist)?.takeIf { it != UNKNOWN },
                    folderAlbum = if (albumId >= 0) c.getLong(albumId) else 0L,
                    number = packed?.rem(DISC_FACTOR)?.takeIf { it > 0 },
                    disc = packed?.div(DISC_FACTOR)?.takeIf { it > 0 },
                    year = c.intOrNull(year)?.takeIf { it > 0 },
                    durationMs = if (duration >= 0) c.getLong(duration) else 0L,
                    addedAt = if (added >= 0) c.getLong(added) else 0L,
                    folder = c.stringOrNull(relativePath) ?: c.stringOrNull(data)?.substringBeforeLast('/', "") ?: "",
                    genre = c.stringOrNull(genre)?.takeIf { it.isNotBlank() },
                )
        }
        return out
    }

    private fun Cursor.stringOrNull(column: Int): String? = if (column < 0 || isNull(column)) null else getString(column)

    private fun Cursor.intOrNull(column: Int): Int? = if (column < 0 || isNull(column)) null else getInt(column)

    private companion object {
        const val TAG = "PhoneShelfReader"

        /** Music only: not ringtones, notifications or recordings. */
        const val MUSIC = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        /** What MediaStore writes when a tag is missing. */
        const val UNKNOWN = "<unknown>"

        const val DISC_FACTOR = 1000

        /** The file's path; deprecated in favor of RELATIVE_PATH, and the folder is all that is read of it. */
        @Suppress("DEPRECATION")
        const val DATA = MediaStore.Audio.Media.DATA

        /** Named by their strings, because the constants are newer than the oldest Android this runs on. */
        const val ALBUM_ARTIST = "album_artist"
        const val GENRE = "genre"
        const val RELATIVE_PATH = "relative_path"

        val BASIC =
            listOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.YEAR,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_ADDED,
                DATA,
            )
    }
}
