// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import nl.mattix.andamp.state.AmpMenu
import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.WinampState

/**
 * What a window's widgets ask of the player behind them. The widgets depend on this and not
 * on a backend, so the same windows can run over a player that only simulates playback.
 */
interface PlayerControls {
    val state: WinampState

    fun play()

    fun pause()

    fun stop()

    fun next()

    fun previous()

    fun toggleShuffle()

    fun toggleRepeat()

    /** [fraction] is 0..1, as the slider reports it. */
    fun setVolume(fraction: Float)

    /** [balance] is -100 (hard left) to 100 (hard right). */
    fun setBalance(balance: Int)

    fun seekTo(fraction: Float)
}

/** What the equalizer's sliders and buttons ask for. */
interface EqControls {
    val state: WinampState

    fun toggleEq()

    fun setBand(
        index: Int,
        value: Int,
    )

    fun setPreamp(value: Int)

    fun resetBands()

    fun presetsMenu(anchor: MenuAnchor): AmpMenu
}

/** What the playlist's rows and scrollbar ask for. */
interface PlaylistControls {
    val state: WinampState

    fun scrollBy(
        rows: Int,
        visibleRows: Int,
    )

    fun selectTrack(index: Int)

    fun selectZero()

    fun playTrack(index: Int)
}

/**
 * All three, which is what a window takes. The split is by subject and not by window: the
 * playlist has a transport in its bottom bar, and the shaded equalizer has the volume slider.
 */
interface WindowControls :
    PlayerControls,
    EqControls,
    PlaylistControls
