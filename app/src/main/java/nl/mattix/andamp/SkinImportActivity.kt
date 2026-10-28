// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import nl.mattix.andamp.state.AppViewModels
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.displayNameOf

/**
 * A Winamp skin (.wsz, or a modern .wal) opened or shared from another app: a file manager, a
 * download, a chat.
 *
 * It shows nothing of its own. It hands the file to the process's one player, as Load skin does,
 * and brings the player forward, where the skin goes on, a notice says why it did not, or a modern
 * skin picked while modern skins are off is offered with them turned on.
 *
 * It is a separate activity and not a filter on [MainActivity] for the reason [PluginImportActivity]
 * gives: another app's VIEW starts its target in that app's task, so the player would open a
 * second time inside the file manager. This one finishes at once and brings the player forward in
 * its own task.
 *
 * The file is opened here, before finishing: the read permission a VIEW or SEND grants belongs to
 * this activity and can end with it, while an open stream outlives that.
 */
class SkinImportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val player =
            ViewModelProvider(
                AppViewModels,
                ViewModelProvider.AndroidViewModelFactory.getInstance(application),
            )[WinampViewModel::class.java]
        val file = handed(intent)
        val stream = file?.let { runCatching { contentResolver.openInputStream(it) }.getOrNull() }
        // what cannot be read changes nothing, as with a mispick in Load skin
        if (file != null && stream != null) {
            player.skinOps.load(stream, displayNameOf(file) ?: getString(R.string.skin_picked_name))
        }
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.SHOW_THE_APP, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
        finish()
    }
}
