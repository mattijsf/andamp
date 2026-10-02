// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

/** What a [CustomBpmRenderer] does to the beat the rest of the preset sees. */
internal enum class AvsBpmMode { ARBITRARY, SKIP, REVERSE }

/**
 * Rewrites the beat for everything after it in the preset.
 *
 * A Misc component that draws nothing: it makes beats at an interval of its
 * own, passes only every Nth one through, or inverts the flag. It writes
 * [AvsRenderState.beat], so the components after it do not see the beat the
 * audio found.
 */
internal class CustomBpmRenderer(
    private val enabled: Boolean,
    private val mode: AvsBpmMode,
    private val beatIntervalMs: Long,
    private val skip: Int,
    private val skipFirst: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) : AvsComponentRenderer {
    private var lastBeatAt = clock()
    private var beatsSeen = 0

    override fun render(
        frame: AvsFrame,
        audio: AvsAudioFrame,
        state: AvsRenderState,
    ) {
        if (!enabled) return
        state.beat =
            when (mode) {
                AvsBpmMode.ARBITRARY -> arbitrary()
                AvsBpmMode.SKIP -> skipping(state.beat)
                AvsBpmMode.REVERSE -> !state.beat
            }
    }

    /**
     * A beat of its own on the wall clock, whatever the music is doing.
     *
     * The original uses the wall clock too (`timer_ms()` in e_custombpm.cpp):
     * the legacy field stores milliseconds between beats, so the rate does not
     * depend on the frame rate.
     */
    private fun arbitrary(): Boolean {
        if (beatIntervalMs <= 0) return false
        val now = clock()
        if (now <= lastBeatAt + beatIntervalMs) return false
        // more than two intervals behind means a stall: restart from now
        lastBeatAt = if (lastBeatAt < now - beatIntervalMs * 2) now else lastBeatAt + beatIntervalMs
        beatsSeen++
        return beatsSeen > skipFirst
    }

    /** Every Nth beat of the ones the audio found, after the first [skipFirst]. */
    private fun skipping(beat: Boolean): Boolean {
        if (!beat) return false
        beatsSeen++
        if (beatsSeen <= skipFirst) return false
        val every = (skip + 1).coerceAtLeast(1)
        return (beatsSeen - skipFirst) % every == 0
    }

    companion object {
        /**
         * The mode is stored as a radio group: one int32 per button
         * (arbitrary, skip, invert), of which the last nonzero one wins, as
         * AVS-File-Decoder's `RadioButton` reads it.
         */
        fun read(
            body: ByteArray,
            clock: () -> Long = System::currentTimeMillis,
        ): CustomBpmRenderer? {
            val reader = BodyReader(body)
            val enabled = reader.int32() != 0
            val arbitraryFlag = reader.int32() != 0
            val skipFlag = reader.int32() != 0
            val invertFlag = reader.int32() != 0
            val mode =
                when {
                    invertFlag -> AvsBpmMode.REVERSE
                    skipFlag -> AvsBpmMode.SKIP
                    arbitraryFlag -> AvsBpmMode.ARBITRARY
                    else -> AvsBpmMode.ARBITRARY
                }
            // the legacy field is the time between beats in milliseconds;
            // e_custombpm.cpp converts it to a BPM with 60000/value
            val intervalMs = reader.int32()
            val skip = reader.int32()
            val skipFirst = reader.int32()
            return if (reader.ok) {
                CustomBpmRenderer(enabled, mode, intervalMs.toLong(), skip, skipFirst, clock)
            } else {
                null
            }
        }
    }
}
