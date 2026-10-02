// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

/**
 * Where the music comes from.
 *
 * A value instead of an enum, because the app names no source beyond the phone: an
 * installed pack declares its source through [PackSources.found], and a row from a source
 * with no pack here is named from its own address by [foreign]. This is how the app keeps
 * the fourth of the five conditions in ENGINEERING.md, which a test checks.
 *
 * Equal by [id], which is also what is stored, so a source read back from disk or
 * recognized from a row is the same source its pack declares.
 */
class MusicSource(
    /** What is stored, so a pack's source must keep its id. */
    val id: String,
    /** What the listener reads: a menu entry, a row that cannot be played. */
    val label: String,
) {
    override fun equals(other: Any?) = other is MusicSource && other.id == id

    override fun hashCode() = id.hashCode()

    override fun toString() = id

    companion object {
        /** Files on the phone, through Media3. */
        val LOCAL = MusicSource("LOCAL", "This Phone")

        /**
         * The sources this install can play from, This Phone first. A source that is
         * installed but not signed in is included, so the listener can reach its sign-in.
         */
        fun present(extra: List<MusicSource> = PackSources.found.map { it.source }): List<MusicSource> = listOf(LOCAL) + extra

        /**
         * A source this install knows nothing about, named from a row's own address:
         * `example:track:…` gives "Example". A pack for that source declares it under the
         * same id, so the two are equal.
         */
        fun foreign(scheme: String): MusicSource =
            MusicSource(scheme.uppercase(), scheme.lowercase().replaceFirstChar { it.uppercase() })
    }
}

/** The Preferences page for [source], which is also the tag of its row there. */
fun sourceRoute(source: MusicSource): String = "source/" + source.id.lowercase()
