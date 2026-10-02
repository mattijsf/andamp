// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

/**
 * Why a source's rows cannot be played here, in the words shown on a row. A source that is
 * not installed and one that is installed but signed out are told apart, because the
 * listener's remedy differs.
 */
enum class SourceAbsence(
    val words: String,
) {
    /** Not on this install: a row from a source whose pack is not installed, or was uninstalled. */
    MISSING("Missing source"),

    /** Installed, and nobody has signed in to it on this device. */
    SIGNED_OUT("Signed out"),
}

/**
 * What this install can reach: the sources it has, and which of them are signed in. The
 * phone is always reachable.
 */
data class SourceReach(
    val present: List<MusicSource> = listOf(MusicSource.LOCAL),
    val signedIn: Set<MusicSource> = emptySet(),
) {
    /** Why [source]'s rows cannot play here, or null when they can. */
    fun absence(source: MusicSource): SourceAbsence? =
        when {
            source == MusicSource.LOCAL -> null
            source !in present -> SourceAbsence.MISSING
            source !in signedIn -> SourceAbsence.SIGNED_OUT
            else -> null
        }

    /** The source as this install names it; a pack's name may be better than the one read from a row. */
    fun named(source: MusicSource): MusicSource = present.firstOrNull { it == source } ?: source
}
