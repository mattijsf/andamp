// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.playback.AudioTap
import nl.mattix.andamp.modernskin.skin.Player
import nl.mattix.andamp.state.WinampState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * What a modern skin reads from the app's player and what its writes do: the place in the song,
 * the unit maps, the held writes that keep a slider from jumping back while the backend catches
 * up, the spectrum and the wake.
 */
class WalPlayerTest {
    private val songs = List(3) { song(it) }
    private val state =
        WinampState().apply {
            playlist = songs
            currentIndex = 0
            transport = Transport.Playing
            volume = 78
        }
    private val controls = DeferredControls(state)
    private val posted = ArrayDeque<Runnable>()
    private val opened = ArrayList<Boolean>()
    private var tap: AudioTap? = null

    /** Set to stop the backend's reports where they are, as a stalled backend does. */
    private var stalledAt: Long? = null

    private val player =
        WalPlayer(
            controls = controls,
            state = state,
            tapOf = { tap },
            positionMs = { stalledAt ?: controls.reportedMs },
            openFile = { opened += it },
            post = { posted += it },
            clock = { controls.now },
        )

    /** Runs what the player posted to main, as the main thread would. */
    private fun drain() {
        while (posted.isNotEmpty()) posted.removeFirst().run()
    }

    /** Moves the clock on in 16 ms frames, reading the place each frame as the engine's tick does; the last place read. */
    private fun frames(ms: Int): Int {
        var place = player.positionMs
        repeat(ms / FRAME_MS) {
            controls.advance(FRAME_MS.toLong())
            place = player.positionMs
        }
        return place
    }

    // ---- reads ---------------------------------------------------------------------------------

    @Test
    fun `the transport reads as the skin's status`() {
        state.transport = Transport.Stopped
        assertEquals(Player.Status.STOPPED, player.status)
        state.transport = Transport.Playing
        assertEquals(Player.Status.PLAYING, player.status)
        state.transport = Transport.Paused
        assertEquals(Player.Status.PAUSED, player.status)
    }

    @Test
    fun `a station and a song of unknown length have no length`() {
        assertEquals(200_000, player.lengthMs)
        state.playlist = listOf(song(0).copy(isStream = true))
        assertEquals(0, player.lengthMs)
        state.playlist = listOf(song(0).copy(durationMs = 0))
        assertEquals(0, player.lengthMs)
    }

    @Test
    fun `the place rises smoothly between the backend's reports`() {
        controls.advance(1_000)
        var last = player.positionMs
        repeat(150) {
            controls.advance(16)
            val now = player.positionMs
            assertTrue("never back, never a leap: $last then $now", now - last in 0..32)
            last = now
        }
        val song = 1_000 + 150 * 16
        assertTrue("on the song's place, $song: $last", last in song - 20..song)
    }

    @Test
    fun `a report that trails the carried place holds it instead of stepping back`() {
        // a backend whose clock runs 4% slow: each report is behind where the place was carried
        var reported = 0L
        stalledAt = 0L
        var last = player.positionMs
        repeat(200) { frame ->
            controls.advance(FRAME_MS.toLong())
            // a report every 250 ms, 240 ms on from the last
            if (controls.now / 250 != (controls.now - FRAME_MS) / 250) {
                reported += 240
                stalledAt = reported
            }
            val now = player.positionMs
            assertTrue("never back: $last then $now, frame $frame", now >= last)
            last = now
        }
        assertTrue("never more than a second past the report: $last", last <= reported + 1_000)
    }

    @Test
    fun `the place runs at most a second past the backend's last report`() {
        frames(10_000)
        stalledAt = controls.reportedMs

        frames(5_000)

        assertEquals(stalledAt!! + 1_000, player.positionMs.toLong())
    }

    @Test
    fun `the place holds while paused`() {
        controls.advance(3_000)
        player.positionMs
        state.transport = Transport.Paused
        val paused = player.positionMs

        controls.advance(2_000)

        assertEquals(paused, player.positionMs)
        assertTrue(paused in 3_000..3_016)
    }

    @Test
    fun `playing again after a pause goes on from where it stopped`() {
        frames(3_000)
        state.transport = Transport.Paused
        frames(5_000)
        val paused = player.positionMs

        state.transport = Transport.Playing
        val resumed = player.positionMs

        assertEquals(paused, resumed)
        assertTrue(frames(160) - resumed in 140..160)
    }

