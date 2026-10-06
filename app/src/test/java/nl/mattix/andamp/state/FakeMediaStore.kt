// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.MediaStore
import nl.mattix.andamp.core.playback.BrowseSourceContractTest

/**
 * A stand-in for the platform's audio library, backed by SQLite, so the selection, the
 * `ALBUM_ID IN (?,?)` list, the LIKE escaping and the sort order that
 * [MediaStoreBrowseSource] sends are applied to the rows.
 *
 * It has only the columns the app asks for.
 */
class FakeMediaStore : ContentProvider() {
    private lateinit var db: SQLiteDatabase

    override fun onCreate(): Boolean {
        db = SQLiteDatabase.create(null)
        db.execSQL(
            "CREATE TABLE artists (_id INTEGER PRIMARY KEY, artist TEXT, " +
                "number_of_albums INTEGER, number_of_tracks INTEGER)",
        )
        db.execSQL(
            "CREATE TABLE albums (_id INTEGER PRIMARY KEY, album TEXT, artist TEXT, " +
                "artist_id INTEGER, minyear INTEGER, numsongs INTEGER)",
        )
        db.execSQL(
            "CREATE TABLE media (_id INTEGER PRIMARY KEY, title TEXT, artist TEXT, album TEXT, " +
                "album_id INTEGER, duration INTEGER, track INTEGER, _data TEXT)",
        )
        return true
    }

    /** Fills the tables from a contract [BrowseSourceContractTest.Shelf]. */
    fun seed(shelf: BrowseSourceContractTest.Shelf) {
        var artistId = 1L
        var albumId = 100L
        var trackId = 1000L
        shelf.artists.forEach { artist ->
            db.insert(
                "artists",
                null,
                ContentValues().apply {
                    put("_id", artistId)
                    put("artist", artist.name)
                    put("number_of_albums", artist.albums.size)
                    put("number_of_tracks", artist.albums.sumOf { it.tracks.size })
                },
            )
            artist.albums.forEach { album ->
                db.insert(
                    "albums",
                    null,
                    ContentValues().apply {
                        put("_id", albumId)
                        put("album", album.title)
                        put("artist", artist.name)
                        put("artist_id", artistId)
                        put("minyear", album.year ?: 0)
                        put("numsongs", album.tracks.size)
                    },
                )
                album.tracks.forEach { (title, number) ->
                    db.insert(
                        "media",
                        null,
                        ContentValues().apply {
                            put("_id", trackId)
                            put("title", title)
                            put("artist", artist.name)
                            put("album", album.title)
                            put("album_id", albumId)
                            put("duration", TRACK_MS)
                            if (number != null) put("track", number)
                            put("_data", "/music/${artist.name}/${album.title}/$title.mp3")
                        },
                    )
                    trackId++
                }
                albumId++
            }
            artistId++
        }
    }

    /**
     * Files one track of [album] under a second album id, the way MediaStore
     * does when its files sit in two folders: same tags, another row.
     */
    fun splitOff(
        album: String,
        track: String,
    ) {
        val oldId =
            db.rawQuery("SELECT _id FROM albums WHERE album = ? LIMIT 1", arrayOf(album)).use {
                check(it.moveToFirst()) { "no album called $album" }
                it.getLong(0)
            }
        val newId = oldId + SPLIT_OFFSET
        db.execSQL(
            "INSERT INTO albums (_id, album, artist, artist_id, minyear, numsongs) " +
                "SELECT ?, album, artist, artist_id, minyear, 1 FROM albums WHERE _id = ?",
            arrayOf<Any>(newId, oldId),
        )
        db.execSQL("UPDATE media SET album_id = ? WHERE title = ?", arrayOf<Any>(newId, track))
        db.execSQL("UPDATE albums SET numsongs = numsongs - 1 WHERE _id = ?", arrayOf<Any>(oldId))
    }

    /** One audio file the library lists at [path], under [id]. */
    fun file(
        id: Long,
        path: String,
    ) {
        db.insert(
            "media",
            null,
            ContentValues().apply {
                put("_id", id)
                put("title", path.substringAfterLast('/'))
                put("duration", TRACK_MS)
                put("_data", path)
            },
        )
    }

    /** Strips a track's number, the way an untagged file arrives. */
    fun unnumber(track: String) {
        db.execSQL("UPDATE media SET track = NULL WHERE title = ?", arrayOf<Any>(track))
    }

    /**
     * Music access revoked mid-read: every query throws the SecurityException the
     * platform's provider throws.
     */
    @Volatile
    var refuse = false

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (refuse) throw SecurityException("Permission Denial: reading MediaStore requires READ_MEDIA_AUDIO")
        val artistAlbums = uri.pathSegments.takeIf { it.size >= LAST && it.last() == "albums" && it[ARTISTS] == "artists" }
        val table =
            when {
                artistAlbums != null -> "albums"
                uri.path?.endsWith("/audio/artists") == true -> "artists"
                uri.path?.endsWith("/audio/albums") == true -> "albums"
                uri.path?.endsWith("/audio/media") == true -> "media"
                else -> return null
            }
        // the platform's per-artist albums view is a join; here it is a column
        val where =
            artistAlbums?.let { segments ->
                listOfNotNull(selection, "artist_id = ${segments[ARTISTS + 1].toLong()}").joinToString(" AND ")
            } ?: selection
        return db.query(table, projection?.toList()?.toTypedArray(), where, selectionArgs, null, null, sortOrder)
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        /** The index of `artists` in the path of `content://media/external/audio/artists/<id>/albums`. */
        private const val ARTISTS = 2
        private const val LAST = 5
        private const val TRACK_MS = 240_000L

        /** Far enough from any seeded id that a split album cannot collide. */
        private const val SPLIT_OFFSET = 500L

        val AUTHORITY: String = MediaStore.AUTHORITY
    }
}
