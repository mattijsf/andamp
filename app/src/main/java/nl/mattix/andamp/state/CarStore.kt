// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What the listener chose for the car's screen, on Preferences > Car.
 *
 * A car's screen shows the main window through an app of its own, [COMPANION], which lends the
 * screen to the player; see [nl.mattix.andamp.ui.remote.RemoteWindowService]. The one choice
 * is the skin worn there. None chosen means the player's own.
 *
 * Held as snapshot state too, so the window on the car's screen and the page both follow a
 * choice when it is made.
 */
class CarStore(
    private val prefs: SharedPreferences,
    /** Whether a skin is still in the library; a choice of one that is not counts as none. */
    private val installed: (String) -> Boolean = { true },
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        SkinLibrary(context)::has,
    )

    private var chosen: String? by mutableStateOf(prefs.getString(SKIN, null))

    /** The skin chosen for the car, or null for the player's own. */
    var skin: String?
        get() = chosen
        set(value) {
            chosen = value
            prefs.edit().apply { if (value == null) remove(SKIN) else putString(SKIN, value) }.apply()
        }

    /** The skin the car wears over the player's own, or null when it wears that one. */
    val worn: String? get() = chosen?.takeIf(installed)

    /** A skin left the library: a car that asked for it goes back to the player's own. */
    fun forgetSkin(id: String) {
        if (chosen == id) skin = null
    }

    companion object {
        private const val PREFS = "car"
        private const val SKIN = "skin"

        /** The package of the app Android Auto talks to. */
        const val COMPANION = "nl.mattix.andamp.car"

        /** Whether that app is on this phone, which is when the car's settings mean anything. */
        fun companionInstalled(context: Context): Boolean =
            runCatching { context.packageManager.getPackageInfo(COMPANION, 0) }.isSuccess
    }
}
