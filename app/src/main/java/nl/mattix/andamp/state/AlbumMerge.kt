// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.model.LibraryAlbum

/**
 * Folds albums that MediaStore lists more than once.
 *
 * MediaStore derives an album's identity from the tags and the directory, so an album split
 * across two folders arrives as two rows with the same title and artist. The browse source
 * folds those rows together and keeps every underlying id inside the merged one.
 */
object AlbumMerge {
    /** Ids are MediaStore row numbers, so a comma cannot appear inside one. */
    const val ID_SEPARATOR = ","

    /**
     * Folds [albums] on title and artist, case- and space-insensitively, in the
     * order the source listed them. A merged row spells title and artist the
     * way its first row did, sums the track counts it knows, and takes the
     * earliest year any of them named.
     */
    fun byTags(albums: List<LibraryAlbum>): List<LibraryAlbum> =
        albums
            .groupBy { key(it) }
            .values
            .map { group -> if (group.size == 1) group.first() else fold(group) }

    /** The MediaStore ids behind an album id; one of them for an unmerged album. */
    fun idsOf(albumId: String): List<String> = albumId.split(ID_SEPARATOR).filter { it.isNotEmpty() }

    private fun key(album: LibraryAlbum) = album.title.trim().lowercase() + "\u0000" + album.artist.trim().lowercase()

    private fun fold(group: List<LibraryAlbum>): LibraryAlbum {
        val counts = group.mapNotNull { it.trackCount }
        return group.first().copy(
            id = group.joinToString(ID_SEPARATOR) { it.id },
            year = group.mapNotNull { it.year }.minOrNull(),
            trackCount = counts.sum().takeIf { counts.isNotEmpty() },
        )
    }
}
