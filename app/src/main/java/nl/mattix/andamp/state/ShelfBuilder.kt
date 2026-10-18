// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.model.Track
import java.text.Normalizer
import java.util.Locale

/** One audio file as the phone's media index lists it, read once for the library's own index. */
data class PhoneFile(
    val mediaId: Long,
    val uri: String,
    val title: String,
    /** Empty when the file names no artist. */
    val artist: String,
    val album: String,
    /** The album artist the file is tagged with; null when it has none. */
    val albumArtist: String?,
    /**
     * The album as the phone's index makes it: from the tags and the folder the file is in, so
     * the same album in two folders is two of these.
     */
    val folderAlbum: Long,
    /** The track's number on its disc; null when the file has none. */
    val number: Int?,
    val disc: Int?,
    val year: Int?,
    val durationMs: Long,
    /** When the file was added to the phone, in seconds. */
    val addedAt: Long,
    /** The folder the file is in. */
    val folder: String,
    val genre: String?,
)

/** A library's artists, albums and tracks, as the index stores them. */
data class Shelf(
    val artists: List<ShelfArtist>,
    val albums: List<ShelfAlbum>,
    val tracks: List<ShelfTrack>,
) {
    data class ShelfArtist(
        val id: String,
        /** The name as the files spell it; empty for [ShelfBuilder.VARIOUS] and [ShelfBuilder.UNKNOWN]. */
        val name: String,
        /** What it sorts and is found by; see [ShelfBuilder.fold]. */
        val key: String,
        val albumCount: Int,
        val trackCount: Int,
    )

    data class ShelfAlbum(
        val id: String,
        val title: String,
        val key: String,
        val artistId: String,
        /** The album artist's name; empty for various and unknown artists, which are named when read. */
        val artist: String,
        val year: Int?,
        val trackCount: Int,
        /** When its newest file was added, in seconds. */
        val addedAt: Long,
    )

    data class ShelfTrack(
        val track: Track,
        val albumId: String,
        /** Where it stands in its album: by disc, then number, then title. */
        val position: Int,
        val album: String,
        val number: Int?,
        val disc: Int?,
        val year: Int?,
        val genre: String?,
        val addedAt: Long,
        val folder: String,
        /** What a search finds it by: its title, artist and album, folded. */
        val foundBy: String,
    )
}

/**
 * Makes the library's shelf from the phone's audio files: albums by album artist and title,
 * artists by album artist, tracks in album order.
 *
 * A file tagged with an album artist goes under that artist. A file without one goes under the
 * artist of its album, as the phone's index groups it (tags and folder): when the files of that
 * album name more than one artist, it is a compilation, filed under [VARIOUS]. An album split
 * across folders is one album again, because the album artist and the title make it.
 *
 * Ids come from the folded names, so they stay the same when the shelf is made again and a
 * page kept from before still opens.
 */
object ShelfBuilder {
    /** The artist of an album whose files name more than one artist and no album artist. */
    const val VARIOUS = "artist:\u0001various"

    /** The artist of an album whose files name no artist at all. */
    const val UNKNOWN = "artist:\u0001unknown"

    private const val SEPARATOR = '\u001f'

    fun build(files: List<PhoneFile>): Shelf {
        // each name is folded once, however many files carry it
        val folded = HashMap<String, String>()
        val key = { text: String -> folded.getOrPut(text) { fold(text) } }
        val artistOf = albumArtists(files, key)
        val byAlbum = LinkedHashMap<String, MutableList<Int>>()
        files.forEachIndexed { at, file ->
            byAlbum.getOrPut("album:${artistOf[at].id}$SEPARATOR${key(file.album)}") { ArrayList() } += at
        }
        val albums = ArrayList<Shelf.ShelfAlbum>(byAlbum.size)
        val tracks = ArrayList<Shelf.ShelfTrack>(files.size)
        for ((albumId, members) in byAlbum) {
            val first = files[members.first()]
            val artist = artistOf[members.first()]
            val ordered =
                members.sortedWith(
                    compareBy({ files[it].disc ?: 0 }, { files[it].number ?: -1 }, { key(files[it].title) }),
                )
            albums +=
                Shelf.ShelfAlbum(
                    id = albumId,
                    title = first.album,
                    key = key(first.album),
                    artistId = artist.id,
                    artist = artist.name,
                    year = members.mapNotNull { files[it].year }.minOrNull(),
                    trackCount = members.size,
                    addedAt = members.maxOf { files[it].addedAt },
                )
            ordered.forEachIndexed { position, at ->
                val file = files[at]
                tracks +=
                    Shelf.ShelfTrack(
                        track = trackOf(file),
                        albumId = albumId,
                        position = position,
                        album = file.album,
                        number = file.number,
                        disc = file.disc,
                        year = file.year,
                        genre = file.genre,
                        addedAt = file.addedAt,
                        folder = file.folder,
                        foundBy = "${key(file.title)} ${key(file.artist)} ${key(file.album)}",
                    )
            }
        }
        val artists =
            albums
                .groupBy { it.artistId }
                .map { (id, theirs) ->
                    val name = theirs.first().artist
                    Shelf.ShelfArtist(
                        id = id,
                        name = name,
                        key = if (id == VARIOUS || id == UNKNOWN) "" else key(name),
                        albumCount = theirs.size,
                        trackCount = theirs.sumOf { it.trackCount },
                    )
                }
        return Shelf(artists, albums, tracks)
    }

    /** Lowercase, without accents, and with runs of space made one: what a name sorts and is found by. */
    fun fold(text: String): String {
        val lower = text.lowercase(Locale.ROOT)
        // plain ASCII, by far the most common, has no accents to take off
        val bare =
            if (lower.all { it.code < ASCII }) {
                lower
            } else {
                Normalizer.normalize(lower, Normalizer.Form.NFD).filterNot { Character.getType(it) == MARK }
            }
        val out = StringBuilder(bare.length)
        var gap = false
        for (c in bare) {
            if (c.isWhitespace()) {
                gap = out.isNotEmpty()
            } else {
                if (gap) out.append(' ')
                gap = false
                out.append(c)
            }
        }
        return out.toString()
    }

    private const val ASCII = 0x80
    private val MARK = Character.NON_SPACING_MARK.toInt()

    private class Artist(
        val id: String,
        val name: String,
    )

    /** The album artist of every file, by its place in [files]; see the class KDoc. */
    private fun albumArtists(
        files: List<PhoneFile>,
        key: (String) -> String,
    ): Array<Artist> {
        val ofFolderAlbum = HashMap<Long, Artist>()
        files
            .filter { it.albumArtist.isNullOrBlank() }
            .groupBy { it.folderAlbum }
            .forEach { (folderAlbum, members) ->
                val named = members.map { it.artist.trim() }.filter { it.isNotEmpty() }
                ofFolderAlbum[folderAlbum] =
                    when (named.map(key).distinct().size) {
                        0 -> Artist(UNKNOWN, "")
                        1 -> named.first().let { Artist("artist:${key(it)}", it) }
                        else -> Artist(VARIOUS, "")
                    }
            }
        return Array(files.size) { at ->
            val file = files[at]
            file.albumArtist
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { Artist("artist:${key(it)}", it) }
                ?: ofFolderAlbum.getValue(file.folderAlbum)
        }
    }

    private fun trackOf(file: PhoneFile) =
        Track(
            id = "media:${file.mediaId}",
            artist = file.artist,
            title = file.title,
            durationMs = file.durationMs,
            uri = file.uri,
        )
}
