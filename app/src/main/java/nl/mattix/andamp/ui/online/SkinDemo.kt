// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.online

import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.state.AmpMenu
import nl.mattix.andamp.state.EqOps
import nl.mattix.andamp.state.FakeSpectrum
import nl.mattix.andamp.state.FakeWave
import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.RealOscilloscope
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.ui.BLINK_MS
import nl.mattix.andamp.ui.MARQUEE_STEP_MS
import nl.mattix.andamp.ui.window.WindowControls
import kotlin.math.roundToInt

/**
 * A demo player for skin previews. The windows draw from a [WinampState], so a state with no
 * backend, no audio and a playlist of tracks that do not exist draws a working player wearing the
 * skin.
 */
object SkinDemo {
    /**
     * Answers every control and plays nothing. The windows in the preview are the real ones, and
     * each call here only changes the state: stop stops the transport, a dragged band moves its
     * slider.
     */
    class Player(
        override val state: WinampState,
    ) : WindowControls {
        override fun play() {
            state.transport = Transport.Playing
        }

        override fun pause() {
            state.transport = if (state.transport == Transport.Paused) Transport.Playing else Transport.Paused
        }

        override fun stop() {
            state.transport = Transport.Stopped
            state.currentTimeSec = 0
        }

        override fun next() = move(1)

        override fun previous() = move(-1)

        private fun move(by: Int) {
            if (state.playlist.isEmpty()) return
            state.currentIndex = (state.currentIndex + by + state.playlist.size) % state.playlist.size
            state.currentTimeSec = 0
            state.transport = Transport.Playing
        }

        override fun toggleShuffle() {
            state.shuffle = !state.shuffle
        }

        override fun toggleRepeat() {
            state.repeat = !state.repeat
        }

        override fun setVolume(fraction: Float) {
            state.volume = (fraction * VOLUME_TOP).toInt().coerceIn(0, VOLUME_TOP)
        }

        override fun setBalance(balance: Int) {
            state.balance = balance.coerceIn(-BALANCE_EDGE, BALANCE_EDGE)
        }

        override fun seekTo(fraction: Float) {
            val song = state.playlist.getOrNull(state.currentIndex) ?: return
            state.currentTimeSec = (fraction * (song.durationMs / MILLIS)).toInt()
        }

        override fun playTrack(index: Int) {
            state.currentIndex = index.coerceIn(0, (state.playlist.size - 1).coerceAtLeast(0))
            state.currentTimeSec = 0
            state.transport = Transport.Playing
        }

        override fun toggleEq() {
            state.eqOn = !state.eqOn
        }

        override fun setBand(
            index: Int,
            value: Int,
        ) {
            if (index in state.eqBands.indices) state.eqBands[index] = value
        }

        override fun setPreamp(value: Int) {
            state.preamp = value
        }

        override fun resetBands() {
            for (band in state.eqBands.indices) state.eqBands[band] = EqOps.CENTER
        }

        // a preview has no presets to manage
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
    }

    /**
     * The state every museum screenshot was taken in, transcribed from webamp's
     * `screenshotInitialState.ts`: the same four tracks at the same lengths and bitrate, the third
     * one selected, three seconds played, and the same equalizer curve. Matching it keeps the swap
     * from screenshot to live preview from showing.
     */
    fun state(): WinampState =
        WinampState().apply {
            transport = Transport.Playing
            playlist = MUSEUM_TRACKS
            currentIndex = 0
            currentTimeSec = SONG_START
            selectedRows = setOf(2)
            volume = 78
            balance = 0
            eqOn = true
            eqVisible = true
            plVisible = true
            EQ_CURVE.forEachIndexed { at, value -> if (at < eqBands.size) eqBands[at] = value }
            preamp = MUSEUM_PREAMP
        }

    /**
     * Moves everything a running player moves, for the frame at [millis]. The analyzer is the
     * signal the app shows for a backend with no audio tap, through the same [SpectrumPhysics]
     * every source shares.
     */
    fun frame(
        s: WinampState,
        millis: Long,
    ) {
        val seconds = millis / 1000.0
        // stop stops the clock, pause holds it, and the blink is the app's own pause blink
        if (s.transport == Transport.Playing) {
            s.currentTimeSec = SONG_START + (seconds.toInt() % SONG_LENGTH)
        }
        s.blinkOn = s.transport != Transport.Paused || (millis / PAUSE_BLINK_MS) % 2 == 0L
        when (s.transport) {
            // stopped clears the window and paused holds the last frame, as Winamp does
            Transport.Stopped -> {
                FakeSpectrum.reset(s)
                s.visWave.fill(RealOscilloscope.CENTER_ROW)
            }

            Transport.Paused -> {
                Unit
            }

            else -> {
                s.marqueeStep = (millis / MARQUEE_STEP_MS).toInt()
                FakeSpectrum.step(s, seconds)
                FakeWave.step(s, seconds)
            }
        }
        s.visFrame++
    }

    /**
     * The museum's curve, band by band, on webamp's 0..100 slider scale: 60Hz through 16kHz, and 56
     * for the preamp.
     */
    private val MUSEUM_SLIDERS = listOf(52, 74, 83, 91, 80, 54, 23, 19, 34, 75)
    private val EQ_CURVE = MUSEUM_SLIDERS.map(::asBand)
    private val MUSEUM_PREAMP = asBand(56)

    /** webamp's 0..100 slider value on the 0..63 scale Winamp's art draws. */
    private fun asBand(slider: Int) = (slider * 63 / 100f).roundToInt()

    /** The four tracks in the museum's screenshots, at the lengths and the bitrate shown there. */
    private val MUSEUM_TRACKS =
        listOf(
            museumTrack(0, "DJ Mike Llama", "Llama Whipping Intro", 5),
            museumTrack(1, "Marilyn Manson", "Rock Is Dead", 191),
            museumTrack(2, "Propellerheads", "Spybreak! (Short One)", 240),
            museumTrack(3, "Ministry", "Bad Blood", 300),
        )

    private fun museumTrack(
        at: Int,
        artist: String,
        title: String,
        seconds: Int,
    ) = Track("museum-$at", artist, title, seconds * 1000L, bitrateKbps = 128, sampleRateKhz = 44)

    private const val SONG_START = 3
    private const val SONG_LENGTH = 42

    /** The app's own pause blink rate. */
    private const val PAUSE_BLINK_MS = BLINK_MS
    private const val VOLUME_TOP = 100
    private const val BALANCE_EDGE = 100
    private const val MILLIS = 1000.0
}