    @Test
    fun `the place never passes the length`() {
        state.playlist = listOf(song(0).copy(durationMs = 1_100))
        controls.advance(1_000)
        player.positionMs
        stalledAt = controls.reportedMs
        controls.advance(900)

        assertEquals(1_100, player.positionMs)
    }

    @Test
    fun `an index past the playlist reads safely`() {
        state.currentIndex = 7

        assertEquals("", player.title)
        assertEquals(0, player.lengthMs)
        assertEquals(0, player.positionMs)
        assertEquals(emptyMap<String, String>(), player.tags)
        assertEquals(2, player.trackIndex)
        assertEquals(3, player.tracks.size)

        state.playlist = emptyList()
        assertEquals(0, player.trackIndex)
        assertTrue(player.tracks.isEmpty())
    }

    @Test
    fun `the title is the row's text and the tags hold its artist and title`() {
        assertEquals("Artist 0 - Song 0", player.title)
        assertEquals(mapOf("artist" to "Artist 0", "title" to "Song 0"), player.tags)
        assertEquals(128, player.bitrateKbps)
        assertEquals(44, player.sampleRateKhz)
        assertEquals("stereo when the backend does not say", 2, player.channels)
        state.streamChannels = 1
        assertEquals(1, player.channels)

        state.playlist = listOf(song(0).copy(isStream = true))
        state.connecting = true
        assertEquals("[Connecting] file:///music/0.mp3", player.title)
        assertEquals("[Connecting] file:///music/0.mp3", player.tracks[0].title)
    }

    @Test
    fun `the rows are the same list until the playlist changes`() {
        val rows = player.tracks
        assertEquals(listOf("Artist 0 - Song 0", "Artist 1 - Song 1", "Artist 2 - Song 2"), rows.map { it.title })
        assertEquals(200_000, rows[0].lengthMs)

        state.currentIndex = 1
        state.volume = 40
        assertSame(rows, player.tracks)
        assertEquals(1, player.trackIndex)

        state.playlist = songs.reversed()
        assertNotSame(rows, player.tracks)
        assertEquals("Artist 2 - Song 2", player.tracks[0].title)
    }

    @Test
    fun `the unit maps hold at the ends and the center`() {
        assertEquals(0, WalPlayer.fromPercent(0))
        assertEquals(255, WalPlayer.fromPercent(100))
        assertEquals(0, WalPlayer.toPercent(0))
        assertEquals(100, WalPlayer.toPercent(255))
        for (p in 0..100) assertEquals("percent $p", p, WalPlayer.toPercent(WalPlayer.fromPercent(p)))

        assertEquals(0, WalPlayer.fromEq(0))
        assertEquals(128, WalPlayer.fromEq(32))
        assertEquals(255, WalPlayer.fromEq(63))
        assertEquals(0, WalPlayer.toEq(0))
        assertEquals(32, WalPlayer.toEq(128))
        assertEquals(63, WalPlayer.toEq(255))
        for (v in 0..63) assertEquals("band $v", v, WalPlayer.toEq(WalPlayer.fromEq(v)))

        state.volume = 100
        state.eqBands[0] = 0
        state.eqBands[9] = 63
        state.preamp = 32
        assertEquals(255, player.volume)
        assertEquals(0, player.eqBands[0])
        assertEquals(128, player.eqBands[5])
        assertEquals(255, player.eqBands[9])
        assertEquals(128, player.eqPreamp)
    }

    // ---- writes --------------------------------------------------------------------------------

    @Test
    fun `nothing reaches the player before main runs, and the order is kept`() {
        player.play()
        player.seekTo(30_000)
        player.next()
        player.playTrack(2)
        player.pause()
        player.stop()

        assertTrue(controls.calls.isEmpty())
        drain()
        assertEquals(
            listOf("play", "seekTo(${30_000 / 200_000f})", "next", "playTrack(2)", "pause", "stop"),
            controls.calls,
        )
    }

    @Test
    fun `ten volume writes become one with the last value`() {
        for (v in 101..110) player.volume = v

        assertEquals("one task on main", 1, posted.size)
        drain()

        assertEquals(listOf("setVolume(${110 / 255f})"), controls.calls)
    }

