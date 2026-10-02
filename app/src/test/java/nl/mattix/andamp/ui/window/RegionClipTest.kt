// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntOffset
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.skin.RegionTxt
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SkinLoader
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.WindowStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A skin that ships REGION.TXT gets its windows cut to that shape.
 *
 * Touches follow the shape too: Winamp's oddly-shaped players let a click through the
 * notches to whatever is behind them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // the skin decodes through BitmapFactory
@Config(sdk = [35], qualifiers = BIG_WINDOW)
class RegionClipTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var vm: WinampViewModel
    private var behind = 0

    /** The player, cut to its left half. */
    private fun leftHalf(skin: Skin): Skin =
        skin.withRegions(
            RegionTxt.parse(
                """
                [Normal]
                NumPoints=4
                PointList=0,0,137,0,137,116,0,116
                """.trimIndent(),
            ),
        )

    @Before
    fun setUp() {
        val base = SkinLoader.loadBase(app)
        vm = testViewModel()
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                // something to catch what falls through the cut-away half
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .clickable { behind++ },
                )
                MainFloatWindow(
                    vm,
                    leftHalf(base),
                    SCALE,
                    widgets = mainWindowWidgets(vm, onExit = {}) {},
                    height = MAIN_H,
                    defaultOffset = IntOffset.Zero,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun rect() = vm.state.windowRects[WindowStore.MAIN]!!

    private fun tapAt(
        x: Int,
        y: Int,
    ) = compose.onRoot().performTouchInput {
        val at = rect()
        down(Offset((at.left + x) * SCALE + 1f, (at.top + y) * SCALE + 1f))
        up()
    }

    @Test
    fun `the window still answers inside the shape it was cut to`() {
        tapAt(60, MAIN_H / 2)
        compose.waitForIdle()

        assertEquals("a press inside the shape stays with the window", 0, behind)
    }

    @Test
    fun `a press on a cut-away corner reaches what is behind it`() {
        // the right half is not part of this window: Winamp let the desktop
        // through there, and so does this
        tapAt(220, MAIN_H / 2)
        compose.waitForIdle()

        assertTrue("a press on the cut-away half reaches what is behind", behind > 0)
    }

    @Test
    fun `a skin with no region keeps its rectangle`() {
        // the bundled skin ships one, so a skin without has to be made: most
        // classic skins have no REGION.TXT at all and stay rectangles
        val plain = SkinLoader.loadBase(app).withRegions(RegionTxt.parse(null))

        assertTrue(plain.regions.isEmpty)
        assertEquals(null, RegionShape.of(plain, RegionTxt.Window.MAIN, SCALE))
    }
}
