// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.pack

import nl.mattix.andamp.core.model.EqSettings
import nl.mattix.andamp.core.model.RackSettings
import nl.mattix.andamp.core.playback.AudioOut
import nl.mattix.andamp.core.playback.AudioTap
import nl.mattix.andamp.core.playback.PcmProvider

/**
 * An [AudioOut] with no device behind it. It records what it was told and answers what it is
 * rendering and what a visualizer would tap.
 */
internal class FakeOut : AudioOut {
    private val signal = FakeTap()

    /** Null until something is being rendered; see [AudioOut.tap]. */
    override val tap: AudioTap? get() = if (playing) signal else null

    /** The provider it was started with; null once stopped. */
    var samples: PcmProvider? = null
        private set

    /** Whether it has been started and not stopped. */
    var playing = false
        private set

    /** Whether it is paused. */
    var held = false
        private set

    var volume = 1f
        private set

    var equalizer: EqSettings? = null
        private set

    var balance: Float? = null
        private set

    var rack: RackSettings? = null
        private set

    var plugins: List<String> = emptyList()
        private set

    /** The registered interruption listener, or null. */
    var interruptions: ((AudioOut.Interruption) -> Unit)? = null
        private set

    /** Every transport call, by name, in order. */
    val did = mutableListOf<String>()

    override fun start(provider: PcmProvider) {
        did += "start"
        samples = provider
        playing = true
        held = false
    }

    override fun stop() {
        did += "stop"
        playing = false
        samples = null
    }

    override fun pause() {
        did += "pause"
        held = true
    }

    override fun resume() {
        did += "resume"
        held = false
    }

    override fun discard() {
        did += "discard"
    }

    override fun setEqualizer(settings: EqSettings) {
        equalizer = settings
    }

    override fun setBalance(balance: Float) {
        this.balance = balance
    }

    override fun setDsp(rack: RackSettings) {
        this.rack = rack
    }

    override fun setPlugins(sources: List<String>) {
        plugins = sources
    }

    override fun setVolume(fraction: Float) {
        volume = fraction
    }

    override fun setInterruptions(listener: ((AudioOut.Interruption) -> Unit)?) {
        interruptions = listener
    }
}

/** An empty tap: the tests ask whether there is one, never what it holds. */
private class FakeTap : AudioTap {
    override val sampleRateHz = PcmProvider.SAMPLE_RATE_HZ

    override val writtenSamples = 0L

    override fun readAt(
        endSample: Long,
        out: FloatArray,
    ): Boolean = false
}