    @Test
    fun `a written volume reads as written while the state shows what it became`() {
        player.volume = 200
        assertEquals("before main runs", 200, player.volume)
        drain()
        assertEquals(200, player.volume)
        controls.apply()
        assertEquals(78, state.volume)
        assertEquals("after the state shows 78", 200, player.volume)
        controls.advance(5_000)
        assertEquals("five seconds later", 200, player.volume)

        // the volume keys
        state.volume = 50
        assertEquals("a change from elsewhere reads at once", WalPlayer.fromPercent(50), player.volume)
    }

    @Test
    fun `a write the backend never answers gives way after a second`() {
        player.volume = 100
        drain()

        controls.advance(999)
        assertEquals(100, player.volume)
        controls.advance(1)
        assertEquals(WalPlayer.fromPercent(78), player.volume)
    }

    @Test
    fun `volume up twice before main runs adds twenty`() {
        val start = player.volume

        // what the VOLUME_UP action does
        repeat(2) { player.volume = (player.volume + 10).coerceAtMost(255) }

        assertEquals(start + 20, player.volume)
        drain()
        assertEquals(listOf("setVolume(${(start + 20) / 255f})"), controls.calls)
        controls.apply()
        assertEquals(start + 20, player.volume)
    }

    @Test
    fun `values the state passes through on the way stay held`() {
        // a drag: each write reaches main and the backend before the next, and the state lags one behind
        player.volume = 100
        drain()
        player.volume = 120
        controls.apply()
        assertEquals("the first write arrives while the second is held", 120, player.volume)
        drain()
        controls.apply()
        assertEquals(120, player.volume)
    }

    @Test
    fun `a write back to the state's value holds while an earlier write is still on its way`() {
        player.volume = 100
        drain()
        // the backend has the first write and has not answered; 199 is what the state shows, 78
        player.volume = 199
        assertEquals(199, player.volume)

        controls.apply()
        assertEquals("the first write's answer", WalPlayer.toPercent(100), state.volume)
        assertEquals(199, player.volume)

        drain()
        controls.apply()
        assertEquals(78, state.volume)
        assertEquals(199, player.volume)
        controls.advance(5_000)
        assertEquals(199, player.volume)
    }

    @Test
    fun `on the phone's volume steps a drag reads as written, and a key press after it at once`() {
        for (steps in listOf(15, 25)) {
            controls.volumeSteps = steps
            var step = (76 * steps / 100f).roundToInt()
            state.volume = (step * 100f / steps).roundToInt()

            // each write reaches the backend before the next, and the state lags one behind
            for (v in listOf(196, 200, 202, 230, 150)) {
                player.volume = v
                controls.apply()
                assertEquals("$steps steps, while $v is set", v, player.volume)
                drain()
            }
            controls.apply()
            step = (150 * steps / 255f).roundToInt()
            assertEquals((step * 100f / steps).roundToInt(), state.volume)
            assertEquals("$steps steps, landed on its step", 150, player.volume)
            controls.advance(5_000)
            assertEquals("$steps steps, five seconds later", 150, player.volume)

            // the volume keys, one step down
            state.volume = ((step - 1) * 100f / steps).roundToInt()
            assertEquals("$steps steps, a key press", WalPlayer.fromPercent(state.volume), player.volume)
            controls.advance(5_000)
        }
    }

    @Test
    fun `on the phone's volume steps a write that keeps the step it was on reads as written for good`() {
        controls.volumeSteps = 25
        state.volume = 76
        player.volume = 196
        drain()
        controls.apply()
        assertEquals("the phone stays on its step", 76, state.volume)

        controls.advance(999)
        assertEquals(196, player.volume)
        controls.advance(1)
        assertEquals(196, player.volume)
        controls.advance(5_000)
        assertEquals(196, player.volume)

        // the volume keys, one step up
        state.volume = 80
        assertEquals(WalPlayer.fromPercent(80), player.volume)
    }

    @Test
    fun `shuffle on, off and on before main runs ends on`() {
        player.shuffle = true
        player.shuffle = false
        player.shuffle = true
        assertTrue(player.shuffle)

        drain()
        controls.apply()

        assertEquals(listOf("shuffle(true)"), controls.calls)
        assertTrue(state.shuffle)
        assertTrue(player.shuffle)
    }

    @Test
    fun `a switch already as asked is not pressed`() {
        state.repeat = true
        player.repeat = true
        player.eqEnabled = state.eqOn

        drain()

        assertTrue(controls.calls.isEmpty())
    }

