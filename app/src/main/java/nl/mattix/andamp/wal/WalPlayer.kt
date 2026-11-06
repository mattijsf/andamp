// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.os.SystemClock
import nl.mattix.andamp.core.playback.AudioTap
import nl.mattix.andamp.skinplayer.Entry
import nl.mattix.andamp.skinplayer.HeldWrite
import nl.mattix.andamp.skinplayer.PlayerState
import nl.mattix.andamp.skinplayer.SkinPlayer
import nl.mattix.andamp.skinplayer.Transport
import nl.mattix.andamp.skinplayer.WinampFiveUnits
import nl.mattix.andamp.skinplayer.WinampFiveUnits.fromBalance
import nl.mattix.andamp.skinplayer.WinampFiveUnits.fromEq
import nl.mattix.andamp.skinplayer.WinampFiveUnits.fromPercent
import nl.mattix.andamp.skinplayer.WinampFiveUnits.toBalance
import nl.mattix.andamp.skinplayer.WinampFiveUnits.toEq
import nl.mattix.andamp.skinplayer.WinampFiveUnits.toPercent
import nl.mattix.andamp.state.AndampSkinPlayer
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.ui.window.SliderMath
import nl.mattix.andamp.ui.window.WindowControls
import nl.mattix.walkit.Player
import nl.mattix.walkit.Track as SkinTrack

/**
 * The player a modern skin drives: the modernskin [Player], on Winamp 5's scales, over a
 * [SkinPlayer] on Winamp 2's (Andamp's: [AndampSkinPlayer]).
 *
 * The engine calls it on the engine thread. Reads go to the player's snapshot and never throw;
 * writes go to the player's commands, which take effect later.
 *
 * The state follows a write only once the backend reports back, and the engine reads a value
 * back at once and tells the scripts about any change. So a written value is held ([HeldWrite]):
 * it reads as written while the state shows what the write becomes there, and for up to
 * [HeldWrite.HOLD_MS] while the state still shows a value from before the write. Any other value
 * is a change made elsewhere (the volume keys, the notification) and reads at once. When the slider moves the phone's
 * volume, a volume write becomes the nearest of the phone's steps, so it may land up to
 * [VOLUME_SLACK] from the percent it asked for.
 */
