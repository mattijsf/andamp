// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.runtime.Composable
import nl.mattix.andamp.state.SkinEntry
import nl.mattix.andamp.state.WinampViewModel

/**
 * Preferences > Android Auto: what the main window looks like in Android Auto.
 *
 * The page is offered on a phone that has the app Android Auto talks to; see
 * [nl.mattix.andamp.state.AndroidAutoStore.companionInstalled].
 */
data class AndroidAutoPrefs(
    val offered: Boolean = false,
    /** The installed skins. */
    val skins: List<SkinEntry> = emptyList(),
    /** The skin chosen for Android Auto, or null for the player's own. */
    val skin: String? = null,
    val onSkin: (String?) -> Unit = {},
)

/** The Android Auto settings, read off the view model. [offered] is whether this phone has the app Android Auto talks to. */
fun androidAutoPrefsOf(
    vm: WinampViewModel,
    offered: Boolean,
) = AndroidAutoPrefs(
    offered = offered,
    skins = vm.state.skinEntries,
    skin = vm.androidAutoStore.skin,
    onSkin = { vm.androidAutoStore.skin = it },
)

/** The summary on the row that opens the page. */
fun AndroidAutoPrefs.summary(): String =
    skins.firstOrNull { it.id == skin }?.let { "Wears ${it.name}" } ?: "Wears the player's own skin"

@Composable
internal fun AndroidAutoPage(prefs: AndroidAutoPrefs) {
    Section("Skin")
    Note("In Android Auto the main window wears this skin. Default keeps the one you picked for the player.")
    Group {
        SkinPickRow(
            skins = prefs.skins,
            chosen = prefs.skin,
            heading = "Skin in Android Auto",
            tag = "prefs.androidauto.skin",
            onPick = prefs.onSkin,
        )
    }
}
