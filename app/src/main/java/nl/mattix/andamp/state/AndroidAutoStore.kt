// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import nl.mattix.andamp.core.remotewindow.RemoteWindowLink

/**
 * What the listener chose for Android Auto.
 *
 * A car's screen shows the main window through an app of its own, which Android Auto talks to
 * and which lends the screen to the player; see
 * [nl.mattix.andamp.ui.remote.RemoteWindowService]. Two choices are kept: the skin worn there,
 * chosen on Preferences > Android Auto, where none means the player's own, and whether the
 * window fills the screen, which the D in the window there switches.
 *
 * Held as snapshot state too, so the window in Android Auto and the page both follow a
 * choice when it is made.
 */
class AndroidAutoStore(
    private val prefs: SharedPreferences,
    /** Whether a skin is still in the library; a choice of one that is not counts as none. */
    private val installed: (String) -> Boolean = { true },
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        SkinLibrary(context)::has,
    )

    private var chosen: String? by mutableStateOf(prefs.getString(SKIN, null))

    /** The skin chosen for Android Auto, or null for the player's own. */
    var skin: String?
        get() = chosen
        set(value) {
            chosen = value
            prefs.edit().apply { if (value == null) remove(SKIN) else putString(SKIN, value) }.apply()
        }

    private var filling: Boolean by mutableStateOf(prefs.getBoolean(FILL, false))

    /**
     * Android Auto's own Double Size: whether the window is made as large as the screen takes. It
     * is then drawn at a size that is no whole number of pixels to one of its own, which
     * softens their edges. Off, it is drawn at the largest whole number that fits, with black
     * around it. The phone's Double Size is another switch and is not touched from there.
     */
    var fill: Boolean
        get() = filling
        set(value) {
            filling = value
            prefs.edit().putBoolean(FILL, value).apply()
        }

    /** The skin worn in Android Auto over the player's own, or null when that one is worn. */
    val worn: String? get() = chosen?.takeIf(installed)

    /** A skin left the library: if it was the one chosen here, the choice goes back to the player's own. */
    fun forgetSkin(id: String) {
        if (chosen == id) skin = null
    }

    companion object {
        private const val PREFS = "android_auto"
        private const val SKIN = "skin"
        private const val FILL = "fill"

        /**
         * Whether the app Android Auto talks to is on this phone, which is when these settings
         * mean anything.
         */
        fun companionInstalled(context: Context): Boolean {
            val companion = RemoteWindowLink.lenderOf(context.packageName)
            return runCatching { context.packageManager.getPackageInfo(companion, 0) }.isSuccess
        }
    }
}
