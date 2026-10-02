// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.playback

import nl.mattix.andamp.core.model.BrowseCapabilities
import nl.mattix.andamp.core.model.LibraryAlbum
import nl.mattix.andamp.core.model.LibraryArtist
import nl.mattix.andamp.core.model.LibraryPlaylist
import nl.mattix.andamp.core.model.Track

/**
 * A library that can be browsed: artists holding albums holding tracks.
 *
 * It is read-only and separate from [PlaybackBackend]: the leaf is [Track], and a track is
 * played by handing it to a backend's queue.
 *
 * [available] is whatever the source needs to answer at all: a permission for the phone's
 * media library, a login for a streaming service.
 *
 * An empty list is an answer; [SourceUnreachable] is the lack of one. A source that asked
 * and was told "nothing" returns an empty list. A source that could not ask (a server that
 * is down, a network that is gone, an account refused) throws [SourceUnreachable] from any
 * of the reads below, so that the player does not show an empty library for a connection
 * that is down. A source that read part of an answer before failing returns the part. A
 * question about an id the source never handed out answers empty.
 */
interface BrowseSource {
    val capabilities: BrowseCapabilities

    /** Whether anything can be answered right now. */
    val available: Boolean

    suspend fun artists(): List<LibraryArtist>

    /** An artist's albums, or every album when [artistId] is null. */
    suspend fun albums(artistId: String? = null): List<LibraryAlbum>

    suspend fun tracks(albumId: String): List<Track>

    /**
     * Tracks matching [query] against title, artist or album, capped at [limit]. Sources
     * without [BrowseCapabilities.canSearch] keep the default, which answers empty.
     */
    suspend fun search(
        query: String,
        limit: Int = SEARCH_LIMIT,
    ): List<Track> = emptyList()

    /**
     * The source's own named lists, newest first where it has an opinion. Sources without
     * [BrowseCapabilities.hasPlaylists] keep the default, which answers empty.
     */
    suspend fun playlists(): List<LibraryPlaylist> = emptyList()

    /** One list's tracks, in the order that list keeps them. */
    suspend fun playlistTracks(id: String): List<Track> = emptyList()

    /**
     * Artists in the source's catalog, not only in the listener's library.
     *
     * The ids it hands back are this source's own, and [albums] and [tracks] take them.
     * Sources without [BrowseCapabilities.hasCatalogue] keep the default, which answers
     * empty.
     */
    suspend fun findArtists(
        query: String,
        limit: Int = SEARCH_LIMIT,
    ): List<LibraryArtist> = emptyList()

    companion object {
        /** The default cap on the number of results. */
        const val SEARCH_LIMIT = 200
    }
}
