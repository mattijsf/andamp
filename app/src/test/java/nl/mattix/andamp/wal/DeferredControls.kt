// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.state.AmpMenu
import nl.mattix.andamp.state.EqOps
import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.ui.window.WindowControls
import kotlin.math.roundToInt

/**
 * The app's player as a modern skin meets it: each call is written to [calls] at once and reaches
 * the [state] only on [apply], the way the backend's results come back through its state.
 *
 * What comes back is rounded as the real one rounds it: the volume as a whole percent of a float
 * fraction (the view model's mirror), on the nearest of [volumeSteps] when that is set (the slider
 * moving the phone's volume, Media3Backend with DeviceVolume), a seek to whole milliseconds of a
 * float fraction of the song (PlayerFacade.seekToFraction), and the place while playing reported
 * every 250 ms (the Media3 backend's poll). The switches are sent as the view model sends them,
 * worked out from the state at the call: shuffle asks for the opposite of what the state shows.
 */
internal class DeferredControls(
    override val state: WinampState,
) : WindowControls {
    /** Every call, in the order it was made. */
    val calls = ArrayList<String>()

    private val queued = ArrayList<() -> Unit>()

    /** The fake clock the player under test reads. */
    var now = 0L
        private set

    /** The song's real place, in ms. */
    private var placeMs = 0L

    /** The backend's place as last reported: what the player's position supplier reads. */
    var reportedMs = 0L
        private set

    /** The phone's volume steps when the slider moves the phone's volume; 0 when it sets the app's gain. */
    var volumeSteps = 0

    /** Applies every queued call, in order. */
    fun apply() {
        val due = queued.toList()
        queued.clear()
        due.forEach { it() }
    }

    /** Moves the clock on by [ms]; while playing, the song moves with it and its place is reported every poll. */
    fun advance(ms: Long) {
        repeat(ms.toInt()) {
            now++
            if (state.transport == Transport.Playing) placeMs++
            if (now % POLL_MS == 0L) report()
        }
    }

    /** Puts the song at [ms] at once, as a seek the backend has finished. */
    fun place(ms: Long) {
        placeMs = ms
        report()
    }

    private fun report() {
        reportedMs = placeMs
        state.currentTimeSec = (placeMs / MILLIS).toInt()
    }

    private fun later(
        call: String,
        effect: () -> Unit,
    ) {
        calls += call
        queued += effect
    }

    override fun play() =
        later("play") {
            state.transport = Transport.Playing
        }

    override fun pause() =
        later("pause") {
            state.transport =
                when (state.transport) {
                    Transport.Playing -> Transport.Paused
                    Transport.Paused -> Transport.Playing
                    Transport.Stopped -> Transport.Stopped
                }
        }

    override fun stop() =
        later("stop") {
            state.transport = Transport.Stopped
            place(0)
        }

    override fun next() = later("next") { move(1) }

    override fun previous() = later("previous") { move(-1) }

    private fun move(by: Int) {
        val size = state.playlist.size
        if (size == 0) return
        state.currentIndex = (state.currentIndex + by + size) % size
        place(0)
    }

    override fun toggleShuffle() {
        val to = !state.shuffle
        later("shuffle($to)") { state.shuffle = to }
    }

    override fun toggleRepeat() {
        val to = !state.repeat
        later("repeat($to)") { state.repeat = to }
    }

    override fun setVolume(fraction: Float) =
        later("setVolume($fraction)") {
            val heard =
                if (volumeSteps > 0) (fraction.coerceIn(0f, 1f) * volumeSteps).roundToInt() / volumeSteps.toFloat() else fraction
            state.volume = (heard * PERCENT).roundToInt().coerceIn(0, PERCENT)
        }

    override fun setBalance(balance: Int) = later("setBalance($balance)") { state.balance = balance }

    override fun seekTo(fraction: Float) {
        val duration = state.currentTrack?.durationMs ?: 0L
        later("seekTo($fraction)") {
            place((fraction.coerceIn(0f, 1f) * duration).toLong())
        }
    }

    override fun playTrack(index: Int) =
        later("playTrack($index)") {
            state.currentIndex = index
            state.transport = Transport.Playing
            place(0)
        }

    override fun toggleEq() {
        val to = !state.eqOn
        later("eq($to)") { state.eqOn = to }
    }

    override fun setBand(
        index: Int,
        value: Int,
    ) = later("setBand($index, $value)") {
        state.eqBands[index] = value.coerceIn(0, EQ_TOP)
    }

    override fun setPreamp(value: Int) =
        later("setPreamp($value)") {
            state.preamp = value.coerceIn(0, EQ_TOP)
        }

    override fun resetBands() =
        later("resetBands") {
            for (band in state.eqBands.indices) state.eqBands[band] = EqOps.CENTER
        }

    override fun presetsMenu(anchor: MenuAnchor) = AmpMenu("Presets", emptyList(), anchor)

    override fun scrollBy(
        rows: Int,
        visibleRows: Int,
    ) = Unit

    override fun selectTrack(index: Int) = Unit

    override fun selectZero() = Unit

    private companion object {
        /** How often the Media3 backend reports the place while playing. */
        const val POLL_MS = 250L
        const val MILLIS = 1000L
        const val PERCENT = 100
        const val EQ_TOP = 63
    }
}
