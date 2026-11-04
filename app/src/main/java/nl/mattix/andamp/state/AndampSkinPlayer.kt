// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.os.SystemClock
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.playback.AudioTap
import nl.mattix.andamp.skinplayer.Capabilities
import nl.mattix.andamp.skinplayer.Entry
import nl.mattix.andamp.skinplayer.PlayerState
import nl.mattix.andamp.skinplayer.SkinPlayer
import nl.mattix.andamp.skinplayer.StreamInfo
import nl.mattix.andamp.ui.window.WindowControls
import nl.mattix.andamp.ui.window.playOrOpen
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.roundToInt
import nl.mattix.andamp.skinplayer.Transport as SkinTransport

/**
 * Andamp's player as a skin drives it ([SkinPlayer]): the app's [WindowControls], its
 * [WinampState] and the backend's audio tap.
 *
 * Reads are snapshots over the state, which any thread can read; a snapshot is made again only
 * when something it came from changed, so reading it every frame costs little. Commands are
 * posted to main with [post]: the volume, the equalizer and the switches are merged, so only the
 * latest value waits, and the transport, seeks and playlist picks keep their order.
 *
 * @param positionMs the backend's place in the song
 * @param openFile the file picker, called on main: true when Play opens it on an empty playlist,
 *   false for eject
 * @param post runs a task on main
 */
