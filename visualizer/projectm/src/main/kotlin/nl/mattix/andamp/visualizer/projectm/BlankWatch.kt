// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.projectm

/**
 * Finds a preset that loads without an error but draws nothing: projectM compiles some
 * presets and then draws only black, and says nothing about it. Some draw for a moment and
 * then fade to black for good.
 *
 * Black while the music is quiet proves nothing, since many presets only move with sound. So
 * the frame is looked at all the time the preset shows, and [BLACK_MS] of black in a row
 * while music plays marks it. A frame with anything in it starts the count over.
 *
 * Times are in milliseconds, from the caller's clock.
 */
internal class BlankWatch {
    private var path: String? = null
    private var lastSample = 0L
    private var blackWhileAudible = 0L
    private var marked = true

    /** Starts watching the preset at [path], which began showing at [now]; null watches nothing. */
    fun start(
        path: String?,
        now: Long,
    ) {
        this.path = path
        blackWhileAudible = 0
        marked = path == null
        // the first look comes SETTLE_MS in, after projectM's blend from the last preset
        lastSample = now + SETTLE_MS - EVERY_MS
    }

    /** Whether the frame drawn at [now] should be looked at. */
    fun wants(now: Long): Boolean = !marked && now - lastSample >= EVERY_MS

    /**
     * What a look at the frame found: whether it was [black], and whether music was
     * [audible]. Answers that the preset draws something (true), that it draws nothing
     * (false, once; the watch then stops), or nothing new (null).
     */
    fun sampled(
        black: Boolean,
        audible: Boolean,
        now: Long,
    ): Boolean? {
        val since = now - lastSample
        lastSample = now
        if (!black) {
            blackWhileAudible = 0
            return true
        }
        if (audible) blackWhileAudible += since
        if (blackWhileAudible < BLACK_MS) return null
        marked = true
        return false
    }

    /** The preset being watched. */
    val watching: String? get() = path

    companion object {
        /** How often a frame is looked at. */
        const val EVERY_MS = 500L

        /** How long a preset shows before it is first looked at. */
        const val SETTLE_MS = 1_500L

        /** How long a preset may stay black, in a row, while music plays. */
        const val BLACK_MS = 4_000L
    }
}
