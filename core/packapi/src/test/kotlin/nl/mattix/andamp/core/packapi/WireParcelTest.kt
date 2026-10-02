// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.packapi

import android.os.Parcel
import android.os.Parcelable
import nl.mattix.andamp.core.model.AlbumKind
import nl.mattix.andamp.core.model.BackendNotice
import nl.mattix.andamp.core.model.BackendState
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every wire type written to a real [Parcel], read back and compared whole. The conversions
 * to and from the player's model are checked through the same trip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WireParcelTest {
    @Test
    fun `a track keeps everything it was carrying`() {
        val row =
            PackTrack(
                id = "example:track:1",
                artist = "Boards of Canada",
                title = "Dawn Chorus",
                durationMs = 234_567,
                uri = "example:track:1",
                bitrateKbps = 320,
                sampleRateKhz = 44,
                isStream = true,
                artworkUri = "https://example.invalid/cover.jpg",
                defaultName = "12 Dawn Chorus.flac",
            )

        assertEquals(row, there(row))
    }

    @Test
    fun `an artist keeps everything it was carrying`() {
        val artist = PackArtist(id = "example:artist:1", name = "Portishead", albumCount = 3, trackCount = 41)

        assertEquals(artist, there(artist))
    }

    @Test
    fun `an album keeps everything it was carrying`() {
        val album =
            PackAlbum(
                id = "example:album:1",
                title = "Dummy",
                artist = "Portishead",
                year = 1994,
                trackCount = 11,
                kind = AlbumKind.COMPILATION.name,
            )

        assertEquals(album, there(album))
    }

    @Test
    fun `a list keeps everything it was carrying`() {
        val list = PackPlaylist(id = "example:playlist:1", name = "Driving", trackCount = 3, owner = "somebody else")

        assertEquals(list, there(list))
    }

    @Test
    fun `a descriptor keeps everything it was carrying`() {
        val descriptor =
            PackDescriptor(
                scheme = "example:",
                label = "Example",
                version = "1.2.3",
                canSeek = false,
                canEditQueue = false,
                canAttenuate = false,
                hasArtists = false,
                hasAlbums = false,
                canSearch = true,
                hasPlaylists = true,
                hasCatalogue = true,
                skinnable = false,
                updates = "https://example.invalid/update.json",
            )

        assertEquals(descriptor, there(descriptor))
    }

    @Test
    fun `an account keeps everything it was carrying`() {
        val account = PackAccount(signedIn = true, name = "someone")

        assertEquals(account, there(account))
    }

    @Test
    fun `a question keeps everything it was carrying`() {
        val question =
            PackQuestion(
                kind = PackQuestion.PLAYLIST_TRACKS,
                id = "example:playlist:1",
                query = "dark & stormy",
                offset = 1_500,
                limit = 250,
            )

        assertEquals(question, there(question))
    }

    @Test
    fun `an answer keeps every shelf it was carrying`() {
        val answer =
            PackAnswer(
                tracks = listOf(PackTrack("t1", "Muse", "Hexagons", 214_000, "example:track:1")),
                artists = listOf(PackArtist("a1", "Muse", albumCount = 9)),
                albums = listOf(PackAlbum("al1", "Absolution", "Muse", year = 2003)),
                playlists = listOf(PackPlaylist("p1", "Quiet", trackCount = 1)),
                more = true,
                failed = true,
            )

        assertEquals(answer, there(answer))
    }

    @Test
    fun `a state keeps everything it was carrying`() {
        val state =
            PackState(
                transport = Transport.Paused.name,
                positionMs = 61_000,
                currentIndex = 2,
                queue = listOf(PackTrack("t1", "Muse", "Hexagons", 214_000, "example:track:1")),
                volumeFraction = 0.42f,
                shuffle = true,
                repeat = true,
                connecting = true,
                streamBitrateKbps = 192,
                notice = "sourceCannotPlay",
                noticeSeq = 7,
                streamSampleRateKhz = 48,
            )

        assertEquals(state, there(state))
    }

    /** The player's model out to the pack's copy, through a parcel, and back to the player's model. */
    @Test
    fun `a track that goes out and comes back is the track that went out`() {
        val row =
            Track(
                id = "example:track:1",
                artist = "Muse",
                title = "Hexagons",
                durationMs = 214_000,
                bitrateKbps = 320,
                sampleRateKhz = 44,
                uri = "example:track:1",
                defaultName = "06 Hexagons.flac",
                isStream = true,
                artworkUri = "https://example.invalid/cover.jpg",
            )

        assertEquals(row, there(row.toPack()).toTrack())
    }

    @Test
    fun `a playback state that goes out and comes back is the state that went out`() {
        val state =
            BackendState(
                transport = Transport.Playing,
                positionMs = 12_000,
                currentIndex = 1,
                queue =
                    listOf(
                        Track("example:track:1", "Muse", "Hexagons", 214_000, uri = "example:track:1"),
                        Track("example:track:2", "Portishead", "Mysterons", 305_000, uri = "example:track:2"),
                    ),
                volumeFraction = 0.6f,
                shuffle = true,
                repeat = true,
                streamBitrateKbps = 256,
                streamSampleRateKhz = 44,
                connecting = true,
                notice = BackendNotice.NothingPlayableHere,
                noticeSeq = 3,
            )

        assertEquals(state, there(state.toPack()).toState())
    }

    /** Every notice by name: a notice left out of the mapping would cross as no notice. */
    @Test
    fun `every notice a pack can raise crosses the wire as itself`() {
        listOf(
            BackendNotice.SourceCannotPlay,
            BackendNotice.NothingPlayableHere,
            BackendNotice.StationLost,
            BackendNotice.ServerLost,
        ).forEach { notice ->
            val state = BackendState(notice = notice, noticeSeq = 9)

            assertEquals(notice, there(state.toPack()).toState().notice)
        }
    }

    /** An unknown count crosses as [UNKNOWN_COUNT] and comes back as null. */
    @Test
    fun `an unknown count comes back as null`() {
        val artist = PackArtist(id = "example:artist:1", name = "Muse")

        val back = there(artist).toArtist()

        assertNull(back.albumCount)
        assertNull(back.trackCount)
    }

    /** A sample rate of 0, which is the parcel's default, is read as no rate in the model. */
    @Test
    fun `a state whose sample rate is 0 comes back with no rate`() {
        val state = there(PackState(streamBitrateKbps = 320)).toState()

        assertNull(state.streamSampleRateKhz)
        assertEquals(320, state.streamBitrateKbps)
    }

    /** An album kind crosses as a name, and an unknown name is read as an album. */
    @Test
    fun `an unknown album kind is read as an album`() {
        val album = PackAlbum(id = "example:album:1", title = "Geogaddi", artist = "Boards of Canada", kind = "BOXSET")

        assertEquals(AlbumKind.ALBUM, there(album).toAlbum().kind)
    }

    /** One trip through a real parcel: written, rewound, read. */
    @Suppress("DEPRECATION") // the classloader overload is the one that exists below API 33
    private inline fun <reified T : Parcelable> there(value: T): T {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeParcelable(value, 0)
            parcel.setDataPosition(0)
            requireNotNull(parcel.readParcelable<T>(T::class.java.classLoader)) { "the parcel reads back a value" }
        } finally {
            parcel.recycle()
        }
    }
}
