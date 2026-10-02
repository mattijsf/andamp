// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.packapi

import nl.mattix.andamp.core.model.AlbumKind
import nl.mattix.andamp.core.model.LibraryAlbum
import nl.mattix.andamp.core.model.LibraryArtist
import nl.mattix.andamp.core.model.LibraryPlaylist
import nl.mattix.andamp.core.model.Track

/** Conversions between the player's model and the parcels in `Wire.kt`, in both directions. */

fun Track.toPack(): PackTrack =
    PackTrack(
        id = id,
        artist = artist,
        title = title,
        durationMs = durationMs,
        uri = uri,
        bitrateKbps = bitrateKbps ?: 0,
        sampleRateKhz = sampleRateKhz ?: 0,
        isStream = isStream,
        artworkUri = artworkUri,
        defaultName = defaultName,
    )

fun PackTrack.toTrack(): Track =
    Track(
        id = id,
        artist = artist,
        title = title,
        durationMs = durationMs,
        // zero means the pack did not say, which is null in the model
        bitrateKbps = bitrateKbps.takeIf { it > 0 },
        sampleRateKhz = sampleRateKhz.takeIf { it > 0 },
        uri = uri,
        defaultName = defaultName,
        isStream = isStream,
        artworkUri = artworkUri,
    )

fun LibraryArtist.toPack(): PackArtist = PackArtist(id, name, albumCount ?: UNKNOWN_COUNT, trackCount ?: UNKNOWN_COUNT)

fun PackArtist.toArtist(): LibraryArtist = LibraryArtist(id, name, albumCount.takeIf { it >= 0 }, trackCount.takeIf { it >= 0 })

fun LibraryAlbum.toPack(): PackAlbum = PackAlbum(id, title, artist, year ?: UNKNOWN_COUNT, trackCount ?: UNKNOWN_COUNT, kind.name)

fun PackAlbum.toAlbum(): LibraryAlbum =
    LibraryAlbum(
        id = id,
        title = title,
        artist = artist,
        year = year.takeIf { it >= 0 },
        trackCount = trackCount.takeIf { it >= 0 },
        // an unknown kind is read as an album
        kind = AlbumKind.entries.firstOrNull { it.name == kind } ?: AlbumKind.ALBUM,
    )

fun LibraryPlaylist.toPack(): PackPlaylist = PackPlaylist(id, name, trackCount ?: UNKNOWN_COUNT, owner)

fun PackPlaylist.toPlaylist(): LibraryPlaylist = LibraryPlaylist(id, name, trackCount.takeIf { it >= 0 }, owner)
