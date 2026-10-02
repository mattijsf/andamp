// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.geometry.Offset
import nl.mattix.andamp.state.Flung
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.ui.widget.Widget
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A row list as one widget: the gesture of [rowListPointer], plus a long press on a row,
 * which stands in for Winamp's right click.
 */
@Suppress("LongParameterList") // the gesture's seams, plus the widget's own identity
fun rowListWidget(
    id: String,
    bounds: androidx.compose.ui.unit.IntRect,
    state: WinampState,
    rowH: Int,
    rowAt: (Float) -> Int,
    rows: () -> Int,
    visibleRows: () -> Int,
    scroll: () -> Int,
    scrollTo: (Int) -> Unit,
    onPress: (Int, Offset) -> Unit = { _, _ -> },
    onScrollStarted: () -> Unit = {},
    onRelease: (Int, Boolean) -> Unit = { _, _ -> },
    /** Called with the row a long press went down on; null where a list has no menu. */
    onLongPress: ((Int) -> Unit)? = null,
    background: Boolean = false,
    now: () -> Long = System::currentTimeMillis,
): Widget {
    var pressedRow = -1
    val pointer =
        rowListPointer(
            state,
            rowH,
            rowAt,
            rows,
            visibleRows,
            scroll,
            scrollTo,
            onPress = { row, at ->
                pressedRow = row
                onPress(row, at)
            },
            onScrollStarted = onScrollStarted,
            onRelease = onRelease,
            now = now,
        )
    return Widget(
        id,
        bounds,
        pointer = pointer,
        taps = Widget.Taps(onLongPress = onLongPress?.let { fire -> { fire(pressedRow) } }),
        background = background,
    )
}

/**
 * The gesture every row list in this player shares: a press marks a row, a drag scrolls the
 * list, or throws it if the finger was still moving when it left, and only a finger that
 * stayed still acts on the row it went down on.
 *
 * A drag is recognized by the distance the finger traveled ([MOVE_SLOP]), not by a changed
 * scroll position: a list shorter than its window cannot scroll, and a swipe across it must
 * not count as a tap.
 *
 * The callers supply what differs per window: which row a y lands on, what a press does and
 * what a tap means.
 */
@Suppress("LongParameterList") // one gesture with a callback for each thing that differs per list
fun rowListPointer(
    state: WinampState,
    rowH: Int,
    /** Which visible row a window-relative y falls on, before scroll is added. */
    rowAt: (Float) -> Int,
    rows: () -> Int,
    visibleRows: () -> Int,
    scroll: () -> Int,
    scrollTo: (Int) -> Unit,
    /** The row that went down, and where. Fires before any drag is known. */
    onPress: (Int, Offset) -> Unit = { _, _ -> },
    /** The press became a scroll: whatever it lit up is no longer pressed. */
    onScrollStarted: () -> Unit = {},
    /** Released: [tapped] is true only for a still finger that stayed inside. */
    onRelease: (Int, Boolean) -> Unit = { _, _ -> },
    /** The clock the throw is measured against; a test can supply its own. */
    now: () -> Long = System::currentTimeMillis,
): Widget.Pointer {
    var downRow = -1
    var downPos = Offset.Zero
    var moved = false
    var startScroll = 0
    val throwing = Throw(rowH, now)

    fun maxScroll() = (rows() - visibleRows()).coerceAtLeast(0)

    return Widget.Pointer(
        onDown = { pos ->
            // a finger on a list that is still moving stops it
            state.flung = null
            throwing.begin(pos.y)
            downPos = pos
            moved = false
            startScroll = scroll()
            downRow = scroll() + rowAt(pos.y)
            onPress(downRow, pos)
        },
        onDrag = { pos ->
            if (!moved && maxOf(abs(pos.x - downPos.x), abs(pos.y - downPos.y)) > MOVE_SLOP) {
                moved = true
                onScrollStarted()
            }
            if (!moved) return@Pointer
            val deltaRows = ((downPos.y - pos.y) / rowH).roundToInt()
            scrollTo((startScroll + deltaRows).coerceIn(0, maxScroll()))
            throwing.moved(pos.y)
        },
        onUp = { _, inside ->
            val thrown = throwing.speed()
            if (thrown != 0f && scroll() != startScroll) {
                state.flung = Flung(thrown, scroll()) { row -> scrollTo(row.coerceIn(0, maxScroll())) }
            }
            throwing.forget()
            onRelease(downRow, inside && !moved)
        },
    )
}

/** Past this many virtual pixels in any direction, the finger is scrolling. */
const val MOVE_SLOP = 4
