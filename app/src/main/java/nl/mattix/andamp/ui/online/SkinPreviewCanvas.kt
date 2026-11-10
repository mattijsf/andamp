// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.online

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import nl.mattix.andamp.ui.window.MAIN_W
import nl.mattix.andamp.wal.WalPreview
import java.io.File

/**
 * A skin on a running demo player, drawn by the engine the player draws skins with ([WalPreview]),
 * with no audio and no backend behind it.
 *
 * The stack is the museum's composition, main over equalizer over playlist, at one whole-number
 * scale: a fractional scale would filter the art.
 */
@Composable
internal fun SkinPreviewCanvas(
    /** The skin's file. */
    skin: File,
    modifier: Modifier = Modifier,
    /** Whether its controls answer. In a list they do not, so a press opens the row. */
    interactive: Boolean = true,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.TopCenter) {
        WalPreview(skin, wholePlayerWidth(constraints.maxWidth) / MAIN_W, interactive = interactive)
    }
}

/**
 * The widest a player can be drawn in [availablePx] and still land on whole pixels. Anything that
 * shares a frame with the preview is sized by the same rule.
 */
internal fun wholePlayerWidth(availablePx: Int): Int = (availablePx / MAIN_W).coerceAtLeast(1) * MAIN_W

/**
 * [wholePlayerWidth] for a skin shown at its screenshot's shape with only [roomPx] to stand in:
 * the widest whole multiple that fits [availablePx] across and is no taller than the room. A
 * screen that is wider than it is tall would otherwise get a skin taller than itself.
 */
internal fun wholePlayerWidth(
    availablePx: Int,
    roomPx: Int,
): Int {
    // the room as a width: how wide a skin is that is exactly that tall
    val asWide = (roomPx.toLong() * MAIN_W / SHOT_H).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    return minOf(wholePlayerWidth(availablePx), wholePlayerWidth(asWide))
}

/** A screenshot's height: the three windows, 116 each, one under the other. */
private const val SHOT_H = 348

/** The playlist's height in the museum's screenshots: three windows, 116 each. */
internal const val PLAYLIST_H = 116
