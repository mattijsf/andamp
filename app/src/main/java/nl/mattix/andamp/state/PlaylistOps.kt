// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.player.PlayerFacade
import java.util.UUID

/**
 * Playlist selection and queue-editing intents (the SEL/REM/MISC/ADD menus
 * and row interaction). Selection is pure UI state; queue edits go through
 * the facade so the backend can keep playback running when the current
 * track survives an edit.
 */
class PlaylistOps(
    private val state: WinampState,
    private val facade: PlayerFacade,
) {
    fun selectTrack(index: Int) {
        if (index in state.playlist.indices) state.selectedRows = setOf(index)
    }

    fun selectAll() {
        state.selectedRows = state.playlist.indices.toSet()
    }

    fun selectZero() {
        state.selectedRows = emptySet()
    }

    fun invertSelection() {
        state.selectedRows =
            state.playlist.indices
                .filterNot { it in state.selectedRows }
                .toSet()
    }

    fun removeSelected() = editQueue(state.playlist.filterIndexed { i, _ -> i !in state.selectedRows })

    fun removeAll() = editQueue(emptyList())

    /** Winamp's CROP: keep only the selected tracks. No-op without a selection. */
    fun crop() {
        if (state.selectedRows.isEmpty()) return
        editQueue(state.playlist.filterIndexed { i, _ -> i in state.selectedRows })
    }

    /**
     * REM > MISC, Winamp's "Remove all dead files": drops the given ids. The caller decides
     * which rows are dead; filtering by id means a queue edit during the scan cannot remove
     * the wrong rows.
     */
    fun removeDead(deadIds: Set<String>) {
        if (deadIds.isEmpty()) return
        val kept = state.playlist.filterNot { it.id in deadIds }
        // when no id matches (the rows were removed during the scan) nothing is edited, since
        // an edit would clear the selection and reset a stopped cursor to track 0
        if (kept.size == state.playlist.size) return
        editQueue(kept)
    }

    fun sortByTitle() = editQueue(state.playlist.sortedBy { it.title.lowercase() })

    /** Winamp's MISC > sort: reverses the list. */
    fun reverse() = editQueue(state.playlist.reversed())

    /**
     * Winamp's MISC > sort: randomizes the list. Unlike shuffle, which plays the queue in
     * another order, this rewrites the queue itself.
     */
    fun randomize(random: kotlin.random.Random = kotlin.random.Random) = editQueue(state.playlist.shuffled(random))

    private var addCounter = 0

    /** Appends [FakeTracks] with fresh ids; tests use it in place of the add-file dialog. */
    fun addDefaultTracks() {
        addCounter++
        editQueue(state.playlist + FakeTracks.tracks.map { it.copy(id = "${it.id}-add$addCounter") })
    }

    /**
     * ADD > URL: asks for the stream's url, then for a name suggested from its host. The
     * track is appended through the backend's enqueue, so what is playing keeps playing. A
     * url that is not a stream re-opens the prompt with what was typed.
     */
    fun promptAddUrl(initialUrl: String = "") {
        state.namePrompt =
            NamePrompt(title = "Add URL", initial = initialUrl, confirmLabel = "Next") { typedUrl ->
                state.namePrompt = null
                val url = typedUrl.trim()
                if (!StationStore.isStreamUrl(url)) {
                    promptAddUrl(url)
                } else {
                    state.namePrompt =
                        NamePrompt(
                            title = "Name",
                            initial = StationStore.hostOf(url).orEmpty(),
                            confirmLabel = "Add",
                        ) { name ->
                            state.namePrompt = null
                            val called = name.trim().ifEmpty { url }
                            facade.enqueue(
                                listOf(
                                    Track(
                                        id = "url:${UUID.randomUUID()}",
                                        artist = "",
                                        title = called,
                                        durationMs = 0,
                                        uri = url,
                                        // the name the listener gave it, for the row when ICY
                                        // metadata names no track
                                        defaultName = called,
                                        isStream = true,
                                    ),
                                ),
                            )
                        }
                }
            }
    }

    fun scrollBy(
        rows: Int,
        visibleRows: Int,
    ) {
        val maxScroll = (state.playlist.size - visibleRows).coerceAtLeast(0)
        state.playlistScroll = (state.playlistScroll + rows).coerceIn(0, maxScroll)
    }

    private fun editQueue(newTracks: List<Track>) {
        facade.setQueue(newTracks)
        state.selectedRows = emptySet()
        state.playlistScroll = state.playlistScroll.coerceIn(0, (newTracks.size - 1).coerceAtLeast(0))
    }
}
