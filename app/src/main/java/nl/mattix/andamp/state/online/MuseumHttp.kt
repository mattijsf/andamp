// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.online

import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * One HTTP client for everything the museum browser fetches.
 *
 * The catalog and the thumbnails share it. Both hosts speak HTTP/2, so a screenful of skins
 * travels over one connection per host, the cap on requests per host bounds a fast scroll,
 * and a cancelled call stops on the wire.
 *
 * `fastFallback` is set explicitly. The museum's host publishes IPv6 addresses and the
 * resolver returns those first; on a network with no IPv6 route, trying addresses one after
 * another waits out the connect timeout on each. With fast fallback the address families
 * are raced (RFC 8305).
 */
object MuseumHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            // more than OkHttp's default of five, so a screenful of tiles loads in fewer rounds
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = PER_HOST })
            // a resolver that keeps the last answer for a name; see StickyDns
            .dns(names)
            // an address family that cannot answer costs a short delay instead of the connect timeout
            .fastFallback(true)
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            // a whole-call ceiling as well: a response that trickles in never trips the read timeout
            .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    /** Resolves the museum's names ahead of the first request; see [StickyDns]. */
    fun warmNames() {
        for (host in listOf(PICTURES, MUSEUM)) names.warm(host)
    }

    private val names = StickyDns()

    private const val PICTURES = "r2.webampskins.org"
    private const val MUSEUM = "skins.webamp.org"
    private const val PER_HOST = 10
    private const val TIMEOUT_S = 10L
    private const val CALL_TIMEOUT_S = 20L
}
