// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.os.SystemClock
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.playback.AudioTap
import nl.mattix.andamp.modernskin.skin.Player
import nl.mattix.andamp.state.EqOps
import nl.mattix.andamp.state.SpectrumColumns
import nl.mattix.andamp.state.SpectrumPhysics
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.rowLabel
import nl.mattix.andamp.ui.window.WindowControls
import nl.mattix.andamp.ui.window.playOrOpen
import kotlin.math.abs
import kotlin.math.roundToInt
import nl.mattix.andamp.modernskin.skin.Track as SkinTrack

/**
 * The player a modern skin drives: the modernskin [Player] over the app's [WindowControls], its
 * [WinampState] and the backend's audio tap.
 *
 * The engine calls it on the engine thread. Reads are getters over the state, which any thread
 * can read, and they never throw. Writes are posted to main with [post]: the volume, the
 * equalizer and the switches are merged, so only the latest value waits, and the transport, seeks
 * and playlist picks keep their order.
 *
 * The state follows a write only once the backend reports back, and the engine reads a value
 * back at once and tells the scripts about any change. So a written value is held: it reads as
 * written while the state shows what the write becomes there, and for up to [HOLD_MS] while the
 * state still shows a value from before the write. Any other value is a change made elsewhere
 * (the volume keys, the notification) and reads at once. When the slider moves the phone's
 * volume, a volume write becomes the nearest of the phone's steps, so it may land up to
 * [VOLUME_SLACK] from the percent it asked for.
 *
 * @param positionMs the backend's place in the song, read on the engine thread
 * @param openFile the file picker, called on main: true when Play opens it on an empty playlist,
 *   false for eject
 * @param post runs a task on main
 */
