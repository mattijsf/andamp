// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntRect
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.ui.ScaledWindowCanvas
import nl.mattix.andamp.ui.widget.Widget
import nl.mattix.andamp.ui.widget.button
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The gesture: held on a control too small for a finger it magnifies, held
 * anywhere else it is still Winamp's right click.
 *
 * Driven through the canvas, where the rule lives. A press in a title bar lands on a
 * button whenever one is within the hit slop, so the rule is keyed on the button and not on
 * the chrome widget. The bar's backdrop is left out: it is what drags the window, and
 * arming a hold on it would cost the drag an event.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class LoupeInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    /** One device pixel per virtual pixel: at any more the 275px window is wider than this screen. */
    private val scale = 1
    private var closed = 0
    private var menus = 0

    private val state = WinampState()

    private var played = 0

    private val widgets =
        listOf(
            Widget(
                "main.chrome",
                IntRect(0, 0, 275, 116),
                background = true,
                taps = Widget.Taps(onLongPress = { menus++ }),
            ),
            button("main.close", 264, 3, 9, 9) { closed++ },
            // the clutter bar's letters: smaller still, and nowhere near a title bar
            button("main.clutter.o", 10, 25, 8, 8) { menus++ },
            button("main.clutter.a", 10, 33, 8, 7) { menus++ },
            // and something a finger can already hit
            button("main.play", 39, 88, 23, 18) { played++ },
        )

    private fun show() {
        val loupe =
            LoupeGesture(state) { _, on ->
                Loupe(
                    window = "main",
                    roam = Loupe.clusterAround(on, widgets),
                    paint = {},
                    widgets = { widgets },
                    start =
                        Offset(
                            on.bounds.center.x
                                .toFloat(),
                            on.bounds.center.y
                                .toFloat(),
                        ),
                )
            }
        compose.setContent { ScaledWindowCanvas(275, 116, scale, state, widgets, loupe = loupe) {} }
    }

    private fun at(
        x: Float,
        y: Float,
    ) = Offset(x * scale, y * scale)

    @Test
    fun `holding a title-bar button opens the magnifier`() {
        show()
        compose.mainClock.autoAdvance = false

        // three pixels short of the button, which the hit slop still calls a hit
        compose.onRoot().performTouchInput { down(at(261f, 7f)) }
        compose.mainClock.advanceTimeBy(600)

        assertNotNull("holding the button opens the magnifier", state.loupe)
        assertEquals("the hold opens no menu", 0, menus)
    }

    @Test
    fun `it opens aimed at the button, not at the pixel that was pressed`() {
        show()
        compose.mainClock.autoAdvance = false

        compose.onRoot().performTouchInput { down(at(261f, 7f)) }
        compose.mainClock.advanceTimeBy(600)

        assertEquals(268.5f, state.loupe?.focus?.x ?: 0f, 0.6f)
    }

    /**
     * What it is aiming at is lit while it aims, before the finger has moved: a release
     * without moving presses that button.
     */
    @Test
    fun `the control under the crosshair is lit from the moment it opens`() {
        show()
        compose.mainClock.autoAdvance = false

        compose.onRoot().performTouchInput { down(at(261f, 7f)) }
        compose.mainClock.advanceTimeBy(600)

        assertEquals("main.close", state.pressedWidget)
    }

    @Test
    fun `letting go presses what it was aiming at`() {
        show()
        compose.mainClock.autoAdvance = false

        compose.onRoot().performTouchInput { down(at(261f, 7f)) }
        compose.mainClock.advanceTimeBy(600)
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(50)

        assertEquals(1, closed)
        assertNull("the lens closes on release", state.loupe)
    }

    @Test
    fun `holding the bar's own backdrop opens the menu and no magnifier`() {
        show()
        compose.mainClock.autoAdvance = false

        // far from any button: this is the handle a window is dragged by, and
        // a lens here would cost the drag its last move
        compose.onRoot().performTouchInput { down(at(120f, 7f)) }
        compose.mainClock.advanceTimeBy(600)

        assertNull("the window's handle opens no magnifier", state.loupe)
        assertEquals(1, menus)
    }

    @Test
    fun `holding a clutter letter magnifies it too, nowhere near a title bar`() {
        show()
        compose.mainClock.autoAdvance = false

        compose.onRoot().performTouchInput { down(at(13f, 29f)) }
        compose.mainClock.advanceTimeBy(600)

        assertNotNull("holding a clutter letter opens the magnifier", state.loupe)
        // and the lens may walk down the column it belongs to
        assertTrue("the lens reaches the letter under it", (state.loupe?.roam?.bottom ?: 0) >= 40)
    }

    @Test
    fun `holding something a finger can already hit opens no magnifier`() {
        show()
        compose.mainClock.autoAdvance = false

        compose.onRoot().performTouchInput { down(at(50f, 95f)) }
        compose.mainClock.advanceTimeBy(600)

        assertNull("a 23x18 button opens no magnifier", state.loupe)
        assertEquals("the held button does not fire before release", 0, played)
    }
}
