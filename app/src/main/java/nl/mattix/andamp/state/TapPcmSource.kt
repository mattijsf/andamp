// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.playback.AudioTap
import nl.mattix.andamp.core.player.PcmSource

/**
 * Feeds a visualizer engine from the backend's audio tap: one frame's worth of PCM per
 * rendered frame, with no FFT, because the engines do their own analysis.
 *
 * The buffer is sized to `sampleRate / fps`, so samples go in at the rate they are played,
 * and is rebuilt only when the tap or its rate changes. It runs on the render thread and
 * does not allocate per frame.
 */
class TapPcmSource(
    private val fps: Int,
    private val maxSamples: Int,
    private val tapProvider: () -> AudioTap?,
) : PcmSource {
    private var tap: AudioTap? = null
    private var reader: SmoothTapReader? = null
    private var buffer: FloatArray = EMPTY
    private var rate = 0

    override fun read(): FloatArray? {
        val current = tapProvider() ?: return null
        val currentRate = current.sampleRateHz
        if (currentRate <= 0) return null
        if (current !== tap || currentRate != rate) resize(current, currentRate)
        val reader = reader ?: return null
        return if (reader.read(buffer)) buffer else null
    }

    private fun resize(
        newTap: AudioTap,
        newRate: Int,
    ) {
        tap = newTap
        rate = newRate
        buffer = FloatArray(samplesPerFrame(newRate, fps, maxSamples))
        reader = SmoothTapReader(newTap, MILLIS_PER_SECOND / fps.toLong())
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
        val EMPTY = FloatArray(0)
    }
}

/** The PCM samples for one rendered frame: `sampleRate / fps`, at least 128 and at most [maxSamples]. */
fun samplesPerFrame(
    sampleRateHz: Int,
    fps: Int,
    maxSamples: Int,
): Int = (sampleRateHz / fps).coerceIn(MIN_SAMPLES_PER_FRAME, maxSamples.coerceAtLeast(MIN_SAMPLES_PER_FRAME))

private const val MIN_SAMPLES_PER_FRAME = 128
