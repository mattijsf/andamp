// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.model

/**
 * What a station says about itself on connecting.
 *
 * Shoutcast and Icecast send these with the response to the first request: the name the
 * broadcaster chose, the genre, a website and the bitrate it intends to send. Any of them
 * may be absent.
 */
data class StationHeaders(
    val name: String? = null,
    val genre: String? = null,
    val url: String? = null,
    val bitrateKbps: Int? = null,
) {
    val isEmpty: Boolean get() = name == null && genre == null && url == null && bitrateKbps == null
}