internal class WalPlayer(
    private val player: SkinPlayer,
    private val clock: () -> Long = SystemClock::uptimeMillis,
) : Player {
    /** The modern skin player over Andamp's: see [AndampSkinPlayer] for the arguments. */
    constructor(
        controls: WindowControls,
        state: WinampState,
        tapOf: () -> AudioTap?,
        positionMs: () -> Long,
        openFile: (andPlay: Boolean) -> Unit,
        post: (Runnable) -> Unit,
        clock: () -> Long = SystemClock::uptimeMillis,
    ) : this(AndampSkinPlayer(controls, state, tapOf, positionMs, openFile, post, clock), clock)

    private val state: PlayerState get() = player.state

    // ---- reads -----------------------------------------------------------------------------

    override val status: Player.Status
        get() =
            when (state.transport) {
                Transport.PLAYING, Transport.CONNECTING -> Player.Status.PLAYING
                Transport.PAUSED -> Player.Status.PAUSED
                Transport.STOPPED -> Player.Status.STOPPED
            }

    override val lengthMs: Int get() = toInt(state.current?.lengthMs ?: 0)

    /** The current row as the playlist writes it. */
    override val title: String get() = state.current?.title.orEmpty()

    override val bitrateKbps: Int get() = state.stream.bitrateKbps ?: 0

    override val sampleRateKhz: Int get() = state.stream.sampleRateKhz ?: 0

    /** Stereo when the backend does not say, as the classic window's lamps. */
    override val channels: Int get() = state.stream.channels ?: 2

    override val tags: Map<String, String> get() = state.current?.tags.orEmpty()

    override val positionMs: Int get() = toInt(player.positionMs())

    // ---- the volume, the equalizer and the switches -----------------------------------------

    private val volumeHeld = HeldWrite(VOLUME_SLACK, clock = clock)
    private val bandsHeld = Array(PlayerState.EQ_BANDS) { HeldWrite(clock = clock) }
    private val preampHeld = HeldWrite(clock = clock)
    private val panHeld = HeldWrite(clock = clock)
    private val shuffleHeld = HeldWrite(clock = clock)
    private val repeatHeld = HeldWrite(clock = clock)
    private val eqOnHeld = HeldWrite(clock = clock)

    override var volume: Int
        get() = volumeHeld.read(state.volume, ::fromPercent)
        set(value) {
            val v = value.coerceIn(0, WinampFiveUnits.TOP)
            val percent = toPercent(v)
            volumeHeld.write(v, percent, state.volume)
            player.setVolume(percent)
        }

    private val bands = IntArray(PlayerState.EQ_BANDS)

    /** The bands on the skin's scale; the same array each time, filled on every read. */
    override val eqBands: IntArray
        get() {
            val now = state.eqBands
            for (i in bands.indices) bands[i] = bandsHeld[i].read(now.getOrElse(i) { PlayerState.EQ_CENTER }, ::fromEq)
            return bands
        }

    override fun setEqBand(
        band: Int,
        value: Int,
    ) {
        if (band !in 0 until PlayerState.EQ_BANDS) return
        val v = value.coerceIn(0, WinampFiveUnits.TOP)
        bandsHeld[band].write(v, toEq(v), state.eqBands.getOrElse(band) { PlayerState.EQ_CENTER })
        player.setEqBand(band, toEq(v))
    }

    /** The preamp on the skin's scale; near 0 dB it snaps to 0 dB, as the classic window's does. */
    override var eqPreamp: Int
        get() = preampHeld.read(state.preamp, ::fromEq)
        set(value) {
            val v =
                value.coerceIn(0, WinampFiveUnits.TOP).let {
                    if (SliderMath.stickyPreamp(toEq(it)) ==
                        PlayerState.EQ_CENTER
                    ) {
                        WinampFiveUnits.FLAT
                    } else {
                        it
                    }
                }
            preampHeld.write(v, toEq(v), state.preamp)
            player.setPreamp(toEq(v))
        }

    /**
     * The balance on the skin's scale, -127..127, as the classic window's balance sets it; near the
     * middle it snaps to the middle, as that one does.
     */
    override var pan: Int
        get() = panHeld.read(state.balance, ::fromBalance)
        set(value) {
            val v =
                value.coerceIn(-WinampFiveUnits.PAN_REACH, WinampFiveUnits.PAN_REACH).let {
                    if (SliderMath.stickyBalance(toBalance(it)) ==
                        0
                    ) {
                        0
                    } else {
                        it
                    }
                }
            panHeld.write(v, toBalance(v), state.balance)
            player.setBalance(toBalance(v))
        }

    override var shuffle: Boolean
        get() = shuffleHeld.read(state.shuffle.bit) { it } == 1
        set(value) {
            shuffleHeld.write(value.bit, value.bit, state.shuffle.bit)
            player.setShuffle(value)
        }

    override var repeat: Boolean
        get() = repeatHeld.read(state.repeat.bit) { it } == 1
        set(value) {
            repeatHeld.write(value.bit, value.bit, state.repeat.bit)
            player.setRepeat(value)
        }

    override var eqEnabled: Boolean
        get() = eqOnHeld.read(state.eqOn.bit) { it } == 1
        set(value) {
            eqOnHeld.write(value.bit, value.bit, state.eqOn.bit)
            player.setEqEnabled(value)
        }

    /** Read off the player; turning it on does nothing where the player has no crossfade. */
    override var crossfade: Boolean
        get() = player.capabilities.crossfade && state.crossfade
        set(value) = player.setCrossfade(value)

    // ---- the transport and the playlist ----------------------------------------------------

    /** Plays, or on an empty playlist opens the file picker, as the classic Play does. */
    override fun play() = player.play()

    override fun pause() = player.pause()

    override fun stop() = player.stop()

    override fun next() = player.next()

    override fun previous() = player.previous()

    override fun eject() = player.requestOpen(andPlay = false)

    /** Seeks within the current song; see [SkinPlayer.seekTo]. */
    override fun seekTo(ms: Int) = player.seekTo(ms.toLong())

    /** The playlist's rows as the skin shows them, and the entries they were made from. */
    private var rowsOf: List<Entry>? = null
    private var rows: List<SkinTrack> = emptyList()

    /** The entries the version counts, and the count: one more each time the state holds other entries. */
    private var versionOf: List<Entry>? = null
    private var version = 0L

    /** Moves when the state's playlist is another list of entries. */
    override val playlistVersion: Long
        get() {
            val entries = state.playlist
            if (entries !== versionOf) {
                versionOf = entries
                version++
            }
            return version
        }

    /** The playlist's rows as it writes them; the same list until the entries change. */
    override val tracks: List<SkinTrack>
        get() {
            val entries = state.playlist
            if (entries !== rowsOf) {
                rows = entries.map { SkinTrack(it.title, toInt(it.lengthMs)) }
                rowsOf = entries
            }
            return rows
        }

    override val trackIndex: Int get() = state.currentIndex.coerceIn(0, maxOf(0, state.playlist.size - 1))

    override fun playTrack(index: Int) = player.playTrack(index)

    // ---- the spectrum, the waveform and the VU ------------------------------------------------

    override fun spectrum(): FloatArray = player.spectrum()

    override fun waveform(): FloatArray = player.waveform()

    override fun vu(): Pair<Int, Int> = player.vu()

    // ---- the wake ----------------------------------------------------------------------------

    /** What the skin shows of the player, as a value that changes when it does; see [AndampSkinPlayer.watched]. */
    fun watched(): Any = (player as? AndampSkinPlayer)?.watched() ?: state

    internal companion object {
        /**
         * How far in percent from what it asked for a volume write may land: half of a phone's
         * volume step at 15 steps, and the rounding on both sides.
         */
        private const val VOLUME_SLACK = 4

        private val Boolean.bit: Int get() = if (this) 1 else 0

        private fun toInt(ms: Long): Int = ms.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }
}
