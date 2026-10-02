// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

/**
 * How the windows are handed to the layout: always in the same order, each with the depth it
 * draws at.
 *
 * The composition order stays fixed, because a child that changes position is torn down and
 * rebuilt, which would cancel the press that raised the window. Stacking is a z-index per
 * window.
 */
object WindowStacking {
    /** Every window in composition order, each with its depth from [order]. */
    fun stack(
        windows: List<String>,
        order: List<String>,
    ): List<Pair<String, Float>> = windows.map { id -> id to order.indexOf(id).toFloat() }
}
