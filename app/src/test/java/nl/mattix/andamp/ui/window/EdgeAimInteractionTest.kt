// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntRect
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.ui.ScaledWindowCanvas
import nl.mattix.andamp.ui.widget.Widget
import nl.mattix.andamp.ui.widget.button
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A window as wide as the screen has its close button where a fingertip cannot get: the last
 * few pixels before the glass ends. A press near a side of the screen is aimed further out
 * than it landed, so the button at the edge is the one a finger at the edge presses.
 *
 * The window here is the screen: 275 pixels wide, one device pixel each.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class EdgeAimInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private val state = WinampState()
    private var shaded = 0
    private var closed = 0

    /** Where the wide control was pressed, and where the window was told the finger is. */
    private var widePressedAt: Offset? = null
    private var fingerAt: Offset? = null

    private val widgets =
        listOf(
            Widget("main.chrome", IntRect(0, 0, 275, 116), background = true),
            button("main.shade", 254, 3, 9, 9) { shaded++ },
            button("main.close", 264, 3, 9, 9) { closed++ },
            // something a finger can hit, reaching the same edge
            Widget("main.wide", IntRect(200, 40, 275, 60), pointer = Widget.Pointer(onDown = { widePressedAt = it })),
        )

    private fun show(assisted: Boolean = true) {
        val loupe =
            LoupeGesture(
                state,
                aim = { at -> Offset(Loupe.aimedAcross(at.x, 275, Loupe.Edge(reach = 10f, band = 30f)), at.y) },
            ) { finger, on ->
                fingerAt = finger
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
        compose.setContent { ScaledWindowCanvas(275, 116, 1, state, widgets, loupe = loupe.takeIf { assisted }) {} }
    }

    private fun tap(
        x: Float,
        y: Float,
    ) {
        compose.onRoot().performTouchInput {
            down(Offset(x, y))
            up()
        }
        compose.waitForIdle()
    }

    @Test
    fun `a tap as near the edge as a finger gets presses the button at the edge`() {
        show()

        // 15 pixels from the edge, on the shade button's own pixels
        tap(260f, 7f)

        assertEquals(1, closed)
        assertEquals(0, shaded)
    }

    @Test
    fun `a tap further in presses the button beside it`() {
        show()

        tap(253f, 7f)

        assertEquals(1, shaded)
        assertEquals(0, closed)
    }

    @Test
    fun `a hold there opens the lens on the button at the edge, and knows where the finger is`() {
        show()
        compose.mainClock.autoAdvance = false

        compose.onRoot().performTouchInput { down(Offset(260f, 7f)) }
        compose.mainClock.advanceTimeBy(600)

        assertEquals("main.close", state.loupe?.target?.id)
        // the finger's room on the screen is measured from the finger, not from the aim
        assertEquals(260f, fingerAt!!.x, 1f)

        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(100)
        assertEquals("the release presses close", 1, closed)
        assertEquals(0, shaded)
    }

    @Test
    fun `a control large enough for a finger is pressed where the finger is`() {
        show()

        tap(262f, 50f)

        assertEquals(262f, widePressedAt!!.x, 1f)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a mouse presses what it points at`() {
        show()

        compose.onRoot().performMouseInput { click(Offset(260f, 7f)) }
        compose.waitForIdle()

        assertEquals(1, shaded)
        assertEquals(0, closed)
    }

    @Test
    fun `with tap assist off a tap presses what it is on`() {
        show(assisted = false)

        tap(260f, 7f)

        assertEquals(1, shaded)
        assertEquals(0, closed)
    }
}
