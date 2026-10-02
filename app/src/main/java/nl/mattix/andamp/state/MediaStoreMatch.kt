// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlin.math.abs

/**
 * Picks the library entry for a file that has to be found by name.
 *
 * A name alone is not unique ("01 - Intro.mp3"), so duration decides between entries of
 * the same name, and where it cannot, nothing is chosen.
 */
object MediaStoreMatch {
    /** Two files of the same name within this many ms are treated as the same recording. */
    const val DURATION_TOLERANCE_MS = 2000L

    data class Candidate(
        val id: Long,
        val displayName: String,
        val durationMs: Long,
        /** Full path where the provider reports one; used only to break ties. */
        val path: String? = null,
    )

    /**
     * The entry that is [displayName] with [durationMs], or null when the
     * library cannot say which one is meant.
     *
     * A duration of zero means "unknown" (an entry restored from a playlist
     * another app wrote), in which case a single name match is accepted and an
     * ambiguous one is not.
     */
    fun best(
        candidates: List<Candidate>,
        displayName: String,
        durationMs: Long,
        preferPath: String? = null,
    ): Candidate? {
        val named = candidates.filter { it.displayName.equals(displayName, ignoreCase = true) }
        val exactPath = preferPath?.let { path -> named.singleOrNull { it.path == path } }
        return when {
            named.isEmpty() -> null

            exactPath != null -> exactPath

            // an unknown duration cannot choose, so only an unambiguous name does
            durationMs <= 0 -> named.singleOrNull()

            else -> named.minByOrNull { abs(it.durationMs - durationMs) }?.takeIf { close(it, durationMs) }
        }
    }

    private fun close(
        candidate: Candidate,
        durationMs: Long,
    ) = abs(candidate.durationMs - durationMs) <= DURATION_TOLERANCE_MS
}
