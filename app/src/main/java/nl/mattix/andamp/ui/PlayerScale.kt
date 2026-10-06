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
 * As many as the width allows, but no more than lets a stack of [SMALLEST_STACK_H] fit the
 * height, so the player, the equalizer and a short playlist fit a wide screen such as a
 * tablet in landscape. Whole numbers only: the skin is pixel art, and drawn at a fractional
 * scale its pixels come out in two widths. A player that fills the screen is laid out at a
 * whole scale too, and shrunk as one picture; see [playerViewport].
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

/**
 * The surface the player is laid out on: the whole [scale] it draws at, and how much the
 * finished picture is then shrunk to fit the screen.
 *
 * [shrink] is 1 unless the player fills a screen whose size is not a whole multiple of the
 * player's. The player is then laid out at the next whole scale, on a surface larger than
 * the screen, and that surface is drawn smaller: every virtual pixel keeps the same size and
 * only its edges soften.
 */
data class PlayerViewport(
    val scale: Int,
    val shrink: Float,
) {
    /** A length of the screen, in the pixels the player is laid out in. */
    fun inner(px: Int): Int = if (shrink == 1f) px else (px / shrink).roundToInt()
}

/**
 * The surface for a screen of [widthPx] by [heightPx].
 *
 * Filling the screen, the player is as wide as the screen, or as wide as lets a stack of
 * [SMALLEST_STACK_H] fit the height, which leaves room at its sides. Otherwise it is drawn
 * at [playerScale].
 */
fun playerViewport(
    widthPx: Int,
    heightPx: Int,
    fillScreen: Boolean,
): PlayerViewport {
    if (!fillScreen || widthPx <= 0 || heightPx <= 0) return PlayerViewport(playerScale(widthPx, heightPx), 1f)
    // compared as whole numbers, so a screen of exactly the stack's shape is decided one way
    val widthDecides = widthPx.toLong() * SMALLEST_STACK_H <= heightPx.toLong() * MAIN_W
    val screen = if (widthDecides) widthPx else heightPx
    val virtual = if (widthDecides) MAIN_W else SMALLEST_STACK_H
    // the next whole scale up; a screen that is a whole multiple is not shrunk at all
    val scale = ((screen + virtual - 1) / virtual).coerceAtLeast(1)
    return PlayerViewport(scale, screen.toFloat() / (virtual * scale))
}
