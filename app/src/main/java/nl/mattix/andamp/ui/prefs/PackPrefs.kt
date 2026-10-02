// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import nl.mattix.andamp.backend.pack.PackReach

/**
 * A source's row in a line: which of its states this phone is in.
 *
 * The account and the sign-in belong to the source, on the screen it draws in its own app; the
 * player opens that screen and does not draw it.
 *
 * [signedIn] is the app's own record, not the source's. It distinguishes a source that was signed
 * in and has since been uninstalled: the rows it played are still in the playlist and are skipped,
 * and the line says so.
 */
fun packSummary(
    reach: PackReach,
    signedIn: Boolean,
): String =
    when (reach) {
        PackReach.Absent -> {
            if (signedIn) "Uninstalled, and its tracks are skipped" else "Not installed - see More sources below"
        }

        PackReach.Outdated -> {
            "Installed, but too old for this version of Andamp"
        }

        PackReach.Ahead -> {
            "Installed, but made for a newer version of Andamp"
        }

        is PackReach.SignedOut -> {
            "Not signed in"
        }

        is PackReach.Ready -> {
            reach.account.name
                .takeIf { it.isNotBlank() }
                ?.let { "Signed in as $it" }
                ?: "Signed in on this phone"
        }
    }

/**
 * What [PackPage] says about a source built for another contract version, or null for a source
 * that can be asked: which of the two apps is the older one, and so which to update.
 */
fun packNotice(
    named: String,
    reach: PackReach,
): String? =
    when (reach) {
        PackReach.Outdated -> {
            "$named is set up for an older Andamp and is not asked to play anything. " +
                "A newer one puts its tracks back."
        }

        PackReach.Ahead -> {
            "$named is set up for a newer Andamp and is not asked to play anything. " +
                "Updating Andamp puts its tracks back."
        }

        else -> {
            null
        }
    }

/**
 * Preferences > Music sources > [named], when the source has no screen to open. It shows something
 * only for a source built for another contract version; a current source hands over its own
 * settings screen. A source that is too old gets an update button, which opens the same link the
 * Music sources page ends with.
 */
@Composable
fun PackPage(
    named: String,
    reach: PackReach,
) {
    val links = LocalUriHandler.current
    val notice = packNotice(named, reach) ?: return
    Section(named)
    Text(
        notice,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag("prefs.pack.outdated"),
    )
    if (reach != PackReach.Outdated) return
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = { links.openUri(MORE_SOURCES_URL) },
        modifier = Modifier.testTag("prefs.pack.more"),
    ) { Text("Update $named") }
}
