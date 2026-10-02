// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import nl.mattix.andamp.core.playback.PcmRingBuffer

/**
 * The stages every sound this module plays goes through, in order.
 *
 * Samples become float first, so a boost has headroom. The equalizer, the
 * balance and the rack's effects follow, and the output stage handles any peak
 * past full scale and converts to 16-bit. The tap comes last, so the
 * visualizers show what is heard.
 *
 * Shared by ExoPlayer's sink for a local file and by [PcmChain] for samples
 * decoded elsewhere, so both get the same stages, the limiter included.
 */
internal fun audioChain(
    eq: EqAudioProcessor,
    balance: BalanceAudioProcessor,
    dsp: DspAudioProcessor,
    ring: PcmRingBuffer,
): List<AudioProcessor> =
    listOf(
        FloatInAudioProcessor(),
        eq,
        balance,
        dsp,
        OutputAudioProcessor(wanted = dsp::limiter, ring = ring),
        TeeAudioProcessor(PcmTapBufferSink(ring)),
    )
