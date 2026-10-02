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
 * The museum's answers, in the shapes it sends them. Robolectric is here only for org.json, the
 * platform's parser; the client takes its fetch as a parameter, so nothing reaches the network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WebampSkinsTest {
    private val body =
        """
        {"data":{"skins":{"count":102623,"nodes":[
          {"md5":"d770a634cc1ab252cfb16c64f4f5f616","filename":"Zelda_Amp_3.wsz",
           "download_url":"https://r2.webampskins.org/skins/d770.wsz",
           "screenshot_url":"https://r2.webampskins.org/screenshots/d770.png",
           "museum_url":"https://skins.webamp.org/skin/d770","nsfw":false},
          {"md5":"971c1afc05b56f5160903d0748b73239","filename":"Deus_Ex_Amp_by_AJ.wsz",
           "download_url":"https://r2.webampskins.org/skins/971c.wsz",
           "screenshot_url":"https://r2.webampskins.org/screenshots/971c.png",
           "museum_url":"https://skins.webamp.org/skin/971c","nsfw":true}
        ]}}}
        """.trimIndent()

    private fun api(answer: String) = WebampSkins { answer }

    @Test
    fun `a page carries the skins and the size of the whole museum`() =
        runTest {
            val page = api(body).page(offset = 40, count = 2)

            assertEquals(102_623, page.total)
            assertEquals(40, page.offset)
            assertEquals(listOf("Zelda_Amp_3.wsz", "Deus_Ex_Amp_by_AJ.wsz"), page.items.map { it.filename })
            assertEquals("https://r2.webampskins.org/skins/d770.wsz", page.items.first().downloadUrl)
            assertTrue("the nsfw flag is read", page.items[1].nsfw)
        }

    @Test
    fun `a node without a hash or a download url is skipped`() =
        runTest {
            // the query asks for classic-skin fields through a fragment; anything
            // else in the museum comes back as an empty object
            val mixed = """{"data":{"skins":{"count":3,"nodes":[{},{"md5":"abc"},{"md5":"def","download_url":"u"}]}}}"""

            val page = api(mixed).page(0, 3)

            assertEquals(listOf("def"), page.items.map { it.md5 })
        }

    @Test
    fun `a skin with no filename is known by its hash`() =
        runTest {
            val nameless = """{"data":{"skins":{"count":1,"nodes":[{"md5":"abc","download_url":"u"}]}}}"""

            assertEquals(
                "abc.wsz",
                api(nameless)
                    .page(0, 1)
                    .items
                    .single()
                    .filename,
            )
        }

    @Test
    fun `an error body is a failure`() =
        runTest {
            val failed = """{"errors":[{"message":"nope"}]}"""

            val thrown = runCatching { api(failed).page(0, 10) }.exceptionOrNull()

            assertTrue("an error body throws an IOException: $thrown", thrown is IOException)
        }

    @Test
    fun `a body that is not JSON is a failure`() =
        runTest {
            val thrown = runCatching { api("<html>maintenance</html>").page(0, 10) }.exceptionOrNull()

            assertTrue("an HTML body throws an IOException: $thrown", thrown is IOException)
        }

    @Test
    fun `the query asks for the offset, the count and the museum's own order`() {
        val url = WebampSkins.pageUrl(offset = 120, count = 30)
        val query = java.net.URLDecoder.decode(url.substringAfter("query="), "UTF-8")

        assertTrue(url.startsWith(WebampSkins.ENDPOINT))
        assertTrue(query, query.contains("offset: 120"))
        assertTrue(query, query.contains("first: 30"))
        assertTrue(query, query.contains("sort: MUSEUM"))
    }

    @Test
    fun `nothing is asked of the museum's filters`() {
        // the endpoint returns an error when APPROVED is asked for alongside its
        // own order; the browser hides flagged skins itself, from the flag each
        // skin carries
        val query = java.net.URLDecoder.decode(WebampSkins.pageUrl(0, 10), "UTF-8")

        assertTrue(query, !query.contains("filter"))
        assertTrue("the query asks for the nsfw flag", query.contains("nsfw"))
    }
}
