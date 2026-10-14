// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import nl.mattix.andamp.core.remotewindow.RemoteWindowLink
import nl.mattix.andamp.ui.Screen
import nl.mattix.andamp.ui.prefs.ANDROID_AUTO_PAGE

/**
 * The way into Preferences from outside the app: the "Additional settings in the app" button on
 * the phone's App info page ([Intent.ACTION_APPLICATION_PREFERENCES]), and the Android Auto page,
 * which the app Android Auto talks to opens from the same button on its App info page
 * ([RemoteWindowLink.SETTINGS]).
 *
 * It shows nothing. It opens Preferences, or its Android Auto page, in the player's task and
 * finishes; Done there goes back to where it was asked from. It is not a filter on
 * [MainActivity] for the reason [PluginImportActivity] gives.
 */
class SettingsEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = ANDROID_AUTO_PAGE.takeIf { intent?.action == RemoteWindowLink.SETTINGS }
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.SHOW_THE_APP, true)
                .putExtra(MainActivity.GO_TO, Screen.PREFERENCES)
                .putExtra(MainActivity.GO_TO_PAGE, page)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
        finish()
    }
}
