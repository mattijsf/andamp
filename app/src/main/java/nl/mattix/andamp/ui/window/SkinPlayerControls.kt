// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.skinplayer.Entry
import nl.mattix.andamp.skinplayer.PlayerState
import nl.mattix.andamp.skinplayer.SkinPlayer
import nl.mattix.andamp.state.AmpMenu
import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.WinampState
import kotlin.math.roundToInt
import nl.mattix.andamp.skinplayer.Transport as SkinTransport

/**
 * The classic windows over any [SkinPlayer]: their controls become the player's commands, and
 * [sync] copies the player's state into the [state] the windows draw from.
 *
 * The windows' own state (scroll, selection, the seek bar while dragged) stays in [state]. A
 * toggle sends the opposite of what the windows show. Each command is followed by a [sync], so a
 * player that answers at once shows the answer at once; one that answers later shows it at the
 * next [sync], which the host calls every frame.
 */
class SkinPlayerControls(
    private val player: SkinPlayer,
    override val state: WinampState,
) : WindowControls {
    /** The version of the player's state last copied; -1 before the first copy. */
    private var copied = -1L

    /** The tracks made from the playlist last copied, kept while the playlist stays the same. */
    private var tracksFrom: List<Entry>? = null

    init {
        sync()
    }

    /** Copies the player's state into [state] when it changed, and the place in the song always. */
    fun sync() {
        val s = player.state
        if (s.version != copied) {
            copied = s.version
            copy(s)
        }
        state.currentTimeSec = (player.positionMs() / MILLIS).toInt()
    }

    private fun copy(s: PlayerState) {
        if (s.playlist !== tracksFrom) {
            tracksFrom = s.playlist
            state.playlist = s.playlist.mapIndexed(::trackOf)
        }
        state.currentIndex = s.currentIndex
        state.transport =
            when (s.transport) {
                SkinTransport.STOPPED -> Transport.Stopped
                SkinTransport.PAUSED -> Transport.Paused
                SkinTransport.PLAYING, SkinTransport.CONNECTING -> Transport.Playing
            }
        state.connecting = s.transport == SkinTransport.CONNECTING
        state.streamBitrateKbps = s.stream.bitrateKbps
        state.streamSampleRateKhz = s.stream.sampleRateKhz
        state.streamChannels = s.stream.channels
        state.volume = s.volume
        state.balance = s.balance
        state.shuffle = s.shuffle
        state.repeat = s.repeat
        state.eqOn = s.eqOn
        state.preamp = s.preamp
        s.eqBands.forEachIndexed { band, value -> if (band < state.eqBands.size) state.eqBands[band] = value }
    }

    /** What the windows show, read before a command goes to the player. */
    private val shown get() = state

    private fun command(send: SkinPlayer.() -> Unit) {
        player.send()
        sync()
    }

    override fun play() = command { play() }

    /** Winamp's Pause button pauses, and pressed again it plays on. */
    override fun pause() = command { if (shown.transport == Transport.Paused) play() else pause() }

    override fun stop() = command { stop() }

    override fun next() = command { next() }

    override fun previous() = command { previous() }

    override fun toggleShuffle() = command { setShuffle(!shown.shuffle) }

    override fun toggleRepeat() = command { setRepeat(!shown.repeat) }

    override fun setVolume(fraction: Float) = command { setVolume((fraction * PlayerState.VOLUME_MAX).roundToInt()) }

    override fun setBalance(balance: Int) = command { setBalance(balance) }

    override fun seekTo(fraction: Float) {
        val length = player.state.current?.lengthMs ?: 0L
        state.seekPreview = null
        if (length > 0) command { seekTo((fraction.coerceIn(0f, 1f) * length).toLong()) }
    }

    override fun playTrack(index: Int) = command { playTrack(index) }

    override fun toggleEq() = command { setEqEnabled(!shown.eqOn) }

    override fun setBand(
        index: Int,
        value: Int,
    ) = command { setEqBand(index, value) }

    override fun setPreamp(value: Int) = command { setPreamp(value) }

    /** The bands only, as the window's reset leaves the preamp where it is. */
    override fun resetBands() = command { repeat(PlayerState.EQ_BANDS) { setEqBand(it, PlayerState.EQ_CENTER) } }

    /** The contract has no presets, so the menu is empty. */
    override fun presetsMenu(anchor: MenuAnchor) = AmpMenu("Presets", emptyList(), anchor)

    override fun scrollBy(
        rows: Int,
        visibleRows: Int,
    ) {
        val furthest = (state.playlist.size - visibleRows).coerceAtLeast(0)
        state.playlistScroll = (state.playlistScroll + rows).coerceIn(0, furthest)
    }

    override fun selectTrack(index: Int) {
        state.selectedRows = setOf(index)
    }

    override fun selectZero() {
        state.selectedRows = emptySet()
    }

    private companion object {
        const val MILLIS = 1000L

        /** A playlist row as the windows draw it: the artist and title from the tags, else the entry's title. */
        fun trackOf(
            at: Int,
            entry: Entry,
        ) = Track(
            id = "entry-$at",
            artist = entry.tags["artist"].orEmpty(),
            title = entry.tags["title"] ?: entry.title,
            durationMs = entry.lengthMs,
        )
    }
}
