// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.online

import java.net.URLEncoder

/**
 * The museum's own search, as a source the browser can page through.
 *
 * `search_classic_skins` answers with a bare list and no count, so the total is estimated:
 * while a page comes back full there may be more, and a short page is the end.
 */
class SearchSkins(
    private val query: String,
    private val fetch: suspend (url: String) -> String = ::museumGet,
) : SkinsSource {
    override suspend fun page(
        offset: Int,
        count: Int,
    ): SkinsPage {
        val items = parse(fetch(searchUrl(query, offset, count)))
        val more = items.size == count
        return SkinsPage(
            // reports one page more while pages come back full; the short page settles the total
            total = if (more) offset + items.size + count else offset + items.size,
            offset = offset,
            items = items,
        )
    }

    fun parse(body: String): List<OnlineSkin> = skinsIn(answerOf(body).optJSONArray("search_classic_skins"))

    companion object {
        fun searchUrl(
            query: String,
            offset: Int,
            count: Int,
        ): String {
            // the endpoint errors on punctuation it reads as an operator; see MuseumQuery
            val quoted = MuseumQuery.sanitise(query).replace("\\", "\\\\").replace("\"", "\\\"")
            val gql =
                "{ search_classic_skins(query: \"$quoted\", first: $count, offset: $offset) " +
                    "{ md5 filename download_url screenshot_url museum_url nsfw } }"
            return "${WebampSkins.ENDPOINT}?query=${URLEncoder.encode(gql, "UTF-8")}"
        }
    }
}
