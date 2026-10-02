// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common.stream

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.mattix.andamp.core.model.EqSettings
import nl.mattix.andamp.core.model.RackSettings
import nl.mattix.andamp.core.playback.AudioOut
import nl.mattix.andamp.core.playback.PcmProvider

/**
 * An output that reads its provider on the test's clock: every tick it takes everything
 * waiting, until the provider answers -1. It has to read, because the stream backend's
 * position is counted from the bytes that cross.
 *
 * A pause stops the reading and does nothing to what is decoded.
 */
internal class FakeAudioOut(
    private val scope: CoroutineScope,
    private val tickMs: Long = TICK_MS,
) : AudioOut {
    /** The device being taken and let go of. */
    val order = mutableListOf<String>()

    /** What happened to the audio already on its way, kept apart from [order]. */
    val buffered = mutableListOf<String>()

    val volumes = mutableListOf<Float>()

    override val tap = null

    /** Whoever asked to hear about calls, prompts and unplugged headphones. */
    var interruptions: ((AudioOut.Interruption) -> Unit)? = null
        private set

    private var provider: PcmProvider? = null
    private var pump: Job? = null

    /** One tick of audio, which is what a reader of a bounded pipe takes at a time. */
    private val buffer = ByteArray(TICK_BYTES)

    /** For a test that wants to speak as the phone would. */
    fun interrupt(interruption: AudioOut.Interruption) {
        interruptions?.invoke(interruption)
    }

    override fun start(provider: PcmProvider) {
        order += "start"
        this.provider = provider
        drain()
    }

    override fun resume() {
        buffered += "resume"
        drain()
    }

    override fun pause() {
        buffered += "pause"
        stopReading()
    }

    override fun stop() {
        order += "stop"
        stopReading()
        provider = null
    }

    override fun discard() {
        buffered += "discard"
        provider?.discard()
    }

    override fun setVolume(fraction: Float) {
        volumes += fraction
    }

    override fun setInterruptions(listener: ((AudioOut.Interruption) -> Unit)?) {
        interruptions = listener
    }

    override fun setEqualizer(settings: EqSettings) = Unit

    override fun setBalance(balance: Float) = Unit

    override fun setDsp(rack: RackSettings) = Unit

    override fun setPlugins(sources: List<String>) = Unit

    private fun stopReading() {
        pump?.cancel()
        pump = null
    }

    /** Reads [provider] for as long as it has anything to say. */
    private fun drain() {
        val source = provider ?: return
        stopReading()
        pump =
            scope.launch {
                while (took(source)) delay(tickMs)
            }
    }

    /**
     * Takes everything waiting now; false once there will be no more.
     *
     * It reads until the provider answers 0 (nothing ready) or -1 (the end), so the end is
     * noticed in the tick in which the last samples crossed.
     */
    private fun took(source: PcmProvider): Boolean {
        while (true) {
            val read = source.read(buffer)
            if (read == 0) return true
            if (read < 0) return false
        }
    }

    private companion object {
        const val TICK_MS = 100L

        /** A tick of 44.1 kHz stereo 16-bit. */
        const val TICK_BYTES = 17_640
    }
}
