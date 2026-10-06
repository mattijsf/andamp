// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.player.PlayerFacade

/**
 * Asking for a folder again when its music can be read no other way.
 *
 * A row added with ADD > DIR opens through the folder's grant, and without it through the
 * phone's library ([ReadableUri]). When neither works - the grant is gone and the audio
 * permission is off, or the library does not list the file - only the listener can help,
 * by picking the folder again. An app cannot take a grant back on its own.
 *
 * The ask is one prompt and then the system's folder picker, opened on that folder. The
 * grant comes back under the folder's own address, so every list and bookmark that names
 * the folder plays again and nothing is added twice.
 */
class FolderAccessOps(
    private val state: WinampState,
    private val facade: PlayerFacade,
    private val scope: CoroutineScope,
    private val store: PlaylistStore,
    private val readable: ReadableUri,
    /** Where the library is asked whether it lists a file. */
    private val io: kotlin.coroutines.CoroutineContext = Dispatchers.IO,
) {
    /** The folders asked for since the app started, so that reading a list does not ask twice. */
    private val asked = mutableSetOf<String>()

    /**
     * A list or a bookmark was read from disk and is about to be shown or played. A folder
     * its rows cannot be read from is asked for, once since the app started.
     */
    suspend fun listRead(tracks: List<Track>) {
        val folder = unreached(tracks).firstOrNull { it !in asked } ?: return
        ask(folder) {}
    }

    /**
     * A row the listener pressed. One that can be read neither way is not handed to the
     * player, which would skip it and play the next without a word: its folder is asked
     * for, and the row plays once the folder is given. True when the press is taken care
     * of here, which is known only after the library has answered, so it is also true for
     * a row the library turns out to have, and that row is then played from here.
     */
    fun pressed(row: Track): Boolean {
        val uri = row.uri ?: return false
        if (readable.lostFolders(listOf(uri)).isEmpty()) return false
        val play = {
            val at =
                facade.state.value.queue
                    .indexOfFirst { it.id == row.id }
            if (at >= 0) facade.playAt(at)
        }
        scope.launch {
            val folder = unreached(listOf(row)).firstOrNull()
            if (folder == null) play() else ask(folder, play)
        }
        return true
    }

    /**
     * The folder picker answered [ask] with [picked], or with null when it was closed.
     * Only the folder that was asked for helps: the rows name their files through it, so a
     * folder above or beside it opens none of them.
     */
    fun answered(
        ask: FolderAsk,
        picked: Uri?,
    ) {
        when (picked?.toString()) {
            null -> {
                Unit
            }

            ask.folder -> {
                store.rememberTree(picked)
                ask.then()
            }

            else -> {
                state.prompt =
                    prompt(
                        ask.folder,
                        "That was another folder. The songs play only from the folder they were added from.",
                        ask.then,
                    )
            }
        }
    }

    private suspend fun unreached(tracks: List<Track>): Set<String> =
        withContext(io) {
            readable.unreached(tracks.mapNotNull { it.uri })
        }

    private fun ask(
        folder: String,
        then: () -> Unit,
    ) {
        if (state.prompt != null) return
        asked += folder
        state.prompt = prompt(folder, "Andamp can no longer read this folder. Allow it again and its songs play as before.", then)
    }

    private fun prompt(
        folder: String,
        body: String,
        then: () -> Unit,
    ) = AmpPrompt(
        title = "Allow ${GrantScope.folderName(folder)} again?",
        body = body,
        confirmLabel = "Allow",
        dismissLabel = "Not now",
        onConfirm = {
            state.prompt = null
            state.folderAsk = FolderAsk(folder, then)
        },
    )
}

/**
 * [folder] is wanted again, and [then] is what waits for it. The screen that holds the
 * folder picker opens it and hands the answer to [FolderAccessOps.answered]. It is a request
 * object because only an activity can open a picker.
 */
class FolderAsk(
    val folder: String,
    val then: () -> Unit,
)
