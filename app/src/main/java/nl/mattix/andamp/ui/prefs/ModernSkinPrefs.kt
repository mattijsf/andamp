// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.mattix.andamp.R
import nl.mattix.andamp.wal.WalApart
import nl.mattix.andamp.wal.WalGate
import nl.mattix.andamp.wal.WalSharp

/**
 * The Skins section on Preferences > Player: whether Winamp modern skins (.wal) may be added and
 * worn, whether they are drawn with sharp pixels, and whether their windows are set out apart.
 */
data class ModernSkinPrefs(
    /** Mirrors [nl.mattix.andamp.wal.WalGate.on]. */
    val enabled: Boolean = false,
    val onEnabled: (Boolean) -> Unit = {},
    /** Mirrors [nl.mattix.andamp.wal.WalSharp.on]; its row shows only while [enabled]. */
    val sharp: Boolean = false,
    val onSharp: (Boolean) -> Unit = {},
    /** Mirrors [nl.mattix.andamp.wal.WalApart.on]; its row shows only while [enabled]. */
    val apart: Boolean = false,
    val onApart: (Boolean) -> Unit = {},
)

/**
 * The switches as the app holds them. A tap changes the value on main at once and stores it in
 * the background. The stored values are read here too, off main, so the switches show them even
 * when Preferences is the first screen the process draws.
 */
@Composable
internal fun modernSkinPrefsOf(context: Context): ModernSkinPrefs {
    val app = context.applicationContext ?: context
    LaunchedEffect(app) {
        withContext(Dispatchers.IO) {
            WalGate.read(app)
            WalSharp.read(app)
            WalApart.read(app)
        }
    }
    return ModernSkinPrefs(
        enabled = WalGate.on,
        onEnabled = { on -> WalGate.change(app, on) },
        sharp = WalSharp.on,
        onSharp = { on -> WalSharp.change(app, on) },
        apart = WalApart.on,
        onApart = { on -> WalApart.change(app, on) },
    )
}

/**
 * The last section of Preferences > Player: the modern skins switch, a note on what wears a modern
 * skin, and Sharp pixels and Windows apart while modern skins are on.
 */
@Composable
internal fun ModernSkinsSection(prefs: ModernSkinPrefs) {
    Section(stringResource(R.string.prefs_player_skins_section))
    Group { ModernSkinsRow(prefs) }
    Note(stringResource(R.string.prefs_modern_skins_note))
    // sharp pixels change only how a modern skin is drawn, so the row shows while they can be worn
    if (prefs.enabled) {
        Group {
            SharpPixelsRow(prefs)
            WindowsApartRow(prefs)
        }
    }
}

@Composable
private fun WindowsApartRow(prefs: ModernSkinPrefs) {
    SwitchRow(
        title = stringResource(R.string.prefs_windows_apart_title),
        summary = stringResource(if (prefs.apart) R.string.prefs_windows_apart_on else R.string.prefs_windows_apart_off),
        checked = prefs.apart,
        tag = "prefs.apart.switch",
        onChange = prefs.onApart,
    )
}

@Composable
private fun ModernSkinsRow(prefs: ModernSkinPrefs) {
    SwitchRow(
        title = stringResource(R.string.prefs_modern_skins_title),
        summary = stringResource(if (prefs.enabled) R.string.prefs_modern_skins_on else R.string.prefs_modern_skins_off),
        checked = prefs.enabled,
        tag = "prefs.modern.switch",
        onChange = prefs.onEnabled,
    )
}

@Composable
private fun SharpPixelsRow(prefs: ModernSkinPrefs) {
    SwitchRow(
        title = stringResource(R.string.prefs_sharp_pixels_title),
        summary = stringResource(if (prefs.sharp) R.string.prefs_sharp_pixels_on else R.string.prefs_sharp_pixels_off),
        checked = prefs.sharp,
        tag = "prefs.sharp.switch",
        onChange = prefs.onSharp,
    )
}
