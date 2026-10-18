// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import nl.mattix.andamp.core.model.Track

/**
 * The library's own index: for each source, the [Shelf] it was last read as, in SQLite.
 *
 * The library window reads its pages from here, so a page opens without asking the phone's
 * media index or a server, and search can find artists and albums as well as tracks. A shelf
 * is replaced whole, in one transaction, with the [stamp] it was read under; a reader that finds
 * a different stamp at the source reads it again.
 *
 * The file is a cache: it holds nothing that cannot be read again, so it lives where backups do
 * not go, and a schema change starts it over.
 *
 * Every call blocks on the database; callers run it on an IO dispatcher.
 */
class LibraryIndex(
    private val helper: SQLiteOpenHelper,
) {
    /** The index in its file under [Context.getNoBackupFilesDir]; a null [name] keeps it in memory. */
    constructor(context: Context, name: String? = FILE) : this(
        Helper(context.applicationContext, name?.let { java.io.File(context.noBackupFilesDir, it).path }),
    )

    private val db: SQLiteDatabase get() = helper.writableDatabase

    /** The stamp [source]'s shelf was stored under; null when it has none. */
    fun stamp(source: String): String? =
        db.rawQuery("SELECT stamp FROM shelf WHERE source = ?", arrayOf(source)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /** Stores [shelf] as all [source] has, under [stamp], in place of what it had. */
    fun replace(
        source: String,
        shelf: Shelf,
        stamp: String,
    ) {
        val db = db
        db.beginTransaction()
        try {
            for (table in listOf("artist", "album", "track")) db.delete(table, "source = ?", arrayOf(source))
            insertArtists(db, source, shelf.artists)
            insertAlbums(db, source, shelf.albums)
            insertTracks(db, source, shelf.tracks)
            db.insertWithOnConflict(
                "shelf",
                null,
                ContentValues().apply {
                    put("source", source)
                    put("stamp", stamp)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun insertArtists(
        db: SQLiteDatabase,
        source: String,
        artists: List<Shelf.ShelfArtist>,
    ) {
        db
            .compileStatement("INSERT INTO artist (source, id, name, key, album_count, track_count) VALUES (?,?,?,?,?,?)")
            .use { s ->
                for (a in artists) {
                    s.clearBindings()
                    s.bindString(1, source)
                    s.bindString(2, a.id)
                    s.bindString(3, a.name)
                    s.bindString(4, a.key)
                    s.bindLong(5, a.albumCount.toLong())
                    s.bindLong(6, a.trackCount.toLong())
                    s.executeInsert()
                }
            }
    }

    private fun insertAlbums(
        db: SQLiteDatabase,
        source: String,
        albums: List<Shelf.ShelfAlbum>,
    ) {
        db
            .compileStatement(
                "INSERT INTO album (source, id, title, key, artist_id, artist, year, track_count, added_at) " +
                    "VALUES (?,?,?,?,?,?,?,?,?)",
            ).use { s ->
                for (a in albums) {
                    s.clearBindings()
                    s.bindString(1, source)
                    s.bindString(2, a.id)
                    s.bindString(3, a.title)
                    s.bindString(4, a.key)
                    s.bindString(5, a.artistId)
                    s.bindString(6, a.artist)
                    a.year?.let { s.bindLong(7, it.toLong()) } ?: s.bindNull(7)
                    s.bindLong(8, a.trackCount.toLong())
                    s.bindLong(9, a.addedAt)
                    s.executeInsert()
                }
            }
    }

    private fun insertTracks(
        db: SQLiteDatabase,
        source: String,
        tracks: List<Shelf.ShelfTrack>,
    ) {
        db
            .compileStatement(
                "INSERT INTO track (source, id, album_id, position, title, artist, album, duration_ms, uri, " +
                    "number, disc, year, genre, added_at, folder, found_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            ).use { s ->
                for (t in tracks) {
                    s.clearBindings()
                    s.bindString(1, source)
                    s.bindString(2, t.track.id)
                    s.bindString(3, t.albumId)
                    s.bindLong(4, t.position.toLong())
                    s.bindString(5, t.track.title)
                    s.bindString(6, t.track.artist)
                    s.bindString(7, t.album)
                    s.bindLong(8, t.track.durationMs)
                    t.track.uri?.let { s.bindString(9, it) } ?: s.bindNull(9)
                    t.number?.let { s.bindLong(10, it.toLong()) } ?: s.bindNull(10)
                    t.disc?.let { s.bindLong(11, it.toLong()) } ?: s.bindNull(11)
                    t.year?.let { s.bindLong(12, it.toLong()) } ?: s.bindNull(12)
                    t.genre?.let { s.bindString(13, it) } ?: s.bindNull(13)
                    s.bindLong(14, t.addedAt)
                    s.bindString(15, t.folder)
                    s.bindString(16, t.foundBy)
                    s.executeInsert()
                }
            }
    }

    /** [source]'s artists, by name; the artists with no name of their own last. */
    fun artists(source: String): List<Shelf.ShelfArtist> =
        db
            .rawQuery(
                "SELECT id, name, key, album_count, track_count FROM artist WHERE source = ? ORDER BY key = '', key, id",
                arrayOf(source),
            ).use { c -> c.map(::artistAt) }

    /** [source]'s albums by title, or only those of the album artist [artistId]. */
    fun albums(
        source: String,
        artistId: String? = null,
    ): List<Shelf.ShelfAlbum> {
        val where = if (artistId == null) "source = ?" else "source = ? AND artist_id = ?"
        val args = listOfNotNull(source, artistId).toTypedArray()
        return db.rawQuery("SELECT $ALBUM_COLUMNS FROM album WHERE $where ORDER BY key, artist", args).use { c ->
            c.map(::albumAt)
        }
    }

    /** The album [albumId]'s tracks, in album order; empty for an album [source] does not have. */
    fun tracks(
        source: String,
        albumId: String,
    ): List<Track> =
        db
            .rawQuery(
                "SELECT $TRACK_COLUMNS FROM track WHERE source = ? AND album_id = ? ORDER BY position",
                arrayOf(source, albumId),
            ).use { c -> c.map(::trackAt) }

    /** What [query] finds in [source], at most [limit] of each kind; see [Found]. */
    fun search(
        source: String,
        query: String,
        limit: Int,
    ): Found {
        val pattern = LikeEscape.contains(ShelfBuilder.fold(query))
        val artists =
            db
                .rawQuery(
                    "SELECT id, name, key, album_count, track_count FROM artist WHERE source = ? AND key LIKE ? ESCAPE '\\' " +
                        "ORDER BY key LIMIT ?",
                    arrayOf(source, pattern, limit.toString()),
                ).use { c -> c.map(::artistAt) }
        val albums =
            db
                .rawQuery(
                    "SELECT $ALBUM_COLUMNS FROM album WHERE source = ? AND key LIKE ? ESCAPE '\\' ORDER BY key, artist LIMIT ?",
                    arrayOf(source, pattern, limit.toString()),
                ).use { c -> c.map(::albumAt) }
        val tracks =
            db
                .rawQuery(
                    "SELECT $TRACK_COLUMNS FROM track WHERE source = ? AND found_by LIKE ? ESCAPE '\\' " +
                        "ORDER BY title COLLATE NOCASE LIMIT ?",
                    arrayOf(source, pattern, limit.toString()),
                ).use { c -> c.map(::trackAt) }
        return Found(artists, albums, tracks)
    }

    /** What a search found, each kind in its own order. */
    data class Found(
        val artists: List<Shelf.ShelfArtist>,
        val albums: List<Shelf.ShelfAlbum>,
        val tracks: List<Track>,
    )

    /** How many artists, albums and tracks [source] has. */
    fun counts(source: String): Triple<Int, Int, Int> {
        fun count(table: String) =
            db.rawQuery("SELECT COUNT(*) FROM $table WHERE source = ?", arrayOf(source)).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        return Triple(count("artist"), count("album"), count("track"))
    }

    private fun artistAt(c: Cursor) = Shelf.ShelfArtist(c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getInt(4))

    private fun albumAt(c: Cursor) =
        Shelf.ShelfAlbum(
            id = c.getString(0),
            title = c.getString(1),
            key = c.getString(2),
            artistId = c.getString(3),
            artist = c.getString(4),
            year = if (c.isNull(5)) null else c.getInt(5),
            trackCount = c.getInt(6),
            addedAt = c.getLong(7),
        )

    private fun trackAt(c: Cursor) =
        Track(
            id = c.getString(0),
            title = c.getString(1),
            artist = c.getString(2),
            durationMs = c.getLong(3),
            uri = if (c.isNull(4)) null else c.getString(4),
        )

    private inline fun <T> Cursor.map(row: (Cursor) -> T): List<T> {
        val out = ArrayList<T>(count.coerceAtLeast(0))
        while (moveToNext()) out += row(this)
        return out
    }

    private class Helper(
        context: Context,
        name: String?,
    ) : SQLiteOpenHelper(context, name, null, SCHEMA) {
        override fun onConfigure(db: SQLiteDatabase) {
            // reads and the writes of a refresh do not wait for each other
            db.enableWriteAheadLogging()
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE shelf (source TEXT PRIMARY KEY, stamp TEXT NOT NULL)")
            db.execSQL(
                "CREATE TABLE artist (source TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, key TEXT NOT NULL, " +
                    "album_count INTEGER NOT NULL, track_count INTEGER NOT NULL, PRIMARY KEY (source, id))",
            )
            db.execSQL(
                "CREATE TABLE album (source TEXT NOT NULL, id TEXT NOT NULL, title TEXT NOT NULL, key TEXT NOT NULL, " +
                    "artist_id TEXT NOT NULL, artist TEXT NOT NULL, year INTEGER, track_count INTEGER NOT NULL, " +
                    "added_at INTEGER NOT NULL, PRIMARY KEY (source, id))",
            )
            db.execSQL("CREATE INDEX album_by_artist ON album (source, artist_id)")
            db.execSQL(
                "CREATE TABLE track (source TEXT NOT NULL, id TEXT NOT NULL, album_id TEXT NOT NULL, " +
                    "position INTEGER NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, " +
                    "duration_ms INTEGER NOT NULL, uri TEXT, " +
                    "number INTEGER, disc INTEGER, year INTEGER, genre TEXT, added_at INTEGER NOT NULL, folder TEXT NOT NULL, " +
                    "found_by TEXT NOT NULL, PRIMARY KEY (source, id))",
            )
            db.execSQL("CREATE INDEX track_by_album ON track (source, album_id, position)")
        }

        override fun onUpgrade(
            db: SQLiteDatabase,
            oldVersion: Int,
            newVersion: Int,
        ) = startOver(db)

        override fun onDowngrade(
            db: SQLiteDatabase,
            oldVersion: Int,
            newVersion: Int,
        ) = startOver(db)

        /** The index holds only what can be read again, so another schema starts it empty. */
        private fun startOver(db: SQLiteDatabase) {
            for (table in listOf("shelf", "artist", "album", "track")) db.execSQL("DROP TABLE IF EXISTS $table")
            onCreate(db)
        }
    }

    companion object {
        const val FILE = "library-index.db"

        /** The index's schema; raising it empties the index, which is read again. */
        private const val SCHEMA = 1

        private const val ALBUM_COLUMNS = "id, title, key, artist_id, artist, year, track_count, added_at"
        private const val TRACK_COLUMNS = "id, title, artist, duration_ms, uri"
    }
}
