// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.packapi

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import nl.mattix.andamp.core.model.AlbumKind
import nl.mattix.andamp.core.model.BackendNotice
import nl.mattix.andamp.core.model.BackendState
import nl.mattix.andamp.core.model.BrowseCapabilities
import nl.mattix.andamp.core.model.Capabilities
import nl.mattix.andamp.core.model.LibraryAlbum
import nl.mattix.andamp.core.model.LibraryArtist
import nl.mattix.andamp.core.model.LibraryPlaylist
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport

/**
 * What crosses between the player and a pack.
 *
 * The player's own model ([Track], [BackendState] and the rest) is plain Kotlin with no
 * Android in it, so it cannot be sent through a binder; the types in this file are its
 * parcelable copies. The conversions both ways are in `Mapping.kt` and `StateMapping.kt`.
 *
 * Rules for changing the contract:
 *
 * - Any change to the fields of a parcel needs a new [PACK_API]. The types are `@Parcelize`
 *   classes: fields are written and read in declaration order, with no field count and no
 *   size check. A parcel with an added, removed or reordered field is misread by a side
 *   built against the other layout, without an error, so no field can be added compatibly.
 * - Any change to a call of `IMusicSourcePack` or `IPackListener` needs a new [PACK_API].
 * - The player compares a pack's `apiVersion()` with its own [PACK_API] before it asks
 *   anything else, and refuses a pack whose number differs.
 * - [Transport], [AlbumKind] and [BackendNotice] cross as names, and a name the reader does
 *   not know is read as a fallback: stopped, an album, no notice. `VolumeMode` crosses as
 *   its ordinal in `setVolumeMode`, so its entries keep their order.
 */
object PackApi {
    /** The version of this contract. It rises with any change to the calls or to the fields of a parcel. */
    const val PACK_API = 2

    /** The intent action a pack's service answers to. */
    const val ACTION_BIND = "nl.mattix.andamp.source.BIND"

    /** The intent action a pack's own settings screen answers to. */
    const val ACTION_SETTINGS = "nl.mattix.andamp.source.SETTINGS"
}

/** A row in the queue, as [Track] travels. */
@Parcelize
data class PackTrack(
    val id: String,
    val artist: String,
    val title: String,
    val durationMs: Long,
    val uri: String?,
    val bitrateKbps: Int = 0,
    val sampleRateKhz: Int = 0,
    val isStream: Boolean = false,
    val artworkUri: String? = null,
    val defaultName: String? = null,
) : Parcelable

/** An artist on a shelf, as [LibraryArtist] travels. */
@Parcelize
data class PackArtist(
    val id: String,
    val name: String,
    val albumCount: Int = UNKNOWN_COUNT,
    val trackCount: Int = UNKNOWN_COUNT,
) : Parcelable

/** An album on a shelf, as [LibraryAlbum] travels. */
@Parcelize
data class PackAlbum(
    val id: String,
    val title: String,
    val artist: String,
    val year: Int = UNKNOWN_COUNT,
    val trackCount: Int = UNKNOWN_COUNT,
    /** The name of an [AlbumKind]; anything this player does not know is an album. */
    val kind: String = AlbumKind.ALBUM.name,
) : Parcelable

/** One of the source's own lists, as [LibraryPlaylist] travels. */
@Parcelize
data class PackPlaylist(
    val id: String,
    val name: String,
    val trackCount: Int = UNKNOWN_COUNT,
    val owner: String? = null,
) : Parcelable

/**
 * The value an unknown count or year crosses as. These are nullable in the player's model
 * and an `Int` on the wire, so unknown is a number no count can be.
 */
const val UNKNOWN_COUNT = -1

/**
 * Who the pack is, what it can play and what it can be asked. The player reads it once
 * when it binds.
 */
