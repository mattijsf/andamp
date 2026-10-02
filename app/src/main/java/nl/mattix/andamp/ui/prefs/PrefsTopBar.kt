// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** The screen's bar: the page's name, a way back when there is one, and Done. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrefsTopBar(
    title: String,
    deeper: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
) = TopAppBar(
    title = { Text(title) },
    navigationIcon = {
        if (deeper) {
            IconButton(
                onClick = onBack,
                modifier =
                    Modifier
                        .semantics { contentDescription = "Back to preferences" }
                        .testTag("prefs.back"),
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
        }
    },
    actions = {
        TextButton(
            onClick = onClose,
            modifier = Modifier.testTag("prefs.close"),
        ) { Text("Done") }
    },
)
