// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.widget

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntRect
import androidx.lifecycle.ViewModelProvider
import nl.mattix.andamp.MainActivity
import nl.mattix.andamp.state.AppViewModels
import nl.mattix.andamp.state.CurrentSkin
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.displayNameOf
import nl.mattix.andamp.ui.Screen
import nl.mattix.andamp.ui.StatusBarIcons
import nl.mattix.andamp.ui.menu.AmpContextMenu
import nl.mattix.andamp.ui.menu.AmpModals
import nl.mattix.andamp.ui.menu.widgetMenu
import nl.mattix.andamp.ui.prefs.WIDGET_PAGE
import nl.mattix.andamp.ui.theme.rememberSkinColorScheme

/**
 * The player's menu, opened from the widget's own top-left button.
 *
 * A widget can show nothing of its own, so the menu is an activity. It shows the app's own menu,
 * rendered by [AmpModals] and acting on the same view model the player uses, which [AppViewModels]
 * holds for the process. See [widgetMenu] for the entries a home screen cannot offer.
 */
class WidgetMenuActivity : ComponentActivity() {
    private val player: WinampViewModel by lazy {
        ViewModelProvider(
            AppViewModels,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application),
        )[WinampViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // the window reaches under the system bars on every Android version, as it does from 15,
        // so the home screen shows behind them too
        enableEdgeToEdge()
        val skin = CurrentSkin.cached(this)
        // an activity result launcher has to be registered before the activity starts, so it is
        // made here and the menu is given a way to fire it
        val skinPicker =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                picking = false
                uri?.let {
                    contentResolver.openInputStream(it)?.let { stream ->
                        player.skinOps.load(stream, displayNameOf(it) ?: "picked skin")
                    }
                }
                finish()
            }
        // Winamp's Play file... and Play directory... No permission is asked first: what is picked
        // is granted by the picker itself
        val filePicker =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                picking = false
                uri?.let { player.playlistFiles.playAudio(it) }
                finish()
            }
        val folderPicker =
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                picking = false
                uri?.let { player.playlistFiles.playFolder(it) }
                finish()
            }

        fun build() =
            widgetMenu(
                vm = player,
                onWidgetSettings = { open(Screen.PREFERENCES, page = WIDGET_PAGE) },
                onPickSkin = {
                    picking = true
                    skinPicker.launch(arrayOf("*/*"))
                },
                onOpenFile = {
                    picking = true
                    filePicker.launch(arrayOf("audio/*"))
                },
                onOpenFolder = {
                    picking = true
                    folderPicker.launch(null)
                },
                onOpen = { where -> open(where) },
                onExit = {
                    player.exit()
                    finish()
                },
            )
        menu = build()
        setContent {
            // the menu is built once, and lastPlayed is filled in after onCreate, so the menu is
            // rebuilt when it arrives
            val heard = player.state.lastPlayed
            LaunchedEffect(heard) {
                if (heard != null && menu != null) menu = build()
            }
            // closes when nothing is showing: the menu, a modal it opened, or a picker in front
            val showing = picking || menu != null || player.state.showingAModal()
            LaunchedEffect(showing) { if (!showing) finish() }
            // the status bar sits on the wallpaper here, as it does over a floating player
            StatusBarIcons(onPlayer = true, playerFillsScreen = false, dark = false)
            // the menu is this window's own state and not WinampState.activeMenu, which every host
            // of AmpModals draws; the dialogs stay shared
            MaterialTheme(colorScheme = skin?.let { rememberSkinColorScheme(it) } ?: darkColorScheme()) {
                menu?.let { open ->
                    AmpContextMenu(open, IntRect(ANCHOR_X, ANCHOR_Y, ANCHOR_X, ANCHOR_Y)) { menu = null }
                }
            }
            AmpModals(
                player.state,
                anchorBounds = { IntRect(ANCHOR_X, ANCHOR_Y, ANCHOR_X, ANCHOR_Y) },
                skin = skin,
            )
        }
    }

    /** Closes the menu on the way out, so returning here does not reopen it. */
    private fun open(
        where: String?,
        page: String? = null,
    ) {
        menu = null
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.SHOW_THE_APP, true)
                .putExtra(MainActivity.GO_TO, where)
                .putExtra(MainActivity.GO_TO_PAGE, page)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
        finish()
    }

    /** True while the document picker is in front, waiting for an answer. */
    private var picking by mutableStateOf(false)

    /** This window's own menu, kept out of the state every other host draws. */
    private var menu by mutableStateOf<nl.mattix.andamp.state.AmpMenu?>(null)

    override fun onPause() {
        super.onPause()
        // closed here, so it is not still open when the listener returns to the home screen
        menu = null
    }
}

private const val ANCHOR_X = 48
private const val ANCHOR_Y = 320