@Parcelize
data class PackDescriptor(
    /** The scheme its rows' uris start with (`example` for `example:track:1`), which is also the source's id. */
    val scheme: String,
    /** The name the player shows for this source. */
    val label: String,
    /** The pack's own version. */
    val version: String,
    val canSeek: Boolean = true,
    val canEditQueue: Boolean = true,
    val canAttenuate: Boolean = true,
    val hasArtists: Boolean = true,
    val hasAlbums: Boolean = true,
    val canSearch: Boolean = false,
    val hasPlaylists: Boolean = false,
    val hasCatalogue: Boolean = false,
    /** Whether the player may offer a skin choice for this source. */
    val skinnable: Boolean = true,
    /** Where its `update.json` is, or empty when it is not updated from anywhere. */
    val updates: String = "",
    /**
     * The page a listener gets this pack from, or empty when there is none. The player
     * writes it into a saved playlist beside the rows, so that a phone without the pack can
     * say where they came from.
     */
    val home: String = "",
    /**
     * Whether the pack hands its decoded audio to the player through `openAudio`, instead
     * of playing it itself.
     *
     * True gives the source the player's equalizer, effect rack and visualizer. False is
     * for a pack that plays somewhere else (a speaker on the network, another device) and
     * has no samples to give.
     */
    val handsOverAudio: Boolean = true,
    /** The sample rate in Hz and the channel count of that audio; the player renders it as it comes. */
    val sampleRate: Int = 44_100,
    val channels: Int = 2,
) : Parcelable {
    val playback: Capabilities
        get() = Capabilities(canSeek = canSeek, canEditQueue = canEditQueue, canAttenuate = canAttenuate)

    val browse: BrowseCapabilities
        get() =
            BrowseCapabilities(
                hasArtists = hasArtists,
                hasAlbums = hasAlbums,
                canSearch = canSearch,
                hasPlaylists = hasPlaylists,
                hasCatalogue = hasCatalogue,
            )
}

/**
 * Whether this phone holds an account for the pack, and whose it is.
 *
 * The player asks for it with `account()` and is told of changes through
 * `IPackListener.onAccount`. [name] is shown with the source in the player, and is empty
 * when the pack does not say.
 */
@Parcelize
data class PackAccount(
    val signedIn: Boolean,
    val name: String = "",
) : Parcelable

/** What the library is being asked for; one page at a time, see [PackAnswer]. */
@Parcelize
data class PackQuestion(
    val kind: String,
    /** An artist's, an album's or a list's id, for the kinds that name one. */
    val id: String = "",
    /** What is being searched for, for the kinds that search. */
    val query: String = "",
    val offset: Int = 0,
    val limit: Int = PAGE,
) : Parcelable {
    companion object {
        /** Every artist in the listener's library. */
        const val ARTISTS = "artists"

        /** The albums of the artist named by [id], or every album when it is empty. */
        const val ALBUMS = "albums"

        /** The tracks of the album named by [id], in the order the source keeps them. */
        const val TRACKS = "tracks"

        /** The source's own named lists. */
        const val PLAYLISTS = "playlists"

        /** The tracks of the list named by [id]. */
        const val PLAYLIST_TRACKS = "playlistTracks"

        /** Tracks matching [query]. */
        const val SEARCH = "search"

        /** Artists in the source's catalog matching [query], not only in the library. */
        const val FIND_ARTISTS = "findArtists"

        /**
         * The default page size. A binder transaction buffer is about a megabyte for the
         * whole process, so answers are paged.
         */
        const val PAGE = 500
    }
}

/**
 * One page of one answer.
 *
 * One list carries it and the rest are empty; [more] says whether there is more to ask for
 * from further along. [failed] means the pack could not answer at all, which is different
 * from an empty list: see `SourceUnreachable`.
 */
@Parcelize
data class PackAnswer(
    val tracks: List<PackTrack> = emptyList(),
    val artists: List<PackArtist> = emptyList(),
    val albums: List<PackAlbum> = emptyList(),
    val playlists: List<PackPlaylist> = emptyList(),
    val more: Boolean = false,
    val failed: Boolean = false,
) : Parcelable

/**
 * Everything the pack says about playback, as [BackendState] travels. The pack owns the
 * queue, the cursor, shuffle and repeat; the player shows this state.
 */
@Parcelize
data class PackState(
    /** The name of a [Transport]; anything this player does not know is [Transport.Stopped]. */
    val transport: String = Transport.Stopped.name,
    val positionMs: Long = 0,
    val currentIndex: Int = 0,
    val queue: List<PackTrack> = emptyList(),
    val volumeFraction: Float = 0.78f,
    val shuffle: Boolean = false,
    val repeat: Boolean = false,
    val connecting: Boolean = false,
    val streamBitrateKbps: Int = 0,
    /** Which [BackendNotice] this is, as [CANNOT_PLAY], [NOTHING_HERE], [STATION_LOST] or [SERVER_LOST], or empty for none. */
    val notice: String = "",
    /** The number of this raising of [notice]; see [BackendState.noticeSeq]. */
    val noticeSeq: Long = 0,
    /** See [BackendState.streamSampleRateKhz]; 0 is a pack that did not say. */
    val streamSampleRateKhz: Int = 0,
) : Parcelable
