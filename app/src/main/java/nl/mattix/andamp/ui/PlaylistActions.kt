// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import nl.mattix.andamp.state.MediaStoreAudio
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.ui.window.PlaylistMenuActions

/**
 * The playlist menu entries that need something only the screen can hold: a
 * document picker, and the audio permission behind the device's library.
 *
 * The permission is asked for before a picker opens, while it is not held. Refusing it does
 * not stop the add: the picker opens either way, and the picked document plays on its own
 * grant.
 */
@Composable
fun rememberPlaylistActions(vm: WinampViewModel): PlaylistMenuActions {
    // what the open picker was asked for: the three verbs share one launcher, and its
    // callback cannot tell them apart
    var audioAsked by remember { mutableStateOf(Picked.ADD) }
    val audioPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                when (audioAsked) {
                    Picked.ADD -> vm.playlistFiles.addAudio(uri)
                    Picked.ADD_AND_PLAY -> vm.playlistFiles.addAudio(uri, thenPlay = true)
                    Picked.REPLACE -> vm.playlistFiles.playAudio(uri)
                }
            }
            audioAsked = Picked.ADD
        }
    // one launcher, two verbs, as with audioAsked
    var folderReplaces by remember { mutableStateOf(false) }
    val folderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                if (folderReplaces) vm.playlistFiles.playFolder(uri) else vm.playlistFiles.addFolder(uri)
            }
            folderReplaces = false
        }
    val playlistLoader =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) vm.playlistFiles.import(uri)
        }
    val playlistExporter =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PLAYLIST_MIME)) { uri ->
            if (uri != null) vm.playlistFiles.exportTo(uri)
        }

    var pendingAdd by remember { mutableStateOf<(() -> Unit)?>(null) }
    val libraryPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // a granted library can also re-find rows whose grants have lapsed
            if (granted) vm.playlistFiles.healRestoredEntries()
            pendingAdd?.invoke()
            pendingAdd = null
        }
    val withLibrary: (() -> Unit) -> Unit = { add ->
        if (vm.playlistFiles.canReadLibrary()) {
            add()
        } else {
            pendingAdd = add
            libraryPermission.launch(MediaStoreAudio.permission())
        }
    }

    // one remembered instance: these actions are a remember key downstream, and a fresh
    // object per recomposition would rebuild the playlist's widgets and reset their
    // gesture state
    return remember(vm) {
        PlaylistMenuActions(
            addFile = {
                audioAsked = Picked.ADD
                withLibrary { audioPicker.launch(arrayOf("audio/*")) }
            },
            addFileThenPlay = {
                audioAsked = Picked.ADD_AND_PLAY
                withLibrary { audioPicker.launch(arrayOf("audio/*")) }
            },
            playFile = {
                audioAsked = Picked.REPLACE
                withLibrary { audioPicker.launch(arrayOf("audio/*")) }
            },
            addDir = {
                folderReplaces = false
                withLibrary { folderPicker.launch(null) }
            },
            playDir = {
                folderReplaces = true
                withLibrary { folderPicker.launch(null) }
            },
            saveList = { vm.playlistFiles.saveList { name -> playlistExporter.launch(name) } },
            loadList = { vm.playlistFiles.loadList { playlistLoader.launch(arrayOf("*/*")) } },
        )
    }
}

/**
 * What was asked of the one audio picker: [ADD] appends, [ADD_AND_PLAY] appends and plays
 * what it appended, [REPLACE] replaces the queue.
 */
private enum class Picked {
    ADD,
    ADD_AND_PLAY,
    REPLACE,
}

/** What an .m3u is called on this platform; the picker needs a type to create one. */
private const val PLAYLIST_MIME = "audio/x-mpegurl"
