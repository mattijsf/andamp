// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.widget

import androidx.compose.ui.unit.IntOffset

/**
 * Winamp's dense 275px layout makes widgets tiny on a phone (a 9x9 titlebar button is ~10dp at
 * scale 3). The art can't grow, so the hit zone does: a touch that misses everything snaps to the
 * nearest enabled widget within [HIT_SLOP] virtual px. Between neighbors the closer one wins, so
 * dense clusters (EQ bands at 18px pitch) stay individually addressable.
 */
const val HIT_SLOP = 6

/** Squared distance from [pos] to this widget's bounds; 0 when inside. */
fun Widget.distanceSquaredTo(pos: IntOffset): Int {
    // IntRect is right/bottom-exclusive: the nearest inside column is right - 1
    val dx = maxOf(bounds.left - pos.x, pos.x - (bounds.right - 1), 0)
    val dy = maxOf(bounds.top - pos.y, pos.y - (bounds.bottom - 1), 0)
    return dx * dx + dy * dy
}

/**
 * The widget of [group] under the pointer's column.
 *
 * Winamp switches EQ bands on the pointer's x alone: sweeping above the
 * sliders (holding a band at max) still moves band to band, so y is ignored
 * and the nearest column wins.
 */
fun hitTestGroup(
    widgets: List<Widget>,
    group: String,
    x: Float,
): Widget? =
    widgets
        .filter { it.dragGroup == group && it.enabled() }
        .minByOrNull { widget ->
            val left = widget.bounds.left
            val right = widget.bounds.right - 1
            when {
                x < left -> left - x
                x > right -> x - right
                else -> 0f
            }
        }

fun hitTest(
    widgets: List<Widget>,
    pos: IntOffset,
    slop: Int = HIT_SLOP,
): Widget? {
    // A region a widget owns outright beats everything, whatever is drawn over it: the resize grip
    // is under the list and the visualizer in the widget list, so the exact pass alone would give
    // them the press.
    widgets.lastOrNull { it.enabled() && it.owned(pos) && it.bounds.contains(pos) }?.let { return it }

    val exact = widgets.lastOrNull { it.enabled() && it.bounds.contains(pos) }
    if (exact != null && !exact.background) return exact
    // a press inside a backdrop still reaches for the controls on top of it, so they keep their
    // slop
    var best: Widget? = null
    var bestDist = Int.MAX_VALUE
    for (widget in widgets) {
        if (!widget.enabled() || widget.background) continue
        val d = widget.distanceSquaredTo(pos)
        // <= so overlapping ties resolve to the later widget, like the exact pass
        if (d <= slop * slop && d <= bestDist) {
            best = widget
            bestDist = d
        }
    }
    return best ?: exact
}
