// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored playlist is an extended M3U: readable by other players, and
 * lossless for the fields M3U itself cannot carry.
 */
class PlaylistCodecTest {
    private val llama =
        Track(
            id = "llama",
            artist = "DJ Mike Llama",
            title = "Llama Whippin' Intro",
            durationMs = 5304,
            bitrateKbps = 56,
            sampleRateKhz = 22,
            // not the retired intro asset's uri: that one decodes into the
            // default intro track, which is tested below
            uri = "file:///music/llama.mp3",
        )

    /**
     * A saved queue may name the retired intro asset, which the app does not
     * ship. It decodes to the first of [DefaultTracks].
     */
    @Test
    fun `a queue holding the retired intro comes back holding the new one`() {
        val old =
            """
            #EXTM3U
            #ANDAMP:id=llama;artist=DJ+Mike+Llama;title=Llama+Whippin%27+Intro;ms=5304
            asset:///audio/llama.mp3
            """.trimIndent()

        val back = PlaylistCodec.decode(old)

        assertEquals(DefaultTracks.tracks.first(), back.tracks.single())
    }

    @Test
    fun `a queue survives a round trip field for field`() {
        val picked =
            Track("picked-1", "Autechre", "Second Bad Vilbel", 289_000, 192, 44, "content://media/audio/17")
        val saved = PlaylistCodec.Saved(listOf(llama, picked), currentIndex = 1)

        val back = PlaylistCodec.decode(PlaylistCodec.encode(saved))

        assertEquals(saved, back)
    }

    @Test
    fun `metadata M3U cannot express rides along and comes back`() {
        // duration to the millisecond, the artist/title split, bitrate, sample
        // rate and the track id: EXTINF has room for none of it
        val back = PlaylistCodec.decode(PlaylistCodec.encode(PlaylistCodec.Saved(listOf(llama), 0)))

        assertEquals(llama, back.tracks.single())
    }

    @Test
    fun `separators inside a title do not split the line`() {
        val awkward =
            llama.copy(
                id = "awkward",
                artist = "a;b=c",
                title = "x=1; y=2 - and a % sign",
            )

        val back = PlaylistCodec.decode(PlaylistCodec.encode(PlaylistCodec.Saved(listOf(awkward), 0)))

        assertEquals(awkward, back.tracks.single())
    }

    @Test
    fun `the file another player wrote still loads`() {
        val foreign =
            """
            #EXTM3U
            #EXTINF:289,Autechre - Second Bad Vilbel
            /storage/emulated/0/Music/vilbel.mp3
            #EXTINF:-1,Some Stream
            http://example.com/stream
            """.trimIndent()

        val back = PlaylistCodec.decode(foreign)

        assertEquals(2, back.tracks.size)
        assertEquals("Autechre - Second Bad Vilbel", back.tracks[0].title)
        assertEquals(289_000, back.tracks[0].durationMs)
        assertEquals("/storage/emulated/0/Music/vilbel.mp3", back.tracks[0].uri)
        // a negative EXTINF duration means "unknown"
        assertEquals(0, back.tracks[1].durationMs)
    }

    @Test
    fun `a bare list of paths loads with filenames for titles`() {
        val back = PlaylistCodec.decode("/music/a.mp3\n/music/b - c.mp3\n")

        assertEquals(listOf("a.mp3", "b - c.mp3"), back.tracks.map { it.title })
        assertEquals(listOf("/music/a.mp3", "/music/b - c.mp3"), back.tracks.map { it.uri })
    }

    @Test
    fun `restored tracks get distinct ids even without stored ones`() {
        val back = PlaylistCodec.decode("/music/a.mp3\n/music/a.mp3\n")

        assertEquals(
            2,
            back.tracks
                .map { it.id }
                .toSet()
                .size,
        )
    }

    @Test
    fun `a truncated or scrambled file yields what it can and no crash`() {
        val torn =
            """
            #EXTM3U
            #ANDAMP-CURRENT:not-a-number
            #ANDAMP:id=;artist
            #EXTINF:oops,
            content://media/audio/1
            #EXTINF:12
            """.trimIndent()

        val back = PlaylistCodec.decode(torn)

        assertEquals(1, back.tracks.size)
        assertEquals(0, back.currentIndex)
        assertTrue(
            "an unparseable id is replaced, never left empty",
            back.tracks
                .single()
                .id
                .isNotEmpty(),
        )
        assertEquals(0, back.tracks.single().durationMs)
    }

