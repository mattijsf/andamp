// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

/**
 * Parses what the listener types into Winamp's Ctrl+J (jump to time). One line takes
 * `1:30`, `90` or `1.30`, with or without spaces. Text that is not a time is refused.
 */
object JumpToTime {
    /** Seconds, or null when the text is not a time. */
    fun seconds(typed: String): Int? {
        val parts = typed.trim().replace('.', ':').split(':')
        val numbers = parts.map { it.trim().toIntOrNull() }
        val readable = parts.size in 1..2 && numbers.all { it != null && it >= 0 }
        if (!readable) return null
        val read = numbers.filterNotNull()
        return if (read.size == 1) read[0] else read[0] * SECONDS_PER_MINUTE + read[1]
    }

    private const val SECONDS_PER_MINUTE = 60
}
