// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import nl.mattix.andamp.R

/**
 * The screen's bar: the page's name and the arrow back, to the list of pages from a page and to
 * the player from the list, as Android's own screens go back. Where the window is tall the name
 * starts large under the bar and folds into it as the page scrolls, as the phone's Settings app
 * does; a short window keeps the plain bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrefsTopBar(
    title: String,
    deeper: Boolean,
    tall: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val name: @Composable () -> Unit = { Text(title) }
    val backTo = stringResource(if (deeper) R.string.prefs_top_back else R.string.prefs_back_to_player)
    val back: @Composable () -> Unit = {
        IconButton(
            onClick = if (deeper) onBack else onClose,
            modifier =
                Modifier
                    .semantics { contentDescription = backTo }
                    .testTag(if (deeper) "prefs.back" else "prefs.close"),
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
    }
    if (tall) {
        LargeTopAppBar(title = name, navigationIcon = back, scrollBehavior = scrollBehavior)
    } else {
        TopAppBar(title = name, navigationIcon = back, scrollBehavior = scrollBehavior)
    }
}
