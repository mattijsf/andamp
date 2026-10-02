// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.displayName
import nl.mattix.andamp.core.model.looksLikeStream
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The saved playlist, as an extended M3U: the format of Winamp's winamp.m3u, which other
 * players read too.
 *
 * `#EXTINF` carries only a whole-second duration and a display line, so the other fields
 * (the artist/title split, bitrate, sample rate, the track id) are written on an `#ANDAMP`
 * comment line. Other players skip unknown comments, so the file stays a valid playlist.
 *
 * ```
 * #EXTM3U
 * #ANDAMP-CURRENT:0
 * #EXTINF:5,Andamp Whippin' Intro
 * #ANDAMP:id=llama;artist=;title=Andamp+Whippin%27+Intro;ms=5329;kbps=56;khz=22
 * asset:///audio/andamp-intro.mp3
 * ```
 */
object PlaylistCodec {
    const val HEADER = "#EXTM3U"
    private const val CURRENT_PREFIX = "#ANDAMP-CURRENT:"
    private const val SOURCE_PREFIX = "#ANDAMP-SOURCE:"
    private const val EXTINF_PREFIX = "#EXTINF:"
    private const val EXTRA_PREFIX = "#ANDAMP:"
    private const val PAIR_SEPARATOR = ";"
    private const val KEY_SEPARATOR = "="
    private const val MS_PER_SEC = 1000

    /**
     * A queue, the row that was current, and where the rows of other sources come from.
     *
     * [sources] maps a source's id to the page its app is handed out on, one line per
     * source. It is written so a phone without that app can still say where the rows lead.
     */
    data class Saved(
        val tracks: List<Track>,
        val currentIndex: Int,
        val sources: Map<String, String> = emptyMap(),
    )

    fun encode(saved: Saved): String =
        buildString {
            appendLine(HEADER)
            appendLine("$CURRENT_PREFIX${saved.currentIndex}")
            // written before the rows; another player skips these comment lines
            saved.sources.toSortedMap().forEach { (source, page) ->
                appendLine("$SOURCE_PREFIX$source $page")
            }
            saved.tracks.map(::asStored).forEach { track ->
                appendLine("$EXTINF_PREFIX${track.durationMs / MS_PER_SEC},${track.displayName}")
                appendLine(EXTRA_PREFIX + extras(track))
                appendLine(track.uri.orEmpty())
            }
        }

