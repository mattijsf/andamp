// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.model

/**
 * What identifies a recording across sessions and sources, so that a per-track setting such
 * as the equalizer's auto-load curve can be filed under it.
 *
 * A uri does not identify a file on Android: the media library, the document picker and a
 * folder grant each give the same file a different uri, and a rescan can renumber the
 * library row. The key is therefore the artist and title tags when either is present, the
 * file name from the uri for an untagged file, and the whole uri otherwise (a stream).
 *
 * Two copies of a song share a key, and so do a live and a studio take with the same artist
 * and title.
 */
object TrackKey {
    fun of(track: Track): String {
        val tagged = listOf(track.artist, track.title).filter { it.isNotBlank() }
        if (tagged.isNotEmpty()) return normalise(tagged.joinToString(SEPARATOR))
        val uri = track.uri ?: return ""
        return normalise(fileNameOf(uri) ?: uri)
    }

    /**
     * The file's own name inside a uri, whatever shape it came in, or null
     * when the uri names a row or an endpoint rather than a file.
     */
    private fun fileNameOf(uri: String): String? {
        val path = decode(uri.substringBefore('?').substringBefore('#'))
        val last = path.substringAfterLast('/').substringAfterLast(':')
        val dot = last.lastIndexOf('.')
        val extension = if (dot in 1 until last.length - 1) last.substring(dot + 1) else ""
        return last.takeIf { extension.length in EXTENSION_RANGE && extension.all(Char::isLetterOrDigit) }
    }

    /** Percent-decoding, so a document uri's `%2F` reads as the path it is. */
    private fun decode(text: String): String {
        if (!text.contains('%')) return text
        val out = StringBuilder(text.length)
        var at = 0
        while (at < text.length) {
            val c = text[at]
            val hex = if (c == '%' && at + 2 < text.length) text.substring(at + 1, at + 3).toIntOrNull(HEX) else null
            if (hex != null) {
                out.append(hex.toChar())
                at += 3
            } else {
                out.append(c)
                at++
            }
        }
        return out.toString()
    }

    /** Case and spacing do not count: "Muse  -  Hexagons" and "muse - hexagons" are one key. */
    private fun normalise(text: String) = text.trim().lowercase().replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("\\s+")
    private const val SEPARATOR = " - "
    private const val HEX = 16
    private val EXTENSION_RANGE = 2..4
}
