// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import nl.mattix.andamp.ui.window.EQ_H
import nl.mattix.andamp.ui.window.MAIN_H
import nl.mattix.andamp.ui.window.MAIN_W
import nl.mattix.andamp.ui.window.PlaylistLayout
import kotlin.math.roundToInt

/**
 * How many screen pixels each of the player's virtual pixels takes.
 *
 * Upright, as many as the width allows, but no more than lets a stack of [SMALLEST_STACK_H]
 * fit the height, so the player, the equalizer and a short playlist fit a wide screen such
 * as a tablet in landscape. [sideways], as many as let the player and the equalizer stand
 * on the left with the playlist beside them: [SIDEWAYS_STACK_W] across and
 * [SIDEWAYS_STACK_H] down. Whole numbers only: the skin is pixel art, and drawn at a
 * fractional scale its pixels come out in two widths.
 */
fun playerScale(
    widthPx: Int,
    heightPx: Int,
    sideways: Boolean = false,
): Int =
    if (sideways) {
        minOf(widthPx / SIDEWAYS_STACK_W, heightPx / SIDEWAYS_STACK_H).coerceAtLeast(1)
    } else {
        minOf(widthPx / MAIN_W, heightPx / SMALLEST_STACK_H).coerceAtLeast(1)
    }

/**
 * The shortest stack the scale leaves room for upright, in virtual pixels: the player, the
 * equalizer and a playlist of [ROOMY_SEGMENTS] segments.
 */
internal val SMALLEST_STACK_H = MAIN_H + EQ_H + PlaylistLayout.ofSegments(ROOMY_SEGMENTS).height

/** What has to fit sideways: the player over the equalizer, with the playlist beside them. */
internal const val SIDEWAYS_STACK_W = 2 * MAIN_W
internal const val SIDEWAYS_STACK_H = MAIN_H + EQ_H

private const val ROOMY_SEGMENTS = 4

/**
 * The surface the player is laid out on: the whole [scale] it draws at, and how much the
 * finished picture is then shrunk to fit the screen.
 *
 * [shrink] is 1 unless a surface fills a screen whose size is not a whole multiple of what it
 * draws. It is then laid out at the next whole scale, on a surface larger than the screen,
 * and that surface is drawn smaller: every virtual pixel keeps the same size and only its
 * edges soften.
 */
data class PlayerViewport(
    val scale: Int,
    val shrink: Float,
) {
    /** A length of the screen, in the pixels the player is laid out in. */
    fun inner(px: Int): Int = if (shrink == 1f) px else (px / shrink).roundToInt()
}

/** The surface for a screen of [widthPx] by [heightPx]: drawn at [playerScale], not shrunk. */
fun playerViewport(
    widthPx: Int,
    heightPx: Int,
    /** Whether the phone is held sideways; see [playerScale]. */
    sideways: Boolean = false,
): PlayerViewport = PlayerViewport(playerScale(widthPx, heightPx, sideways), 1f)
