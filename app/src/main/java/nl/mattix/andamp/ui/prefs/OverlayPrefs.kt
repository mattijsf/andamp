// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

/**
 * The floating player's settings, handed to the page as data so the page does not see a ViewModel.
 * The row is shown only where the phone can show a floating player, which is Android 11 and later.
 */
data class OverlayPrefs(
    /** Whether the phone can show a floating player; the row hides without it. */
    val offered: Boolean = false,
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
    if (!prefs.offered) return
    SwitchRow(
        // Winamp's name for it, also used by the clutter bar's A and the permission prompt
        title = "Always on top",
        summary = prefs.summary,
        checked = prefs.wanted,
        tag = "prefs.overlay.switch",
        // turning it on also asks for the permission when it is missing, the same question the
        // clutter bar's A asks
        onChange = prefs.onWant,
    )
    val cap = prefs.systemCap
    val grant = prefs.wanted && !prefs.permitted
    val note = prefs.wanted && cap != null
    if (grant || note) {
        // a row of its own under the switch, for what turning it on brings with it
        Column(Modifier.prefsRow().padding(horizontal = 8.dp, vertical = 4.dp)) {
            if (grant) {
                TextButton(onClick = prefs.onGrant, modifier = Modifier.testTag("prefs.overlay.grant")) {
                    Text("Allow drawing over other apps")
                }
            }
            if (note) {
                Text(
                    // Android dims a window that passes touches through to other apps, against
                    // invisible-overlay attacks
                    "Android may show the floating window at ${(cap * PERCENT).toInt()}% opacity.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp).testTag("prefs.overlay.capnote"),
                )
            }
        }
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
