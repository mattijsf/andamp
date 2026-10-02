// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Whether a source has a newer version than the one installed, from the `update.json` it
 * names.
 *
 * The file is two fields, and both are required:
 *
 * ```json
 * { "version": "0.5.0", "page": "https://example.org/source/download" }
 * ```
 *
 * `page` is a web page and never an APK: the app downloads and installs no apps,
 * which is the fifth of the conditions in ENGINEERING.md. It reads this file for each
 * installed source, compares, and opens the source's download page in the browser.
 */
object SourceUpdates {
    /** What the file says. */
    data class Latest(
        val version: String,
        /** Where the newer version is downloaded, which the browser opens. */
        val page: String,
    )

    /** The outcome of a check. */
    sealed interface Check {
        data object Checking : Check

        data class UpToDate(
            val version: String,
        ) : Check

        data class Available(
            val latest: Latest,
        ) : Check

        /** The file could not be fetched or read: offline, moved, or not a file of this shape. */
        data object Failed : Check
    }

    /** Fetches, reads and compares, off the main thread. */
    suspend fun check(
        url: String,
        installed: String?,
    ): Check = withContext(Dispatchers.IO) { answer(runCatching { parse(fetch(url)) }.getOrNull(), installed) }

    /** The decision alone, without a network. */
    fun answer(
        latest: Latest?,
        installed: String?,
    ): Check =
        when {
            latest == null -> Check.Failed
            installed != null && !isNewer(latest.version, installed) -> Check.UpToDate(installed)
            else -> Check.Available(latest)
        }

    fun parse(json: String): Latest? {
        val file = JSONObject(json)
        val version = file.optString("version").trim()
        val page = file.optString("page").trim()
        // only an https page is accepted
        if (version.isEmpty() || !page.startsWith("https://")) return null
        return Latest(version, page)
    }

    /**
     * Whether [latest] comes after [installed], number by number: 0.10.0 is after 0.9.3.
     * What follows a dash is a tag and is ignored: `1.2.0-beta` is 1.2.0.
     */
    fun isNewer(
        latest: String,
        installed: String,
    ): Boolean {
        val a = numbers(latest)
        val b = numbers(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(version: String) =
        version
            .substringBefore('-')
            .removePrefix("v")
            .split('.')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode}" }
            // the file is two fields of JSON, so the read is bounded
            connection.inputStream.use { it.readAtMost(MAX_BYTES).decodeToString() }
        } finally {
            connection.disconnect()
        }
    }

    private const val TIMEOUT_MS = 10_000
    private const val MAX_BYTES = 16 * 1024
}