@Suppress("TooManyFunctions") // every command and signal SkinPlayer asks for, plus the position rules
internal class AndampSkinPlayer(
    private val controls: WindowControls,
    private val winamp: WinampState,
    private val tapOf: () -> AudioTap?,
    positionMs: () -> Long,
    private val openFile: (andPlay: Boolean) -> Unit,
    private val post: (Runnable) -> Unit,
    private val clock: () -> Long = SystemClock::uptimeMillis,
) : SkinPlayer {
    private val backendMs = positionMs

    /** The player has no crossfade. */
    override val capabilities = Capabilities(crossfade = false)

    // ---- the snapshot ------------------------------------------------------------------------

    /** What the last snapshot was made from, each list compared by identity. */
    private var madeFrom: List<Any?> = emptyList()
    private var snapshot = PlayerState()

    /** The playlist's entries and what their text came from, made again only when that changes. */
    private var entriesFrom: List<Any?> = emptyList()
    private var entries: List<Entry> = emptyList()

    /** The playlist the last snapshot showed, for [playTrack]. */
    private var shownPlaylist: List<Track> = emptyList()

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    override val state: PlayerState
        get() {
            val from = sources()
            if (sameAs(from, madeFrom)) return snapshot
            val changed = madeFrom.isNotEmpty()
            madeFrom = from
            val next = make()
            if (next.copy(version = snapshot.version) != snapshot) {
                snapshot = next.copy(version = snapshot.version + 1)
                if (changed) listeners.forEach { it() }
            }
            return snapshot
        }

    /**
     * Hears each change the next time the state is read: the state is read where it is made, so a
     * change shows when a reader (a skin's frame, the app's screen) looks.
     */
    override fun addListener(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    /** Every field of the state a snapshot reads, the lists as they are (compared by identity). */
    private fun sources(): List<Any?> =
        listOf(
            winamp.transport,
            winamp.connecting,
            winamp.currentIndex,
            winamp.playlist,
            winamp.rowFormat,
            winamp.rowInfo,
            winamp.reach,
            winamp.streamBitrateKbps,
            winamp.streamSampleRateKhz,
            winamp.streamChannels,
            winamp.volume,
            winamp.balance,
            winamp.shuffle,
            winamp.repeat,
            winamp.eqOn,
            winamp.eqAuto,
            winamp.preamp,
            winamp.eqBands.toList(),
        )

    private fun sameAs(
        a: List<Any?>,
        b: List<Any?>,
    ): Boolean =
        a.size == b.size &&
            a.indices.all { i ->
                val x = a[i]
                val y = b[i]
                if (x is List<*> && i != BANDS_AT) x === y else x == y
            }

    private fun make(): PlayerState {
        val playlist = winamp.playlist
        val connectingAt = if (winamp.connecting) winamp.currentIndex else -1
        val rowsFrom = listOf(playlist, winamp.rowFormat, winamp.rowInfo, winamp.reach, connectingAt)
        if (!sameAs(rowsFrom, entriesFrom)) {
            entriesFrom = rowsFrom
            entries =
                playlist.mapIndexed { i, track ->
                    Entry(winamp.rowLabel(track, i), lengthOf(track), tagsOf(track))
                }
        }
        shownPlaylist = playlist
        val transport =
            when (winamp.transport) {
                Transport.Playing -> if (winamp.connecting) SkinTransport.CONNECTING else SkinTransport.PLAYING
                Transport.Paused -> SkinTransport.PAUSED
                Transport.Stopped -> SkinTransport.STOPPED
            }
        return PlayerState(
            transport = transport,
            playlist = entries,
            currentIndex = winamp.currentIndex,
            stream = StreamInfo(winamp.bitrateKbps, winamp.sampleRateKhz, winamp.streamChannels),
            volume = winamp.volume,
            balance = winamp.balance,
            shuffle = winamp.shuffle,
            repeat = winamp.repeat,
            crossfade = false,
            eqOn = winamp.eqOn,
            eqAuto = winamp.eqAuto,
            preamp = winamp.preamp,
            eqBands = winamp.eqBands.toList(),
        )
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
    override fun positionMs(): Long {
        val track = winamp.currentTrack
        val transport = winamp.transport
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
        val length = lengthOf(track)
        return if (length > 0) minOf(shownMs, length) else shownMs
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

    override fun setVolume(volume: Int) = merge { it.volume = volume.coerceIn(0, PlayerState.VOLUME_MAX) }

    override fun setEqBand(
        band: Int,
        value: Int,
    ) {
        if (band !in 0 until PlayerState.EQ_BANDS) return
        merge { it.bands[band] = value.coerceIn(0, PlayerState.EQ_MAX) }
    }

    override fun setPreamp(value: Int) = merge { it.preamp = value.coerceIn(0, PlayerState.EQ_MAX) }

    override fun setBalance(balance: Int) =
        merge { it.balance = balance.coerceIn(-PlayerState.BALANCE_MAX, PlayerState.BALANCE_MAX) }

    override fun setShuffle(on: Boolean) = merge { it.shuffle = on }

    override fun setRepeat(on: Boolean) = merge { it.repeat = on }

    override fun setEqEnabled(on: Boolean) = merge { it.eqOn = on }

    /** AUTO is set from the equalizer's presets menu; a skin cannot set it here. */
    override fun setEqAuto(on: Boolean) = Unit

    /** The player has no crossfade. */
    override fun setCrossfade(on: Boolean) = Unit

    override fun resetEq() =
        onMain {
            controls.resetBands()
            controls.setPreamp(PlayerState.EQ_CENTER)
        }

    /** Merged writes on their way to main; guarded by itself. */
    private class Pending {
        var volume: Int? = null
        val bands = arrayOfNulls<Int>(PlayerState.EQ_BANDS)
        var preamp: Int? = null
        var balance: Int? = null
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
                out.balance = balance
                out.shuffle = shuffle
                out.repeat = repeat
                out.eqOn = eqOn
                volume = null
                bands.fill(null)
                preamp = null
                balance = null
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

    /** Applies the merged writes, on main. A switch is pressed only when the state differs from what was asked. */
    private fun applyMerged() {
        val p = synchronized(pending) { pending.take() }
        p.volume?.let { controls.setVolume(it / PlayerState.VOLUME_MAX.toFloat()) }
        p.bands.forEachIndexed { band, v -> v?.let { controls.setBand(band, it) } }
        p.preamp?.let { controls.setPreamp(it) }
        p.balance?.let { controls.setBalance(it) }
        p.eqOn?.let { if (winamp.eqOn != it) controls.toggleEq() }
        p.shuffle?.let { if (winamp.shuffle != it) controls.toggleShuffle() }
        p.repeat?.let { if (winamp.repeat != it) controls.toggleRepeat() }
    }

    private fun onMain(task: () -> Unit) = post(Runnable(task))

    // ---- the transport and the playlist ----------------------------------------------------

    /** Plays, or on an empty playlist opens the file picker, as the classic Play does. */
    override fun play() = onMain { playOrOpen(winamp.playlist, controls::play) { openFile(true) } }

    override fun pause() = onMain { controls.pause() }

    override fun stop() = onMain { controls.stop() }

    override fun next() = onMain { controls.next() }

    override fun previous() = onMain { controls.previous() }

    override fun requestOpen(andPlay: Boolean) = onMain { openFile(andPlay) }

    /**
     * Seeks within the current song. A station, a song of unknown length and a stopped player do
     * not seek, as on the classic seek bar. The place reads as [ms] until the backend gets there.
     */
    override fun seekTo(ms: Long) {
        val track = winamp.currentTrack ?: return
        val length = lengthOf(track)
        val transport = winamp.transport
        if (length <= 0 || transport == Transport.Stopped) return
        val target = ms.coerceIn(0, length)
        val now = clock()
        observe(transport, now)
        seekHold = SeekHold(track.id, target, now, lastRaw, rawAt)
        shownFor = track.id
        shownMs = target
        onMain { if (winamp.currentTrack?.id == track.id) controls.seekTo(target / length.toFloat()) }
    }

    /**
     * Plays entry [index] of the playlist the last snapshot showed. On main the row is checked by its
     * id and address: when it still sits at [index] that row plays, and when it moved, the row with
     * the same id and address nearest [index], so a song queued twice plays the copy picked.
     */
    override fun playTrack(index: Int) {
        val track = shownPlaylist.ifEmpty { winamp.playlist }.getOrNull(index) ?: return
        onMain {
            val playlist = winamp.playlist
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

    // ---- the spectrum, the waveform and the VU ------------------------------------------------

    private var columns: SpectrumColumns? = null
    private val levels = FloatArray(SPECTRUM_BANDS)
    private val wave = FloatArray(WAVE_SAMPLES)
    private var meter = 0 to 0
    private var steppedAt = NEVER

    /** [SPECTRUM_BANDS] levels 0..1 from the audio being heard, or empty while nothing plays or there is no tap. */
    override fun spectrum(): FloatArray = if (step()) levels else EMPTY

    /**
     * The newest [WAVE_SAMPLES] samples of the audio being heard, -1..1, or empty while nothing
     * plays or there is no tap; silence while the tap has nothing to give.
     */
    override fun waveform(): FloatArray = if (step()) wave else EMPTY

    /** The loudest sample of the window being heard, 0..255, the same for both sides. */
    override fun vu(): Pair<Int, Int> = if (step()) meter else SILENT

    /**
     * Reads the tap again once [FRAME_MS] or more have passed, with the time that really went by,
     * and keeps the last read in between. The tap is taken the first time there is one. False while
     * nothing plays or there is no tap.
     */
    private fun step(): Boolean {
        if (winamp.transport != Transport.Playing) return false
        val c =
            columns ?: tapOf()?.let { SpectrumColumns(it, FRAME_MS, SPECTRUM_BANDS, WAVE_SAMPLES) }?.also { columns = it }
                ?: return false
        val now = clock()
        val elapsed = if (steppedAt == NEVER) FRAME_MS else now - steppedAt
        if (elapsed >= FRAME_MS) {
            steppedAt = now
            if (c.read(elapsed)) {
                for (i in levels.indices) levels[i] = (c.values[i] / SpectrumPhysics.MAX).coerceIn(0f, 1f)
                c.wave.copyInto(wave)
            } else {
                levels.fill(0f)
                wave.fill(0f)
            }
            val level = (c.peak * VU_TOP).roundToInt().coerceIn(0, VU_TOP)
            if (level != meter.first) meter = level to level
        }
        return true
    }

    // ---- the wake ----------------------------------------------------------------------------

    /** Every field of the state a skin reads, as one value. */
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
     * What a skin shows of the player, as a value that changes when the state behind any read
     * changes; read on main to wake the engine.
     */
    fun watched(): Any =
        Watched(
            transport = winamp.transport,
            timeSec = winamp.currentTimeSec,
            index = winamp.currentIndex,
            playlist = winamp.playlist,
            rows = listOf(winamp.rowFormat, winamp.rowInfo, winamp.reach, winamp.connecting),
            stream = listOf(winamp.streamBitrateKbps, winamp.streamSampleRateKhz, winamp.streamChannels),
            volume = winamp.volume,
            switches = listOf(winamp.shuffle, winamp.repeat, winamp.eqOn),
            bands = winamp.eqBands.toList(),
            preamp = winamp.preamp,
        )

    init {
        // the first snapshot, so the first change after it is told
        state
    }

    internal companion object {
        /** How long a seek's target outlasts a backend that still reports the old place. */
        private const val HOLD_MS = 1_000L

        /** How far the place may run past the backend's last report. */
        private const val MAX_LEAD_MS = 1_000L

        /** How near the backend's place must come to a seek's target for the seek to count as done. */
        private const val SEEK_NEAR_MS = 1_000L

        /** How often the spectrum and the waveform are read at most. */
        private const val FRAME_MS = 16L

        /** Winamp's vis has 75 bands. */
        private const val SPECTRUM_BANDS = 75

        /** Winamp hands a visualizer 576 samples of the waveform. */
        private const val WAVE_SAMPLES = 576

        /** The top of the VU. */
        private const val VU_TOP = 255

        /** Where the bands sit in [sources]: a list compared by its values, not by identity. */
        private const val BANDS_AT = 17

        private const val NOT_SHOWN = -1L
        private const val NEVER = Long.MIN_VALUE
        private val EMPTY = FloatArray(0)
        private val SILENT = 0 to 0

        fun lengthOf(track: Track?): Long = if (track == null || track.isStream) 0 else track.durationMs.coerceAtLeast(0)

        private fun tagsOf(track: Track): Map<String, String> =
            buildMap {
                if (track.artist.isNotBlank()) put("artist", track.artist)
                if (track.title.isNotBlank()) put("title", track.title)
            }
    }
}
