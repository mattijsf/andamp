// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.online

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.mattix.andamp.state.online.OnlineSkin
import nl.mattix.andamp.state.online.SkinCatalog
import nl.mattix.andamp.state.online.SkinsPage
import nl.mattix.andamp.state.online.SkinsSource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * One skin to a screen, and a swipe that lands on one: after the scroll
 * settles, the first visible item starts at the top of the viewport.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class SkinGridSnapTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val museum =
        SkinsSource { offset, count ->
            SkinsPage(
                total = 200,
                offset = offset,
                items =
                    (0 until count)
                        .map { offset + it }
                        .filter { it < 200 }
                        .map { OnlineSkin("m$it", "skin$it.wsz", "s$it", "d$it", null, false) },
            )
        }

    private fun show(wide: Boolean): LazyGridState {
        lateinit var grid: LazyGridState
        val catalog = SkinCatalog(museum, scope, io = Dispatchers.Unconfined, pageSize = 40, settleMs = 0)
        catalog.show(0..20)
        compose.setContent {
            grid = rememberLazyGridState()
            MaterialTheme(colorScheme = darkColorScheme()) {
                SkinGrid(
                    catalog = catalog,
                    gridState = grid,
                    wide = wide,
                    isInstalled = { false },
                    isBusy = { false },
                    onInstall = {},
                    onUninstall = {},
                    onOpen = {},
                )
            }
        }
        compose.waitForIdle()
        return grid
    }

    private fun flick() {
        compose.onNodeWithTag("online.grid").performTouchInput {
            swipeUp(startY = centerY, endY = centerY - SHORT_SWIPE)
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(SETTLING_MS)
        compose.waitForIdle()
    }

    @Test
    fun `a short swipe lands on the next skin`() {
        val grid = show(wide = true)

        flick()

        assertEquals("the skin lands at the top of the screen", 0, grid.firstVisibleItemScrollOffset)
        assertEquals("a short swipe moves to the next skin", 1, grid.firstVisibleItemIndex)
    }

    @Test
    fun `the wall of tiles is left to scroll freely`() {
        // the small tiles are a fraction of a screen each and are not snapped
        val grid = show(wide = false)

        flick()

        assertEquals("the tiles scroll freely, without snapping", true, grid.firstVisibleItemScrollOffset > 0)
    }

    private companion object {
        const val SHORT_SWIPE = 300f
        const val SETTLING_MS = 2_000L
    }
}
