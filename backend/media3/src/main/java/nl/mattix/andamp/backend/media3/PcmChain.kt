// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import com.google.common.collect.ImmutableList
import nl.mattix.andamp.core.model.EqSettings
import nl.mattix.andamp.core.model.RackSettings
import nl.mattix.andamp.core.playback.AudioTap
import nl.mattix.andamp.core.playback.PcmProvider
import nl.mattix.andamp.core.playback.PcmRingBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The equalizer, the balance, the effect rack and the visualizer tap, run over
 * samples decoded outside ExoPlayer.
 *
 * The processors and their order are those of a local file, because both are
 * built from [audioChain]. The instances are this chain's own, since a
 * processor is configured for one stream and holds that stream's tails.
 * ExoPlayer supplies the bytes for a file; a [PcmProvider] supplies them here.
 *
 * Not safe to share between threads: [process] and [flush] belong to the one
 * thread that renders. The settings may arrive from any thread, and so may
 * [reportAhead] and [holdAhead], one at a time.
 *
 * Media3 types stay inside this module. A [PcmProvider] goes in and an
 * [AudioTap] comes out.
 */
class PcmChain(
    private val eq: EqAudioProcessor = EqAudioProcessor(),
    private val balance: BalanceAudioProcessor = BalanceAudioProcessor(),
    private val dsp: DspAudioProcessor = DspAudioProcessor(),
    private val ring: PcmRingBuffer = PcmRingBuffer(),
) {
    /** What the visualizers read, as for a local file. */
    val tap: AudioTap get() = ring

    private val pipeline =
        AudioProcessingPipeline(ImmutableList.copyOf(audioChain(eq, balance, dsp, ring))).apply {
            configure(
                AudioProcessor.AudioFormat(
                    PcmProvider.SAMPLE_RATE_HZ,
                    PcmProvider.CHANNELS,
                    C.ENCODING_PCM_16BIT,
                ),
            )
            // the overload without metadata is deprecated; DEFAULT is for a
            // stream with no timeline of its own
            flush(AudioProcessor.StreamMetadata.DEFAULT)
        }

    /**
     * The input buffer, kept from one call to the next so [process] does not
     * allocate on the render thread.
     */
    private var input: ByteBuffer = ByteBuffer.allocateDirect(START_BYTES).order(ByteOrder.LITTLE_ENDIAN)

    /**
     * Where [process] leaves its result: the first bytes, as many as it
     * returned. The next call overwrites it.
     */
    var output: ByteArray = ByteArray(START_BYTES)
        private set

    init {
        ring.onFormatChanged(PcmProvider.SAMPLE_RATE_HZ)
    }

    fun setEqualizer(settings: EqSettings) = eq.update(settings)

    fun setBalance(balance: Float) = this.balance.update(balance)

    fun setDsp(rack: RackSettings) = dsp.update(rack)

    /** The plug-ins a listener installed; see [DspAudioProcessor.setPlugins]. */
    fun setPlugins(sources: List<String>) = dsp.setPlugins(sources)

    /**
     * Tells the tap that the last sample [process] made is [samples] ahead of
     * the one being heard; see [PcmRingBuffer.reportAhead].
     */
    fun reportAhead(samples: Long) = ring.reportAhead(samples)

    /** Tells the tap the sound has stopped moving; see [PcmRingBuffer.holdAhead]. */
    fun holdAhead() = ring.holdAhead()

    /**
     * Runs [bytes] of [buffer] through the chain, and returns how many bytes
     * of [output] it produced.
     *
     * The pipeline is drained until it has no output left, because a
     * processor may hold samples back and later return more than it was given.
     */
    fun process(
        buffer: ByteArray,
        bytes: Int,
    ): Int {
        if (bytes <= 0) return 0
        if (input.capacity() < bytes) input = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN)
        input.clear()
        input.put(buffer, 0, bytes)
        input.flip()
        var made = 0
        while (input.hasRemaining()) {
            pipeline.queueInput(input)
            made = drain(made)
        }
        return drain(made)
    }

    /**
     * Drops everything the chain is still holding, such as a reverb's tail and
     * the filters' state, as ExoPlayer does to its own processors on a seek or
     * a track change. Called only from the thread that calls [process].
     */
    fun flush() = pipeline.flush(AudioProcessor.StreamMetadata.DEFAULT)

    /** Takes everything the pipeline has ready into [output] after its first [from] bytes. */
    private fun drain(from: Int): Int {
        var made = from
        while (true) {
            val ready = pipeline.output
            val size = ready.remaining()
            if (size == 0) return made
            if (output.size < made + size) output = output.copyOf(maxOf(made + size, output.size * 2))
            ready.get(output, made, size)
            made += size
        }
    }

    private companion object {
        /** The initial buffer size: one read by [PcmAudioOut]. */
        const val START_BYTES = 16_384
    }
}
