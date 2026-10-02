// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.model

/**
 * What can be said about one track: the lines its source can supply, in the order they
 * should be read. A source with nothing to say answers null.
 */
data class TrackInfo(
    /** What the dialog is titled: the track as the listener knows it. */
    val heading: String,
    val lines: List<Line>,
) {
    data class Line(
        val label: String,
        val value: String,
    )

    companion object {
        /**
         * Builds an info block, dropping every line whose value is null or blank. Null when
         * no line is left.
         */
        fun of(
            heading: String,
            lines: List<Pair<String, String?>>,
        ): TrackInfo? {
            val kept = lines.mapNotNull { (label, value) -> value?.trim()?.takeIf { it.isNotEmpty() }?.let { Line(label, it) } }
            return if (kept.isEmpty()) null else TrackInfo(heading, kept)
        }
    }
}
