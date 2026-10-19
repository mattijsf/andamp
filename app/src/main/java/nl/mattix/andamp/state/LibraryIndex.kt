// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import nl.mattix.andamp.core.model.AlbumKind
import nl.mattix.andamp.core.model.Track

/**
 * The library's own index: for each source, what it was last read as, in SQLite.
 *
 * The library window reads its pages from here, so a page opens without asking the phone's
 * media index or a server, and search can find artists and albums as well as tracks.
 *
 * A source is stored in one of two ways. The phone's music is read whole and stored as one
 * [Shelf], replaced in one transaction under the [stamp] it was read with. A source behind a
 * server is stored a piece at a time as the library asks for it ([Piece]): its artists, its
 * albums, an artist's albums, an album's tracks, each with when it was stored and whether the
 * source answered it all the way through.
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

    // --- a source read whole ---

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
    ) = transaction { db ->
        forget(db, source)
        insertArtists(db, source, shelf.artists)
        upsertAlbums(db, source, shelf.albums, listed = true)
        relate(db, source, shelf.albums)
        for ((albumId, tracks) in shelf.tracks.groupBy { it.albumId }) insertTracks(db, source, albumId, tracks)
        db.insertWithOnConflict(
            "shelf",
            null,
            ContentValues().apply {
                put("source", source)
                put("stamp", stamp)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** Drops everything [source] has, as when its account changes. */
    fun clear(source: String) = transaction { db -> forget(db, source) }

    private fun forget(
        db: SQLiteDatabase,
        source: String,
    ) {
        for (table in listOf("shelf") + TABLES) db.delete(table, "source = ?", arrayOf(source))
    }

    // --- a source read a piece at a time ---

    /** A question the index can hold the answer to, for a source read a piece at a time. */
    data class Piece(
        val kind: String,
        val id: String = "",
    ) {
        companion object {
            val ARTISTS = Piece("artists")
            val ALBUMS = Piece("albums")

            fun artist(id: String) = Piece("artist", id)

            fun album(id: String) = Piece("album", id)
        }
    }

    /** When an answer was stored, in milliseconds, and whether the source gave all of it. */
    data class Stored(
        val at: Long,
        val whole: Boolean,
    )

    /** When [piece] of [source] was stored; null when it never was. */
    fun stored(
        source: String,
        piece: Piece,
    ): Stored? =
        db
            .rawQuery(
                "SELECT at, whole FROM stored WHERE source = ? AND kind = ? AND id = ?",
                arrayOf(source, piece.kind, piece.id),
            ).use { c -> if (c.moveToFirst()) Stored(c.getLong(0), c.getInt(1) != 0) else null }

    /** Stores [source]'s artists, in place of those it had. */
    fun storeArtists(
        source: String,
        artists: List<Shelf.ShelfArtist>,
        at: Stored,
    ) = transaction { db ->
        db.delete("artist", "source = ?", arrayOf(source))
        insertArtists(db, source, artists)
        mark(db, source, Piece.ARTISTS, at)
    }

    /** Stores [source]'s albums: these are all it has, the albums an ALBUMS page lists. */
    fun storeAlbums(
        source: String,
        albums: List<Shelf.ShelfAlbum>,
        at: Stored,
    ) = transaction { db ->
        db.execSQL("UPDATE album SET listed = 0 WHERE source = ?", arrayOf(source))
        upsertAlbums(db, source, albums, listed = true)
        mark(db, source, Piece.ALBUMS, at)
    }

    /** Stores the albums of the artist [artistId], in the order the source gave them. */
    fun storeArtistAlbums(
        source: String,
        artistId: String,
        albums: List<Shelf.ShelfAlbum>,
        at: Stored,
    ) = transaction { db ->
        db.delete("artist_album", "source = ? AND artist_id = ?", arrayOf(source, artistId))
        upsertAlbums(db, source, albums, listed = false)
        db.compileStatement(RELATE).use { s ->
            albums.forEachIndexed { position, album ->
                s.clearBindings()
                s.bindString(1, source)
                s.bindString(2, artistId)
                s.bindString(3, album.id)
                s.bindLong(4, position.toLong())
                s.executeInsert()
            }
        }
        mark(db, source, Piece.artist(artistId), at)
    }

    /** Stores the tracks of the album [albumId], called [album], in its order. */
    fun storeTracks(
        source: String,
        albumId: String,
        album: String,
        tracks: List<Track>,
        at: Stored,
    ) = transaction { db ->
        db.delete("track", "source = ? AND album_id = ?", arrayOf(source, albumId))
        val rows =
            tracks.mapIndexed { position, track ->
                Shelf.ShelfTrack(
                    track = track,
                    albumId = albumId,
                    position = position,
                    album = album,
                    number = null,
                    disc = null,
                    year = null,
                    genre = null,
                    addedAt = 0,
                    folder = "",
                    foundBy = ShelfBuilder.fold("${track.title} ${track.artist} $album"),
                )
            }
        insertTracks(db, source, albumId, rows)
        mark(db, source, Piece.album(albumId), at)
    }

    private fun mark(
        db: SQLiteDatabase,
        source: String,
        piece: Piece,
        at: Stored,
    ) {
        db.insertWithOnConflict(
            "stored",
            null,
            ContentValues().apply {
                put("source", source)
                put("kind", piece.kind)
                put("id", piece.id)
                put("at", at.at)
                put("whole", if (at.whole) 1 else 0)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    // --- writing ---

    private fun transaction(write: (SQLiteDatabase) -> Unit) {
        val db = db
        db.beginTransaction()
        try {
            write(db)
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
            .compileStatement(
                "INSERT OR REPLACE INTO artist (source, id, name, key, album_count, track_count) VALUES (?,?,?,?,?,?)",
            ).use { s ->
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

    /**
     * Writes [albums], replacing what is stored of the same ids. [listed] marks them as on the
     * source's ALBUMS page; an album stored only as an artist's keeps the mark it had.
     */
    private fun upsertAlbums(
        db: SQLiteDatabase,
        source: String,
        albums: List<Shelf.ShelfAlbum>,
        listed: Boolean,
    ) {
        val update =
            db.compileStatement(
                "UPDATE album SET title = ?, key = ?, artist_id = ?, artist = ?, year = ?, track_count = ?, added_at = ?, " +
                    "kind = ?${if (listed) ", listed = 1" else ""} WHERE source = ? AND id = ?",
            )
        val insert =
            db.compileStatement(
                "INSERT OR IGNORE INTO album (title, key, artist_id, artist, year, track_count, added_at, kind, source, id, " +
                    "listed) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            )
        try {
            for (a in albums) {
                bindAlbum(update, a, source)
                if (update.executeUpdateDelete() > 0) continue
                bindAlbum(insert, a, source)
                insert.bindLong(11, if (listed) 1 else 0)
                insert.executeInsert()
            }
        } finally {
            update.close()
            insert.close()
        }
    }

    /** Binds an album's columns in the order the update and the insert above share. */
    private fun bindAlbum(
        s: SQLiteStatement,
        a: Shelf.ShelfAlbum,
        source: String,
    ) {
        s.clearBindings()
        s.bindString(1, a.title)
        s.bindString(2, a.key)
        s.bindString(3, a.artistId)
        s.bindString(4, a.artist)
        a.year?.let { s.bindLong(5, it.toLong()) } ?: s.bindNull(5)
        s.bindLong(6, a.trackCount.toLong())
        s.bindLong(7, a.addedAt)
        s.bindString(8, a.kind.name)
        s.bindString(9, source)
        s.bindString(10, a.id)
    }

    /** Each album as its album artist's, for a shelf read whole. */
    private fun relate(
        db: SQLiteDatabase,
        source: String,
        albums: List<Shelf.ShelfAlbum>,
    ) {
        db.compileStatement(RELATE).use { s ->
            for (a in albums) {
                s.clearBindings()
                s.bindString(1, source)
                s.bindString(2, a.artistId)
                s.bindString(3, a.id)
                s.bindLong(4, 0)
                s.executeInsert()
            }
        }
    }

    private fun insertTracks(
        db: SQLiteDatabase,
        source: String,
        albumId: String,
        tracks: List<Shelf.ShelfTrack>,
    ) {
        db
            .compileStatement(
                "INSERT OR REPLACE INTO track (source, album_id, id, position, title, artist, album, duration_ms, uri, " +
                    "number, disc, year, genre, added_at, folder, found_by, bitrate, sample_rate, default_name, is_stream, " +
                    "artwork) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            ).use { s ->
                for (t in tracks) {
                    val track = t.track
                    s.clearBindings()
                    s.bindString(1, source)
                    s.bindString(2, albumId)
                    s.bindString(3, track.id)
                    s.bindLong(4, t.position.toLong())
                    s.bindString(5, track.title)
                    s.bindString(6, track.artist)
                    s.bindString(7, t.album)
                    s.bindLong(8, track.durationMs)
                    s.bindNullable(9, track.uri)
                    s.bindNullable(10, t.number)
                    s.bindNullable(11, t.disc)
                    s.bindNullable(12, t.year)
                    s.bindNullable(13, t.genre)
                    s.bindLong(14, t.addedAt)
                    s.bindString(15, t.folder)
                    s.bindString(16, t.foundBy)
                    s.bindNullable(17, track.bitrateKbps)
                    s.bindNullable(18, track.sampleRateKhz)
                    s.bindNullable(19, track.defaultName)
                    s.bindLong(20, if (track.isStream) 1 else 0)
                    s.bindNullable(21, track.artworkUri)
                    s.executeInsert()
                }
            }
    }

    // --- reading ---

    /** [source]'s artists, by name; the artists with no name of their own last. */
    fun artists(source: String): List<Shelf.ShelfArtist> =
        db
            .rawQuery(
                "SELECT id, name, key, album_count, track_count FROM artist WHERE source = ? ORDER BY key = '', key, id",
                arrayOf(source),
            ).use { c -> c.map(::artistAt) }

    /**
     * [source]'s albums by title, or those of the artist [artistId] in the order they were
     * stored for that artist.
     */
    fun albums(
        source: String,
        artistId: String? = null,
    ): List<Shelf.ShelfAlbum> =
        if (artistId == null) {
            db
                .rawQuery(
                    "SELECT $ALBUM_COLUMNS FROM album WHERE source = ? AND listed = 1 ORDER BY key, artist",
                    arrayOf(source),
                ).use { c -> c.map(::albumAt) }
        } else {
            db
                .rawQuery(
                    "SELECT ${ALBUM_COLUMNS.split(", ").joinToString { "a.$it" }} FROM artist_album r " +
                        "JOIN album a ON a.source = r.source AND a.id = r.album_id " +
                        "WHERE r.source = ? AND r.artist_id = ? ORDER BY r.position, a.key, a.artist",
                    arrayOf(source, artistId),
                ).use { c -> c.map(::albumAt) }
        }

    /** Whether [source] has the artist [artistId] among its artists. */
    fun hasArtist(
        source: String,
        artistId: String,
    ): Boolean =
        db
            .rawQuery("SELECT 1 FROM artist WHERE source = ? AND id = ?", arrayOf(source, artistId))
            .use { it.moveToFirst() }

    /** The album [albumId] as stored for [source]; null when it has none by that id. */
    fun album(
        source: String,
        albumId: String,
    ): Shelf.ShelfAlbum? =
        db
            .rawQuery("SELECT $ALBUM_COLUMNS FROM album WHERE source = ? AND id = ?", arrayOf(source, albumId))
            .use { c -> if (c.moveToFirst()) albumAt(c) else null }

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
                    "SELECT $ALBUM_COLUMNS FROM album WHERE source = ? AND listed = 1 AND key LIKE ? ESCAPE '\\' " +
                        "ORDER BY key, artist LIMIT ?",
                    arrayOf(source, pattern, limit.toString()),
                ).use { c -> c.map(::albumAt) }
        val tracks =
            db
                .rawQuery(
                    "SELECT $TRACK_COLUMNS FROM track WHERE source = ? AND found_by LIKE ? ESCAPE '\\' " +
                        "GROUP BY id ORDER BY title COLLATE NOCASE LIMIT ?",
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
        fun count(sql: String) = db.rawQuery(sql, arrayOf(source)).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        return Triple(
            count("SELECT COUNT(*) FROM artist WHERE source = ?"),
            count("SELECT COUNT(*) FROM album WHERE source = ? AND listed = 1"),
            count("SELECT COUNT(*) FROM track WHERE source = ?"),
        )
    }

    private fun artistAt(c: Cursor) = Shelf.ShelfArtist(c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getInt(4))

    private fun albumAt(c: Cursor) =
        Shelf.ShelfAlbum(
            id = c.getString(0),
            title = c.getString(1),
            key = c.getString(2),
            artistId = c.getString(3),
            artist = c.getString(4),
            year = c.intOrNull(5),
            trackCount = c.getInt(6),
            addedAt = c.getLong(7),
            kind = AlbumKind.entries.firstOrNull { it.name == c.getString(8) } ?: AlbumKind.ALBUM,
        )

    private fun trackAt(c: Cursor) =
        Track(
            id = c.getString(0),
            title = c.getString(1),
            artist = c.getString(2),
            durationMs = c.getLong(3),
            uri = c.stringOrNull(4),
            bitrateKbps = c.intOrNull(5),
            sampleRateKhz = c.intOrNull(6),
            defaultName = c.stringOrNull(7),
            isStream = c.getInt(8) != 0,
            artworkUri = c.stringOrNull(9),
        )

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
                "CREATE TABLE stored (source TEXT NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, at INTEGER NOT NULL, " +
                    "whole INTEGER NOT NULL, PRIMARY KEY (source, kind, id))",
            )
            db.execSQL(
                "CREATE TABLE artist (source TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, key TEXT NOT NULL, " +
                    "album_count INTEGER NOT NULL, track_count INTEGER NOT NULL, PRIMARY KEY (source, id))",
            )
            db.execSQL(
                "CREATE TABLE album (source TEXT NOT NULL, id TEXT NOT NULL, title TEXT NOT NULL, key TEXT NOT NULL, " +
                    "artist_id TEXT NOT NULL, artist TEXT NOT NULL, year INTEGER, track_count INTEGER NOT NULL, " +
                    "added_at INTEGER NOT NULL, kind TEXT NOT NULL, listed INTEGER NOT NULL, PRIMARY KEY (source, id))",
            )
            db.execSQL(
                "CREATE TABLE artist_album (source TEXT NOT NULL, artist_id TEXT NOT NULL, album_id TEXT NOT NULL, " +
                    "position INTEGER NOT NULL, PRIMARY KEY (source, artist_id, album_id))",
            )
            db.execSQL(
                "CREATE TABLE track (source TEXT NOT NULL, album_id TEXT NOT NULL, id TEXT NOT NULL, " +
                    "position INTEGER NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, " +
                    "duration_ms INTEGER NOT NULL, uri TEXT, number INTEGER, disc INTEGER, year INTEGER, genre TEXT, " +
                    "added_at INTEGER NOT NULL, folder TEXT NOT NULL, found_by TEXT NOT NULL, bitrate INTEGER, " +
                    "sample_rate INTEGER, default_name TEXT, is_stream INTEGER NOT NULL, artwork TEXT, " +
                    "PRIMARY KEY (source, album_id, id))",
            )
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
            for (table in listOf("shelf") + TABLES) db.execSQL("DROP TABLE IF EXISTS $table")
            onCreate(db)
        }
    }

    companion object {
        const val FILE = "library-index.db"

        /** The index's schema; raising it empties the index, which is read again. */
        private const val SCHEMA = 2

        /** The tables that hold a source's rows, besides its shelf's stamp. */
        private val TABLES = listOf("stored", "artist", "album", "artist_album", "track")

        private const val ALBUM_COLUMNS = "id, title, key, artist_id, artist, year, track_count, added_at, kind"
        private const val TRACK_COLUMNS =
            "id, title, artist, duration_ms, uri, bitrate, sample_rate, default_name, is_stream, artwork"

        private const val RELATE = "INSERT OR REPLACE INTO artist_album (source, artist_id, album_id, position) VALUES (?,?,?,?)"
    }
}

private fun SQLiteStatement.bindNullable(
    at: Int,
    value: String?,
) = if (value == null) bindNull(at) else bindString(at, value)

private fun SQLiteStatement.bindNullable(
    at: Int,
    value: Int?,
) = if (value == null) bindNull(at) else bindLong(at, value.toLong())

private fun Cursor.intOrNull(at: Int): Int? = if (isNull(at)) null else getInt(at)

private fun Cursor.stringOrNull(at: Int): String? = if (isNull(at)) null else getString(at)

private inline fun <T> Cursor.map(row: (Cursor) -> T): List<T> {
    val out = ArrayList<T>(count.coerceAtLeast(0))
    while (moveToNext()) out += row(this)
    return out
}
