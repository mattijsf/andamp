// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.androidauto

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import nl.mattix.andamp.core.remotewindow.RemoteWindowLink

/**
 * The "Additional settings in the app" button on this app's App info page. Its settings are in
 * the player, on Preferences > Android Auto, so this opens that page there
 * ([RemoteWindowLink.SETTINGS]). A player from before that page opens as it does from its icon,
 * and without a player a toast says to install it.
 */
class SettingsEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val player = RemoteWindowLink.playerOf(packageName)
        val settings = Intent(RemoteWindowLink.SETTINGS).setPackage(player)
        val open =
            settings.takeIf { packageManager.resolveActivity(it, 0) != null }
                ?: packageManager.getLaunchIntentForPackage(player)
        if (open != null) {
            startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            Toast.makeText(this, R.string.settings_need_player, Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
