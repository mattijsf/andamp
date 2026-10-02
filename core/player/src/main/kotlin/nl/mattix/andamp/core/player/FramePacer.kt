// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.player

/**
 * Holds a render loop to a frame rate.
 *
 * `eglSwapBuffers` on a TextureView does not block on the compositor, so an unpaced loop runs
 * faster than the display. That costs battery and runs presets that move per frame too fast.
 *
 * It keeps a running deadline, and starts over from the current time when a frame overruns
 * by more than [RESYNC_FRAMES] frames.
 */
class FramePacer(
    fps: Int,
    private val nowNs: () -> Long = System::nanoTime,
    private val sleepNs: (Long) -> Unit = ::sleepNanos,
) {
    private val frameNs = NANOS_PER_SECOND / fps
    private var deadlineNs = nowNs()

    /** Sleeps until this frame's slot is up. Returns immediately when already late. */
    fun await() {
        deadlineNs += frameNs
        val now = nowNs()
        if (now - deadlineNs > frameNs * RESYNC_FRAMES) {
            // a stall or a backgrounded app: start again from here and skip the frames owed
            deadlineNs = now
            return
        }
        val remainingNs = deadlineNs - now
        // A small overshoot is normal, because Thread.sleep is not precise, and must not move
        // the deadline: the next sleep is then shorter by the overshoot, which holds the
        // average at the target rate.
        if (remainingNs <= 0) return
        sleepNs(remainingNs)
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L

        /** How many frames behind counts as a stall. */
        const val RESYNC_FRAMES = 4
    }
}

private fun sleepNanos(ns: Long) = Thread.sleep(ns / 1_000_000L, (ns % 1_000_000L).toInt())
