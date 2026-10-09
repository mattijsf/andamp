// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import nl.mattix.andamp.state.FolderAsk
import nl.mattix.andamp.state.WinampViewModel

/**
 * Opens the system's folder picker when a folder is wanted again (`folderAsk`), on that
 * folder, so that giving it back is two taps and no search. The answer goes to
 * `folderAccess.answered`.
 */
@Composable
fun FolderAsks(vm: WinampViewModel) {
    // The picker needs an activity's result registry, which the floating player's window
    // has none of. The ask is left standing: the floating player opens the app for it, and
    // the app's own surface answers it here.
    if (LocalActivityResultRegistryOwner.current == null) return
    // what the open picker was asked for: its callback outlives the state that asked
    var waiting by remember { mutableStateOf<FolderAsk?>(null) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { picked ->
            waiting?.let { vm.folderAccess.answered(it, picked) }
            waiting = null
        }
    val asked = vm.state.folderAsk ?: return
    LaunchedEffect(asked) {
        vm.state.folderAsk = null
        waiting = asked
        // a tree uri names a grant; the picker's starting place is the folder's own document
        val tree = Uri.parse(asked.folder)
        val start =
            runCatching { DocumentsContract.buildDocumentUri(tree.authority, DocumentsContract.getTreeDocumentId(tree)) }
                .getOrNull()
        picker.launch(start)
    }
}
