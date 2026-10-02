// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import nl.mattix.andamp.ui.window.EQ_H
import nl.mattix.andamp.ui.window.MAIN_H
import nl.mattix.andamp.ui.window.MAIN_W
import nl.mattix.andamp.ui.window.PlaylistLayout

/**
 * How many screen pixels each of the player's virtual pixels takes.
 *
 * As many as the width allows, but no more than lets a stack of [SMALLEST_STACK_H] fit the
 * height, so the player, the equalizer and a short playlist fit a wide screen such as a
 * tablet in landscape. Whole numbers only: the skin is pixel art, and a fractional scale
 * smears it.
 */
fun playerScale(
    widthPx: Int,
    heightPx: Int,
): Int = minOf(widthPx / MAIN_W, heightPx / SMALLEST_STACK_H).coerceAtLeast(1)

/**
 * The shortest stack the scale leaves room for, in virtual pixels: the player, the equalizer
 * and a playlist of [ROOMY_SEGMENTS] segments.
 */
internal val SMALLEST_STACK_H = MAIN_H + EQ_H + PlaylistLayout.ofSegments(ROOMY_SEGMENTS).height

private const val ROOMY_SEGMENTS = 4
