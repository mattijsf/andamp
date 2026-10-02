// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.online

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.mattix.andamp.state.online.OnlineSkin
import nl.mattix.andamp.state.online.OnlineSkins
import nl.mattix.andamp.state.online.ShuffledSkins
import nl.mattix.andamp.state.online.SkinCatalog
import nl.mattix.andamp.state.online.SkinInstalls
import nl.mattix.andamp.state.online.SkinOrder
import nl.mattix.andamp.state.online.SkinsPage
import nl.mattix.andamp.state.online.SkinsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Browsing the museum without a local copy of its list: a shuffle is
 * arithmetic, a search is the endpoint's, and installed skins are on the
 * phone. These drive that through the panel, to check that it is wired to the
 * state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class BrowsingTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val museum =
        SkinsSource { offset, count ->
            SkinsPage(
                90,
                offset,
                (0 until count).map { OnlineSkin("live${offset + it}", "live${offset + it}.wsz", "s", "d", null, false) },
            )
        }

    private val mine =
        listOf(
            OnlineSkin("a".repeat(32), "Mine.wsz", "s", "d", null, false),
            OnlineSkin("b".repeat(32), "Other.wsz", "s", "d", null, false),
        )

    private var museumAsked = 0

    private fun show(): OnlineSkins {
        val counting =
            SkinsSource { offset, count ->
                museumAsked++
                museum.page(offset, count)
            }
        val online =
            OnlineSkins(
                museum = SkinCatalog(counting, scope, io = Dispatchers.Unconfined, settleMs = 0),
                ops =
                    object : SkinInstalls {
                        override var message: String? = null

                        override fun isInstalled(md5: String) = mine.any { it.md5 == md5 }

                        override fun installedHashes() = mine.map { it.md5 }.toSet()

                        override fun installedSkins() = mine

                        override fun isBusy(md5: String) = false

                        override fun install(skin: OnlineSkin) = Unit

                        override fun uninstall(skin: OnlineSkin) = Unit

                        override fun forgetId(id: String) = Unit
                    },
                shuffling = { seed ->
                    SkinCatalog(
                        ShuffledSkins(counting, seed, SHUFFLE_PAGE) { 90 },
                        scope,
                        io = Dispatchers.Unconfined,
                        pageSize = SHUFFLE_PAGE,
                        settleMs = 0,
                    )
                },
            )
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                OnlineSkinsScreen(online, onClose = {})
            }
        }
        compose.waitForIdle()
        return online
    }

    private fun openFilters() {
        compose.onNodeWithTag("online.tune").performClick()
        compose.waitForIdle()
    }

    @Test
    fun `shuffling needs nothing fetched`() {
        // a shuffle is a permutation of positions, so it needs no list
        val online = show()

        openFilters()
        compose.onNodeWithTag("online.sort.shuffled").assertIsEnabled().performClick()
        compose.waitForIdle()

        assertEquals(SkinOrder.SHUFFLED, online.order)
        assertTrue("the shuffled list has skins", online.catalog.total > 0)
    }

    @Test
    fun `the sort chips are disabled during a search`() {
        // `search_classic_skins` answers by how well each skin matches and
        // takes no ordering argument
        val online = show()

        online.search("zelda")
        compose.waitForIdle()
        openFilters()

        compose.onNodeWithTag("online.sort.museum").assertIsNotEnabled()
        compose.onNodeWithTag("online.sort.shuffled").assertIsNotEnabled()
    }

    @Test
    fun `only skins you have is answered by the phone, not the museum`() {
        val online = show()
        openFilters()
        val before = museumAsked

        compose.onNodeWithTag("online.f.installed").performClick()
        compose.waitForIdle()

        assertEquals(mine.size, online.catalog.total)
        assertEquals("the museum is not asked about skins on the phone", before, museumAsked)
    }

    @Test
    fun `the sheet lies over the list and leaves the grid its size`() {
        show()
        val before = compose.onNodeWithTag("online.grid").fetchSemanticsNode().size

        openFilters()

        compose.onNodeWithTag("online.filters").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithTag("online.grid").fetchSemanticsNode().size)
    }

    @Test
    fun `tapping away from the sheet closes it`() {
        show()
        openFilters()
        compose.onNodeWithTag("online.filters").assertIsDisplayed()

        compose.onNodeWithTag("online.filters.away").performClick()
        compose.waitForIdle()

        assertEquals(0, compose.onAllNodesWithTag("online.filters").fetchSemanticsNodes().size)
    }

    private companion object {
        const val SHUFFLE_PAGE = 20
    }
}
