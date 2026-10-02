// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common.audio

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Why a stream would not open: the connection to the server, or the track itself.
 *
 * `MediaExtractor` throws the same `IOException` for every failure to open an http URL. A
 * lost connection is worth trying again; a deleted file or a response that is not audio is
 * not.
 *
 * So when the extractor has refused, the server is asked once, directly, for the first
 * byte of the same URL with the same headers. No answer at all is the network. A 5xx, a
 * 408 or a 429 is a trouble that passes. Any other status, including a 2xx whose body the
 * extractor could not read, is the track.
 *
 * The request is made only after a failure, and each of its waits is bounded by [WAIT_MS],
 * because it runs on the decode thread.
 */
internal object ServerAnswer {
    /** Statuses worth asking again for: the server is overloaded, or down for a moment. */
    private const val REQUEST_TIMEOUT = 408
    private const val TOO_MANY_REQUESTS = 429
    private const val SERVER_ERRORS = 500

    /** How long the question may take, to connect and again to answer. */
    private const val WAIT_MS = 10_000

    /** Whether an HTTP [status] is a trouble that passes, and not an answer about the track. */
    fun passing(status: Int): Boolean = status >= SERVER_ERRORS || status == REQUEST_TIMEOUT || status == TOO_MANY_REQUESTS

    /**
     * Asks the server about [url] and answers the [Ending] its reply means, for
     * a stream the extractor would not open with [refused].
     */
    fun about(
        url: String,
        headers: Map<String, String>,
        refused: IOException,
    ): Ending {
        val status =
            try {
                status(url, headers)
            } catch (e: IOException) {
                return Ending.Dropped(e.message ?: e.javaClass.simpleName)
            } catch (e: IllegalArgumentException) {
                // not a URL a connection can be made for: that is the track
                return Ending.Broke(e.message ?: refused.message ?: NOT_OPENED)
            }
        return meaning(status, refused)
    }

    /** What the server's [status] says about a stream the extractor refused with [refused]; the pure half of [about]. */
    fun meaning(
        status: Int,
        refused: IOException,
    ): Ending =
        when {
            status < 0 -> {
                Ending.Dropped("the server sent no status")
            }

            passing(status) -> {
                Ending.Dropped("the server answered $status")
            }

            status in HttpURLConnection.HTTP_OK until HttpURLConnection.HTTP_MULT_CHOICE -> {
                Ending.Broke(refused.message ?: NOT_OPENED)
            }

            else -> {
                Ending.Broke("the server answered $status")
            }
        }

    private fun status(
        url: String,
        headers: Map<String, String>,
    ): Int {
        val connection = URL(url).openConnection() as? HttpURLConnection ?: throw IllegalArgumentException("not http")
        return try {
            connection.connectTimeout = WAIT_MS
            connection.readTimeout = WAIT_MS
            connection.instanceFollowRedirects = true
            // one byte, so a server that honors ranges sends next to nothing; the
            // connection is closed before a body is read
            connection.setRequestProperty("Range", "bytes=0-0")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private const val NOT_OPENED = "the stream would not open"
}
