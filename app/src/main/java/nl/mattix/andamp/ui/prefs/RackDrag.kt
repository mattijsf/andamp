// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs

/**
 * A card being dragged through the rack.
 *
 * The rack is a plain column inside a scrolling page, so each card reports its own height as it is
 * laid out, and this works out which position the finger is over from those.
 *
 * Reordering happens during the drag, so the sound follows the finger: the rack is a signal chain.
 */
internal class RackDrag {
    /** The plug-in under the finger, or null when nothing is being dragged. */
    var carried by mutableStateOf<String?>(null)
        private set

    /** How far it has been dragged from where its card sits now. */
    var offset by mutableStateOf(0f)
        private set

    private val heights = mutableMapOf<String, Int>()

    fun measured(
        pluginId: String,
        height: Int,
    ) {
        heights[pluginId] = height
    }

    fun start(pluginId: String) {
        carried = pluginId
        offset = 0f
    }

    fun stop() {
        carried = null
        offset = 0f
    }

    /**
     * Moves the carried card by [delta] and returns the position it should now hold, or null while
     * it is still over its own.
     *
     * A card swaps once it has moved half its neighbor's height, and the offset is reduced by that
     * neighbor's height so the card stays under the finger.
     */
    fun drag(
        delta: Float,
        order: List<String>,
    ): Int? {
        val at = order.indexOf(carried ?: return null)
        if (at < 0) return null
        offset += delta
        val next = if (offset > 0) at + 1 else at - 1
        val neighbour = order.getOrNull(next)?.let { heights[it] }?.toFloat()
        if (neighbour == null) {
            // nothing left to pass: the card stops at the edge of the rack and does not follow the
            // finger off the list
            val own = (heights[carried] ?: 0).toFloat() / 2f
            offset = offset.coerceIn(-own, own)
            return null
        }
        return if (abs(offset) < neighbour / 2f) {
            null
        } else {
            offset -= if (offset > 0) neighbour else -neighbour
            next
        }
    }
}
