// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

/**
 * The floating player's settings, handed to the page as data so the page does not see a ViewModel.
 */
data class OverlayPrefs(
    val wanted: Boolean = false,
    val permitted: Boolean = false,
    val onWant: (Boolean) -> Unit = {},
    /** Opens the system screen that grants drawing over other apps. */
    val onGrant: () -> Unit = {},
    val summary: String = "",
    /**
     * The opacity Android caps the floating window at, or null when the platform reports no cap;
     * the note is then not shown.
     */
    val systemCap: Float? = null,
)

@Composable
fun OverlayRow(prefs: OverlayPrefs) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            // Winamp's name for it, also used by the clutter bar's A and the permission prompt
            Text("Always on top", style = MaterialTheme.typography.bodyLarge)
            Text(prefs.summary, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = prefs.wanted,
            // turning it on also asks for the permission when it is missing, the same question the
            // clutter bar's A asks
            onCheckedChange = prefs.onWant,
            modifier = Modifier.testTag("prefs.overlay.switch"),
        )
    }
    if (prefs.wanted && !prefs.permitted) {
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = prefs.onGrant, modifier = Modifier.testTag("prefs.overlay.grant")) {
            Text("Allow drawing over other apps")
        }
    }
    val cap = prefs.systemCap
    if (prefs.wanted && cap != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            // Android dims a window that passes touches through to other apps, against
            // invisible-overlay attacks
            "Android may show the floating window at ${(cap * PERCENT).toInt()}% opacity.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("prefs.overlay.capnote"),
        )
    }
}

private const val PERCENT = 100

/**
 * The opacity Android caps a touch-passing overlay at, or null where the platform enforces none.
 * The cap exists since Android 12 and its value is platform policy, so it is read from the
 * platform.
 */
fun overlayCapOf(context: android.content.Context): Float? {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return null
    val input = context.getSystemService(android.hardware.input.InputManager::class.java) ?: return null
    return input.maximumObscuringOpacityForTouch.takeIf { it < 1f }
}

/** The system screen that grants drawing over other apps, aimed at this app's own row. */
fun overlaySettingsIntent(context: android.content.Context) =
    android.content.Intent(
        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        "package:${context.packageName}".toUri(),
    )
