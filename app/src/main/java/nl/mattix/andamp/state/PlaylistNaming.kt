// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.model.Track
import java.time.LocalDate

/**
 * The filename LIST > SAVE LIST offers. The listener can type over it in the system's save
 * dialog. It carries the date, so a save on another day does not overwrite this one.
 */
object PlaylistNaming {
    const val EXTENSION = ".m3u"
    private const val MAX_STEM = 40

    fun fileName(
        tracks: List<Track>,
        today: LocalDate,
    ): String {
        val artist = tracks.mapNotNull { it.artist.takeIf(String::isNotBlank) }.distinct().singleOrNull()
        val stem = (artist ?: "Andamp playlist").take(MAX_STEM).let(::sanitise)
        return "$stem $today$EXTENSION"
    }

    /**
     * What the listener typed, as a filename: sanitized and ending in .m3u. An empty answer
     * becomes the default name.
     */
    fun withExtension(typed: String): String {
        val cleaned = sanitise(typed.trim().removeSuffix(EXTENSION))
        return cleaned + EXTENSION
    }

    /** Replaces everything but letters, digits, space, `-` and `_`, so the name works on any filesystem. */
    private fun sanitise(name: String): String =
        name
            .map { if (it.isLetterOrDigit() || it in KEPT_PUNCTUATION) it else '-' }
            .joinToString("")
            .trim()
            .ifEmpty { "Andamp playlist" }

    private val KEPT_PUNCTUATION = setOf(' ', '-', '_')
}
