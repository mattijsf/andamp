// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.model

/**
 * Backend-agnostic track. Nullable fields are metadata a backend may not supply (for
 * example a stream without a fixed bitrate); the UI leaves the readout blank, as Winamp did.
 */
data class Track(
    val id: String,
    val artist: String,
    val title: String,
    val durationMs: Long,
    val bitrateKbps: Int? = null,
    val sampleRateKhz: Int? = null,
    /**
     * Where the audio is: `asset:///`, `content://`, `file://`, `http://`, `https://`, or a
     * source's own scheme. Null for a track with no address, as in tests.
     */
    val uri: String? = null,
    /**
     * What to call this before its tags are known, normally the filename.
     *
     * Entries can be listed at once under this name and renamed when their tags have been
     * read. Webamp carries the same field (`defaultName` in `actionCreators/files.ts`).
     */
    val defaultName: String? = null,
    /**
     * A live source with no beginning and no end: a station.
     *
     * Whoever makes the entry sets it. It is not derived from the address, because a source
     * may serve seekable songs over http. The position bar, the displayed length and seeking
     * follow it.
     */
    val isStream: Boolean = false,
    /**
     * Where this track's cover can be fetched, when the backend knows. The player shows it
     * in the media notification and on the lock screen.
     *
     * Null is ordinary: a file with no embedded art, a station, a backend that does not say.
     */
    val artworkUri: String? = null,
)

/**
 * How long this is, in seconds, and zero for a station.
 *
 * HLS reports the length of its sliding window as a duration, which is not a track length,
 * so [Track.durationMs] is ignored when [Track.isStream] is set.
 */
val Track.durationSec: Int get() = if (isStream) 0 else (durationMs / 1000).toInt()

/**
 * Winamp's playlist line, by the ladder webamp keeps in `js/trackUtils.ts`: artist and title
 * when both are known, else the title, else the name it was added under, else the filename
 * off the end of its uri.
 *
 * The last two rungs are what an entry shows while its tags are still being read.
 */
val Track.displayName: String get() =
    when {
        artist.isNotBlank() && title.isNotBlank() -> "$artist - $title"
        title.isNotBlank() -> title
        !defaultName.isNullOrBlank() -> defaultName
        else -> uri?.substringAfterLast('/')?.substringBefore('?').orEmpty()
    }

/**
 * What an address suggests when an entry does not record [Track.isStream], as in a saved
 * playlist without that field: true for `http://` and `https://`. An entry that knows sets
 * [Track.isStream] itself.
 */
fun looksLikeStream(uri: String?): Boolean =
    uri.orEmpty().let { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
