// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntOffset
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.ui.widget.button
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Shading a window is a double tap on its title bar, and it leaves that title bar where
 * it was.
 *
 * A window's place is a center-relative offset, so a window that loses 102px of height
 * without being re-anchored would slide half of that down the screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = BIG_WINDOW)
class ShadeToggleInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private val state = WinampState()

    /** What a window's bar looks like: a button in it, and one just under it. */
    private val widgets =
        listOf(
            button("eq.on", 14, TITLE_BAR_H + 4, 26, 12) { underPressed++ },
            button("eq.close", 264, 3, 9, 9) { inBarPressed++ },
        )

    private var underPressed = 0

    private var inBarPressed = 0
    private val width = MAIN_W
    private val mainTop = 60

    private var screenH = 0

    @Before
    fun setUp() {
        compose.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                screenH = constraints.maxHeight / SCALE
                val height = heightOf(MAIN_H, state.mainShaded)
                FloatingSkinWindow(
                    id = WindowStore.MAIN,
                    state = state,
                    scale = SCALE,
                    width = width,
                    height = height,
                    offset = state.mainOffset,
                    defaultOffset = IntOffset(0, mainTop - (screenH - height) / 2),
                    onMove = { state.mainOffset = it },
                    titleH = TITLE_BAR_H,
                    widgets = widgets,
                    onTitleDoubleTap = { state.setShaded(WindowStore.MAIN, !state.mainShaded, MAIN_H) },
                    modifier = Modifier.fillMaxSize(),
                ) {}
            }
        }
        compose.waitForIdle()
    }

    private fun rect() = state.windowRects[WindowStore.MAIN] ?: error("never laid out")

    private fun devicePoint(
        x: Float,
        y: Float,
    ): Offset {
        val root = compose.onRoot().fetchSemanticsNode().size
        return Offset((root.width - width * SCALE) / 2f + x * SCALE, y * SCALE)
    }

    private fun doubleTapTitle() {
        val y = rect().top + TITLE_BAR_H / 2f
        // one gesture block: two taps as close together as a finger makes them
        compose.onRoot().performTouchInput {
            down(devicePoint(60f, y))
            up()
            advanceEventTime(60)
            down(devicePoint(60f, y))
            up()
        }
        compose.waitForIdle()
    }

    @Test
    fun `a double tap on the title bar collapses the window`() {
        doubleTapTitle()

        assertTrue("the window collapses", state.mainShaded)
        assertEquals(SHADE_H, rect().height)
    }

    @Test
    fun `a second double tap puts it back`() {
        doubleTapTitle()
        doubleTapTitle()

        assertEquals(false, state.mainShaded)
        assertEquals(MAIN_H, rect().height)
    }

    @Test
    fun `collapsing leaves the title bar where it was`() {
        // a window that has been moved: its place is its own, not the stack's
        state.mainOffset = IntOffset(0, mainTop + 40 - (screenH - MAIN_H) / 2)
        compose.waitForIdle()
        val before = rect().top

        doubleTapTitle()

        assertEquals("the title bar stays put", before, rect().top)
    }

    @Test
    fun `expanding leaves the title bar where it was too`() {
        state.mainOffset = IntOffset(0, mainTop + 40 - (screenH - MAIN_H) / 2)
        compose.waitForIdle()
        doubleTapTitle()
        val collapsed = rect().top

        doubleTapTitle()

        assertEquals("the title bar stays put on expanding", collapsed, rect().top)
    }

    @Test
    fun `a slow pair of taps is two taps, not a collapse`() {
        val y = rect().top + TITLE_BAR_H / 2f
        compose.onRoot().performTouchInput {
            down(devicePoint(60f, y))
            up()
        }
        // the double tap is timed against the monotonic clock, so that is the
        // one a slow pair of taps has to move
        org.robolectric.shadows.ShadowSystemClock
            .advanceBy(java.time.Duration.ofMillis(1_500))
        compose.onRoot().performTouchInput {
            down(devicePoint(60f, y))
            up()
        }
        compose.waitForIdle()

        assertEquals(false, state.mainShaded)
    }

    @Test
    fun `a double tap below the title bar leaves the window alone`() {
        val y = rect().top + TITLE_BAR_H + 20f
        compose.onRoot().performTouchInput {
            down(devicePoint(60f, y))
            up()
            down(devicePoint(60f, y))
            up()
        }
        compose.waitForIdle()

        assertEquals(false, state.mainShaded)
    }

    @Test
    fun `the bare title bar is the handle, whatever sits just under it`() {
        // hit slop reaches down, so a press on the bar's last row must not land
        // on the button below it. The press is above the button, with x inside
        // its columns, which is what would let hit slop claim it
        val y = rect().top + TITLE_BAR_H - 1f
        compose.onRoot().performTouchInput {
            down(devicePoint(20f, y))
            up()
        }
        compose.waitForIdle()

        assertEquals("a press on the title bar does not reach the control below it", 0, underPressed)
    }

    @Test
    fun `a button in the title bar still gets its press`() {
        // the chrome owns the bar, but not the widgets drawn in it
        compose.onRoot().performTouchInput {
            down(devicePoint(268f, rect().top + 7f))
            up()
        }
        compose.waitForIdle()

        assertEquals("the button in the bar gets its press", 1, inBarPressed)
    }

    @Test
    fun `a drag is not the first half of a double tap`() {
        // the pointer's coordinates are window-relative, so a window that
        // follows the finger reports no travel; the drag must still not count
        // as a tap
        val from = rect().top + TITLE_BAR_H / 2f
        compose.onRoot().performTouchInput {
            down(devicePoint(60f, from))
            moveTo(devicePoint(60f, from + 30f))
            moveTo(devicePoint(60f, from + 60f))
            up()
            advanceEventTime(60)
            down(devicePoint(60f, rect().top + TITLE_BAR_H / 2f))
            up()
        }
        compose.waitForIdle()

        assertEquals("a drag and a tap do not collapse the window", false, state.mainShaded)
    }
}
