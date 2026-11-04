// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.online

import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.skinplayer.Entry
import nl.mattix.andamp.skinplayer.FakeSkinPlayer
import nl.mattix.andamp.skinplayer.StreamInfo
import nl.mattix.andamp.state.FakeSpectrum
import nl.mattix.andamp.state.FakeWave
import nl.mattix.andamp.state.RealOscilloscope
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.ui.BLINK_MS
import nl.mattix.andamp.ui.MARQUEE_STEP_MS
import nl.mattix.andamp.ui.window.SkinPlayerControls
import kotlin.math.roundToInt

/**
 * A demo player for skin previews: the real windows over a [FakeSkinPlayer], which plays
 * nothing, through the same [SkinPlayerControls] any player sits behind. The tracks do not exist,
 * and each control only changes the player: stop stops the transport, a dragged band moves its
 * slider.
 *
 * It starts in the state every museum screenshot was taken in, transcribed from webamp's
 * `screenshotInitialState.ts`: the same four tracks at the same lengths and bitrate, the third
 * one selected, three seconds played, and the same equalizer curve. Matching it keeps the swap
 * from screenshot to live preview from showing.
 */
class SkinDemo {
    /** The demo's clock, the millis of the last [frame]. */
    private var now = 0L

    val player =
        FakeSkinPlayer(
            MUSEUM_ENTRIES,
            clock = { now },
            stream = StreamInfo(bitrateKbps = MUSEUM_KBPS, sampleRateKhz = MUSEUM_KHZ),
        ).apply {
            EQ_CURVE.forEachIndexed { band, value -> setEqBand(band, value) }
            setPreamp(MUSEUM_PREAMP)
            play()
            seekTo(SONG_START * MILLIS)
        }

    /** What the windows draw. */
    val state =
        WinampState().apply {
            selectedRows = setOf(2)
            eqVisible = true
            plVisible = true
        }

    /** What the windows' controls ask. */
    val controls = SkinPlayerControls(player, state)

    /**
     * Moves everything a running player moves, for the frame at [millis]. The song's place loops
     * over the stretch the demo shows. The analyzer is the signal the app shows for a backend with
     * no audio tap, through the same [SpectrumPhysics] every source shares.
     */
    fun frame(millis: Long) {
        now = millis
        controls.sync()
        val s = state
        val second = s.currentTimeSec
        if (second >= SONG_START) s.currentTimeSec = SONG_START + (second - SONG_START) % SONG_LENGTH
        val seconds = millis / 1000.0
        // pause holds the clock, and the blink is the app's own pause blink
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

    private companion object {
        /**
         * The museum's curve, band by band, on webamp's 0..100 slider scale: 60Hz through 16kHz, and 56
         * for the preamp.
         */
        val MUSEUM_SLIDERS = listOf(52, 74, 83, 91, 80, 54, 23, 19, 34, 75)
        val EQ_CURVE = MUSEUM_SLIDERS.map(::asBand)
        val MUSEUM_PREAMP = asBand(56)

        /** webamp's 0..100 slider value on the 0..63 scale Winamp's art draws. */
        fun asBand(slider: Int) = (slider * 63 / 100f).roundToInt()

        /** The four tracks in the museum's screenshots, at the lengths shown there. */
        val MUSEUM_ENTRIES =
            listOf(
                museumEntry("DJ Mike Llama", "Llama Whipping Intro", 5),
                museumEntry("Marilyn Manson", "Rock Is Dead", 191),
                museumEntry("Propellerheads", "Spybreak! (Short One)", 240),
                museumEntry("Ministry", "Bad Blood", 300),
            )

        fun museumEntry(
            artist: String,
            title: String,
            seconds: Int,
        ) = Entry("$artist - $title", seconds * 1000L, mapOf("artist" to artist, "title" to title))

        const val MUSEUM_KBPS = 128
        const val MUSEUM_KHZ = 44

        const val SONG_START = 3
        const val SONG_LENGTH = 42

        /** The app's own pause blink rate. */
        const val PAUSE_BLINK_MS = BLINK_MS
        const val MILLIS = 1000L
    }
}
