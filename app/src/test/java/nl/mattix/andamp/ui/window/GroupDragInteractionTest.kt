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
import androidx.compose.ui.unit.IntRect
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.WindowStore
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The player, the equalizer stuck under it, and a playlist a little further
 * down: dragging the player has to carry the equalizer *and* dock the pair
 * against the playlist.
 *
 * This exercises the wiring; the geometry has its own tests. A window that does not hand
 * its group to the snap docks by its own edge and stops short.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = BIG_WINDOW)
class GroupDragInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private val state = WinampState()
    private val width = 275
    private val height = 116

    /** main at 0..116, eq flush under it, playlist 20px below the pair. */
    private val mainTop = 0
    private val eqTop = 116
    private val playlistTop = 252

    private var screenH = 0

    @Before
    fun setUp() {
        state.windowRects[WindowStore.PLAYLIST] = IntRect(0, playlistTop, width, playlistTop + 200)
        compose.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                screenH = constraints.maxHeight / SCALE
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
                    widgets = emptyList(),
                    dragsGroup = true,
                    modifier = Modifier.fillMaxSize(),
                ) {}
                FloatingSkinWindow(
                    id = WindowStore.EQ,
                    state = state,
                    scale = SCALE,
                    width = width,
                    height = height,
                    offset = state.eqOffset,
                    defaultOffset = IntOffset(0, eqTop - (screenH - height) / 2),
                    onMove = { state.eqOffset = it },
                    titleH = TITLE_BAR_H,
                    widgets = emptyList(),
                    modifier = Modifier.fillMaxSize(),
                ) {}
            }
        }
        compose.waitForIdle()
    }

    private fun topOf(id: String) = state.windowRects[id]?.top ?: error("$id never laid out")

    private fun devicePoint(
        x: Float,
        y: Float,
    ): Offset {
        val root = compose.onRoot().fetchSemanticsNode().size
        val originX = (root.width - width * SCALE) / 2f
        return Offset(originX + x * SCALE, y * SCALE)
    }

    @Test
    fun `the pair starts docked`() {
        assertEquals(mainTop, topOf(WindowStore.MAIN))
        assertEquals(eqTop, topOf(WindowStore.EQ))
    }

    @Test
    fun `dragging the player carries the equalizer and docks the pair on the playlist`() {
        // 15px of travel leaves the equalizer's bottom 5px short of the
        // playlist; the snap has to close that, and the equalizer has to be
        // what closes it
        compose.onRoot().performTouchInput {
            down(devicePoint(60f, 6f))
            moveTo(devicePoint(60f, 12f))
            moveTo(devicePoint(60f, 21f))
            up()
        }
        compose.waitForIdle()

        assertEquals("the equalizer lands on the playlist", playlistTop, topOf(WindowStore.EQ) + height)
        assertEquals(playlistTop - 2 * height, topOf(WindowStore.MAIN))
    }

    @Test
    fun `dragging the stack to the bottom keeps it a stack`() {
        // the group snaps as one rectangle and must clamp as one too: clamped
        // per window, the members that met the bottom would stop while the
        // ones above kept going, and a flush stack would end up overlapping
        compose.onRoot().performTouchInput {
            // far past the bottom: the anchor may travel until ITS title bar
            // meets the safe edge, which is long after the equalizer's did
            down(devicePoint(60f, 6f))
            moveTo(devicePoint(60f, 300f))
            moveTo(devicePoint(60f, 520f))
            up()
        }
        compose.waitForIdle()

        val main = state.windowRects[WindowStore.MAIN]!!
        val eq = state.windowRects[WindowStore.EQ]!!
        assertEquals("the pair stays flush", main.bottom, eq.top)
        assertEquals(
            "the equalizer stays in the player's group",
            setOf(WindowStore.MAIN, WindowStore.EQ),
            DockGroup.of(WindowStore.MAIN, state.windowRects) - WindowStore.PLAYLIST,
        )
        // and what state believes must be what the layout drew
        val storedTop = (screenH - height) / 2 + (state.eqOffset?.y ?: 0)
        assertEquals("the stored offset matches the drawn window", eq.top, storedTop)
    }
}