    @Test
    fun `a current index past the end is pulled back onto the queue`() {
        val text = PlaylistCodec.encode(PlaylistCodec.Saved(listOf(llama), currentIndex = 7))

        assertEquals(0, PlaylistCodec.decode(text).currentIndex)
    }

    @Test
    fun `an empty queue encodes to a header and decodes back to nothing`() {
        val text = PlaylistCodec.encode(PlaylistCodec.Saved(emptyList(), 0))

        assertTrue(text.startsWith(PlaylistCodec.HEADER))
        assertEquals(emptyList<Track>(), PlaylistCodec.decode(text).tracks)
    }

    @Test
    fun `a track with no uri is dropped rather than restored as unplayable`() {
        val mockOnly = llama.copy(id = "no-uri", uri = null)

        val back = PlaylistCodec.decode(PlaylistCodec.encode(PlaylistCodec.Saved(listOf(mockOnly), 0)))

        assertEquals(emptyList<Track>(), back.tracks)
        assertNull(back.tracks.firstOrNull())
    }

    @Test
    fun `a saved station is the station, not whatever was playing`() {
        // ICY metadata rewrites a stream's row title to the current song; the
        // file stores the station's name (defaultName) as the title
        val playing =
            Track(
                id = "station:q",
                artist = "",
                title = "COLDPLAY",
                durationMs = 0,
                uri = "https://audio.example.org/radio_example_live_high.aac",
                defaultName = "Radio Example",
                isStream = true,
            )

        val restored = PlaylistCodec.decode(PlaylistCodec.encode(PlaylistCodec.Saved(listOf(playing), 0))).tracks.single()

        assertEquals("Radio Example", restored.title)
        assertEquals("", restored.artist)
        assertEquals("Radio Example", restored.defaultName)
    }

    @Test
    fun `a local file keeps the tags it was read with`() {
        val song = Track("t1", "Muse", "Hexagons", 6_000, uri = "content://media/42", defaultName = "07 track.mp3")

        val restored = PlaylistCodec.decode(PlaylistCodec.encode(PlaylistCodec.Saved(listOf(song), 0))).tracks.single()

        assertEquals("Hexagons", restored.title)
        assertEquals("Muse", restored.artist)
    }

    /**
     * A source's song plays at whatever quality the source gives it each time,
     * so the file stores no bitrate or sample rate for it.
     */
    @Test
    fun `a music source's song is saved without a bitrate or sample rate`() {
        val song = Track("example:track:1", "Muse", "Hexagons", 214_000, 320, 44, "example:track:1")

        val written = PlaylistCodec.encode(PlaylistCodec.Saved(listOf(song), 0))
        val restored = PlaylistCodec.decode(written).tracks.single()

        assertFalse("no kbps field is written: $written", written.contains("kbps="))
        assertFalse("no khz field is written: $written", written.contains("khz="))
        assertEquals(song.copy(bitrateKbps = null, sampleRateKhz = null), restored)
    }

    @Test
    fun `a station is saved without a bitrate or sample rate`() {
        val station =
            Track(
                "station:q",
                "",
                "Radio Example",
                0,
                128,
                44,
                "https://example.invalid/live.aac",
                defaultName = "Radio Example",
                isStream = true,
            )

        val restored = PlaylistCodec.decode(PlaylistCodec.encode(PlaylistCodec.Saved(listOf(station), 0))).tracks.single()

        assertNull(restored.bitrateKbps)
        assertNull(restored.sampleRateKhz)
    }

    /** Figures stored for a source's song are ignored on read. */
    @Test
    fun `stored figures for a music source's song are not read back`() {
        val old =
            """
            #EXTM3U
            #ANDAMP:id=example%3Atrack%3A1;artist=Muse;title=Hexagons;ms=214000;kbps=320;khz=44
            example:track:1
            """.trimIndent()

        val back = PlaylistCodec.decode(old).tracks.single()

        assertNull(back.bitrateKbps)
        assertNull(back.sampleRateKhz)
    }

    @Test
    fun `a file on the phone keeps its bitrate and sample rate`() {
        val back = PlaylistCodec.decode(PlaylistCodec.encode(PlaylistCodec.Saved(listOf(llama), 0))).tracks.single()

        assertEquals(56, back.bitrateKbps)
        assertEquals(22, back.sampleRateKhz)
    }
}
