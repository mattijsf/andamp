// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.remote

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Where the list on a lent surface is scrolled to, in the surface's pixels.
 *
 * The owner of the surface reports a drag as a distance and a fling as a speed. A drag moves
 * the list with the finger. A fling is kept as [thrown] until the list, which has the frame
 * clock, has carried it out.
 */
internal class ListScroll {
    /** How far the list is scrolled down from its top. */
    var offset by mutableFloatStateOf(0f)
        private set

    /** The fling the list has yet to carry out, or null. */
    var thrown: Throw? by mutableStateOf(null)
        private set

    private var farthest = 0f

    /** The list was laid out: [max] is as far as it scrolls. */
    fun bound(max: Float) {
        farthest = max.coerceAtLeast(0f)
        offset = offset.coerceIn(0f, farthest)
    }

    /** Scrolls to [to], as near as the list goes. */
    fun moveTo(to: Float) {
        offset = to.coerceIn(0f, farthest)
    }

    /** The finger moved [fingerDy] down the surface; the list follows it, and a fling stops. */
    fun dragBy(fingerDy: Float) {
        thrown = null
        moveTo(offset - fingerDy)
    }

    /** The finger left the surface moving [fingerVy] pixels a second down it. */
    fun throwAt(fingerVy: Float) {
        thrown = Throw(-fingerVy)
    }

    /** The fling has run out. */
    fun rest() {
        thrown = null
    }

    /** Whether the list is at one of its ends, where a fling has nowhere left to go. */
    val atAnEnd: Boolean get() = offset <= 0f || offset >= farthest

    /** One fling: the speed the list starts at, in pixels a second down the list. */
    class Throw(
        val velocity: Float,
    )
}
