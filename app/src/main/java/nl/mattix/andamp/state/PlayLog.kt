// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import nl.mattix.andamp.core.model.Track

/**
 * What the listener played, kept on the phone and nowhere else: for each song, how often and
 * when it was last played, for the lists the library makes of it (most played, recently
 * played, not played lately).
 *
 * A song is its artist and title, folded ([ShelfBuilder.fold]), so the same song played from
 * the phone and from a server counts as one. The copy played last is the one kept, and the
 * one a list plays.
 *
 * Unlike the library's index this is the listener's own history, which cannot be read again
 * from anywhere, so it lives where backups go.
 *
 * Every call blocks on the database; callers run it on an IO dispatcher.
 */
class PlayLog(
    private val helper: SQLiteOpenHelper,
) {
    /** The log in its file among the app's databases; a null [name] keeps it in memory. */
    constructor(context: Context, name: String? = FILE) : this(Helper(context.applicationContext, name))

    private val db: SQLiteDatabase get() = helper.writableDatabase

    /** A song in the log: the copy played last, how often, and when last, in milliseconds. */
    data class Played(
        val track: Track,
        val plays: Int,
        val lastAt: Long,
    )

    /** [track] was played, at [at]. */
    fun played(
        track: Track,
        at: Long,
    ) {
        val key = keyOf(track)
        val db = db
        db.beginTransaction()
        try {
            val plays =
                db
                    .rawQuery(
                        "SELECT plays FROM played WHERE key = ?",
                        arrayOf(key),
                    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            db.insertWithOnConflict(
                "played",
                null,
                ContentValues().apply {
                    put("key", key)
                    put("id", track.id)
                    put("title", track.title)
                    put("artist", track.artist)
                    put("duration_ms", track.durationMs)
                    put("uri", track.uri)
                    put("default_name", track.defaultName)
                    put("artwork", track.artworkUri)
                    put("plays", plays + 1)
                    put("last_at", at)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** The songs played most, the most first. */
    fun mostPlayed(limit: Int): List<Played> = query("ORDER BY plays DESC, last_at DESC", emptyArray(), limit)

    /** The songs played last, the latest first. */
    fun recentlyPlayed(limit: Int): List<Played> = query("ORDER BY last_at DESC", emptyArray(), limit)

    /**
     * The songs played more than once that have not been played since [before], the most
     * played first: favorites that were let go.
     */
    fun notLately(
        before: Long,
        limit: Int,
    ): List<Played> = query("WHERE plays > 1 AND last_at < ? ORDER BY plays DESC, last_at", arrayOf(before.toString()), limit)

    private fun query(
        rest: String,
        args: Array<String>,
        limit: Int,
    ): List<Played> =
        db
            .rawQuery(
                "SELECT id, title, artist, duration_ms, uri, default_name, artwork, plays, last_at " +
                    "FROM played $rest LIMIT $limit",
                args,
            ).use { c ->
                val out = ArrayList<Played>(c.count.coerceAtLeast(0))
                while (c.moveToNext()) out += playedAt(c)
                out
            }

    private fun playedAt(c: Cursor) =
        Played(
            track =
                Track(
                    id = c.getString(0),
                    title = c.getString(1),
                    artist = c.getString(2),
                    durationMs = c.getLong(3),
                    uri = if (c.isNull(4)) null else c.getString(4),
                    defaultName = if (c.isNull(5)) null else c.getString(5),
                    artworkUri = if (c.isNull(6)) null else c.getString(6),
                ),
            plays = c.getInt(7),
            lastAt = c.getLong(8),
        )

    private class Helper(
        context: Context,
        name: String?,
    ) : SQLiteOpenHelper(context, name, null, SCHEMA) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE played (key TEXT PRIMARY KEY, id TEXT NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, " +
                    "duration_ms INTEGER NOT NULL, uri TEXT, default_name TEXT, artwork TEXT, plays INTEGER NOT NULL, " +
                    "last_at INTEGER NOT NULL)",
            )
            db.execSQL("CREATE INDEX played_last ON played (last_at)")
        }

        override fun onUpgrade(
            db: SQLiteDatabase,
            oldVersion: Int,
            newVersion: Int,
        ) = Unit
    }

    companion object {
        const val FILE = "plays.db"
        private const val SCHEMA = 1

        /** A song's key: its artist and title, folded, so two copies of it are one song. */
        fun keyOf(track: Track): String = ShelfBuilder.fold(track.artist) + '\u001f' + ShelfBuilder.fold(track.title)
    }
}

/**
 * Tells when the track playing has been played: once it has run for half its length or four
 * minutes, whichever comes first, as music services count a play. Each time a track starts
 * again from the top is another play. A station is not counted, nor a track whose length is
 * not known.
 */
class PlayCounter(
    private val count: (Track) -> Unit,
) {
    private var current: String? = null
    private var counted = false
    private var lastMs = 0L

    /** What the player is doing now: [track] at [positionMs], [playing] or not. */
    fun observe(
        track: Track?,
        positionMs: Long,
        playing: Boolean,
    ) {
        if (track == null) {
            current = null
            return
        }
        if (track.id != current) {
            current = track.id
            counted = false
        } else if (positionMs + BACK_TO_START_MS < lastMs && positionMs < BACK_TO_START_MS) {
            // the same track again from the top: repeat, or picked again
            counted = false
        }
        lastMs = positionMs
        val countable = playing && !track.isStream && track.durationMs > 0
        if (counted || !countable) return
        if (positionMs >= minOf(track.durationMs / 2, ENOUGH_MS)) {
            counted = true
            count(track)
        }
    }

    private companion object {
        /** Four minutes: a long track counts once it has played this long. */
        const val ENOUGH_MS = 4 * 60 * 1000L

        /** A track back within this of its start, from further on, has started again. */
        const val BACK_TO_START_MS = 5_000L
    }
}