internal class WalPlayer(
    private val controls: WindowControls,
    private val state: WinampState,
    private val tapOf: () -> AudioTap?,
    positionMs: () -> Long,
    private val openFile: (andPlay: Boolean) -> Unit,
    private val post: (Runnable) -> Unit,
    private val clock: () -> Long = SystemClock::uptimeMillis,
) : Player {
    private val backendMs = positionMs

    // ---- reads -----------------------------------------------------------------------------

    override val status: Player.Status
        get() =
            when (state.transport) {
                Transport.Playing -> Player.Status.PLAYING
                Transport.Paused -> Player.Status.PAUSED
                Transport.Stopped -> Player.Status.STOPPED
            }

    override val lengthMs: Int get() = lengthOf(state.currentTrack)

    /** The current row as the playlist writes it. */
    override val title: String
        get() {
            val index = state.currentIndex
            return state.playlist
                .getOrNull(index)
                ?.let { state.rowLabel(it, index) }
                .orEmpty()
        }

    override val bitrateKbps: Int get() = state.bitrateKbps ?: 0

    override val sampleRateKhz: Int get() = state.sampleRateKhz ?: 0

    /** Stereo when the backend does not say, as the classic window's lamps. */
    override val channels: Int get() = state.streamChannels ?: 2

    override val tags: Map<String, String>
        get() {
            val track = state.currentTrack ?: return emptyMap()
            return buildMap {
                if (track.artist.isNotBlank()) put("artist", track.artist)
                if (track.title.isNotBlank()) put("title", track.title)
            }
        }

    // ---- the place in the song -------------------------------------------------------------

    /** The backend's last place, and when this player first saw it. */
    private var lastRaw = -1L
    private var rawAt = 0L
    private var lastTransport = Transport.Stopped

    /** The place last read out, and the track it belongs to; [NOT_SHOWN] after a stop or a new track. */
    private var shownMs = NOT_SHOWN
    private var shownFor: String? = null

    /** A seek on its way: where to, when it was asked, and the backend's place then. */
    private class SeekHold(
        val trackId: String,
        val target: Long,
        val at: Long,
        val oldMs: Long,
        val oldAt: Long,
    )

    private var seekHold: SeekHold? = null

    /**
     * The backend's place, carried on with [clock] while playing, since the backend reports it a
     * few times a second: never more than [MAX_LEAD_MS] past its last report and never past the
     * length. Paused, it holds. A step back of up to a second (a report that trails the carried
     * place, a seek landing a little short) holds the place until the song catches up, so a seek
     * bar's thumb never jumps back by itself.
     */
    override val positionMs: Int
        get() {
            val track = state.currentTrack
            val transport = state.transport
            if (track == null || transport == Transport.Stopped) {
                seekHold = null
                shownMs = NOT_SHOWN
                lastTransport = Transport.Stopped
                return 0
            }
            val now = clock()
            val raw = observe(transport, now)
            if (track.id != shownFor) {
                shownFor = track.id
                shownMs = NOT_SHOWN
            }
            val playing = transport == Transport.Playing
            val place = heldSeek(track, playing, now) ?: (raw + if (playing) minOf(now - rawAt, MAX_LEAD_MS) else 0)
            if (place >= shownMs || shownMs - place > MAX_LEAD_MS) shownMs = place
            val length = lengthOf(track).toLong()
            return (if (length > 0) minOf(shownMs, length) else shownMs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

    /** Reads the backend's place; the carried place starts again from each new report and from each change of [transport]. */
    private fun observe(
        transport: Transport,
        now: Long,
    ): Long {
        val raw = backendMs().coerceAtLeast(0)
        if (raw != lastRaw || transport != lastTransport) {
            lastRaw = raw
            rawAt = now
            lastTransport = transport
        }
        return raw
    }

    /**
     * Where a seek on its way shows: at its target until the backend's place lands within a second
     * of it, for up to [HOLD_MS] while the backend is still where the song was. A place that is
     * neither ends it at once. Null when no seek waits.
     */
    private fun heldSeek(
        track: Track,
        playing: Boolean,
        now: Long,
    ): Long? {
        val hold = seekHold ?: return null
        val oldCourse = hold.oldMs + if (playing) rawAt - hold.oldAt else 0
        val toTarget = abs(lastRaw - hold.target)
        val toOld = abs(lastRaw - oldCourse)
        val landed = toTarget <= SEEK_NEAR_MS && toTarget < toOld
        val waiting = toOld <= SEEK_NEAR_MS && now - hold.at < HOLD_MS
        if (hold.trackId != track.id || landed || !waiting) {
            seekHold = null
            return null
        }
        return hold.target
    }

    // ---- the volume, the equalizer and the switches -----------------------------------------

    /**
     * One written value; see the class comment. [before] holds what the state showed at each write
     * since the last one landed, and [earlier] the images of the writes that had not landed, since
     * those can still arrive first, each within [slack].
     *
     * A write lands within [slack] of its image, or on the image once the state has shown it: the
     * first such value that is not from before is where it landed, and only that value holds it
     * from then on. A value from before that is also where the write can land holds it, and after
     * [HOLD_MS] counts as where it landed, since a write can land on the value the state showed.
     */
    private inner class Held(
        private val slack: Int = 0,
    ) {
        private var active = false
        private var landed = false
        private var shown = false
        private var value = 0
        private var image = 0
        private var at = 0L
        private val before = HashSet<Int>()
        private val earlier = HashSet<Int>()

        fun write(
            value: Int,
            image: Int,
            mirror: Int,
        ) {
            if (active && !landed) {
                earlier += this.image
            } else {
                before.clear()
                earlier.clear()
            }
            earlier -= image
            before += mirror
            this.value = value
            this.image = image
            at = clock()
            active = true
            landed = false
            shown = false
        }

        /** The held value, or the state's [mirror] through [unheld] once the write no longer holds. */
        fun read(
            mirror: Int,
            unheld: (Int) -> Int,
        ): Int {
            if (active && !holds(mirror)) active = false
            return if (active) value else unheld(mirror)
        }

        /** Whether the write still holds while the state shows [mirror]; notes where it landed. */
        private fun holds(mirror: Int): Boolean {
            if (landed) return mirror == image
            if (mirror == image) shown = true
            val lands = abs(mirror - image) <= if (shown) 0 else slack
            val fromBefore = mirror in before || earlier.any { abs(it - mirror) <= slack }
            val waited = clock() - at
            if (lands && (!fromBefore || waited >= HOLD_MS)) {
                image = mirror
                landed = true
            }
            return lands || (fromBefore && waited < HOLD_MS)
        }
    }

    private val volumeHeld = Held(VOLUME_SLACK)
    private val bandsHeld = Array(EQ_BANDS) { Held() }
    private val preampHeld = Held()
    private val shuffleHeld = Held()
    private val repeatHeld = Held()
    private val eqOnHeld = Held()

    override var volume: Int
        get() = volumeHeld.read(state.volume, ::fromPercent)
        set(value) {
            val v = value.coerceIn(0, SKIN_TOP)
            volumeHeld.write(v, toPercent(v), state.volume)
            merge { it.volume = v }
        }

    private val bands = IntArray(EQ_BANDS)

    /** The bands on the skin's scale; the same array each time, filled on every read. */
    override val eqBands: IntArray
        get() {
            for (i in bands.indices) bands[i] = bandsHeld[i].read(state.eqBands.getOrElse(i) { EqOps.CENTER }, ::fromEq)
            return bands
        }

    override fun setEqBand(
        band: Int,
        value: Int,
    ) {
        if (band !in 0 until EQ_BANDS) return
        val v = value.coerceIn(0, SKIN_TOP)
        bandsHeld[band].write(v, toEq(v), state.eqBands.getOrElse(band) { EqOps.CENTER })
        merge { it.bands[band] = v }
    }

    /**
     * The preamp on the skin's scale. A skin's slider sets the value it shows, without the classic
     * window's snap to 0 dB.
     */
    override var eqPreamp: Int
        get() = preampHeld.read(state.preamp, ::fromEq)
        set(value) {
            val v = value.coerceIn(0, SKIN_TOP)
            preampHeld.write(v, toEq(v), state.preamp)
            merge { it.preamp = v }
        }

    override var shuffle: Boolean
        get() = shuffleHeld.read(state.shuffle.bit) { it } == 1
        set(value) {
            shuffleHeld.write(value.bit, value.bit, state.shuffle.bit)
            merge { it.shuffle = value }
        }

    override var repeat: Boolean
        get() = repeatHeld.read(state.repeat.bit) { it } == 1
        set(value) {
            repeatHeld.write(value.bit, value.bit, state.repeat.bit)
            merge { it.repeat = value }
        }

    override var eqEnabled: Boolean
        get() = eqOnHeld.read(state.eqOn.bit) { it } == 1
        set(value) {
            eqOnHeld.write(value.bit, value.bit, state.eqOn.bit)
            merge { it.eqOn = value }
        }

    /** The player has no crossfade: it reads off, and turning it on does nothing. */
    override var crossfade: Boolean
        get() = false
        set(value) = Unit

    /** Merged writes on their way to main; guarded by itself. */
    private class Pending {
        var volume: Int? = null
        val bands = arrayOfNulls<Int>(EQ_BANDS)
        var preamp: Int? = null
        var shuffle: Boolean? = null
        var repeat: Boolean? = null
        var eqOn: Boolean? = null

        /** Whether a task to apply these is posted and has not run yet. */
        var posted = false

        /** These values, leaving this one empty. */
        fun take(): Pending =
            Pending().also { out ->
                out.volume = volume
                bands.copyInto(out.bands)
                out.preamp = preamp
                out.shuffle = shuffle
                out.repeat = repeat
                out.eqOn = eqOn
                volume = null
                bands.fill(null)
                preamp = null
                shuffle = null
                repeat = null
                eqOn = null
                posted = false
            }
    }

    private val pending = Pending()

    /** Records a merged write, and posts the task that applies them unless one waits already. */
    private fun merge(change: (Pending) -> Unit) {
        val first =
            synchronized(pending) {
                change(pending)
                val was = pending.posted
                pending.posted = true
                !was
            }
        if (first) onMain(::applyMerged)
    }

    /** Applies the merged writes, on main. A switch is pressed only when the state differs from what the skin asked. */
    private fun applyMerged() {
        val p = synchronized(pending) { pending.take() }
        p.volume?.let { controls.setVolume(it / SKIN_TOP.toFloat()) }
        p.bands.forEachIndexed { band, v -> v?.let { controls.setBand(band, toEq(it)) } }
        p.preamp?.let { controls.setPreamp(toEq(it)) }
        p.eqOn?.let { if (state.eqOn != it) controls.toggleEq() }
        p.shuffle?.let { if (state.shuffle != it) controls.toggleShuffle() }
        p.repeat?.let { if (state.repeat != it) controls.toggleRepeat() }
    }

    private fun onMain(task: () -> Unit) = post(Runnable(task))

    // ---- the transport and the playlist ----------------------------------------------------

    /** Plays, or on an empty playlist opens the file picker, as the classic Play does. */
    override fun play() = onMain { playOrOpen(state.playlist, controls::play) { openFile(true) } }

    override fun pause() = onMain { controls.pause() }

    override fun stop() = onMain { controls.stop() }

    override fun next() = onMain { controls.next() }

    override fun previous() = onMain { controls.previous() }

    override fun eject() = onMain { openFile(false) }

    /**
     * Seeks within the current song. A station, a song of unknown length and a stopped player do
     * not seek, as on the classic seek bar. The place reads as [ms] until the backend gets there.
     */
    override fun seekTo(ms: Int) {
        val track = state.currentTrack ?: return
        val length = lengthOf(track)
        val transport = state.transport
        if (length <= 0 || transport == Transport.Stopped) return
        val target = ms.coerceIn(0, length).toLong()
        val now = clock()
        observe(transport, now)
        seekHold = SeekHold(track.id, target, now, lastRaw, rawAt)
        shownFor = track.id
        shownMs = target
        onMain { if (state.currentTrack?.id == track.id) controls.seekTo(target / length.toFloat()) }
    }

    /**
     * The rows a skin's playlist shows, and what their text came from: the playlist, the row format,
     * what the library knows of the rows and which sources are reachable, each compared by identity,
     * and the row being connected to.
     */
    private class Rows(
        val playlist: List<Track>,
        val from: List<Any?>,
        val connectingAt: Int,
        val tracks: List<SkinTrack>,
    ) {
        fun madeFrom(
            from: List<Any?>,
            connectingAt: Int,
        ): Boolean = connectingAt == this.connectingAt && from.indices.all { from[it] === this.from[it] }
    }

    private var rows: Rows? = null

    /** The playlist's rows as it writes them; the same list until something their text comes from changes. */
    override val tracks: List<SkinTrack>
        get() {
            val playlist = state.playlist
            val from = listOf(playlist, state.rowFormat, state.rowInfo, state.reach)
            val connectingAt = if (state.connecting) state.currentIndex else -1
            rows?.takeIf { it.madeFrom(from, connectingAt) }?.let { return it.tracks }
            val tracks = playlist.mapIndexed { i, track -> SkinTrack(state.rowLabel(track, i), lengthOf(track)) }
            rows = Rows(playlist, from, connectingAt, tracks)
            return tracks
        }

    override val trackIndex: Int get() = state.currentIndex.coerceIn(0, maxOf(0, state.playlist.size - 1))

    /**
     * Plays row [index] of the playlist the skin last read. On main the row is checked by its id
     * and address: when it still sits at [index] that row plays, and when it moved, the row with
     * the same id and address nearest [index], so a song queued twice plays the copy picked.
     */
    override fun playTrack(index: Int) {
        val track = (rows?.playlist ?: state.playlist).getOrNull(index) ?: return
        onMain {
            val playlist = state.playlist
            val at =
                if (playlist.getOrNull(index)?.isRow(track) == true) {
                    index
                } else {
                    playlist.indices.filter { playlist[it].isRow(track) }.minByOrNull { abs(it - index) }
                }
            if (at != null) controls.playTrack(at)
        }
    }

    private fun Track.isRow(track: Track) = id == track.id && uri == track.uri

    // ---- the spectrum and the VU -------------------------------------------------------------

    private var columns: SpectrumColumns? = null
    private val levels = FloatArray(SPECTRUM_BANDS)
    private var meter = 0 to 0
    private var steppedAt = NEVER

    /** [SPECTRUM_BANDS] levels 0..1 from the audio being heard, or empty while nothing plays or there is no tap. */
    override fun spectrum(): FloatArray = if (step()) levels else EMPTY

    /** The loudest sample of the window being heard, 0..255, the same for both sides. */
    override fun vu(): Pair<Int, Int> = if (step()) meter else SILENT

    /**
     * Reads the tap again once [FRAME_MS] or more have passed, with the time that really went by,
     * and keeps the last read in between. The tap is taken the first time there is one. False while
     * nothing plays or there is no tap.
     */
    private fun step(): Boolean {
        if (state.transport != Transport.Playing) return false
        val c = columns ?: tapOf()?.let { SpectrumColumns(it, FRAME_MS, SPECTRUM_BANDS) }?.also { columns = it } ?: return false
        val now = clock()
        val elapsed = if (steppedAt == NEVER) FRAME_MS else now - steppedAt
        if (elapsed >= FRAME_MS) {
            steppedAt = now
            if (c.read(elapsed)) {
                for (i in levels.indices) levels[i] = (c.values[i] / SpectrumPhysics.MAX).coerceIn(0f, 1f)
            } else {
                levels.fill(0f)
            }
            val level = (c.peak * SKIN_TOP).roundToInt().coerceIn(0, SKIN_TOP)
            if (level != meter.first) meter = level to level
        }
        return true
    }

    // ---- the wake ----------------------------------------------------------------------------

    /** Every field of the state the getters read, as one value. */
    private data class Watched(
        val transport: Transport,
        val timeSec: Int,
        val index: Int,
        val playlist: List<Track>,
        val rows: List<Any?>,
        val stream: List<Int?>,
        val volume: Int,
        val switches: List<Boolean>,
        val bands: List<Int>,
        val preamp: Int,
    )

    /**
     * What the skin shows of the player, as a value that changes when the state behind any getter
     * changes; read on main to wake the engine.
     */
    fun watched(): Any =
        Watched(
            transport = state.transport,
            timeSec = state.currentTimeSec,
            index = state.currentIndex,
            playlist = state.playlist,
            rows = listOf(state.rowFormat, state.rowInfo, state.reach, state.connecting),
            stream = listOf(state.streamBitrateKbps, state.streamSampleRateKhz, state.streamChannels),
            volume = state.volume,
            switches = listOf(state.shuffle, state.repeat, state.eqOn),
            bands = state.eqBands.toList(),
            preamp = state.preamp,
        )

    internal companion object {
        /** How long a written value outlasts a state that still shows the old one. */
        private const val HOLD_MS = 1_000L

        /**
         * How far in percent from what it asked for a volume write may land: half of a phone's
         * volume step at 15 steps, and the rounding on both sides.
         */
        private const val VOLUME_SLACK = 4

        /** How far the place may run past the backend's last report. */
        private const val MAX_LEAD_MS = 1_000L

        /** How near the backend's place must come to a seek's target for the seek to count as done. */
        private const val SEEK_NEAR_MS = 1_000L

        /** How often the spectrum is read at most. */
        private const val FRAME_MS = 16L

        /** Winamp's vis has 75 bands. */
        private const val SPECTRUM_BANDS = 75

        /** The top of the skin's scale for the volume, the equalizer and the VU. */
        private const val SKIN_TOP = 255

        /** The equalizer's flat on the skin's scale. */
        private const val SKIN_FLAT = 128

        /** The top of the app's equalizer scale, whose flat is [EqOps.CENTER]. */
        private const val EQ_TOP = 63

        private const val PERCENT = 100
        private const val EQ_BANDS = WinampState.EQ_BANDS
        private const val NOT_SHOWN = -1L
        private const val NEVER = Long.MIN_VALUE
        private val EMPTY = FloatArray(0)
        private val SILENT = 0 to 0

        private val Boolean.bit: Int get() = if (this) 1 else 0

        /** The state's volume, 0..100, on the skin's 0..255. */
        fun fromPercent(percent: Int): Int = (percent * SKIN_TOP / PERCENT.toFloat()).roundToInt()

        /**
         * A skin volume as the state will show it once the backend has it, rounded as the backend
         * reports it; on the phone's volume steps it lands within [VOLUME_SLACK] of this.
         */
        fun toPercent(v: Int): Int = (v / SKIN_TOP.toFloat() * PERCENT).roundToInt()

        /**
         * The app's 0..63 band, flat at 32, on the skin's 0..255, flat at 128. Below flat and above it
         * the slopes differ, so the ends and the flat land on each other.
         */
        fun fromEq(v: Int): Int =
            if (v <= EqOps.CENTER) {
                (v * SKIN_FLAT / EqOps.CENTER.toFloat()).roundToInt()
            } else {
                SKIN_FLAT + ((v - EqOps.CENTER) * (SKIN_TOP - SKIN_FLAT) / (EQ_TOP - EqOps.CENTER).toFloat()).roundToInt()
            }

        /** A skin band on the app's scale; the inverse of [fromEq]. */
        fun toEq(v: Int): Int =
            if (v <= SKIN_FLAT) {
                (v * EqOps.CENTER / SKIN_FLAT.toFloat()).roundToInt()
            } else {
                EqOps.CENTER + ((v - SKIN_FLAT) * (EQ_TOP - EqOps.CENTER) / (SKIN_TOP - SKIN_FLAT).toFloat()).roundToInt()
            }

        private fun lengthOf(track: Track?): Int =
            if (track == null || track.isStream) 0 else track.durationMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }
}
