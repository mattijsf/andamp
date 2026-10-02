// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.welcome

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import nl.mattix.andamp.state.online.OnlineSkin
import nl.mattix.andamp.ui.menu.AmpDialog
import nl.mattix.andamp.ui.online.MuseumTiles
import nl.mattix.andamp.ui.online.SkinBlur
import nl.mattix.andamp.ui.online.SkinPreviewCanvas
import nl.mattix.andamp.ui.online.SkinShot

/**
 * The welcome, as a dialog over the player. The player behind it already wears the skin the first
 * answer offers to keep.
 */
@Composable
fun WelcomeDialog(choice: WelcomeChoice) {
    // closes only through one of its two answers: no press outside and no back gesture
    AmpDialog(onDismiss = null) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            WelcomeScreen(choice)
        }
    }
}

@Composable
fun WelcomeScreen(choice: WelcomeChoice) {
    Column(
        Modifier
            // not an intrinsic width: the preview is a SubcomposeLayout, and asking one for its
            // intrinsic size throws
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("Pick a look", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Skins in the menu changes it whenever you like.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OwnSkinCard(choice)
        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))
        MuseumCard(choice)
    }
}

@Composable
private fun OwnSkinCard(choice: WelcomeChoice) {
    Column(Modifier.fillMaxWidth().testTag("welcome.andamp")) {
        Text("Andamp's own", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Drawn for this app, and on Android 12 and later it takes its colors from your wallpaper.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                // the player alone: the canvas stacks all three windows, and this clips at the main
                // window's aspect
                .aspectRatio(MAIN_ASPECT)
                .clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.TopCenter,
        ) {
            // the player itself, drawn by the same code as the screen behind this one
            choice.skin?.let { SkinPreviewCanvas(it, interactive = false) }
        }
        Spacer(Modifier.height(12.dp))
        // the quieter of the two buttons: it keeps what is already on screen
        OutlinedButton(
            onClick = choice.onKeep,
            modifier = Modifier.fillMaxWidth().testTag("welcome.keep"),
        ) {
            Text("Keep this")
        }
    }
}

/** The museum's front row: the first tiles of the catalog, arranged by [firstRow]. */
@Composable
private fun MuseumCard(choice: WelcomeChoice) {
    // the whole block opens the museum, not just the button under it
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = choice.onBrowse)
            .testTag("welcome.museum"),
    ) {
        Text("A classic skin", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            // a floor until the museum answers with the real count, formatted the same way so the
            // line keeps its shape
            if (choice.museumCount > 0) {
                "${"%,d".format(choice.museumCount)} classic skins"
            } else {
                "${"%,d".format(MUSEUM_FLOOR)}+ classic skins"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        MuseumGrid(choice.tiles)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = choice.onBrowse,
            modifier = Modifier.fillMaxWidth().testTag("welcome.browse"),
        ) {
            Text("Browse the museum")
        }
    }
}

/** The first [ACROSS] tiles of [firstRow], in one row. */
@Composable
private fun MuseumGrid(tiles: List<OnlineSkin>) {
    val row = firstRow(tiles).take(ACROSS)
    Row(
        Modifier.fillMaxWidth().testTag("welcome.strip"),
        horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
    ) {
        if (row.isEmpty()) {
            // nothing fetched yet: the three tiles this card shows are known by name and their
            // blurs ship with the app, so the strip is drawn from the first frame
            MuseumTiles.front.forEach { tile ->
                SkinBlur(
                    blurHash = tile.blurHash,
                    describedAs = "",
                    modifier =
                        Modifier
                            .weight(1f)
                            .aspectRatio(SHOT_ASPECT)
                            .clip(RoundedCornerShape(4.dp))
                            .testTag("welcome.tile.${tile.md5}"),
                )
            }
            return@Row
        }
        row.forEach { skin ->
            SkinShot(
                url = skin.screenshotUrl,
                describedAs = skin.filename,
                contentScale = ContentScale.Fit,
                blurHash = MuseumTiles.blurHashOf(skin.md5),
                modifier =
                    Modifier
                        .weight(1f)
                        .aspectRatio(SHOT_ASPECT)
                        .clip(RoundedCornerShape(4.dp))
                        .testTag("welcome.tile.${skin.md5}"),
            )
        }
        // a short row keeps its tiles the width they would have had
        repeat(ACROSS - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

/**
 * The museum's front, with one tile moved up. Its first four are all Winamp's own skins, the
 * original and three later official ones. Garfield, the museum's fifth, moves into third place so
 * the first row also shows a community skin. Nothing else is rearranged.
 */
internal fun firstRow(tiles: List<OnlineSkin>): List<OnlineSkin> {
    val pinned = tiles.firstOrNull { it.md5 == GARFIELD } ?: return tiles
    val rest = tiles.filterNot { it.md5 == GARFIELD }
    if (rest.size < PIN_AT) return tiles
    return rest.take(PIN_AT) + pinned + rest.drop(PIN_AT)
}

/** `Garfield.zip`, the tile [firstRow] moves. */
private const val GARFIELD = "47597ab8e5ffcd39686d455c10c3b436"

/** Third across, so it lands in the first row beside the original. */
private const val PIN_AT = 2

/** A lower bound on the museum's size, shown until it reports its count. */
private const val MUSEUM_FLOOR = 90_000

/** How many tiles the card shows, in one row. */
private const val ACROSS = 3

/** The gap between two tiles. */
private val TILE_GAP = 6.dp

/** A museum screenshot's shape: 275 by 348, the three windows stacked. */
private const val SHOT_ASPECT = 275f / 348f

/** The main window's shape, 275 by 116, which the preview is clipped to. */
private const val MAIN_ASPECT = 275f / 116f
