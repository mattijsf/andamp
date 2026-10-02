// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Reads the whole stream, up to [max] bytes.
 *
 * Everything the app reads whole (a skin, a playlist, a plug-in, an update file) is small,
 * but a picked file or a server's answer may be any size, so the read stops as soon as it
 * passes [max]. `InputStream.readNBytes` is available only from API 33.
 *
 * @return every byte of the stream; a stream of [max] bytes fits.
 * @throws StreamTooLarge as soon as the stream goes past [max]. It is an [IOException], so
 *     a caller that handles a failed read handles this too.
 */
internal fun InputStream.readAtMost(max: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val chunk = ByteArray(CHUNK)
    // a Long, so a ceiling near Int.MAX_VALUE cannot overflow
    var total = 0L
    while (true) {
        val read = read(chunk)
        if (read < 0) return out.toByteArray()
        total += read
        if (total > max) throw StreamTooLarge(max)
        out.write(chunk, 0, read)
    }
}

/**
 * What [readAtMost] throws for a stream longer than it will read. It has its own type so a
 * caller can report the size limit in its own words.
 */
internal class StreamTooLarge(
    /** The ceiling the stream went past, in bytes. */
    val max: Int,
) : IOException("stream exceeds $max bytes")

/** Bytes read per call. */
private const val CHUNK = 16 * 1024
