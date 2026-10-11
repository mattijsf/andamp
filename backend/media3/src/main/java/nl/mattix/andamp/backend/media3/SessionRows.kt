// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import java.text.Normalizer

/**
 * The playlist as a browser sees it: a car's list, a watch, the assistant.
 *
 * The rows are read from the session's player each time they are asked for, so
 * a browser is shown what the player holds. Each row is listed under an id of
 * its own ([rowIds]), which is what the browser sends back to have it played.
 * Rows are text, as in the playlist window: they carry no cover.
 */
internal class SessionRows(
    private val player: Player,
) {
    /** Every row, in playlist order. */
    fun all(): List<MediaItem> {
        val timeline = player.currentTimeline
        val window = Timeline.Window()
        val items = List(timeline.windowCount) { row -> timeline.getWindow(row, window).mediaItem }
        val ids = rowIds(items.map { it.mediaId })
        return items.mapIndexed { row, item -> listed(item, ids[row]) }
    }

    /**
     * The rows that hold every word of [words] in their artist or title,
     * whatever the case and the accents.
     */
    fun matching(words: String): List<MediaItem> {
        val wanted = folded(words).split(' ').filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return emptyList()
        return all().filter { row ->
            val said = folded("${row.mediaMetadata.artist ?: ""} ${row.mediaMetadata.title ?: ""}")
            wanted.all { it in said }
        }
    }

    /**
     * The row a request to play means, or null when it means none.
     *
     * A request carries the id of a row it was shown, or the words somebody
     * said. Words pick the first row that matches them, and no words at all
     * ("play Andamp") mean the row that is on.
     */
    fun asked(request: MediaItem): MediaItem? {
        val rows = all()
        rows.firstOrNull { it.mediaId == request.mediaId }?.let { return it }
        val words = request.requestMetadata.searchQuery ?: return null
        return if (words.isBlank()) rows.getOrNull(player.currentMediaItemIndex) else matching(words).firstOrNull()
    }

    /** The row with [id], or null when the playlist has none. */
    fun named(id: String): MediaItem? = all().firstOrNull { it.mediaId == id }

    private fun listed(
        item: MediaItem,
        id: String,
    ): MediaItem =
        item
            .buildUpon()
            .setMediaId(id)
            .setMediaMetadata(
                item.mediaMetadata
                    .buildUpon()
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setArtworkUri(null)
                    .setExtras(null)
                    .build(),
            ).build()

    /** Lower case, without accents: "Beyoncé" and "beyonce" are one word. */
    private fun folded(text: String): String =
        Normalizer
            .normalize(text, Normalizer.Form.NFD)
            .replace(MARKS, "")
            .lowercase()

    private companion object {
        val MARKS = Regex("\\p{Mn}+")
    }
}
