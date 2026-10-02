// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.online

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import coil3.request.Disposable
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import nl.mattix.andamp.state.online.SkinCatalog

/**
 * Fetches the screenshots just past the edge of the screen.
 *
 * It fetches only once the grid has stood still for [STILL_MS], because a scroll passes over tiles
 * nobody is reading. And it cancels everything it asked for when the grid moves again: a tile
 * cancels its own picture when it scrolls away, but a prefetch has no tile to do that, and stale
 * prefetches would queue ahead of the pictures on screen.
 */
@Composable
internal fun PrefetchThumbnails(
    catalog: SkinCatalog,
    gridState: LazyGridState,
) {
    val context = LocalContext.current
    val running = remember { mutableListOf<Disposable>() }
    DisposableEffect(Unit) {
        onDispose { running.forEach { it.dispose() }.also { running.clear() } }
    }
    LaunchedEffect(catalog, gridState) {
        snapshotFlow {
            val visible = gridState.layoutInfo.visibleItemsInfo
            visible.firstOrNull()?.index to visible.lastOrNull()?.index
        }.collectLatest { (first, last) ->
            // the grid moved: cancel what was being fetched for where it was
            running.forEach { it.dispose() }
            running.clear()
            if (first == null || last == null) return@collectLatest
            delay(STILL_MS)
            val loader = SkinThumbnails.ahead(context)
            for (at in nextUp(first..last, catalog.total)) {
                val url = catalog.skins[at]?.screenshotUrl ?: continue
                running += loader.enqueue(ImageRequest.Builder(context).data(url).build())
            }
        }
    }
}

/**
 * The positions to fetch around [visible]: [AHEAD] below and [BEHIND] above, more below because
 * that is the way a grid is read.
 */
internal fun nextUp(
    visible: IntRange,
    total: Int,
): IntRange {
    if (total <= 0) return IntRange.EMPTY
    val from = (visible.first - BEHIND).coerceAtLeast(0)
    val to = (visible.last + AHEAD).coerceAtMost(total - 1)
    return from..to
}

private const val AHEAD = 12
private const val BEHIND = 4
private const val STILL_MS = 250L
