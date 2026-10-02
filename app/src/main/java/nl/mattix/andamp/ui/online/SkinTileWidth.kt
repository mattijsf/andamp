// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.online

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private const val TILE_MIN = 108

/** The most tiles a row holds, however wide the screen. */
private const val MOST_ACROSS = 6

/**
 * How narrow a tile may be on a screen [across] wide: [TILE_MIN] dp, which is three to a row on a
 * phone, and at least a sixth of the screen, so a wide screen gets larger tiles instead of more.
 */
internal fun tileMinWidth(across: Dp): Dp = maxOf(TILE_MIN.dp, across / MOST_ACROSS)
