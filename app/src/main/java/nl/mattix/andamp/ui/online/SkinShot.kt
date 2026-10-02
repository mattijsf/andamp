// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.online

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

/**
 * A skin's screenshot.
 *
 * Coil asks once and there is no retry here: a retry replaces the request before it, replacing one
 * cancels it, and a cancelled request looks like a failure, so a scroll would feed a retry counter
 * with its own cancellations. Resolver failures are handled in
 * [nl.mattix.andamp.state.online.StickyDns].
 */
@Composable
internal fun SkinShot(
    url: String,
    describedAs: String,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    /**
     * What stands in the tile until the picture arrives; see [MuseumTiles]. Decoded small and
     * stretched over the tile.
     */
    blurHash: String? = null,
) {
    val blur =
        remember(blurHash) {
            blurHash?.let { BlurHash.decode(it, BLUR_W, BLUR_H) }?.let(::BitmapPainter)
        }
    AsyncImage(
        model = url,
        contentDescription = describedAs,
        contentScale = contentScale,
        placeholder = blur,
        // a failed request keeps the blur
        error = blur,
        modifier = modifier,
    )
}

/** The stand-in's size: small, in roughly the 275x348 shape of a screenshot. */
private const val BLUR_W = 24
private const val BLUR_H = 30

/**
 * A tile with no request behind it: the blur alone. The welcome strip shows these before the museum
 * has answered.
 */
@Composable
internal fun SkinBlur(
    blurHash: String,
    describedAs: String,
    modifier: Modifier = Modifier,
) {
    val blur = remember(blurHash) { BlurHash.decode(blurHash, BLUR_W, BLUR_H) } ?: return
    Image(
        bitmap = blur,
        contentDescription = describedAs,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}
