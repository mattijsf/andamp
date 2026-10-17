// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.mattix.andamp.R
import nl.mattix.andamp.ui.menu.idleLabelRes

/** Narrows the list to the presets whose title or folders hold every word typed. */
@Composable
internal fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.prefs_milkdrop_search_hint)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }, modifier = Modifier.testTag("milkdrop.search.clear")) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.prefs_milkdrop_search_clear))
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        shape = MaterialTheme.shapes.extraLarge,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("milkdrop.search"),
    )
}

/** What can play: the favorites, the engine's idle preset, and each pack. */
@Composable
internal fun Sources(prefs: VisualizerPrefs) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        FilterChip(
            selected = prefs.favoritesOn,
            onClick = prefs.onSelectFavorites,
            enabled = prefs.favoriteCount > 0,
            label = { Text(stringResource(R.string.prefs_milkdrop_favorites_chip, prefs.favoriteCount)) },
            leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null) },
            modifier = Modifier.testTag("milkdrop.source.favorites"),
        )
        FilterChip(
            selected = !prefs.favoritesOn && prefs.activePack == null,
            onClick = { prefs.onSelectPack(null) },
            label = { Text(stringResource(prefs.plugin.idleLabelRes)) },
            modifier = Modifier.testTag("milkdrop.source.idle"),
        )
        prefs.packs.forEach { pack ->
            FilterChip(
                selected = !prefs.favoritesOn && prefs.activePack == pack.name,
                onClick = { prefs.onSelectPack(pack.name) },
                label = { Text(pack.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.testTag("milkdrop.source.${pack.name}"),
            )
        }
    }
}

/** The line over the list: how many presets, from where, or how many match the search. */
@Composable
internal fun ListHeader(
    prefs: VisualizerPrefs,
    selector: PresetSelector,
    /** How many presets match the search; null while nothing is searched for. */
    found: Int?,
    query: String,
) {
    val pack = prefs.activePack
    Text(
        when {
            found == 0 -> {
                stringResource(R.string.prefs_milkdrop_search_none, query.trim())
            }

            found != null -> {
                pluralStringResource(R.plurals.prefs_milkdrop_search_count, found, found, selector.paths.size)
            }

            prefs.favoritesOn -> {
                pluralStringResource(R.plurals.prefs_milkdrop_favorites_count, selector.paths.size, selector.paths.size)
            }

            pack == null -> {
                stringResource(
                    if (prefs.favoriteCount >
                        0
                    ) {
                        R.string.prefs_milkdrop_idle_playing
                    } else {
                        R.string.prefs_milkdrop_idle_no_favorites
                    },
                    stringResource(prefs.plugin.idleLabelRes),
                )
            }

            selector.paths.isEmpty() -> {
                stringResource(R.string.prefs_milkdrop_pack_loading)
            }

            else -> {
                pluralStringResource(R.plurals.prefs_milkdrop_pack_count, selector.paths.size, selector.paths.size, pack)
            }
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp).testTag("milkdrop.list.header"),
    )
}
