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
 * The plug-in window resizes, the way Winamp's video and visualization windows did.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // skin decode goes through BitmapFactory
@Config(sdk = [35], qualifiers = WIDE_WINDOW)
class MilkdropResizeInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var skin: Skin
    private lateinit var vm: WinampViewModel
    private var screenH = 0

    private val frame get() = frameFor(skin)

    @Before
    fun setUp() {
        skin = SkinLoader.loadBase(app)
        vm = testViewModel()
        vm.state.milkdropOn = true
        compose.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                screenH = constraints.maxHeight / SCALE
                MilkdropWindow(vm, skin, SCALE, defaultOffset = IntOffset(0, 0), modifier = Modifier.fillMaxSize())
            }
        }
        compose.waitForIdle()
    }

    private fun width() = milkdropWidth(vm.state.milkdropCols)

    private fun height() = milkdropHeight(frame, milkdropContentH(vm.state.milkdropSteps))

    private fun top() = (screenH - height()) / 2 + (vm.state.milkdropOffset?.y ?: 0)

    private fun devicePoint(
        x: Float,
        y: Float,
    ): Offset {
        val root = compose.onRoot().fetchSemanticsNode().size
        return Offset((root.width - MILKDROP_W * SCALE) / 2f + x * SCALE, y * SCALE)
    }

    private fun dragGrip(
        fromY: Float,
        toY: Float,
    ) = compose.onRoot().performTouchInput {
        val x = MILKDROP_W - RESIZE_GRIP / 2f
        down(devicePoint(x, fromY))
        moveTo(devicePoint(x, (fromY + toY) / 2f))
        moveTo(devicePoint(x, toY))
        up()
    }

    @Test
    fun `the grip makes the visual taller, and the top edge stays put`() {
        val topBefore = top()
        val bottom = topBefore + height()

        dragGrip(bottom - RESIZE_GRIP / 2f, bottom - RESIZE_GRIP / 2f + 40f)

        assertEquals("the visual grows by the drag", milkdropContentH(null) + 40, milkdropContentH(vm.state.milkdropSteps))
        assertEquals("the top edge stays put", topBefore, top())
    }

    @Test
    fun `the grip makes it shorter, down to a floor`() {
        val bottom = top() + height()

        dragGrip(bottom - RESIZE_GRIP / 2f, 0f)

        assertEquals(MILKDROP_MIN_STEPS, vm.state.milkdropSteps)
    }

    @Test
    fun `it cannot be grown past the bottom of the screen`() {
        val bottom = top() + height()

        dragGrip(bottom - RESIZE_GRIP / 2f, screenH + 300f)

        val rect = vm.state.windowRects[WindowStore.MILKDROP]!!
        assertTrue("the visual stays on screen: $rect of $screenH", rect.bottom <= screenH)
    }

    @Test
    fun `a double tap on the visual makes it full screen`() {
        // the window's chrome covers every pixel of the window so it can be
        // dragged from anywhere, so the visual has to win its own touches. A
        // tap's effect happens on the render thread and is not visible from
        // here; the double tap lands in state, so it is what this asserts.
        val middle = top() + frame.titleH + MILKDROP_CONTENT_H / 2f

        val point = devicePoint(MILKDROP_W / 2f, middle)
        compose.onRoot().performTouchInput {
            down(point)
            up()
            advanceEventTime(DOUBLE_TAP_GAP_MS) // inside the double-tap window, outside long-press
            down(point)
            up()
        }
        compose.waitForIdle()

        assertEquals(true, vm.state.milkdropFullscreen)
    }

    @Test
    fun `dragging the window itself still moves it`() {
        val titleY = top() + frame.titleH / 2f

        compose.onRoot().performTouchInput {
            down(devicePoint(60f, titleY))
            moveTo(devicePoint(60f, titleY + 60f))
            moveTo(devicePoint(60f, titleY + 120f))
            up()
        }

        assertTrue("the title bar drags the window", top() > 60)
        assertEquals("moving the window keeps its size", null, vm.state.milkdropSteps)
    }

    @Test
    fun `the grip widens the plug-in window in 25px steps`() {
        val bottom = top() + height()
        val x = width() - RESIZE_GRIP / 2f
        val y = bottom - RESIZE_GRIP / 2f

        compose.onRoot().performTouchInput {
            down(devicePoint(x, y))
            moveTo(devicePoint(x + 30f, y))
            moveTo(devicePoint(x + 60f, y))
            up()
        }
        compose.waitForIdle()

        val room = ((vm.state.screenW - MILKDROP_W) / MILKDROP_WIDTH_STEP).coerceAtLeast(0)
        assertEquals(minOf(2, room), vm.state.milkdropCols)
        assertEquals(milkdropWidth(vm.state.milkdropCols), vm.state.windowRects[WindowStore.MILKDROP]?.width)
    }

    @Test
    fun `widening keeps the top-left corner still`() {
        val before = vm.state.windowRects[WindowStore.MILKDROP]!!
        val x = width() - RESIZE_GRIP / 2f
        val y = before.bottom - RESIZE_GRIP / 2f

        compose.onRoot().performTouchInput {
            down(devicePoint(x, y))
            moveTo(devicePoint(x + 40f, y))
            up()
        }
        compose.waitForIdle()

        val after = vm.state.windowRects[WindowStore.MILKDROP]!!
        assertEquals("the left edge stays put", before.left, after.left)
        assertEquals("the top edge stays put", before.top, after.top)
    }

    @Test
    fun `a widen that spans several frames still grows to the right`() {
        // One pointer event per frame, as in a real drag: a window that anchors
        // against its current width instead of the width it was grabbed at
        // re-centers itself when the first step is composed, and its left edge
        // moves left with every step after that.
        val before = vm.state.windowRects[WindowStore.MILKDROP]!!
        val x = width() - RESIZE_GRIP / 2f
        val y = before.bottom - RESIZE_GRIP / 2f

        compose.onRoot().performTouchInput { down(devicePoint(x, y)) }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { moveTo(devicePoint(x + 30f, y)) }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { moveTo(devicePoint(x + 60f, y)) }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()

        val after = vm.state.windowRects[WindowStore.MILKDROP]!!
        assertEquals("the window widens by two steps", before.width + 2 * MILKDROP_WIDTH_STEP, after.width)
        assertEquals("the left edge stays put", before.left, after.left)
    }

    @Test
    fun `a tap a hair wide of the close button still closes the window`() {
        val rect = vm.state.windowRects[WindowStore.MILKDROP]!!
        val x = (rect.left + frame.closeX(rect.width) - 3) * SCALE + 1f
        val y = (rect.top + frame.closeY() + 4) * SCALE + 1f

        compose.onRoot().performTouchInput {
            down(Offset(x, y))
            up()
        }
        compose.waitForIdle()

        assertTrue("the near miss closes the window", !vm.state.milkdropOn)
    }

    private companion object {
        /** Comfortably inside Compose's double-tap window, and outside its long-press one. */
        const val DOUBLE_TAP_GAP_MS = 50L
    }
}
