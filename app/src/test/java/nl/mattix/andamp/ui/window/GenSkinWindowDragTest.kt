// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SkinLoader
import nl.mattix.andamp.state.WinampState
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
 * Dragging a floating window into its bounds and back.
 *
 * The stored offset stops where the window stops. If only the rendered position were
 * clamped, a window shoved 400px off-screen would need 400px of travel back before it moved.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // skin decode goes through BitmapFactory
@Config(sdk = [35], qualifiers = BIG_WINDOW)
class GenSkinWindowDragTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var skin: Skin
    private lateinit var frame: WindowFrame
    private var stored by mutableStateOf(IntOffset.Zero)

    @Before
    fun setUp() {
        skin = SkinLoader.loadBase(ApplicationProvider.getApplicationContext<Application>())
        frame = frameFor(skin)
        compose.setContent {
            Box(Modifier.size(CONTAINER_W.dp, CONTAINER_H.dp)) {
                GenSkinWindow(
                    skin = skin,
                    state = WinampState(),
                    scale = 1,
                    spec =
                        GenWindowSpec(
                            title = "Skins",
                            width = WINDOW_W,
                            height = WINDOW_H,
                            offset = stored,
                            // the scaffold hands over where the window now is, not how far it moved
                            onMove = { place -> stored = place },
                            onClose = {},
                        ),
                    widgets = emptyList(),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.waitForIdle()
    }

    /**
     * Grab the title bar and walk the finger [dx], [dy] in [steps] moves, one
     * layout pass per move. The pass matters: positions arrive window-relative,
     * so the window's own motion is part of the arithmetic. Injecting every
     * move inside a single frame would describe input no device produces.
     */
    private fun dragTitleBar(
        dx: Int,
        dy: Int,
        steps: Int = STEPS,
    ) {
        // the grab has to land on the window where it currently sits
        val startX = (CONTAINER_W - WINDOW_W) / 2f + stored.x + GRAB_INSET
        val startY = (CONTAINER_H - WINDOW_H) / 2f + stored.y + frame.titleH / 2f
        compose.onRoot().performTouchInput { down(Offset(startX, startY)) }
        for (i in 1..steps) {
            compose.onRoot().performTouchInput {
                moveTo(Offset(startX + dx.toFloat() * i / steps, startY + dy.toFloat() * i / steps))
            }
            compose.waitForIdle()
        }
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
    }

    private fun maxX() = (CONTAINER_W - WINDOW_W) / 2 + WINDOW_W / 2

    /** Grab the left border, level with the content area, and pull down. */
    private fun dragLeftBorder(dy: Int) {
        val x = (CONTAINER_W - WINDOW_W) / 2f + frame.leftW / 2f
        val y = (CONTAINER_H - WINDOW_H) / 2f + WINDOW_H / 2f
        compose.onRoot().performTouchInput { down(Offset(x, y)) }
        for (i in 1..STEPS) {
            compose.onRoot().performTouchInput { moveTo(Offset(x, y + dy.toFloat() * i / STEPS)) }
            compose.waitForIdle()
        }
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
    }

    @Test
    fun `a drag past the edge parks the stored offset on the bound, not beyond it`() {
        dragTitleBar(dx = OVERSHOOT, dy = 0)
        assertEquals(maxX(), stored.x)
    }

    @Test
    fun `the window comes straight back after being dragged past the edge`() {
        dragTitleBar(dx = OVERSHOOT, dy = 0)
        dragTitleBar(dx = -NUDGE, dy = 0)
        // it followed the nudge instead of first re-crossing the overshoot;
        // the first move of a gesture goes into the dispatcher's slop, so the
        // travel is the nudge less that pixel
        val movedBack = maxX() - stored.x
        assertTrue("the window moves back about $NUDGE: $movedBack", movedBack in NUDGE - 2..NUDGE)
    }

    @Test
    fun `a drag below the screen keeps the title bar reachable`() {
        dragTitleBar(dx = 0, dy = CONTAINER_H)
        val top = (CONTAINER_H - WINDOW_H) / 2 + stored.y
        // the title bar is the only handle, so its bottom stops at the safe
        // edge (all of the container here: Robolectric reports no insets)
        assertEquals(CONTAINER_H, top + frame.titleH)
    }

    @Test
    fun `the side borders drag the window, not just the title bar`() {
        dragLeftBorder(dy = NUDGE)
        assertTrue("the left border drags the window: y=${stored.y}", stored.y > 0)
    }

    private companion object {
        const val STEPS = 20
        const val CONTAINER_W = 275
        const val CONTAINER_H = 800
        const val WINDOW_W = 275
        const val WINDOW_H = 138

        /** Well past any bound, so the clamp is what stops the window. */
        const val OVERSHOOT = 400
        const val NUDGE = 20
        const val GRAB_INSET = 60f
    }
}
