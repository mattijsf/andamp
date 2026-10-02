// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.online

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.mattix.andamp.state.online.OnlineSkin
import nl.mattix.andamp.state.online.OnlineSkins
import nl.mattix.andamp.state.online.SkinCatalog
import nl.mattix.andamp.state.online.SkinInstalls
import nl.mattix.andamp.state.online.SkinsPage
import nl.mattix.andamp.state.online.SkinsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * The museum browser, driven through its UI. No network: the catalog is fed
 * by a fake museum and installs are recorded, not performed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class OnlineSkinsInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    // Compose state, because the row has to notice: an install flips its button
    private val installed = androidx.compose.runtime.mutableStateListOf<String>()
    private val installs = mutableListOf<String>()
    private val uninstalls = mutableListOf<String>()
    private var closed = false

    private class FakeMuseum(
        val total: Int = 500,
        var fail: Boolean = false,
        val flagged: Set<Int> = emptySet(),
    ) : SkinsSource {
        override suspend fun page(
            offset: Int,
            count: Int,
        ): SkinsPage {
            if (fail) throw IOException("no network")
            val items =
                (0 until count).map { at ->
                    val n = offset + at
                    OnlineSkin("m$n", "skin$n.wsz", "https://example.invalid/$n.png", "d$n", null, nsfw = n in flagged)
                }
            return SkinsPage(total, offset, items)
        }
    }

    /** What a search asked for, if the test made one. */
    private val searched = mutableListOf<String>()

    private fun show(
        museum: FakeMuseum = FakeMuseum(),
        catalog: SkinCatalog =
            SkinCatalog(museum, scope, io = Dispatchers.Unconfined, pageSize = 40, settleMs = 0),
        hits: FakeMuseum = FakeMuseum(total = 3),
    ): SkinCatalog {
        val online =
            OnlineSkins(
                museum = catalog,
                searching = { what ->
                    searched += what
                    SkinCatalog(hits, scope, io = Dispatchers.Unconfined, pageSize = 40, settleMs = 0)
                },
                ops =
                    object : SkinInstalls {
                        override var message: String? = null

                        override fun isInstalled(md5: String) = md5 in installed

                        override fun installedHashes() = installed.toSet()

                        override fun installedSkins() = emptyList<OnlineSkin>()

                        override fun isBusy(md5: String) = false

                        override fun install(skin: OnlineSkin) {
                            installs += skin.md5
                            installed.add(skin.md5)
                        }

                        override fun uninstall(skin: OnlineSkin) {
                            uninstalls += skin.md5
                            installed.remove(skin.md5)
                        }

                        override fun forgetId(id: String) = Unit
                    },
            )
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                OnlineSkinsScreen(online, onClose = { closed = true })
            }
        }
        compose.waitForIdle()
        return catalog
    }

    @Test
    fun `the museum's skins are tiles, and its size is in the title`() {
        show()

        compose.onNodeWithTag("online.tile.m0").assertIsDisplayed()
        compose.onNodeWithText("500 in the museum").assertIsDisplayed()
    }

    @Test
    fun `a tap opens the skin large, and it closes again`() {
        show()

        compose.onNodeWithTag("online.tile.m0").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("online.enlarged").assertIsDisplayed()
        compose.onNodeWithText("skin0").assertIsDisplayed()

        compose.onNodeWithTag("online.close").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTagCount("online.viewer", 0)
    }

    @Test
    fun `install becomes uninstall once it is installed`() {
        show()
        compose.onNodeWithTag("online.tile.m0").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("online.install.m0").performClick()
        compose.waitForIdle()

        assertEquals(listOf("m0"), installs)
        compose.onNodeWithTag("online.uninstall.m0").assertIsDisplayed()

        compose.onNodeWithTag("online.uninstall.m0").performClick()
        compose.waitForIdle()

        assertEquals(listOf("m0"), uninstalls)
        compose.onNodeWithTag("online.install.m0").assertIsDisplayed()
    }

    @Test
    fun `a museum out of reach offers a way to try again`() {
        val museum = FakeMuseum(fail = true)
        val catalog = show(museum)

        compose.onNodeWithText("The museum is out of reach").assertIsDisplayed()
        museum.fail = false
        compose.onNodeWithTag("online.retry").performClick()
        compose.waitForIdle()

        assertEquals(500, catalog.total)
        compose.onNodeWithTag("online.tile.m0").assertIsDisplayed()
    }

    @Test
    fun `what the museum flagged keeps its place and is never shown`() {
        // hidden, not skipped: the handle beside the grid addresses museum
        // positions, so a tile that is not shown still takes its place. The
        // filter sheet has no switch to show it
        show(FakeMuseum(flagged = setOf(1)))

        compose.onAllNodesWithTagCount("online.tile.m1", 0)
        compose.onNodeWithTag("online.covered").assertIsDisplayed()

        compose.onNodeWithTag("online.tune").performClick()
        compose.waitForIdle()

        compose.onAllNodesWithTagCount("online.f.flagged", 0)
        compose.onNodeWithTag("online.covered").assertIsDisplayed()
    }

    @Test
    fun `the layout switch shows one skin at a time, with its name and its button`() {
        // one to a screen shows the name and an install button without
        // opening the skin
        show()
        compose.onAllNodesWithTagCount("online.install.m0", 0)

        compose.onNodeWithTag("online.layout").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("online.tile.m0").assertIsDisplayed()
        compose.onNodeWithTag("online.install.m0").assertIsDisplayed()
        compose.onNodeWithText("skin0").assertIsDisplayed()

        compose.onNodeWithTag("online.layout").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("online.tile.m0").assertIsDisplayed()
        compose.onAllNodesWithTagCount("online.install.m0", 0)
    }

    @Test
    fun `switching layout keeps your place in the museum`() {
        // both layouts are the same list at the same position
        show()
        compose.onNodeWithTag("online.grid").performTouchInput { swipeUp(startY = centerY, endY = 0f) }
        compose.waitForIdle()
        compose.onAllNodesWithTagCount("online.tile.m0", 0)

        compose.onNodeWithTag("online.layout").performClick()
        compose.waitForIdle()

        compose.onAllNodesWithTagCount("online.tile.m0", 0)
    }

    @Test
    fun `a search shows what the museum found, and clearing it brings the museum back`() {
        // the search is the museum's own, not a filter over what is loaded:
        // it is a different list, so it starts at its own top
        show(hits = FakeMuseum(total = 3))

        compose.onNodeWithTag("online.search").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("online.query").performTextInput("zelda")
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()

        assertEquals(listOf("zelda"), searched)
        compose.onNodeWithText("3 found").assertIsDisplayed()

        compose.onNodeWithTag("online.search").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("500 in the museum").assertIsDisplayed()
    }

    @Test
    fun `a running preview still opens its details`() {
        // the preview answers its own buttons in the viewer; in the list a
        // tap on it opens the viewer
        show()
        compose.onNodeWithTag("online.layout").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("online.tile.m0").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("online.viewer").assertIsDisplayed()
    }

    @Test
    fun `closing is one tap`() {
        show()

        compose.onNodeWithTag("online.back").performClick()

        assertTrue(closed)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(
        tag: String,
        expected: Int,
    ) {
        val found = onAllNodesWithTag(tag).fetchSemanticsNodes().size
        assertEquals("$expected nodes are tagged $tag", expected, found)
    }
}
