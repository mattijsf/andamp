// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModelProvider
import nl.mattix.andamp.state.AppViewModels
import nl.mattix.andamp.state.PluginLinks
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.ui.Screen
import nl.mattix.andamp.ui.prefs.PLUGINS_PAGE

/**
 * A .lua plug-in opened or shared from another app: a file manager, a download, a chat.
 *
 * It shows nothing of its own. It hands the file to the process's one player, the same install the
 * picker in Preferences > Plug-ins does, and opens that page, which reports the result.
 *
 * It is a separate activity and not a filter on [MainActivity] because another app's VIEW starts
 * its target in that app's own task, so the player would open a second time inside the file
 * manager. This one finishes at once and brings the player forward in its own task.
 *
 * A link to a plug-in on Andamp's site arrives here too, as an App Link (see
 * [nl.mattix.andamp.state.PluginLinks]). It is an address to fetch, and what it holds is offered on
 * the same page, not installed.
 *
 * The file is opened here, before finishing: the read permission a VIEW or SEND grants belongs to
 * this activity and can end with it, while an open stream outlives that. The reading happens off
 * the main thread in [nl.mattix.andamp.state.PluginOps.install].
 */
class PluginImportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val player =
            ViewModelProvider(
                AppViewModels,
                ViewModelProvider.AndroidViewModelFactory.getInstance(application),
            )[WinampViewModel::class.java]
        val file = handed(intent)
        if (file?.scheme == "https" || file?.scheme == "http") {
            val link = file.toString()
            if (PluginLinks.accepts(link)) {
                player.pluginOps.offerFrom(link)
            } else {
                player.pluginOps.unreadable("that link is not a plug-in on ${PluginLinks.HOST}")
            }
        } else {
            val stream = file?.let { runCatching { contentResolver.openInputStream(it) }.getOrNull() }
            if (stream != null) {
                player.pluginOps.install(stream)
            } else {
                player.pluginOps.unreadable("the file could not be opened")
            }
        }
        player.state.arrivalDestination = Screen.PREFERENCES
        player.state.arrivalPage = PLUGINS_PAGE
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.SHOW_THE_APP, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
        finish()
    }
}

/** The file a VIEW points at, or the one a SEND carries; null for anything else. */
internal fun handed(intent: Intent): Uri? =
    when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    }
