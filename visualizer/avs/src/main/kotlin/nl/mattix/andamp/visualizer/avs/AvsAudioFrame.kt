// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

/**
 * What the components get to react to, for one frame.
 *
 * AVS components ask for a channel: left, right or center. This app's audio tap
 * is mono (`AudioTap` in `:core:playback` mixes on the way in), so all three
 * answer the same samples, and a preset that draws the two channels against
 * each other draws them identical.
 */
class AvsAudioFrame(
    /** Oldest first, -1..1. */
    val waveform: FloatArray = FloatArray(SAMPLES),
    /** Magnitudes, 0..1, low frequency first. */
    val spectrum: FloatArray = FloatArray(SAMPLES),
    val beat: Boolean = false,
) {
    /**
     * One sample picked by position: spectrum answers its 0..1 magnitude,
     * waveform its -1..1 sample. The simple Render components read this; a
     * Super Scope's `v` is [scopeValueAt].
     */
    fun valueAt(
        source: AvsAudioSource,
        index: Int,
        points: Int,
    ): Float {
        val from = if (source == AvsAudioSource.SPECTRUM) spectrum else waveform
        if (from.isEmpty() || points <= 0) return 0f
        val at = if (points == 1) 0 else index * (from.size - 1) / (points - 1)
        return from[at.coerceIn(0, from.size - 1)]
    }

    /**
     * The value a Super Scope's point [index] of [points] sees as `v`, in -1..1
     * for both sources.
     *
     * Transcribed from vis_avs `e_superscope.cpp:177-182` (BSD; see NOTICE.md):
     * the point sits at fractional sample `index * 576 / points` and the two
     * neighboring samples are linearly interpolated, so a scope is smooth at
     * any point count, including more points than samples. AVS then maps the
     * raw byte through `v = b / 128 - 1`; for the waveform that is the signed
     * sample itself (already this array's -1..1), and for the spectrum it
     * stretches the 0..255 magnitude across -1..1, so silence reads -1.
     */
    fun scopeValueAt(
        source: AvsAudioSource,
        index: Int,
        points: Int,
    ): Double {
        val from = if (source == AvsAudioSource.SPECTRUM) spectrum else waveform
        if (from.isEmpty() || points <= 0) return 0.0
        val at = index * from.size.toDouble() / points
        val whole = at.toInt().coerceIn(0, from.size - 1)
        val lerp = at - whole
        // AVS reads sample 576 off the end of its buffer for the last point;
        // this clamps to the last sample
        val next = (whole + 1).coerceAtMost(from.size - 1)
        val value = from[whole] * (1.0 - lerp) + from[next] * lerp
        return if (source == AvsAudioSource.SPECTRUM) value * 2.0 - 1.0 else value
    }

    companion object {
        /** What AVS hands a scope: 576 samples, the Winamp visualization window. */
        const val SAMPLES = 576
    }
}
