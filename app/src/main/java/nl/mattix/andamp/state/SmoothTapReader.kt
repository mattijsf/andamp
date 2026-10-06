// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.playback.AudioTap
import kotlin.math.abs

/**
 * A read position over the tap that advances smoothly.
 *
 * The tap's write head advances in decode-time bursts (players buffer hundreds of ms
 * ahead), so reading the latest samples would freeze between bursts. This clock advances
 * at the sample rate every frame and locks onto the write head minus what the player has
 * buffered, giving a continuously sliding window over the audio that is being heard; it
 * snaps on seeks and at the start.
 *
 * How far behind the write head that is comes from the tap ([AudioTap.aheadSamples]), which
 * differs by phone and by output: a wired speaker and a Bluetooth one are not the same
 * distance behind. A tap that cannot tell gets [TRAIL_S].
 *
 * One instance per visualizer, each with its own read position.
 */
class SmoothTapReader(
    private val tap: AudioTap,
    private val frameMs: Long,
) {
    private var readPos = -1L
    private var lastWritten = -1L
    private var stalledMs = 0L

    fun read(out: FloatArray): Boolean {
        val rate = tap.sampleRateHz
        if (rate <= 0) return false
        // a paused or stopped player stops writing; after STALL_SILENCE_MS without new samples
        // the read fails, so a visualizer is not fed the last buffered audio in a loop
        val written = tap.writtenSamples
        if (written == lastWritten) {
            stalledMs += frameMs
            if (stalledMs >= STALL_SILENCE_MS) return false
        } else {
            lastWritten = written
            stalledMs = 0
        }
        val ahead = tap.aheadSamples
        val target = written - if (ahead > 0) ahead else (rate * TRAIL_S).toLong()
        if (readPos < 0 || abs(target - readPos) > rate) {
            readPos = target // start or seek: snap
        } else {
            readPos += rate * frameMs / 1000 // advance in real time
            readPos += ((target - readPos) * DRIFT_CORRECTION).toLong() // rate-lock to the source
        }
        return when {
            readPos < out.size -> {
                false
            }

            tap.readAt(readPos, out) -> {
                true
            }

            else -> {
                readPos = target // fell out of the buffer: resync and try the fresh spot
                tap.readAt(readPos, out)
            }
        }
    }

    private companion object {
        // for a tap that does not say how far ahead it runs: roughly a small audio sink's
        // buffering
        const val TRAIL_S = 0.25f
        const val DRIFT_CORRECTION = 0.05f

        /**
         * The write head advances in decode bursts a few hundred ms apart
         * while playing, so only a gap well past that means stopped.
         */
        const val STALL_SILENCE_MS = 600L
    }
}
