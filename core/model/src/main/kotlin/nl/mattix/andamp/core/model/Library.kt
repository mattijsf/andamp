// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.model

/**
 * What a music library lists, backend-agnostic like [Track].
 *
 * A count is null when the source does not know it; the UI then leaves it blank.
 */
data class LibraryArtist(
    val id: String,
    val name: String,
    val albumCount: Int? = null,
    val trackCount: Int? = null,
)

data class LibraryAlbum(
    val id: String,
    val title: String,
    val artist: String,
    val year: Int? = null,
    val trackCount: Int? = null,
    /**
     * What kind of record this is, where the source distinguishes them. A source that does
     * not say leaves [AlbumKind.ALBUM].
     */
    val kind: AlbumKind = AlbumKind.ALBUM,
)

/** The kinds of record a catalog tells apart. */
enum class AlbumKind(
    val label: String,
) {
    ALBUM("ALBUMS"),
    SINGLE("SINGLES"),
    COMPILATION("COMPILATIONS"),
}

/**
 * A named list the source keeps for the listener. The player reads it and does not change
 * it, unlike the playlists the player saves itself.
 */
data class LibraryPlaylist(
    val id: String,
    val name: String,
    val trackCount: Int? = null,
    /**
     * Who made it, as the source names them, when that is somebody other than
     * the listener: null for the listener's own lists, and wherever the source
     * does not say.
     */
    val owner: String? = null,
)

/**
 * What a browse source can answer, declared up front like [Capabilities]. The UI skips a
 * level the source does not have.
 */
data class BrowseCapabilities(
    val hasArtists: Boolean = true,
    val hasAlbums: Boolean = true,
    /** Whether search answers with anything; the UI hides the entry otherwise. */
    val canSearch: Boolean = false,
    /** Whether the source keeps named lists of its own ([LibraryPlaylist]). */
    val hasPlaylists: Boolean = false,
    /**
     * Whether the source has a catalog to search beyond what the listener already has in
     * the library.
     */
    val hasCatalogue: Boolean = false,
)
