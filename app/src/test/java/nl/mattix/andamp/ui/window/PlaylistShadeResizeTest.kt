// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import android.app.Application
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntOffset
import androidx.test.core.app.ApplicationProvider
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
 * Winamp's shade bar can be widened by a 9x9 grip beside the close button, where every
 * other window uses its corner.
 *
 * The bar has no height to give, so its grip moves in a single axis. The offset is
 * measured from the screen's center, so half of every added column has to be given back
 * or widening moves the window leftwards.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // skin decode goes through BitmapFactory
@Config(sdk = [35], qualifiers = WIDE_WINDOW)
class PlaylistShadeResizeTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var skin: Skin
    private lateinit var vm: WinampViewModel

    @Before
    fun setUp() {
        skin = SkinLoader.loadBase(app)
        vm = testViewModel()
        vm.state.plShaded = true
        compose.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                PlaylistShadeFloatWindow(
                    vm,
                    skin,
                    SCALE,
                    expandedH = 116,
                    defaultOffset = IntOffset(0, 0),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun rect() = vm.state.windowRects[WindowStore.PLAYLIST]!!

    /** Window-relative virtual coordinates to device pixels. */
    private fun devicePoint(
        x: Float,
        y: Float,
    ): Offset {
        val here = rect()
        return Offset((here.left + x) * SCALE.toFloat(), (here.top + y) * SCALE.toFloat())
    }

    private fun dragGrip(by: Float) {
        val grip = playlistShadeGrip(rect().width)
        val x = grip.left + grip.width / 2f
        val y = grip.top + grip.height / 2f
        compose.onRoot().performTouchInput {
            down(devicePoint(x, y))
            moveTo(devicePoint(x + by / 2f, y))
            moveTo(devicePoint(x + by, y))
            up()
        }
        compose.waitForIdle()
    }

    private fun room() = ((vm.state.screenW - PL_W) / PlaylistLayout.WIDTH_STEP).coerceAtLeast(0)

    @Test
    fun `the bar starts at the playlist's own width`() {
        assertEquals(PL_W, rect().width)
    }

    @Test
    fun `the grip widens the bar in 25px steps`() {
        dragGrip(by = 60f)

        assertEquals(minOf(2, room()), vm.state.plCols)
        assertEquals(PL_W + vm.state.plCols * PlaylistLayout.WIDTH_STEP, rect().width)
    }

    @Test
    fun `widening keeps the left edge still`() {
        val before = rect()

        dragGrip(by = 40f)

        assertEquals("the left edge stays put", before.left, rect().left)
        assertEquals("the bar keeps its height", before.height, rect().height)
    }

    @Test
    fun `it narrows back to the playlist's own width and no further`() {
        dragGrip(by = 60f)
        assertTrue("the bar is wider than the playlist before narrowing", vm.state.plCols > 0)

        dragGrip(by = -400f)

        assertEquals(0, vm.state.plCols)
        assertEquals(PL_W, rect().width)
    }

    @Test
    fun `it cannot be grown past the screen`() {
        dragGrip(by = 4_000f)

        assertEquals(room(), vm.state.plCols)
        assertTrue("the bar fits the screen", rect().width <= vm.state.screenW)
    }

    /** The controls follow the right edge of a widened bar. */
    @Test
    fun `the close button follows the right edge`() {
        dragGrip(by = 60f)
        val widened = rect().width

        val widgets = playlistShadeWidgets(vm, expandedH = 116, width = widened)
        val close = widgets.first { it.id == "pl.close" }

        assertEquals(widened - 11, close.bounds.left)
        assertTrue("the close button stays on the bar", close.bounds.right <= widened)
    }

    @Test
    fun `the grip rides it too`() {
        dragGrip(by = 60f)

        val grip = playlistShadeGrip(rect().width)

        assertEquals(rect().width - 29, grip.left)
    }
}
