// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.os.SystemClock
import nl.mattix.andamp.core.playback.AudioTap
import nl.mattix.andamp.modernskin.skin.Player
import nl.mattix.andamp.skinplayer.Entry
import nl.mattix.andamp.skinplayer.PlayerState
import nl.mattix.andamp.skinplayer.SkinPlayer
import nl.mattix.andamp.skinplayer.Transport
import nl.mattix.andamp.state.AndampSkinPlayer
import nl.mattix.andamp.state.EqOps
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.ui.window.SliderMath
import nl.mattix.andamp.ui.window.WindowControls
import kotlin.math.abs
import kotlin.math.roundToInt
import nl.mattix.andamp.modernskin.skin.Track as SkinTrack

/**
 * The player a modern skin drives: the modernskin [Player], on Winamp 5's scales, over a
 * [SkinPlayer] on Winamp 2's (Andamp's: [AndampSkinPlayer]).
 *
 * The engine calls it on the engine thread. Reads go to the player's snapshot and never throw;
 * writes go to the player's commands, which take effect later.
 *
 * The state follows a write only once the backend reports back, and the engine reads a value
 * back at once and tells the scripts about any change. So a written value is held: it reads as
 * written while the state shows what the write becomes there, and for up to [HOLD_MS] while the
 * state still shows a value from before the write. Any other value is a change made elsewhere
 * (the volume keys, the notification) and reads at once. When the slider moves the phone's
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
    private val bandsHeld = Array(PlayerState.EQ_BANDS) { Held() }
    private val preampHeld = Held()
    private val panHeld = Held()
    private val shuffleHeld = Held()
    private val repeatHeld = Held()
    private val eqOnHeld = Held()

    override var volume: Int
        get() = volumeHeld.read(state.volume, ::fromPercent)
        set(value) {
            val v = value.coerceIn(0, SKIN_TOP)
            val percent = toPercent(v)
            volumeHeld.write(v, percent, state.volume)
            player.setVolume(percent)
        }

    private val bands = IntArray(PlayerState.EQ_BANDS)

    /** The bands on the skin's scale; the same array each time, filled on every read. */
    override val eqBands: IntArray
        get() {
            val now = state.eqBands
            for (i in bands.indices) bands[i] = bandsHeld[i].read(now.getOrElse(i) { EqOps.CENTER }, ::fromEq)
            return bands
        }

    override fun setEqBand(
        band: Int,
        value: Int,
    ) {
        if (band !in 0 until PlayerState.EQ_BANDS) return
        val v = value.coerceIn(0, SKIN_TOP)
        bandsHeld[band].write(v, toEq(v), state.eqBands.getOrElse(band) { EqOps.CENTER })
        player.setEqBand(band, toEq(v))
    }

    /** The preamp on the skin's scale; near 0 dB it snaps to 0 dB, as the classic window's does. */
    override var eqPreamp: Int
        get() = preampHeld.read(state.preamp, ::fromEq)
        set(value) {
            val v = value.coerceIn(0, SKIN_TOP).let { if (SliderMath.stickyPreamp(toEq(it)) == EqOps.CENTER) SKIN_FLAT else it }
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
            val v = value.coerceIn(-PAN_REACH, PAN_REACH).let { if (SliderMath.stickyBalance(toBalance(it)) == 0) 0 else it }
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
        /** How long a written value outlasts a state that still shows the old one. */
        private const val HOLD_MS = 1_000L

        /**
         * How far in percent from what it asked for a volume write may land: half of a phone's
         * volume step at 15 steps, and the rounding on both sides.
         */
        private const val VOLUME_SLACK = 4

        /** The top of the skin's scale for the volume, the equalizer and the VU. */
        private const val SKIN_TOP = 255

        /** How far the balance reaches either way on the skin's scale, and on the state's. */
        private const val PAN_REACH = 127
        private const val BALANCE_TOP = 100

        /** The equalizer's flat on the skin's scale. */
        private const val SKIN_FLAT = 128

        /** The top of the app's equalizer scale, whose flat is [EqOps.CENTER]. */
        private const val EQ_TOP = 63

        private const val PERCENT = 100

        private val Boolean.bit: Int get() = if (this) 1 else 0

        private fun toInt(ms: Long): Int = ms.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

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

        /** A balance of -100..100 on the skin's scale. */
        fun fromBalance(balance: Int): Int = (balance * PAN_REACH / BALANCE_TOP.toFloat()).roundToInt()

        /** A skin balance as the state shows it, -100..100. */
        fun toBalance(pan: Int): Int = (pan * BALANCE_TOP / PAN_REACH.toFloat()).roundToInt()
    }
}
