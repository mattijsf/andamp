// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import nl.mattix.andamp.core.model.Track

/**
 * What a station says it is playing, split into artist and title.
 *
 * Shoutcast's `StreamTitle` is one string, and by convention stations write it
 * as `Artist - Title`. Text without the separator is a title with no artist.
 * The split is at the first separator, on the assumption that a title with a
 * dash in it is more common than an artist with one.
 */
internal object IcyTitle {
    private const val SEPARATOR = " - "

    data class Split(
        val artist: String,
        val title: String,
    )

    /**
     * What [track] becomes under a station's [raw] `StreamTitle`, or null
     * when nothing should change.
     *
     * Null for a local file, whose tags were read when it was added. Null too
     * when the station repeats itself, so the queue is not republished.
     */
    fun applyTo(
        track: Track,
        raw: String?,
        /** ID3 names the artist in its own frame; Shoutcast leaves it inside [raw]. */
        artist: String? = null,
    ): Track? {
        if (!track.isStream) return null
        val split =
            if (!artist.isNullOrBlank() && !raw.isNullOrBlank()) {
                // two separate fields need no splitting
                Split(artist.trim(), raw.trim())
            } else {
                split(raw) ?: return null
            }
        if (split.artist == track.artist && split.title == track.title) return null
        return track.copy(artist = split.artist, title = split.title)
    }

    fun split(raw: String?): Split? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val at = text.indexOf(SEPARATOR)
        if (at <= 0) return Split("", text)
        val artist = text.take(at).trim()
        val title = text.drop(at + SEPARATOR.length).trim()
        // "Artist - " with nothing after it is one field
        return if (title.isEmpty()) Split("", text) else Split(artist, title)
    }
}