    /**
     * Reads back what [encode] wrote, and also a plain M3U or a bare list of paths, where
     * the filename stands in for the missing title.
     */
    fun decode(text: String): Saved {
        var current = 0
        var pendingSeconds: Long? = null
        var pendingLine: String? = null
        var pendingExtras: Map<String, String> = emptyMap()
        val tracks = mutableListOf<Track>()
        val sources = mutableMapOf<String, String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> {
                    Unit
                }

                line.startsWith(CURRENT_PREFIX) -> {
                    current = line.removePrefix(CURRENT_PREFIX).trim().toIntOrNull() ?: 0
                }

                line.startsWith(EXTINF_PREFIX) -> {
                    val body = line.removePrefix(EXTINF_PREFIX)
                    pendingSeconds = body.substringBefore(',').trim().toLongOrNull()
                    pendingLine = body.substringAfter(',', "").trim().ifEmpty { null }
                }

                line.startsWith(EXTRA_PREFIX) -> {
                    pendingExtras = pairs(line.removePrefix(EXTRA_PREFIX))
                }

                line.startsWith(SOURCE_PREFIX) -> {
                    val said = line.removePrefix(SOURCE_PREFIX).trim()
                    val source = said.substringBefore(' ').trim()
                    val page = said.substringAfter(' ', "").trim()
                    if (source.isNotEmpty() && page.isNotEmpty()) sources[source] = page
                }

                // any other comment is ignored
                line.startsWith("#") -> {
                    Unit
                }

                // not a location: binary content
                !isPlausibleLocation(line) -> {
                    Unit
                }

                else -> {
                    tracks += track(line, tracks.size, pendingSeconds, pendingLine, pendingExtras)
                    pendingSeconds = null
                    pendingLine = null
                    pendingExtras = emptyMap()
                }
            }
        }
        return Saved(tracks, current.coerceIn(0, (tracks.size - 1).coerceAtLeast(0)), sources)
    }

    /**
     * Rejects lines of a truncated or binary file: those decode to replacement characters
     * or control characters, which do not appear in a path. Spaces are allowed.
     */
    private fun isPlausibleLocation(line: String): Boolean = line.none { it == '\uFFFD' || it.isISOControl() }

    /**
     * The entry as it is written to the file. A station's row shows the song ICY reports
     * while it plays; the file stores the station itself, under its own name.
     */
    private fun asStored(track: Track): Track {
        if (!track.isStream) return track
        // the name it was added under, or else the host of its address; an entry with
        // neither is stored as it is
        val station =
            track.defaultName?.takeIf { it.isNotEmpty() }
                ?: hostOf(track.uri)
                ?: return track
        return track.copy(artist = "", title = station)
    }

    /** The host of an address, used to name a station that has no name. */
    private fun hostOf(uri: String?): String? =
        runCatching { java.net.URI(uri.orEmpty()).host }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun extras(track: Track): String =
        buildList {
            add("id" to track.id)
            add("artist" to track.artist)
            add("title" to track.title)
            add("ms" to track.durationMs.toString())
            if (keepsReadouts(track)) {
                track.bitrateKbps?.let { add("kbps" to it.toString()) }
                track.sampleRateKhz?.let { add("khz" to it.toString()) }
            }
            // the row's default name: a station's own name, or the filename a folder entry
            // arrived under
            track.defaultName?.takeIf { it.isNotEmpty() }?.let { add("name" to it) }
            // marks a station; an entry without the key is classified by looksLikeStream
            // when read
            if (track.isStream) add("stream" to "1")
            // the cover's address, so a restored queue has its pictures
            track.artworkUri?.takeIf { it.isNotEmpty() }?.let { add("art" to it) }
        }.joinToString(PAIR_SEPARATOR) { (key, value) -> "$key$KEY_SEPARATOR${encodeValue(value)}" }

    private fun pairs(body: String): Map<String, String> =
        body
            .split(PAIR_SEPARATOR)
            .mapNotNull { pair ->
                val key = pair.substringBefore(KEY_SEPARATOR, "")
                if (key.isEmpty() || !pair.contains(KEY_SEPARATOR)) {
                    null
                } else {
                    key to decodeValue(pair.substringAfter(KEY_SEPARATOR))
                }
            }.toMap()

    private fun track(
        uri: String,
        index: Int,
        seconds: Long?,
        displayLine: String?,
        extras: Map<String, String>,
    ): Track {
        // migration of a saved value: a queue that names the retired intro asset gets the
        // intro the app ships
        if (uri == RETIRED_INTRO) return DefaultTracks.tracks.first()
        // a foreign playlist has no ids; the position and the uri's hash make one for this
        // load
        val id = extras["id"]?.takeIf { it.isNotEmpty() } ?: "restored-$index-${uri.hashCode()}"
        val fallbackTitle = displayLine ?: uri.substringAfterLast('/').ifEmpty { uri }
        val restored =
            Track(
                id = id,
                artist = extras["artist"] ?: "",
                title = extras["title"] ?: fallbackTitle,
                // EXTINF writes an unknown length as -1 (streams do this); negative means unknown
                durationMs = (extras["ms"]?.toLongOrNull() ?: seconds?.times(MS_PER_SEC) ?: 0).coerceAtLeast(0),
                bitrateKbps = extras["kbps"]?.toIntOrNull(),
                sampleRateKhz = extras["khz"]?.toIntOrNull(),
                defaultName = extras["name"]?.takeIf { it.isNotEmpty() },
                isStream = extras["stream"] == "1" || (extras["stream"] == null && looksLikeStream(uri)),
                artworkUri = extras["art"]?.takeIf { it.isNotEmpty() },
                uri = uri,
            )
        // a file may carry bitrate and sample rate for a row that does not keep them
        return if (keepsReadouts(restored)) restored else restored.copy(bitrateKbps = null, sampleRateKhz = null)
    }

    /**
     * Whether a row's bitrate and sample rate are stored. A file on the phone keeps them.
     * A station or a song from another source does not: its rate can differ each time it
     * plays, and the player reports it while it plays.
     */
    private fun keepsReadouts(track: Track): Boolean = !track.isStream && SourceForRows.of(track) == MusicSource.LOCAL

    /** The retired intro asset a saved queue may name; see [DefaultTracks] for the current one. */
    private const val RETIRED_INTRO = "asset:///audio/llama.mp3"

    // %-encoding keeps ';', '=' and newlines inside a value from splitting the line
    private fun encodeValue(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun decodeValue(value: String): String =
        runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)
}
