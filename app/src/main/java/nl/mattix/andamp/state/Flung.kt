// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

/**
 * A thrown list: its speed, the row it started from, and how to move it.
 *
 * [carry] is given the row the throw has reached and clamps it itself, because each list
 * has its own end. There is no start time here: the code that advances the throw keeps its
 * own, read from the frame clock it uses.
 */
class Flung(
    val rowsPerSecond: Float,
    val from: Int,
    val carry: (row: Int) -> Unit,
)
