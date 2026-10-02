// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.skin.BundledSkins
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SkinLoader
import nl.mattix.andamp.state.SkinEntry
import nl.mattix.andamp.state.SkinLibrary
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.WindowStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The skin manager's list, with a library too big for the window. One widget serves both
 * gestures: a still finger acts on its row, a moving one scrolls. Guards against a scroll
 * layer over the rows taking every press.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // skin decode goes through BitmapFactory
@Config(sdk = [35], qualifiers = WIDE_WINDOW)
class SkinManagerInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var skin: Skin
    private lateinit var vm: WinampViewModel
    private lateinit var frame: WindowFrame
    private val layout = SkinManagerLayout()

    /** More entries than fit: [layout.visibleRows] plus one. */
    private val overflowing = layout.visibleRows + 1

    @Before
    fun setUp() {
        File(app.filesDir, "skins").deleteRecursively()
        skin = SkinLoader.loadBase(app)
        frame = frameFor(skin)
        vm = testViewModel()

        // a library that outgrows the window; distinct bytes so the content
        // hash keeps them apart instead of deduplicating them into one
        val library = SkinLibrary(app)
        repeat(overflowing - BundledSkins.all.size) { i -> library.save(ByteArray(16) { i.toByte() }, "skin$i") }
        vm.state.skinEntries = library.list()
        assertEquals(overflowing, vm.state.skinEntries.size)

        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                SkinManagerWindow(vm, skin, SCALE, Modifier.fillMaxSize())
            }
        }
        compose.waitForIdle()
    }

    /**
     * Virtual window coordinates to device pixels. The window floats centered
     * in the container rather than sitting at the origin, so the usual
     * multiply-by-scale is off by half the leftover space.
     */
    private fun devicePoint(
        x: Float,
        y: Float,
    ): Offset {
        val root = compose.onRoot().fetchSemanticsNode().size
        val originX = (root.width - SkinManagerLayout.WIDTH * SCALE) / 2f
        val originY = (root.height - layout.height(frame) * SCALE) / 2f
        return Offset(originX + x * SCALE, originY + y * SCALE)
    }

    private fun tapWindow(
        x: Float,
        y: Float,
    ) = compose.onRoot().performTouchInput {
        down(devicePoint(x, y))
        up()
    }

    private fun dragWindow(
        x: Float,
        fromY: Float,
        toY: Float,
    ) = compose.onRoot().performTouchInput {
        down(devicePoint(x, fromY))
        moveTo(devicePoint(x, (fromY + toY) / 2f))
        moveTo(devicePoint(x, toY))
        up()
    }

    /** Window-relative y of the given visible row's middle. */
    private fun rowCenterY(row: Int) = frame.titleH + SkinManagerLayout.rowY(row) + SkinManagerLayout.ROW_H / 2f

    /** Every visible row must be fully inside the list, the last one included. */
    private fun lastRowBottom() =
        frame.titleH + SkinManagerLayout.rowY(layout.visibleRows - 1) +
            SkinManagerLayout.ROW_H

    private fun nameColumnX() = frame.leftW + 20f

    private fun removeColumnX() = frame.leftW + SkinManagerLayout.listWidth(frame) - SkinManagerLayout.REMOVE_W / 2f

    private fun settle() {
        repeat(SETTLE_PASSES) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            Thread.sleep(SETTLE_MS)
        }
    }

    /**
     * The list fits the window, so there is nowhere to scroll - the case a
     * tap-vs-scroll rule that compares scroll positions gets wrong, firing the
     * row the swipe started on. Here that would apply a skin nobody chose.
     */
    @Test
    fun `a swipe over a short list applies nothing`() {
        vm.state.skinEntries = SkinLibrary(app).list().take(2)
        compose.waitForIdle()
        assertTrue("the fixture is shorter than the window", vm.state.skinEntries.size < layout.visibleRows)
        val before = vm.skinOps.currentId

        dragWindow(nameColumnX(), rowCenterY(0), rowCenterY(0) + 4 * SkinManagerLayout.ROW_H)
        settle()

        assertEquals(before, vm.skinOps.currentId)
    }

    @Test
    fun `a press in the REM column removes that row even when the list overflows`() {
        // the first row the listener owns: what ships with the app has no REM
        val row = vm.state.skinEntries.indexOfFirst { BundledSkins.of(it.id) == null }
        val doomed = vm.state.skinEntries[row]

        tapWindow(removeColumnX(), rowCenterY(row))
        settle()

        assertTrue(
            "the REM press removes row $row",
            SkinLibrary(app).list().none { it.id == doomed.id },
        )
    }

    @Test
    fun `a press on a row applies that skin even when the list overflows`() {
        // pick a real skin first so the base row is something to switch *to*:
        // the fixture's byte-array entries cannot be applied, but base can
        val real = app.assets.open("skins/AndAmp Light.wsz").use { it.readBytes() }
        vm.skinOps.load(real.inputStream(), "Custom.wsz")
        settle()
        vm.state.skinEntries = SkinLibrary(app).list()
        compose.waitForIdle()
        assertTrue("the fixture makes a skin other than base current", vm.skinOps.currentId != SkinEntry.BASE_ID)
        val baseRow = vm.state.skinEntries.indexOfFirst { it.id == SkinEntry.BASE_ID }

        tapWindow(nameColumnX(), rowCenterY(baseRow))
        settle()

        assertEquals(SkinEntry.BASE_ID, vm.skinOps.currentId)
    }

    @Test
    fun `the last visible row fits inside the list area`() {
        val listBottom = frame.titleH + layout.listHeight()
        assertTrue(
            "row ${layout.visibleRows} fits inside the list: ${lastRowBottom()} against $listBottom",
            lastRowBottom() <= listBottom,
        )
    }

    @Test
    fun `a press just above the list drags the window instead of scrolling`() {
        // the title bar sits within touch slop of the list's top edge
        val before = vm.state.skinManagerOffset
        dragWindow(nameColumnX(), frame.titleH - 2f, frame.titleH - 2f + 3 * SkinManagerLayout.ROW_H)
        settle()

        assertEquals("the list does not scroll", 0, vm.state.skinScroll)
        assertTrue("the window moves: ${vm.state.skinManagerOffset}", vm.state.skinManagerOffset != before)
    }

    @Test
    fun `a vertical drag scrolls the list instead of acting on a row`() {
        val before = SkinLibrary(app).list()

        dragWindow(removeColumnX(), rowCenterY(4), rowCenterY(4) - SkinManagerLayout.ROW_H)
        settle()

        assertEquals("one row of travel scrolls one row", 1, vm.state.skinScroll)
        assertEquals("a scroll removes nothing", before, SkinLibrary(app).list())
    }

    @Test
    fun `the list cannot scroll past its last row`() {
        dragWindow(nameColumnX(), rowCenterY(7), rowCenterY(0) - 4 * SkinManagerLayout.ROW_H)
        settle()

        assertEquals(overflowing - layout.visibleRows, vm.state.skinScroll)
    }

    private companion object {
        const val SETTLE_PASSES = 20
        const val SETTLE_MS = 10L
    }

    @Test
    fun `a row count that no longer fits is drawn at what does`() {
        // restored from a taller screen, or left over when the keyboard came
        // up: the window must not draw itself off the bottom
        vm.state.skinRows = 60

        compose.waitForIdle()

        val rect = vm.state.windowRects[WindowStore.SKINS]
        assertNotNull(rect)
        assertTrue(
            "the skin browser stays on screen: $rect of ${vm.state.screenH}",
            rect!!.bottom <= vm.state.screenH,
        )
    }

    @Test
    fun `the grip resizes even where the list is drawn across it`() {
        val before = vm.state.skinRows ?: SkinManagerLayout.DEFAULT_ROWS
        // the grip's top-left corner, in window coordinates: the list is drawn
        // across it, and the list is a control that would take the press
        val x = SkinManagerLayout.WIDTH - RESIZE_GRIP + 2f
        val y = layout.height(frame) - RESIZE_GRIP + 2f

        compose.onRoot().performTouchInput {
            down(devicePoint(x, y))
            moveTo(devicePoint(x, y + 30f))
            moveTo(devicePoint(x, y + 60f))
            up()
        }
        compose.waitForIdle()

        assertTrue(
            "the grip adds rows: $before -> ${vm.state.skinRows}",
            (vm.state.skinRows ?: before) > before,
        )
    }

    @Test
    fun `the grip widens the skin browser in 25px steps`() {
        val before = vm.state.skinCols
        val x = SkinManagerLayout.WIDTH - RESIZE_GRIP / 2f
        val y = layout.height(frame) - RESIZE_GRIP / 2f

        compose.onRoot().performTouchInput {
            down(devicePoint(x, y))
            moveTo(devicePoint(x + 30f, y))
            moveTo(devicePoint(x + 60f, y))
            up()
        }
        compose.waitForIdle()

        val room =
            ((vm.state.screenW - SkinManagerLayout.WIDTH) / SkinManagerLayout.WIDTH_STEP).coerceAtLeast(0)
        assertEquals(minOf(2, room), vm.state.skinCols)
        assertEquals(
            SkinManagerLayout.widthOfCols(vm.state.skinCols),
            vm.state.windowRects[WindowStore.SKINS]?.width,
        )
        assertTrue("the window widens when there is room", (vm.state.skinCols) > before || room == 0)
    }

    @Test
    fun `widening the skin browser keeps its top-left corner still`() {
        val before = vm.state.windowRects[WindowStore.SKINS]!!
        val x = SkinManagerLayout.WIDTH - RESIZE_GRIP / 2f
        val y = layout.height(frame) - RESIZE_GRIP / 2f

        compose.onRoot().performTouchInput {
            down(devicePoint(x, y))
            moveTo(devicePoint(x + 40f, y))
            up()
        }
        compose.waitForIdle()

        val after = vm.state.windowRects[WindowStore.SKINS]!!
        assertEquals("the left edge stays put", before.left, after.left)
        assertEquals("the top edge stays put", before.top, after.top)
    }

    @Test
    fun `a widen that spans several frames still grows to the right`() {
        val before = vm.state.windowRects[WindowStore.SKINS]!!
        val x = before.width - RESIZE_GRIP / 2f
        val y = layout.height(frame) - RESIZE_GRIP / 2f

        compose.onRoot().performTouchInput { down(devicePoint(x, y)) }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { moveTo(devicePoint(x + 30f, y)) }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { moveTo(devicePoint(x + 60f, y)) }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()

        val after = vm.state.windowRects[WindowStore.SKINS]!!
        assertEquals("the window widens by two steps", before.width + 2 * SkinManagerLayout.WIDTH_STEP, after.width)
        assertEquals("the left edge stays put", before.left, after.left)
    }

    @Test
    fun `a tap a hair wide of the close button still closes the window`() {
        vm.state.skinManagerOpen = true
        // measured off the rectangle the window published, so the tap lands
        // where the button is drawn rather than where the layout assumed
        val rect = vm.state.windowRects[WindowStore.SKINS]!!
        val x = (rect.left + frame.closeX(rect.width) - 3) * SCALE + 1f
        val y = (rect.top + frame.closeY() + 4) * SCALE + 1f

        compose.onRoot().performTouchInput {
            down(Offset(x, y))
            up()
        }
        compose.waitForIdle()

        assertTrue("the near miss closes the window", !vm.state.skinManagerOpen)
    }
}
