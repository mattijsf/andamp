// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import android.app.Application
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.zIndex
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.skin.Dest
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SkinLoader
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.WindowStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The playlist as a window you can move: dragged by its title bar, docked
 * against the stack above it, resized by the grip in its corner.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // skin decode goes through BitmapFactory
@Config(sdk = [35], qualifiers = BIG_WINDOW)
class PlaylistFloatInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var skin: Skin
    private lateinit var vm: WinampViewModel

    /** Where the stack ends: the playlist starts flush under it. */
    private val dockedTop = 232
    private val dockedSegments = 8
    private val dockedHeight = PlaylistLayout.ofSegments(dockedSegments).height
    private var screenH = 0
    private var measuredScreenH = 0

    @Before
    fun setUp() {
        skin = SkinLoader.loadBase(app)
        vm = testViewModel()
        // this suite covers the docked fallback: a playlist with no size of its
        // own takes [dockedSegments]. A first launch seeds one instead
        // (WindowStore.PLAYLIST_START_SEGMENTS), which PersistenceOpsTest covers
        vm.state.plSegments = null
        // the anchored stack the playlist docks against, as the screen publishes
        // it in production: the player and the equalizer above it
        vm.state.windowRects["main"] = IntRect(0, 0, PL_W, 116)
        vm.state.windowRects["eq"] = IntRect(0, 116, PL_W, dockedTop)
        compose.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // the screen's own shape: a fixed composition order, stacking by
                // zIndex. Reordering the children instead would tear the window
                // down as it is raised, and take the press with it
                key(WindowStore.PLAYLIST) {
                    // the screen the window centers in, measured where it is known
                    val virtualH = constraints.maxHeight / SCALE
                    measuredScreenH = virtualH
                    // the way the screen composes it: hidden when closed, and a
                    // different composable when it is collapsed to its bar
                    if (vm.state.plVisible && vm.state.plShaded) {
                        FloatingSkinWindow(
                            id = WindowStore.PLAYLIST,
                            state = vm.state,
                            scale = SCALE,
                            width = PL_W,
                            height = SHADE_H,
                            offset = vm.state.plOffset,
                            defaultOffset = dockedOffset(dockedTop, SHADE_H, virtualH),
                            onMove = { vm.state.plOffset = it },
                            titleH = SHADE_H,
                            widgets = emptyList(),
                            modifier = Modifier.fillMaxSize(),
                        ) {}
                    } else if (vm.state.plVisible) {
                        PlaylistFloatWindow(
                            vm,
                            skin,
                            SCALE,
                            PlaylistMenuActions(),
                            dockedTop = dockedTop,
                            dockedSegments = dockedSegments,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .zIndex(
                                        vm.state.windowOrder
                                            .indexOf(WindowStore.PLAYLIST)
                                            .toFloat(),
                                    ),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        screenH = measuredScreenH
    }

    /** The playlist's top-left on screen, in virtual px. */
    private fun windowTop(): Int {
        val offset = vm.state.plOffset ?: IntOffset(0, dockedTop - (screenH - dockedHeight) / 2)
        val height = PlaylistLayout.ofSegments(vm.state.plSegments ?: dockedSegments).height
        return (screenH - height) / 2 + offset.y
    }

    private fun devicePoint(
        x: Float,
        y: Float,
    ): Offset {
        val root = compose.onRoot().fetchSemanticsNode().size
        val originX = (root.width - PL_W * SCALE) / 2f
        return Offset(originX + x * SCALE, y * SCALE)
    }

    private fun dragOnScreen(
        x: Float,
        fromY: Float,
        toY: Float,
    ) = compose.onRoot().performTouchInput {
        down(devicePoint(x, fromY))
        moveTo(devicePoint(x, (fromY + toY) / 2f))
        moveTo(devicePoint(x, toY))
        up()
    }

    @Test
    fun `the playlist starts flush under the stack`() {
        assertEquals(dockedTop, windowTop())
    }

    @Test
    fun `a window behind another moves on the first drag, without a tap to wake it`() {
        // it is not in front: the press both raises it and drags it, in one go
        vm.state.windowOrder = listOf(WindowStore.PLAYLIST, WindowStore.LIBRARY)
        val titleY = dockedTop + Dest.PL_TOP_H / 2f

        dragOnScreen(60f, titleY, titleY + 120f)

        assertTrue("the first drag moves the window", windowTop() > dockedTop + 60)
        assertEquals(WindowStore.PLAYLIST, vm.state.windowOrder.last())
    }

    @Test
    fun `a long press on the window is Winamp's right click`() {
        var menus = 0
        // the chrome owns every pixel that is not a control, which is where a
        // right click landed on the desktop
        val chrome =
            windowChromeWidget(
                id = "probe.chrome",
                bounds = IntRect(0, 0, PL_W, 100),
                grip = null,
                drag = WindowDrag(),
                resize = WindowResize(),
                offsetNow = { IntOffset.Zero },
                heightNow = { 100 },
                onMove = { _, _ -> },
                onResize = {},
                onLongPress = { menus++ },
            )

        assertTrue("a window with a menu answers a long press", chrome.hasLongPress)
        chrome.longPress(vm.state)
        assertEquals(1, menus)
    }

    @Test
    fun `a double tap on a row plays it, focused or not`() {
        // the window is not in front: the first tap raises it, and must still
        // count as the first half of the double tap
        vm.state.windowOrder = listOf(WindowStore.PLAYLIST, WindowStore.LIBRARY)
        val rowY = dockedTop + Dest.PL_TOP_H + Dest.PL_ROW_H + 3f // row 1

        compose.onRoot().performTouchInput {
            down(devicePoint(100f, rowY))
            up()
        }
        compose.onRoot().performTouchInput {
            down(devicePoint(100f, rowY))
            up()
        }
        compose.waitForIdle()

        assertEquals(1, vm.state.currentIndex)
        assertEquals(nl.mattix.andamp.core.model.Transport.Playing, vm.state.transport)
    }

    @Test
    fun `a drag on the title bar moves the window`() {
        val titleY = dockedTop + Dest.PL_TOP_H / 2f

        dragOnScreen(60f, titleY, titleY + 120f)

        assertNotNull("the drag gives the playlist a place of its own", vm.state.plOffset)
        assertTrue("the window moves below its start: ${windowTop()}", windowTop() > dockedTop + 60)
    }

    @Test
    fun `a drag back near the stack docks it flush again`() {
        val titleY = dockedTop + Dest.PL_TOP_H / 2f
        dragOnScreen(60f, titleY, titleY + 120f)
        compose.waitForIdle()
        val movedTitle = windowTop() + Dest.PL_TOP_H / 2f

        // back to within a few px of flush: the snap closes the gap
        dragOnScreen(60f, movedTitle, movedTitle - 116f)

        assertEquals(dockedTop, windowTop())
    }

    @Test
    fun `a small careful drag moves the window, snap or no snap`() {
        // docked flush under the stack, so every pixel of this drag is inside
        // the snap's pull, which must not swallow it
        val titleY = dockedTop + Dest.PL_TOP_H / 2f

        dragOnScreen(60f, titleY, titleY + 14f)

        assertTrue("a 14px drag moves the window: ${windowTop()}", windowTop() > dockedTop + 8)
    }

    @Test
    fun `a drag moves the window as far as the finger, not further`() {
        val titleY = dockedTop + Dest.PL_TOP_H / 2f

        // three events in one gesture; each measures against a window the
        // layout has not caught up with yet, and none may add the whole
        // travel again
        compose.onRoot().performTouchInput {
            down(devicePoint(60f, titleY))
            moveTo(devicePoint(60f, titleY + 40f))
            moveTo(devicePoint(60f, titleY + 80f))
            moveTo(devicePoint(60f, titleY + 120f))
            up()
        }

        val travelled = windowTop() - dockedTop
        assertTrue("a 120px drag moves the window about 120px: $travelled", travelled in 100..140)
    }

    @Test
    fun `any part of the window that is not a control drags it`() {
        // the strip under the menu buttons: art, not a control
        val bottomBar = dockedTop + dockedHeight - 3f

        dragOnScreen(60f, bottomBar, bottomBar + 90f)

        assertTrue("the bottom bar drags the window: ${windowTop()}", windowTop() > dockedTop + 60)
    }

    @Test
    fun `a drag that starts on a control still belongs to the control`() {
        // the rows scroll; they do not carry the window with them
        val firstRow = dockedTop + Dest.PL_TOP_H + 6f

        dragOnScreen(60f, firstRow, firstRow + 120f)

        assertEquals("the rows do not drag the window", dockedTop, windowTop())
    }

    @Test
    fun `the grip resizes in whole segments and remembers the size`() {
        val bottom = dockedTop + dockedHeight

        dragOnScreen(PL_W - RESIZE_GRIP / 2f, bottom - RESIZE_GRIP / 2f, bottom - RESIZE_GRIP / 2f - 58f)

        assertEquals(dockedSegments - 2, vm.state.plSegments)
        // the top edge stayed where it was; only the bottom came up
        assertEquals(dockedTop, windowTop())
    }

    @Test
    fun `resizing the playlist leaves the window docked under it where it is`() {
        // a resize moves one window: the one being resized. An edge that
        // arrives at a neighbor must not push it along, or a window nobody
        // touched walks down the screen.
        val bottom = dockedTop + dockedHeight
        val height = 60
        // a rectangle and an offset that agree: a window's place is the offset,
        // and the rectangle is what that offset draws
        vm.state.windowRects[WindowStore.MILKDROP] = IntRect(0, bottom, PL_W, bottom + height)
        vm.state.milkdropOffset = IntOffset(0, bottom - (screenH - height) / 2)
        val startedAt = vm.state.milkdropOffset

        dragOnScreen(PL_W - RESIZE_GRIP / 2f, bottom - RESIZE_GRIP / 2f, bottom - RESIZE_GRIP / 2f - 58f)
        compose.waitForIdle()

        assertEquals("the playlist resizes by two segments", dockedSegments - 2, vm.state.plSegments)
        assertEquals("the window under it stays put", startedAt, vm.state.milkdropOffset)
    }

    @Test
    fun `the grip keeps working after the window has been resized once`() {
        val bottom = dockedTop + dockedHeight
        dragOnScreen(PL_W - RESIZE_GRIP / 2f, bottom - RESIZE_GRIP / 2f, bottom - RESIZE_GRIP / 2f - 58f)
        val afterFirst = vm.state.plSegments
        val newBottom = dockedTop + PlaylistLayout.ofSegments(afterFirst!!).height

        // the grip moved up with the window's bottom edge; grabbing it where it
        // now is has to resize again, not drag the window off its dock
        dragOnScreen(PL_W - RESIZE_GRIP / 2f, newBottom - RESIZE_GRIP / 2f, newBottom - RESIZE_GRIP / 2f - 58f)

        assertEquals("the second grab resizes again", afterFirst - 2, vm.state.plSegments)
        assertEquals("the second grab leaves the window docked", dockedTop, windowTop())
    }

    @Test
    fun `the grip never shrinks the window past Winamp's minimum`() {
        val bottom = dockedTop + dockedHeight

        dragOnScreen(PL_W - RESIZE_GRIP / 2f, bottom - RESIZE_GRIP / 2f, 0f)

        assertEquals(PlaylistLayout.MIN_SEGMENTS, vm.state.plSegments)
    }

    @Test
    fun `the whole grip resizes, not just the half the slop leaves over`() {
        // the LIST button sits a few px left of the grip, and its hit slop
        // reaches across; the grip's own left columns still resize
        val bottom = dockedTop + dockedHeight
        val leftEdgeOfGrip = (PL_W - RESIZE_GRIP + 1).toFloat()

        dragOnScreen(leftEdgeOfGrip, bottom - RESIZE_GRIP / 2f, bottom - RESIZE_GRIP / 2f - 58f)

        assertEquals(dockedSegments - 2, vm.state.plSegments)
    }

    @Test
    fun `the grip cannot drag the bottom off the screen`() {
        // a window that has been moved down has less room below it than the
        // screen has: the cap has to measure from the window's own top edge
        vm.state.plOffset = IntOffset(0, 60)
        compose.waitForIdle()
        val top = windowTop()
        val bottom = top + PlaylistLayout.ofSegments(vm.state.plSegments ?: dockedSegments).height

        dragOnScreen(PL_W - RESIZE_GRIP / 2f, bottom - RESIZE_GRIP / 2f, screenH + 400f)

        val rect = vm.state.windowRects[WindowStore.PLAYLIST]!!
        assertTrue("the window stays on screen: $rect of $screenH", rect.bottom <= screenH)
    }

    @Test
    fun `dragging the grip to the bottom keeps the size it reached`() {
        // "as big as it goes" must be stored as a size, not as null: null
        // reads back as the docked default, which is measured under the stack,
        // not under a window that has been moved down, and the window would
        // spring past the screen the moment the finger came off
        vm.state.plOffset = IntOffset(0, 29)
        compose.waitForIdle()
        val top = windowTop()
        val bottom = top + PlaylistLayout.ofSegments(vm.state.plSegments ?: dockedSegments).height

        dragOnScreen(PL_W - RESIZE_GRIP / 2f, bottom - RESIZE_GRIP / 2f, screenH + 400f)

        val rect = vm.state.windowRects[WindowStore.PLAYLIST]!!
        assertNotNull("the size it reached is remembered", vm.state.plSegments)
        assertTrue("the window stays on screen: $rect of $screenH", rect.bottom <= screenH)
        assertEquals("the top edge stays put", top, rect.top)
    }

    @Test
    fun `a size restored from a bigger screen is kept, with its bar in reach`() {
        vm.state.plSegments = dockedSegments * 3
        compose.waitForIdle()

        val rect = vm.state.windowRects[WindowStore.PLAYLIST]!!
        assertEquals(
            "the restored size is kept",
            PlaylistLayout.ofSegments(dockedSegments * 3).height,
            rect.height,
        )
        assertTrue("the title bar is in reach: $rect", rect.top + Dest.PL_TOP_H <= screenH)
    }

    @Test
    fun `grabbing the grip after moving the window does not yank it`() {
        // moved down far enough that its docked height no longer fits: it must
        // already be drawn at a height the grip can hold, or the first event
        // takes the difference away in one go, under the fingertip
        vm.state.plOffset = IntOffset(0, dockedTop - (screenH - dockedHeight) / 2 + 100)
        compose.waitForIdle()
        val before = vm.state.windowRects[WindowStore.PLAYLIST]!!

        // barely a touch: press the grip and move a single pixel
        val x = PL_W - RESIZE_GRIP / 2f
        compose.onRoot().performTouchInput {
            down(devicePoint(x, before.bottom - RESIZE_GRIP / 2f))
            moveTo(devicePoint(x, before.bottom - RESIZE_GRIP / 2f - 1f))
            up()
        }

        val after = vm.state.windowRects[WindowStore.PLAYLIST]!!
        assertEquals("the top edge stays put", before.top, after.top)
        assertTrue(
            "the bottom moves at most one step on contact: ${before.bottom - after.bottom}px",
            kotlin.math.abs(before.bottom - after.bottom) <= Dest.PL_TILE_STEP,
        )
    }

    @Test
    fun `a window dragged off the bottom keeps its size`() {
        // Winamp let a window hang off the screen; only its handle has to stay
        // in reach. Shrinking it instead loses rows the listener chose.
        val before = PlaylistLayout.ofSegments(vm.state.plSegments ?: dockedSegments).height
        val titleY = dockedTop + Dest.PL_TOP_H / 2f

        dragOnScreen(60f, titleY, screenH.toFloat())

        val rect = vm.state.windowRects[WindowStore.PLAYLIST]!!
        assertEquals("the window keeps its size, hanging off the screen", before, rect.height)
        assertTrue("the title bar stays in the safe area: $rect", rect.top + Dest.PL_TOP_H <= screenH)
    }

    @Test
    fun `a size the listener dragged to is remembered even when it matches the docked one`() {
        // storing "the same as docked" as null hands the window back to the
        // stack, which is a different height as soon as it has been moved
        val bottom = dockedTop + dockedHeight
        dragOnScreen(PL_W - RESIZE_GRIP / 2f, bottom - RESIZE_GRIP / 2f, bottom - RESIZE_GRIP / 2f - 58f)
        val smaller = vm.state.plSegments!!

        dragOnScreen(
            PL_W - RESIZE_GRIP / 2f,
            dockedTop + PlaylistLayout.ofSegments(smaller).height - RESIZE_GRIP / 2f,
            dockedTop + dockedHeight - RESIZE_GRIP / 2f,
        )

        assertEquals(dockedSegments, vm.state.plSegments)
    }

    @Test
    fun `a press anywhere brings the playlist to the front`() {
        vm.state.windowOrder = listOf(WindowStore.PLAYLIST, WindowStore.LIBRARY)

        compose.onRoot().performTouchInput {
            down(devicePoint(60f, dockedTop + Dest.PL_TOP_H / 2f))
            up()
        }
        compose.waitForIdle()

        assertEquals(WindowStore.PLAYLIST, vm.state.windowOrder.last())
    }

    @Test
    fun `the playlist publishes where it is, for the windows that dock against it`() {
        val rect = vm.state.windowRects[WindowStore.PLAYLIST]

        assertNotNull(rect)
        assertEquals(dockedTop, rect!!.top)
        assertEquals(IntRect(rect.left, dockedTop, rect.left + PL_W, dockedTop + dockedHeight), rect)
    }

    @Test
    fun `a closed playlist stops being something to dock against`() {
        // every other window drops its rectangle when it leaves the tree; a
        // stale one is a window neighbors snap to and group with while it is
        // not on screen at all
        assertNotNull(vm.state.windowRects[WindowStore.PLAYLIST])

        vm.state.plVisible = false
        compose.waitForIdle()

        assertNull("a closed playlist publishes no rectangle", vm.state.windowRects[WindowStore.PLAYLIST])
    }

    @Test
    fun `collapsing swaps the composable and still publishes a rectangle`() {
        vm.state.plShaded = true
        compose.waitForIdle()

        val rect = vm.state.windowRects[WindowStore.PLAYLIST]
        assertEquals("the collapsed playlist publishes a rectangle", SHADE_H, rect?.height)
        assertEquals("the bar stays put", dockedTop, rect?.top)
    }

    @Test
    fun `dragging the playlist down and back leaves the size it had`() {
        // moved down there is less room, so it is drawn shorter; that cap is
        // not written back as the remembered size
        val before = vm.state.plSegments
        val titleY = dockedTop + Dest.PL_TOP_H / 2f

        dragOnScreen(60f, titleY, titleY + 120f)
        dragOnScreen(60f, windowTop() + Dest.PL_TOP_H / 2f, titleY)

        assertEquals("the remembered size is unchanged", before, vm.state.plSegments)
    }

    @Test
    fun `the grip widens the window in 25px steps`() {
        val bottom = dockedTop + dockedHeight
        val x = PL_W - RESIZE_GRIP / 2f
        val y = bottom - RESIZE_GRIP / 2f

        compose.onRoot().performTouchInput {
            down(devicePoint(x, y))
            moveTo(devicePoint(x + 30f, y))
            moveTo(devicePoint(x + 60f, y))
            up()
        }
        compose.waitForIdle()

        // 60 virtual px is two steps, but never more than the screen affords
        val room = ((vm.state.screenW - PL_W) / PlaylistLayout.WIDTH_STEP).coerceAtLeast(0)
        val expected = minOf(2, room)
        assertEquals("the window widens by up to two steps", expected, vm.state.plCols)
        assertEquals(
            PlaylistLayout.widthOfCols(expected),
            vm.state.windowRects[WindowStore.PLAYLIST]?.width,
        )
    }

    @Test
    fun `it never grows wider than the window Android gave us`() {
        val bottom = dockedTop + dockedHeight
        val x = PL_W - RESIZE_GRIP / 2f
        val y = bottom - RESIZE_GRIP / 2f

        compose.onRoot().performTouchInput {
            down(devicePoint(x, y))
            moveTo(devicePoint(x + 400f, y))
            up()
        }
        compose.waitForIdle()

        val rect = vm.state.windowRects[WindowStore.PLAYLIST]!!
        assertTrue("the window fits the screen: ${rect.width} of ${vm.state.screenW}", rect.width <= vm.state.screenW)
    }

    @Test
    fun `it never goes narrower than Winamp's own 275`() {
        val bottom = dockedTop + dockedHeight
        val x = PL_W - RESIZE_GRIP / 2f
        val y = bottom - RESIZE_GRIP / 2f

        compose.onRoot().performTouchInput {
            down(devicePoint(x, y))
            moveTo(devicePoint(x - 200f, y))
            up()
        }
        compose.waitForIdle()

        assertEquals(0, vm.state.plCols)
        assertEquals(PL_W, vm.state.windowRects[WindowStore.PLAYLIST]?.width)
    }

    @Test
    fun `widening grows to the right, not out of the middle`() {
        // the grip is the bottom-right corner, so the top-left corner is what
        // stays still - a window that grew from its center would crawl left
        // out from under the finger
        val before = vm.state.windowRects[WindowStore.PLAYLIST]!!
        val x = PL_W - RESIZE_GRIP / 2f
        val y = before.bottom - RESIZE_GRIP / 2f

        compose.onRoot().performTouchInput {
            down(devicePoint(x, y))
            moveTo(devicePoint(x + 30f, y))
            moveTo(devicePoint(x + 60f, y))
            up()
        }
        compose.waitForIdle()

        val after = vm.state.windowRects[WindowStore.PLAYLIST]!!
        assertEquals("the left edge stays put", before.left, after.left)
        assertEquals("the top edge stays put", before.top, after.top)
    }
}
