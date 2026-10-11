// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import nl.mattix.andamp.state.SkinEntry
import nl.mattix.andamp.state.WinampViewModel

/**
 * Preferences > Car: what the main window looks like on a car's screen.
 *
 * The page is offered on a phone that has the app Android Auto talks to; see
 * [nl.mattix.andamp.state.CarStore].
 */
data class CarPrefs(
    val offered: Boolean = false,
    /** The installed skins. */
    val skins: List<SkinEntry> = emptyList(),
    /** The skin chosen for the car, or null for the player's own. */
    val skin: String? = null,
    val onSkin: (String?) -> Unit = {},
)

/** The car's settings, read off the view model. [offered] is whether this phone has the car's app. */
fun carPrefsOf(
    vm: WinampViewModel,
    offered: Boolean,
) = CarPrefs(
    offered = offered,
    skins = vm.state.skinEntries,
    skin = vm.carStore.skin,
    onSkin = { vm.carStore.skin = it },
)

/** The summary on the row that opens the page. */
fun CarPrefs.summary(): String = skins.firstOrNull { it.id == skin }?.let { "Wears ${it.name}" } ?: "Wears the player's own skin"

@Composable
internal fun CarPage(prefs: CarPrefs) {
    Section("Skin")
    Text(
        "On the car's screen the main window wears this skin. " +
            "Default keeps the one you picked for the player.",
        style = MaterialTheme.typography.bodyMedium,
    )
    SkinPickRow(
        skins = prefs.skins,
        chosen = prefs.skin,
        heading = "Skin in the car",
        tag = "prefs.car.skin",
        onPick = prefs.onSkin,
    )
}
