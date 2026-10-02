// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * The third-party notices, as a page. Each entry shows the name and license, what it does here, and
 * the address of its source. The projectM entry carries its LGPL-2.1 source offer in
 * [Notice.obligation].
 */
@Composable
internal fun ColumnScope.LicencesPage(notices: List<Notice> = Notices.ALL) {
    Spacer(Modifier.height(8.dp))
    Text(
        "Andamp is built on other people's work. Each entry links to its source, " +
            "where the full license text lives.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    notices.forEachIndexed { index, notice ->
        if (index > 0) HorizontalDivider()
        NoticeRow(notice)
    }
}

/** One notice. The whole row opens its source. */
@Composable
private fun NoticeRow(notice: Notice) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                val view = Intent(Intent.ACTION_VIEW, Uri.parse(notice.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // on a device with no browser the row does nothing
                runCatching { context.startActivity(view) }.recoverCatching { error ->
                    if (error !is ActivityNotFoundException) throw error
                }
            }.testTag("licence.${notice.name}")
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(notice.name, style = MaterialTheme.typography.titleMedium)
            Text(
                notice.licence,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                notice.what,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            notice.obligation?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                notice.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
    }
}
