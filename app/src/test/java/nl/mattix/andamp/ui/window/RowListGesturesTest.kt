// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.geometry.Offset
import nl.mattix.andamp.state.WinampState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gesture grammar the playlist, the library and the skin manager share, asserted once:
 * a drag is a travelled distance, not a changed scroll position. Their own interaction
 * tests cover each window's wiring to it.
 */
class RowListGesturesTest {
    private val rowH = 10

    /** The throw is measured against time, so the tests hand it their own. */
    private var clock = 0L

    private class Recorder {
        var pressed = -1
        var released = -1
        var tapped = false
        var scrollStarts = 0
    }

    private fun grammar(
        state: WinampState,
        rows: Int,
        visible: Int,
        log: Recorder,
    ) = rowListPointer(
        state,
        rowH,
        rowAt = { y -> (y / rowH).toInt() },
        rows = { rows },
        visibleRows = { visible },
        scroll = { state.playlistScroll },
        scrollTo = { state.playlistScroll = it },
        onPress = { row, _ -> log.pressed = row },
        onScrollStarted = { log.scrollStarts++ },
        onRelease = { row, tapped ->
            log.released = row
            log.tapped = tapped
        },
        now = { clock },
    )

    /** Guards against a swipe over a list with nowhere to scroll reading as a still finger. */
    @Test
    fun `a swipe across a list too short to scroll is not a tap`() {
        val state = WinampState()
        val log = Recorder()
        val pointer = grammar(state, rows = 2, visible = 8, log = log)

        pointer.onDown(Offset(5f, 5f))
        pointer.onDrag(Offset(5f, 45f))
        pointer.onUp(Offset(5f, 45f), true)

        assertEquals("the list cannot scroll", 0, state.playlistScroll)
        assertFalse("a swipe does not read as a tap", log.tapped)
    }

    @Test
    fun `a still finger taps the row it went down on`() {
        val state = WinampState()
        val log = Recorder()
        val pointer = grammar(state, rows = 20, visible = 8, log = log)

        pointer.onDown(Offset(5f, 25f))
        pointer.onUp(Offset(5f, 25f), true)

        assertEquals(2, log.pressed)
        assertEquals(2, log.released)
        assertTrue(log.tapped)
    }

    @Test
    fun `a finger that wandered within the slop still taps`() {
        val state = WinampState()
        val log = Recorder()
        val pointer = grammar(state, rows = 20, visible = 8, log = log)

        pointer.onDown(Offset(5f, 25f))
        pointer.onDrag(Offset(8f, 27f)) // 3px, 2px: inside MOVE_SLOP
        pointer.onUp(Offset(8f, 27f), true)

        assertTrue(log.tapped)
        assertEquals(0, log.scrollStarts)
    }

    @Test
    fun `a finger released outside the list acts on nothing`() {
        val state = WinampState()
        val log = Recorder()
        val pointer = grammar(state, rows = 20, visible = 8, log = log)

        pointer.onDown(Offset(5f, 25f))
        pointer.onUp(Offset(5f, 900f), false)

        assertFalse(log.tapped)
    }

    @Test
    fun `the row a press lands on counts from the scroll, not from the window`() {
        val state = WinampState()
        state.playlistScroll = 6
        val log = Recorder()
        val pointer = grammar(state, rows = 20, visible = 8, log = log)

        pointer.onDown(Offset(5f, 25f))

        assertEquals(8, log.pressed)
    }

    @Test
    fun `dragging scrolls by whole rows and stops at the ends`() {
        val state = WinampState()
        val log = Recorder()
        val pointer = grammar(state, rows = 20, visible = 8, log = log)

        pointer.onDown(Offset(5f, 80f))
        pointer.onDrag(Offset(5f, 50f)) // three rows up the list

        assertEquals(3, state.playlistScroll)
        assertEquals(1, log.scrollStarts)

        pointer.onDrag(Offset(5f, 800f)) // far past the top
        assertEquals(0, state.playlistScroll)

        pointer.onDrag(Offset(5f, -800f)) // far past the end
        assertEquals("12 rows of overflow is as far as it goes", 12, state.playlistScroll)
    }

    @Test
    fun `a press on a moving list stops it`() {
        val state = WinampState()
        val log = Recorder()
        val pointer = grammar(state, rows = 20, visible = 8, log = log)
        state.flung =
            nl.mattix.andamp.state
                .Flung(500f, 0) {}

        pointer.onDown(Offset(5f, 25f))

        assertNull(state.flung)
    }

    @Test
    fun `a finger still moving when it leaves throws the list`() {
        val state = WinampState()
        val log = Recorder()
        val pointer = grammar(state, rows = 20, visible = 8, log = log)

        pointer.onDown(Offset(5f, 80f))
        clock += TAIL
        pointer.onDrag(Offset(5f, 60f))
        clock += TAIL
        pointer.onDrag(Offset(5f, 20f))
        pointer.onUp(Offset(5f, 20f), true)

        assertNotNull("a fast drag leaves the list moving", state.flung)
    }

    @Test
    fun `a list that did not move is not thrown`() {
        val state = WinampState()
        val log = Recorder()
        val pointer = grammar(state, rows = 2, visible = 8, log = log)

        pointer.onDown(Offset(5f, 50f))
        clock += TAIL
        pointer.onDrag(Offset(5f, 10f))
        pointer.onUp(Offset(5f, 10f), true)

        assertNull(state.flung)
    }

    private companion object {
        /** Long enough for [Throw] to count the move as a measured one. */
        const val TAIL = RowFling.TAIL_MS + 1
    }
}