    @Test
    fun `equalizer writes reach the player on its scale`() {
        player.setEqBand(2, 255)
        player.setEqBand(2, 0)
        player.eqPreamp = 128
        player.setEqBand(12, 40)

        assertEquals(0, player.eqBands[2])
        drain()
        assertEquals(listOf("setBand(2, 0)", "setPreamp(32)"), controls.calls)
    }

    @Test
    fun `a seek holds its place until the backend is within a second of it`() {
        frames(10_000)
        player.seekTo(100_000)
        assertEquals(100_000, player.positionMs)

        drain()
        assertEquals("the backend is still where the song was", 100_000, frames(192))

        controls.apply()
        assertTrue("landed: ${player.positionMs}", player.positionMs in 100_000..100_001)
        val place = frames(496)
        assertTrue("and plays on from there: $place", place in 100_496 - 20..100_496)
    }

    @Test
    fun `a seek back by less than a second holds its place until the backend gets there`() {
        frames(10_000)
        player.seekTo(9_500)
        drain()
        assertEquals("the backend is still where the song was", 9_500, frames(192))

        controls.apply()
        var last = player.positionMs
        repeat(31) {
            controls.advance(FRAME_MS.toLong())
            val now = player.positionMs
            assertTrue("on from the seek, never back: $last then $now", now - last in 0..32)
            last = now
        }
        assertTrue("and plays on from there: $last", last in 9_996 - 20..9_996)
    }

    @Test
    fun `a seek that lands a little short while paused does not step back`() {
        frames(10_000)
        state.transport = Transport.Paused
        player.seekTo(100_000)
        drain()

        controls.place(99_990)

        assertEquals(100_000, frames(320))
    }

    @Test
    fun `a seek the backend does not answer gives way after a second`() {
        frames(10_000)
        player.seekTo(100_000)
        drain()

        val place = frames(1_008)

        assertTrue("back on the song's place: $place", place in 11_008 - 20..11_008)
    }

    @Test
    fun `a backend that lands elsewhere is followed at once`() {
        frames(10_000)
        player.seekTo(100_000)
        drain()

        controls.place(60_000)

        assertEquals(60_000, player.positionMs)
    }

    @Test
    fun `a seek that reaches main after the song changed is dropped`() {
        frames(1_000)
        player.seekTo(100_000)
        state.currentIndex = 1

        drain()

        assertTrue(controls.calls.isEmpty())
    }

    @Test
    fun `a station, a song of unknown length and a stopped player do not seek`() {
        state.playlist = listOf(song(0).copy(isStream = true))
        player.seekTo(5_000)
        state.playlist = listOf(song(0).copy(durationMs = 0))
        player.seekTo(5_000)
        state.playlist = songs
        state.transport = Transport.Stopped
        player.seekTo(5_000)

        drain()

        assertTrue(controls.calls.isEmpty())
        assertEquals(0, player.positionMs)
    }

    @Test
    fun `a row that moved before main runs is the row that plays`() {
        player.tracks
        player.playTrack(1)
        state.playlist = listOf(songs[0], songs[2], songs[1])

        drain()

        assertEquals(listOf("playTrack(2)"), controls.calls)
    }

    @Test
    fun `a song queued twice plays the copy picked`() {
        state.playlist = listOf(songs[0], songs[1], songs[0])
        player.tracks
        player.playTrack(2)

        drain()

        assertEquals(listOf("playTrack(2)"), controls.calls)
    }

    @Test
    fun `when a song queued twice moved before main runs, the copy nearest the pick plays`() {
        state.playlist = listOf(songs[0], songs[1], songs[2], songs[0])
        player.tracks
        player.playTrack(3)
        state.playlist = listOf(songs[0], songs[1], songs[0])

        drain()

        assertEquals(listOf("playTrack(2)"), controls.calls)
    }

    @Test
    fun `play on an empty playlist opens the file picker, and eject always does`() {
        state.playlist = emptyList()
        player.play()
        player.eject()

        drain()

        assertEquals(listOf(true, false), opened)
        assertTrue(controls.calls.isEmpty())
    }

    // ---- the spectrum ----------------------------------------------------------------------------

    @Test
    fun `the spectrum has 75 levels from 0 to 1`() {
        tap = SineTap(1_000.0)

        val levels = player.spectrum()

        assertEquals(75, levels.size)
        assertTrue(levels.all { it in 0f..1f })
        assertEquals("a loud tone fills its band", 1f, levels.max())
        assertTrue("and leaves the far ones", levels[0] < 1f && levels[74] < 1f)
    }

