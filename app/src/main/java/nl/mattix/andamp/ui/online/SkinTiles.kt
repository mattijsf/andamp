// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.online

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.state.online.OnlineSkin
import nl.mattix.andamp.state.online.SkinCatalog

/**
 * The museum as a grid of screenshots, in two sizes: small tiles to sweep through, or one skin to a
 * screen with its name and install button. Both are the same list at the same position, so
 * switching keeps the place.
 *
 * Every position in the museum is a tile from the start, whether its page has arrived or not, so
 * the handle beside the grid can address all of them.
 */
@Composable
@Suppress("LongParameterList") // one tile, and everything a tile can do
internal fun SkinGrid(
    catalog: SkinCatalog,
    gridState: LazyGridState,
    wide: Boolean,
    isInstalled: (String) -> Boolean,
    isBusy: (String) -> Boolean,
    /** The skin itself, once it has been fetched for a live preview; null until then. */
    live: (String) -> Skin? = { null },
    onInstall: (OnlineSkin) -> Unit,
    onUninstall: (OnlineSkin) -> Unit,
    onOpen: (OnlineSkin) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // one to a screen means one that fits on the screen: the shot keeps its shape until that
        // shape is taller than there is room for. A preview draws on whole pixels, so the picture
        // it replaces, the frame they share and the height of the row are sized in the same whole
        // pixels
        val across = with(LocalDensity.current) { wholePlayerWidth(constraints.maxWidth).toDp() }
        val shot = minOf(across / SHOT_RATIO, maxHeight - ACTIONS_H.dp)
        // every wide item has the same height, blanks included, so the snap has row boundaries to
        // land on
        val itemHeight = shot + ACTIONS_H.dp
        val snap = rememberSnapFlingBehavior(gridState, SnapPosition.Start)
        LazyVerticalGrid(
            columns = if (wide) GridCells.Fixed(1) else GridCells.Adaptive(tileMinWidth(maxWidth)),
            state = gridState,
            flingBehavior = if (wide) snap else ScrollableDefaults.flingBehavior(),
            contentPadding = PaddingValues(if (wide) 0.dp else TILE_GAP.dp),
            modifier = Modifier.fillMaxSize().testTag("$TAG.grid"),
        ) {
            items(count = catalog.total.coerceAtLeast(0), key = { it }) { at ->
                val skin = catalog.skins[at]
                val blank =
                    if (wide) {
                        Modifier
                            .fillMaxWidth()
                            .height(itemHeight)
                            .padding(TILE_GAP.dp)
                            .clip(RoundedCornerShape(TILE_CORNER.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    } else {
                        tileShape()
                    }
                when {
                    skin == null -> {
                        EmptyTile(
                            failure = catalog.failureAt(at),
                            coming = catalog.isLoading(at),
                            shape = blank,
                        ) { catalog.retry(at) }
                    }

                    skin.nsfw -> {
                        CoveredTile(blank)
                    }

                    wide -> {
                        WideSkin(
                            skin = skin,
                            live = live(skin.md5),
                            width = across,
                            height = itemHeight,
                            installed = isInstalled(skin.md5),
                            busy = isBusy(skin.md5),
                            onInstall = { onInstall(skin) },
                            onUninstall = { onUninstall(skin) },
                            onOpen = { onOpen(skin) },
                        )
                    }

                    else -> {
                        SkinTile(skin, tileShape(), installed = isInstalled(skin.md5)) { onOpen(skin) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkinTile(
    skin: OnlineSkin,
    shape: Modifier,
    installed: Boolean,
    onOpen: () -> Unit,
) {
    Box(
        shape
            .clickable(onClick = onOpen)
            .skinHero(skin.md5)
            .reportsHome(skin.md5)
            .testTag("$TAG.tile.${skin.md5}"),
    ) {
        SkinShot(
            url = skin.screenshotUrl,
            describedAs = skin.filename,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (installed) InstalledMark(Modifier.align(Alignment.BottomEnd))
    }
}

/** One skin filling the width, with its name and the install button under it. */
@Composable
@Suppress("LongParameterList") // one skin, and everything that can be done with it
private fun WideSkin(
    skin: OnlineSkin,
    live: Skin?,
    width: Dp,
    height: Dp,
    installed: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onOpen: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(height)
            .testTag("$TAG.tile.${skin.md5}"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .width(width)
                .weight(1f)
                .clip(RoundedCornerShape(TILE_CORNER.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(onClick = onOpen)
                .skinHero(skin.md5)
                .reportsHome(skin.md5),
        ) {
            // the screenshot holds the space until the skin arrives, then fades out as the live
            // preview fades in
            androidx.compose.animation.AnimatedVisibility(
                visible = live == null,
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                SkinShot(
                    url = skin.screenshotUrl,
                    describedAs = skin.filename,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = live != null,
                enter = fadeIn(),
                modifier = Modifier.fillMaxSize(),
            ) {
                live?.let { SkinPreviewCanvas(it, Modifier.fillMaxSize(), interactive = false) }
            }
            if (installed) InstalledMark(Modifier.align(Alignment.BottomEnd))
        }
        Row(
            Modifier
                .width(width)
                .height(ACTIONS_H.dp)
                .padding(horizontal = GAP.dp, vertical = TILE_GAP.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GAP.dp),
        ) {
            Text(
                skin.filename
                    .asSkinName(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (installed) {
                FilledTonalButton(
                    onClick = onUninstall,
                    colors =
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    modifier = Modifier.testTag("$TAG.uninstall.${skin.md5}"),
                ) { Text("Uninstall") }
            } else {
                Button(
                    onClick = onInstall,
                    enabled = !busy,
                    modifier = Modifier.testTag("$TAG.install.${skin.md5}"),
                ) { Text(if (busy) "Installing…" else "Install") }
            }
        }
    }
}

/** The mark on a tile whose skin is already installed. */
@Composable
private fun InstalledMark(modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(TILE_GAP.dp)
            .size(BADGE.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Check,
            contentDescription = "Installed",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(BADGE_ICON.dp),
        )
    }
}

/**
 * A skin the museum flagged. It keeps its place, because a position in the grid is a position in
 * the museum, which the handle beside it addresses; its art does not load.
 */
@Composable
private fun CoveredTile(shape: Modifier) {
    Box(shape.testTag("$TAG.covered"), contentAlignment = Alignment.Center) {
        Text(
            "not shown",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A tile whose page has not arrived: a spinner for a page on its way, a retry button for one that
 * failed, and blank for one nobody has asked for yet.
 */
@Composable
private fun EmptyTile(
    failure: String?,
    coming: Boolean,
    shape: Modifier,
    onRetry: () -> Unit,
) {
    when {
        failure != null -> {
            Box(shape.clickable(onClick = onRetry).testTag("$TAG.retile"), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Try this page again",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        coming -> {
            Box(shape.testTag("$TAG.coming"), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    strokeWidth = SPINNER_STROKE.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = FAINT),
                    modifier = Modifier.size(SPINNER.dp),
                )
            }
        }

        else -> {
            Box(shape)
        }
    }
}

/** The shape of every small tile: the screenshot's, whether it has one or not. */
@Composable
private fun tileShape(): Modifier =
    Modifier
        .padding(TILE_GAP.dp)
        .aspectRatio(SHOT_RATIO)
        .clip(RoundedCornerShape(TILE_CORNER.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest)

internal const val SHOT_RATIO = 275f / 348f
internal const val GAP = 12

/**
 * The name shown for a skin's file: an archive called Hexagon_Amp.wsz is a skin called Hexagon Amp.
 */
internal fun String.asSkinName(): String = removeSuffix(".wsz").removeSuffix(".zip").replace('_', ' ')

/** A count grouped the way the phone's locale groups numbers: 93,603, not 93603. */
internal fun Int.asCount(): String =
    java.text.NumberFormat
        .getIntegerInstance()
        .format(this)

private const val TILE_GAP = 3
private const val TILE_CORNER = 6
private const val BADGE = 20
private const val BADGE_ICON = 14
private const val SPINNER = 20
private const val SPINNER_STROKE = 2
private const val FAINT = 0.5f

/** Room under a full-width shot for the name and the button. */
private const val ACTIONS_H = 64

/**
 * Tells the viewer where this tile is, while its skin is the one open. Only that tile reports, so a
 * scroll does not write shared state for every tile.
 */
@Composable
private fun Modifier.reportsHome(md5: String): Modifier {
    val home = LocalSkinHome.current ?: return this
    if (LocalOpenedSkin.current != md5) return this
    return this.onGloballyPositioned { home.value = it.boundsInWindow() }
}
