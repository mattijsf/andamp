// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.skinplayer

/**
 * What a skin may ask of the player. A command may take effect later: the player confirms it by a
 * later [SkinPlayer.state]. Transport commands keep their order; for the volume, the balance and
 * the equalizer only the latest value counts. Values outside their range are clamped. A command
 * the player has no [Capabilities] for does nothing.
 */
interface PlayerCommands :
    TransportCommands,
    SoundCommands

/** Playing, moving through the playlist and the song, and a skin's Eject; see [PlayerCommands]. */
interface TransportCommands {
    fun play()

    fun pause()

    fun stop()

    fun next()

    fun previous()

    /** Makes entry [index] current and plays it. */
    fun playTrack(index: Int)

    fun seekTo(ms: Long)

    /** A skin's Eject: the host lets the listener pick a file, played at once when [andPlay]. */
    fun requestOpen(andPlay: Boolean)
}

/** The volume, the balance, the play order switches and the equalizer; see [PlayerCommands]. */
interface SoundCommands {
    fun setVolume(volume: Int)

    fun setBalance(balance: Int)

    fun setShuffle(on: Boolean)

    fun setRepeat(on: Boolean)

    fun setCrossfade(on: Boolean)

    fun setEqEnabled(on: Boolean)

    fun setEqAuto(on: Boolean)

    fun setEqBand(
        band: Int,
        value: Int,
    )

    fun setPreamp(value: Int)

    /** Every band and the preamp back to 0 dB. */
    fun resetEq()
}

/**
 * What a skin's visualizers read, once a frame. Each is empty (or zero) when nothing plays or the
 * player cannot give it ([Capabilities]).
 */
interface PlayerSignals {
    /** Spectrum levels 0..1, 75 bands as Winamp's visualizer. */
    fun spectrum(): FloatArray = FloatArray(0)

    /** The waveform heard: samples -1..1, oldest first, 576 of them as Winamp hands a visualizer. */
    fun waveform(): FloatArray = FloatArray(0)

    /** Left and right VU, 0..255. */
    fun vu(): Pair<Int, Int> = 0 to 0
}

/** What a player can do; a skin greys out or hides what it lacks. */
data class Capabilities(
    val seek: Boolean = true,
    val eq: Boolean = true,
    val crossfade: Boolean = true,
    val playlist: Boolean = true,
    val spectrum: Boolean = true,
    val waveform: Boolean = true,
    val vu: Boolean = true,
)

/**
 * The player a skin drives, classic or modern: a snapshot of its [state], the live position, the
 * commands and the signals. Anything that implements it can sit under both skin systems; the
 * contract tests (testFixtures, `SkinPlayerContract`) say what it must do.
 */
interface SkinPlayer :
    PlayerCommands,
    PlayerSignals {
    val state: PlayerState

    val capabilities: Capabilities get() = Capabilities()

    /** Where the current song is, in ms; read as often as a skin draws. */
    fun positionMs(): Long

    /** Calls [listener] after each change of [state], on the thread the change happens on; returns how to stop. */
    fun addListener(listener: () -> Unit): AutoCloseable
}
