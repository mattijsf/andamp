// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.mattix.andamp.R
import nl.mattix.andamp.core.model.Track
import kotlin.coroutines.CoroutineContext

/**
 * What the media library keeps as files, the saved lists and the stations, made, renamed and
 * deleted from the window.
 *
 * Each asks first, for a name or whether to delete, changes the file on [io], and then tells
 * the library, which reads its page again. A change that does not take says so in the bar.
 */
internal class LibraryKeeping(
    private val state: WinampState,
    private val scope: CoroutineScope,
    private val lists: PlaylistLibrary,
    private val stations: StationStore,
    private val io: CoroutineContext,
    private val words: Words,
    /** A short note in the library's bar. */
    private val flash: (String) -> Unit,
    /** The saved lists changed; the one named is gone, when there is one. */
    private val listsChanged: (gone: String?) -> Unit,
    private val stationsChanged: () -> Unit,
) {
    /** Renames the saved list [name] to what the listener types. */
    fun renameList(name: String) {
        state.namePrompt =
            NamePrompt(
                title = words.get(R.string.menu_rename_list),
                initial = name,
                confirmLabel = words.get(R.string.prompt_save),
            ) { typed ->
                state.namePrompt = null
                val to = typed.trim()
                if (to.isNotEmpty() && to != name) {
                    change(R.string.library_cant_rename, { lists.rename(name, to) }) { listsChanged(null) }
                }
            }
    }

    /** Deletes the saved list [name] once the listener says so. */
    fun deleteList(name: String) {
        state.prompt =
            deleteListPrompt(name, words, onDone = { state.prompt = null }) {
                change(R.string.library_cant_delete, { lists.delete(name) }) { listsChanged(name) }
            }
    }

    /** RADIO's NEW: asks for a url, then for a name suggested from its host. */
    fun newStation() {
        state.namePrompt =
            NamePrompt(
                title = words.get(R.string.library_station_url_title),
                confirmLabel = words.get(R.string.library_station_next),
            ) { typedUrl ->
                state.namePrompt = null
                val url = typedUrl.trim()
                if (!StationStore.isStreamUrl(url)) {
                    flash(words.get(R.string.library_not_http))
                } else {
                    state.namePrompt =
                        NamePrompt(
                            title = words.get(R.string.library_station_name_title),
                            initial = StationStore.hostOf(url).orEmpty(),
                            confirmLabel = words.get(R.string.library_station_add),
                        ) { name ->
                            state.namePrompt = null
                            scope.launch {
                                withContext(io) { stations.add(name, url) }
                                stationsChanged()
                            }
                        }
                }
            }
    }

    /** Renames [station] to what the listener types, on its row and as the name it plays under. */
    fun renameStation(station: Track) {
        state.namePrompt =
            NamePrompt(
                title = words.get(R.string.library_station_name_title),
                initial = station.title,
                confirmLabel = words.get(R.string.prompt_save),
            ) { typed ->
                state.namePrompt = null
                val to = typed.trim()
                if (to.isNotEmpty() && to != station.title) {
                    change(R.string.library_cant_rename, { stations.rename(station.id, to) }, stationsChanged)
                }
            }
    }

    /** Deletes [station] once the listener says so. */
    fun deleteStation(station: Track) {
        state.prompt =
            AmpPrompt(
                title = words.get(R.string.prompt_delete_station_title, station.title),
                body = words.get(R.string.prompt_delete_station_body),
                confirmLabel = words.get(R.string.prompt_delete),
                dismissLabel = words.get(R.string.prompt_cancel),
                onConfirm = {
                    state.prompt = null
                    change(R.string.library_cant_delete, { stations.remove(station.id) }, stationsChanged)
                },
            )
    }

    /** Runs [write] on [io]; then [done], or [failed] in the bar when it did not take. */
    private fun change(
        failed: Int,
        write: () -> Boolean,
        done: () -> Unit,
    ) {
        scope.launch {
            if (withContext(io) { write() }) done() else flash(words.get(failed))
        }
    }
}
