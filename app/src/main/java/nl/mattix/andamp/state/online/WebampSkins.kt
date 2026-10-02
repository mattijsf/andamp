// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.online

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One skin in the museum, as the browser needs it. */
data class OnlineSkin(
    val md5: String,
    val filename: String,
    val screenshotUrl: String,
    val downloadUrl: String,
    val museumUrl: String?,
    val nsfw: Boolean,
)

/**
 * A museum skin built from its hash. The museum keeps its screenshots, downloads and pages
 * at addresses derived from the hash, so a skin can be described without a request.
 */
internal fun skinAt(
    md5: String,
    filename: String,
    nsfw: Boolean = false,
) = OnlineSkin(
    md5 = md5,
    filename = filename,
    screenshotUrl = "https://r2.webampskins.org/screenshots/$md5.png",
    downloadUrl = "https://r2.webampskins.org/skins/$md5.wsz",
    museumUrl = "https://skins.webamp.org/skin/$md5",
    nsfw = nsfw,
)

/** A slice of the museum, and how big the whole thing is. */
data class SkinsPage(
    val total: Int,
    val offset: Int,
    val items: List<OnlineSkin>,
)

/** Where a page of the museum comes from. */
fun interface SkinsSource {
    suspend fun page(
        offset: Int,
        count: Int,
    ): SkinsPage
}

/**
 * The Winamp Skin Museum's catalog, over its public GraphQL endpoint.
 *
 * Offset paging with a total: `skins(sort: MUSEUM, first:, offset:)` answers with `count`
 * for the whole museum, which lets the browser show a scrollbar over the whole list.
 *
 * [fetch] is injected, so tests drive the parser and the paging without a network.
 */
class WebampSkins(
    private val fetch: suspend (url: String) -> String = ::museumGet,
) : SkinsSource {
    override suspend fun page(
        offset: Int,
        count: Int,
    ): SkinsPage {
        val body = fetch(pageUrl(offset, count))
        return parse(body, offset)
    }

    /** Parses a museum response; a body carrying errors throws. */
    fun parse(
        body: String,
        offset: Int,
    ): SkinsPage {
        val skins = answerOf(body).optJSONObject("skins") ?: throw IOException("the museum answered without any skins in it")
        val items = skinsIn(skins.optJSONArray("nodes"))
        return SkinsPage(total = skins.optInt("count", items.size), offset = offset, items = items)
    }

    companion object {
        const val ENDPOINT = "https://skins.webamp.org/graphql"

        /**
         * The catalog query, in the museum's own order and unfiltered.
         *
         * The endpoint's filters are not used: APPROVED together with `sort: MUSEUM`
         * returns an error, and APPROVED means "curated" (12,699 of 102,623 skins), not
         * "not flagged". Every skin is fetched, and each carries its own `nsfw` flag.
         */
        fun pageUrl(
            offset: Int,
            count: Int,
        ): String {
            val query =
                "{ skins(sort: MUSEUM, first: $count, offset: $offset) " +
                    "{ count nodes { ... on ClassicSkin " +
                    "{ md5 filename download_url screenshot_url museum_url nsfw } } } }"
            return "$ENDPOINT?query=${URLEncoder.encode(query, "UTF-8")}"
        }
    }
}

/**
 * The museum answering with an error where the skins should be. It has its own type
 * because the request arrived and the server would not answer it, which differs from a
 * network failure.
 */
class MuseumRefused(
    message: String,
) : IOException(message)

/** The `data` of a museum answer; a body carrying errors throws [MuseumRefused]. */
internal fun answerOf(body: String): JSONObject {
    val json = runCatching { JSONObject(body) }.getOrNull()
    val data = json?.takeIf { !it.has("errors") }?.optJSONObject("data")
    if (data == null) {
        throw when {
            json == null -> IOException("the museum answered with something that is not JSON")
            json.has("errors") -> MuseumRefused("the museum refused: ${json.optJSONArray("errors")}")
            else -> IOException("the museum answered without any skins in it")
        }
    }
    return data
}

/** The classic skins in [nodes]. */
internal fun skinsIn(nodes: JSONArray?): List<OnlineSkin> =
    (0 until (nodes?.length() ?: 0)).mapNotNull { at ->
        val node = nodes?.optJSONObject(at) ?: return@mapNotNull null
        val md5 = node.optString("md5").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        // a node that is not a classic skin has none of the fragment's fields and is skipped
        val download = node.optString("download_url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        OnlineSkin(
            md5 = md5,
            filename = node.optString("filename").takeIf { it.isNotBlank() } ?: "$md5.wsz",
            screenshotUrl = node.optString("screenshot_url"),
            downloadUrl = download,
            museumUrl = node.optString("museum_url").takeIf { it.isNotBlank() },
            nsfw = node.optBoolean("nsfw", false),
        )
    }

/** One GET on the shared client. Cancelling the coroutine cancels the call. */
internal suspend fun museumGet(url: String): String =
    suspendCancellableCoroutine { waiting ->
        val call =
            MuseumHttp.client.newCall(
                Request
                    .Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .build(),
            )
        waiting.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) = waiting.resumeWithException(e)

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    response.use { answer ->
                        if (!answer.isSuccessful) {
                            waiting.resumeWithException(IOException("the museum answered ${answer.code}"))
                        } else {
                            runCatching { answer.body?.string().orEmpty() }
                                .onSuccess { waiting.resume(it) }
                                .onFailure { waiting.resumeWithException(it) }
                        }
                    }
                }
            },
        )
    }
