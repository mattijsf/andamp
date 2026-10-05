// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * A plug-in offered by a link on Andamp's site: an Install button that links to the .lua.
 *
 * The link is an App Link, verified against the site's `/.well-known/assetlinks.json`, so
 * Android hands it to Andamp; where Andamp is not installed, the same link downloads the
 * file. Only https links into the plug-in folder of one of the [FOLDERS] are taken: the
 * site, and mattix.nl, which serves the same files. The manifest filters say so and
 * [accepts] checks again, because the receiving activity also receives files from anywhere.
 *
 * Following a link installs nothing. The fetched file is shown to the listener first
 * ([PluginOps.offerFrom]), and only Add installs it. Lua runs in Andamp's sandbox with no
 * access to Android.
 */
object PluginLinks {
    /** The site, as it is named to the listener. */
    const val SITE = "andamp.nl"

    /** The plug-in folder of each host a link is taken from. */
    private val FOLDERS =
        mapOf(
            SITE to "/extensions/plugins/",
            "mattix.nl" to "/andamp/extensions/plugins/",
        )

    /** Whether [url] is a plug-in link on the site. */
    fun accepts(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val folder = FOLDERS[uri.host] ?: return false
        val path = uri.path.orEmpty()
        return uri.scheme == "https" &&
            uri.port == -1 &&
            uri.userInfo == null &&
            path.startsWith(folder) &&
            path.endsWith(".lua") &&
            !path.contains("/../")
    }

    /**
     * The file at [url], as text; throws with a reason the listener can read.
     *
     * Redirects are not followed, since one could lead off the site. The read is bounded
     * by [PluginOps.MAX_SOURCE_BYTES].
     */
    fun download(url: String): String {
        require(accepts(url)) { "that is not a plug-in link on $SITE" }
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "the site answered ${connection.responseCode}" }
            connection.inputStream.use { it.readAtMost(PluginOps.MAX_SOURCE_BYTES).decodeToString() }
        } finally {
            connection.disconnect()
        }
    }

    private const val TIMEOUT_MS = 15_000
}
