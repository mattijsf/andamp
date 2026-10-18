// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.displayName

/** One thing a list can say about a song, under the name Winamp's title formatting gives it. */
enum class TrackField(
    val code: String,
) {
    ARTIST("artist"),
    TITLE("title"),
    ALBUM("album"),
    NUMBER("tracknumber"),
    YEAR("year"),
    GENRE("genre"),
    ;

    /** Whether every [Track] has it; the others come from the library's index ([TrackInfo]). */
    val onTrack: Boolean get() = this == ARTIST || this == TITLE
}

/** What the library's index knows of a track besides its artist and title; see [LibraryIndex.infoOf]. */
data class TrackInfo(
    val album: String = "",
    val number: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
)

/**
 * How a list writes a song: [fields] in order, the text between each two of them ([gaps], one
 * fewer than the fields), and text before the first ([lead]) and after the last ([trail]).
 *
 * A row shows the fields its song has. A field it lacks is left out with the text after it, so
 * `%artist% - %album% - %title%` writes a song without an album as `Artist - Title`. The lead goes
 * with the first field and the trail with the last, so `%title% (%year%)` drops the brackets
 * along with a missing year.
 *
 * It is stored as Winamp writes a title format: each field as its code between percent signs
 * ([TrackField.code]) and the text as itself, a percent sign doubled.
 */
data class RowFormat(
    val lead: String,
    val fields: List<TrackField>,
    val gaps: List<String>,
    val trail: String,
) {
    init {
        require(gaps.size == (fields.size - 1).coerceAtLeast(0)) { "one gap between each two fields" }
    }

    /** Whether a row needs the library's index, for a field a [Track] does not carry. */
    val needsIndex: Boolean get() = fields.any { !it.onTrack }

    /**
     * The row for [track], with what the index knows of it in [info].
     *
     * A track whose tags are not read yet has no title, and a song that has none of the fields
     * has nothing to show: both are written as the playlist always wrote them ([displayName]).
     */
    fun write(
        track: Track,
        info: TrackInfo?,
    ): String {
        if (track.title.isBlank()) return track.displayName
        val values = fields.map { valueOf(it, track, info) }
        val shown = values.indices.filter { values[it].isNotEmpty() }
        if (shown.isEmpty()) return track.displayName
        return buildString {
            if (shown.first() == 0) append(lead)
            shown.forEachIndexed { n, at ->
                if (n > 0) append(gaps[shown[n - 1]])
                append(values[at])
            }
            if (shown.last() == fields.lastIndex) append(trail)
        }
    }

    /** The format with each field written as [name] calls it, as a row of a song that has them all. */
    fun spelled(name: (TrackField) -> String): String =
        buildString {
            append(lead)
            fields.forEachIndexed { at, field ->
                if (at > 0) append(gaps[at - 1])
                append(name(field))
            }
            append(trail)
        }

    /** The format as it is stored, as Winamp writes one; see [parse]. */
    fun encode(): String =
        buildString {
            append(escape(lead))
            fields.forEachIndexed { at, field ->
                if (at > 0) append(escape(gaps[at - 1]))
                append('%').append(field.code).append('%')
            }
            append(escape(trail))
        }

    /** The field at [from] moved to [to]; the text between the fields stays where it is. */
    fun moved(
        from: Int,
        to: Int,
    ): RowFormat {
        if (from !in fields.indices || to !in fields.indices) return this
        val order = fields.toMutableList()
        order.add(to, order.removeAt(from))
        return copy(fields = order)
    }

    /** [field] added after the others, after [NEW_GAP]. */
    fun added(field: TrackField): RowFormat = copy(fields = fields + field, gaps = if (fields.isEmpty()) gaps else gaps + NEW_GAP)

    /** The field at [at] taken out, with the text before it, or after it when it is the first. */
    fun removed(at: Int): RowFormat {
        if (at !in fields.indices) return this
        val gap = if (at > 0) at - 1 else 0
        return copy(fields = fields.filterIndexed { n, _ -> n != at }, gaps = gaps.filterIndexed { n, _ -> n != gap })
    }

    /** The text between the field at [at] and the next one set to [text]. */
    fun withGap(
        at: Int,
        text: String,
    ): RowFormat = if (at in gaps.indices) copy(gaps = gaps.mapIndexed { n, gap -> if (n == at) text else gap }) else this

    companion object {
        /** Artist and title, as Winamp's playlist writes a song. */
        val DEFAULT = RowFormat("", listOf(TrackField.ARTIST, TrackField.TITLE), listOf(" - "), "")

        /** The text a field added after another gets in between. */
        const val NEW_GAP = " - "

        /**
         * The format stored as [text]; see [encode]. A percent sign that starts no known field is
         * text, and so is one that is not closed.
         */
        fun parse(text: String): RowFormat {
            val fields = mutableListOf<TrackField>()
            val texts = mutableListOf(StringBuilder())
            var at = 0
            while (at < text.length) {
                val c = text[at]
                val close = if (c == '%') text.indexOf('%', at + 1) else -1
                // a field named twice is text the second time, as a list has one block of each
                val field = if (close > at) fieldNamed(text.substring(at + 1, close))?.takeIf { it !in fields } else null
                when {
                    c != '%' -> {
                        texts.last().append(c).also { at++ }
                    }

                    close == at + 1 -> {
                        texts.last().append('%').also { at += 2 }
                    }

                    field != null -> {
                        fields += field
                        texts += StringBuilder()
                        at = close + 1
                    }

                    else -> {
                        texts.last().append(c).also { at++ }
                    }
                }
            }
            if (fields.isEmpty()) return RowFormat(texts.single().toString(), emptyList(), emptyList(), "")
            return RowFormat(
                lead = texts.first().toString(),
                fields = fields,
                gaps = texts.subList(1, texts.size - 1).map { it.toString() },
                trail = texts.last().toString(),
            )
        }

        private fun fieldNamed(code: String) = TrackField.entries.firstOrNull { it.code.equals(code, ignoreCase = true) }

        private fun escape(text: String) = text.replace("%", "%%")

        private fun valueOf(
            field: TrackField,
            track: Track,
            info: TrackInfo?,
        ): String =
            when (field) {
                TrackField.ARTIST -> {
                    track.artist.trim()
                }

                TrackField.TITLE -> {
                    track.title.trim()
                }

                TrackField.ALBUM -> {
                    info?.album?.trim().orEmpty()
                }

                TrackField.NUMBER -> {
                    info
                        ?.number
                        ?.takeIf { it > 0 }
                        ?.toString()
                        .orEmpty()
                }

                TrackField.YEAR -> {
                    info
                        ?.year
                        ?.takeIf { it > 0 }
                        ?.toString()
                        .orEmpty()
                }

                TrackField.GENRE -> {
                    info?.genre?.trim().orEmpty()
                }
            }
    }
}

/**
 * Writes song rows in the listener's [RowFormat], asking the library's index ([infoOf], by uri)
 * only when the format names a field a track does not carry.
 */
class SongWords(
    private val format: () -> RowFormat,
    private val infoOf: suspend (List<String>) -> Map<String, TrackInfo>,
) {
    /** The row of each of [tracks], in order. */
    suspend fun of(tracks: List<Track>): List<String> {
        val now = format()
        val info = if (now.needsIndex) infoOf(tracks.mapNotNull { it.uri }) else emptyMap()
        return tracks.map { now.write(it, it.uri?.let(info::get)) }
    }
}
