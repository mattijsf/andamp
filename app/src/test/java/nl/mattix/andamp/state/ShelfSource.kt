// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.model.BrowseCapabilities
import nl.mattix.andamp.core.model.LibraryAlbum
import nl.mattix.andamp.core.model.LibraryArtist
import nl.mattix.andamp.core.model.LibraryPlaylist
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.playback.BrowseSource
import nl.mattix.andamp.core.playback.BrowseSourceContractTest.Shelf
import nl.mattix.andamp.core.playback.SourceUnreachable

/** A source over [shelf], counting what it is asked; [reachable] false makes every question fail. */
internal class ShelfSource(
    private var shelf: Shelf,
    var reachable: Boolean = true,
) : BrowseSource {
    override val capabilities = BrowseCapabilities(canSearch = true, hasPlaylists = true, hasCatalogue = true)
    override val available = true
    val asked = mutableListOf<String>()

    fun replace(with: Shelf) {
        shelf = with
    }

    private fun answer(question: String) {
        asked += question
        if (!reachable) throw SourceUnreachable("the server is down")
    }

    private fun artistId(name: String) = "ar-$name"

    private fun albumId(title: String) = "al-$title"

    private fun everyone() = shelf.artists + shelf.catalogue

    private fun trackOf(
        artist: String,
        title: String,
    ) = Track("t-$title", artist, title, 1000, uri = "example:track:$title")

    override suspend fun artists(): List<LibraryArtist> {
        answer("artists")
        return shelf.artists.map { LibraryArtist(artistId(it.name), it.name, albumCount = it.albums.size) }
    }

    override suspend fun albums(artistId: String?): List<LibraryAlbum> {
        answer("albums:$artistId")
        val whose = if (artistId == null) shelf.artists else everyone().filter { artistId(it.name) == artistId }
        return whose.flatMap { artist ->
            artist.albums.map { LibraryAlbum(albumId(it.title), it.title, artist.name, it.year) }
        }
    }

    override suspend fun tracks(albumId: String): List<Track> {
        answer("tracks:$albumId")
        for (artist in everyone()) {
            val album = artist.albums.firstOrNull { albumId(it.title) == albumId } ?: continue
            return album.tracks.sortedBy { it.second ?: -1 }.map { (title, _) -> trackOf(artist.name, title) }
        }
        return emptyList()
    }

    override suspend fun search(
        query: String,
        limit: Int,
    ): List<Track> {
        answer("search:$query")
        return shelf.artists
            .flatMap { artist -> artist.albums.flatMap { album -> album.tracks.map { trackOf(artist.name, it.first) } } }
            .filter { it.title.contains(query, ignoreCase = true) }
            .take(limit)
    }

    override suspend fun playlists(): List<LibraryPlaylist> {
        answer("playlists")
        return shelf.playlists.map { LibraryPlaylist("pl-${it.name}", it.name, it.titles.size) }
    }

    override suspend fun playlistTracks(id: String): List<Track> {
        answer("playlist:$id")
        val list = shelf.playlists.firstOrNull { "pl-${it.name}" == id } ?: return emptyList()
        return list.titles.map { trackOf("", it) }
    }

    override suspend fun findArtists(
        query: String,
        limit: Int,
    ): List<LibraryArtist> {
        answer("find:$query")
        return shelf.catalogue
            .filter {
                it.name.contains(
                    query,
                    ignoreCase = true,
                )
            }.map { LibraryArtist(artistId(it.name), it.name) }
    }
}
