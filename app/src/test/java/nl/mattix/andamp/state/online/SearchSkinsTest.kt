// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.online

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * The museum's own search, paged. It answers with a bare list and no count, so the total is
 * estimated from the pages; the query also has to survive being put inside a GraphQL string.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SearchSkinsTest {
    private fun answer(vararg md5: String) =
        """{"data":{"search_classic_skins":[${
            md5.joinToString(",") {
                """{"md5":"$it","filename":"$it.wsz","download_url":"https://r2/skins/$it.wsz",
                   "screenshot_url":"https://r2/screenshots/$it.png","museum_url":null,"nsfw":false}"""
            }
        }]}}"""

    @Test
    fun `a full page means there is more behind it`() =
        runTest {
            val page = SearchSkins("zelda") { answer("a", "b") }.page(offset = 0, count = 2)

            assertEquals(listOf("a", "b"), page.items.map { it.md5 })
            assertTrue("a full page reports more behind it", page.total > page.items.size)
        }

    @Test
    fun `a short page is the end of the list`() =
        runTest {
            val page = SearchSkins("zelda") { answer("a") }.page(offset = 40, count = 20)

            assertEquals(41, page.total)
        }

    @Test
    fun `nothing found is an empty list`() =
        runTest {
            val page = SearchSkins("qzx") { """{"data":{"search_classic_skins":[]}}""" }.page(0, 20)

            assertEquals(0, page.total)
            assertTrue(page.items.isEmpty())
        }

    @Test
    fun `an error body is a failure`() =
        runTest {
            val thrown =
                runCatching {
                    SearchSkins("zelda") { """{"errors":[{"message":"nope"}]}""" }.page(0, 20)
                }.exceptionOrNull()

            assertTrue("an error body throws an IOException: $thrown", thrown is IOException)
        }

    /**
     * [MuseumQuery] turns a quote into a space before it reaches the GraphQL string. The escaping
     * in `searchUrl` guards the query's own syntax behind that.
     */
    @Test
    fun `a quote in the search does not break the query`() {
        val url = SearchSkins.searchUrl("""say "hi"""", offset = 0, count = 5)
        val query = java.net.URLDecoder.decode(url.substringAfter("query="), "UTF-8")

        assertTrue(query, query.contains("""query: "say hi""""))
        assertTrue(query, query.contains("first: 5"))
    }
}
