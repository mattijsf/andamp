// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * The screen's bar: the page's name, a way back when there is one, and Done. Where the window is
 * tall the name starts large under the bar and folds into it as the page scrolls, as the
 * phone's Settings app does; a short window keeps the plain bar.
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
    val back: @Composable () -> Unit = {
        if (deeper) {
            IconButton(
                onClick = onBack,
                modifier =
                    Modifier
                        .semantics { contentDescription = "Back to preferences" }
                        .testTag("prefs.back"),
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
        }
    }
    val done: @Composable RowScope.() -> Unit = {
        TextButton(
            onClick = onClose,
            modifier = Modifier.testTag("prefs.close"),
        ) { Text("Done") }
    }
    if (tall) {
        LargeTopAppBar(title = name, navigationIcon = back, actions = done, scrollBehavior = scrollBehavior)
    } else {
        TopAppBar(title = name, navigationIcon = back, actions = done, scrollBehavior = scrollBehavior)
    }
}