    @Test
    fun `within 16 ms the spectrum is the same array and the tap is not read`() {
        val sine = SineTap(1_000.0)
        tap = sine
        val first = player.spectrum()
        val reads = sine.reads

        controls.advance(10)
        assertSame(first, player.spectrum())
        player.vu()
        assertEquals(reads, sine.reads)

        controls.advance(6)
        player.spectrum()
        assertEquals(reads + 1, sine.reads)
    }

    @Test
    fun `the tap is read with the time that went by`() {
        val moving = MovingTap()
        tap = moving
        player.spectrum()

        controls.advance(40)
        player.spectrum()

        val ends = moving.ends
        assertEquals("40 ms of audio on", 44_100L * 40 / 1000, ends[1] - ends[0])
    }

    @Test
    fun `nothing playing or no tap gives an empty spectrum and a still meter`() {
        player.spectrum()
        assertEquals("no tap", 0, player.spectrum().size)
        assertEquals(0 to 0, player.vu())

        tap = SineTap(1_000.0)
        assertEquals("a tap that comes later is taken", 75, player.spectrum().size)

        state.transport = Transport.Stopped
        assertEquals(0, player.spectrum().size)
        assertEquals(0 to 0, player.vu())
        state.transport = Transport.Paused
        assertEquals(0, player.spectrum().size)
    }

    @Test
    fun `the meter is the window's peak, the same on both sides`() {
        tap = SineTap(1_000.0, amplitude = 0.5)
        val (left, right) = player.vu()
        assertEquals(left, right)
        assertTrue("half scale: $left", left in 125..128)
    }

    @Test
    fun `silence leaves the meter at zero`() {
        tap = SineTap(1_000.0, amplitude = 0.0)
        assertEquals(0 to 0, player.vu())
        assertTrue(player.spectrum().all { it == 0f })
    }

    @Test
    fun `samples that are not numbers read as silence`() {
        tap = NotANumberTap()

        assertTrue(player.spectrum().all { it == 0f })
        assertEquals(0 to 0, player.vu())
    }

    // ---- the wake --------------------------------------------------------------------------------

    @Test
    fun `what the skin watches changes with the volume, the track and the equalizer only`() {
        val before = player.watched()
        state.pressedWidget = "main.play"
        state.marqueeStep = 4
        assertEquals("a field no getter reads", before, player.watched())

        state.volume = 50
        assertNotEquals(before, player.watched())
        state.volume = 78
        assertEquals(before, player.watched())

        state.currentIndex = 1
        assertNotEquals(before, player.watched())
        state.currentIndex = 0

        state.eqBands[3] = 40
        assertNotEquals(before, player.watched())
    }

    /** A steady tone, already well written. */
    private class SineTap(
        private val freqHz: Double,
        private val amplitude: Double = 0.8,
    ) : AudioTap {
        override val sampleRateHz = RATE
        override val writtenSamples = 10_000_000L
        var reads = 0

        override fun readAt(
            endSample: Long,
            out: FloatArray,
        ): Boolean {
            reads++
            for (i in out.indices) out[i] = (amplitude * sin(2.0 * PI * freqHz * i / RATE)).toFloat()
            return true
        }
    }

    /** A tap whose write head moves with the test's clock, recording where each read ended. */
    private inner class MovingTap : AudioTap {
        override val sampleRateHz = RATE
        override val writtenSamples: Long get() = 1_000_000L + controls.now * RATE / 1000
        val ends = ArrayList<Long>()

        override fun readAt(
            endSample: Long,
            out: FloatArray,
        ): Boolean {
            ends += endSample
            out.fill(0.1f)
            return true
        }
    }

    /** A tap whose samples are all not a number. */
    private class NotANumberTap : AudioTap {
        override val sampleRateHz = RATE
        override val writtenSamples = 10_000_000L

        override fun readAt(
            endSample: Long,
            out: FloatArray,
        ): Boolean {
            out.fill(Float.NaN)
            return true
        }
    }

    private companion object {
        const val RATE = 44_100
        const val FRAME_MS = 16

        fun song(n: Int) =
            Track(
                id = "song-$n",
                artist = "Artist $n",
                title = "Song $n",
                durationMs = 200_000,
                bitrateKbps = 128,
                sampleRateKhz = 44,
                uri = "file:///music/$n.mp3",
            )
    }
}
