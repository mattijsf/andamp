// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.mattix.andamp.core.player.PlayerFacade

/**
 * What a session leaves for the next one: shuffle and repeat, the queue and its current
 * row, the visualizer's mode, and where the windows were left.
 *
 * Every writer is fed by what the backend or the snapshot state reports, so a change is
 * saved whichever code made it. Every writer debounces, because a drag emits per frame and
 * a multi-file add arrives in a burst; [flush] writes what the debounce still holds when
 * the app goes away.
 *
 * [start] restores first and collects after, so a writer's first emission is the restored
 * value and not the default.
 */
class PersistenceOps(
    private val state: WinampState,
    private val facade: PlayerFacade,
    private val scope: CoroutineScope,
    private val playlistStore: PlaylistStore,
    private val transportStore: TransportStore,
    private val windowStore: WindowStore,
    private val visualsStore: VisualsStore,
    /** Where the writes happen. */
    private val io: kotlin.coroutines.CoroutineContext = Dispatchers.IO,
) {
    /**
     * Whether the queue on screen is the one to store.
     *
     * A backend that cannot edit its queue is showing a remote source's queue. Writing
     * that over the stored playlist would lose the listener's own, and
     * [PlaylistStore.retainOnly] would then release the document grants of its rows. Read
     * per write, because the source can change while the app runs.
     */
    private val ownsQueue: Boolean get() = facade.capabilities.canEditQueue

    private var pendingSave: PlaylistCodec.Saved? = null
    private var savedSnapshot: PlaylistCodec.Saved? = null
    private var pendingWindows: WindowLayoutMemory? = null
    private var savedWindows: WindowLayoutMemory? = null

    /** Restores what was remembered, then watches everything that can change it. */
    fun start() {
        restoreTransport()
        restoreWindows()
        state.visMode = visualsStore.load().mode
        collectTransport()
        collectPlaylist()
        collectVisuals()
        collectWindows()
    }

    /**
     * Writes what the debounce is still holding. Called when the app goes to the
     * background, because [AppViewModels] keeps the view model alive past every activity
     * and `onCleared` may never come; and from `onCleared`, synchronously, since no scope
     * is left there.
     */
    fun flush() {
        pendingSave?.takeIf { it != savedSnapshot }?.let(::storePlaylist)
        pendingWindows?.takeIf { it != savedWindows }?.let(windowStore::save)
    }

    /** Restores shuffle and repeat. Volume is not restored: it is the phone's media volume. */
    private fun restoreTransport() {
        transportStore.load().let { stored ->
            facade.setShuffle(stored.shuffle)
            facade.setRepeat(stored.repeat)
        }
    }

    /** Fed by what the backend reports, so a menu, a skin button and a headset are all covered. */
    private fun collectTransport() {
        scope.launch {
            facade.state
                .map { TransportState(it.shuffle, it.repeat) }
                .distinctUntilChanged()
                .drop(1) // the restored value is already on disk
                .collect { transportStore.saveSettings(it) }
        }
    }

    private fun collectPlaylist() {
        scope.launch {
            facade.state
                .map { it.queue to it.currentIndex }
                .distinctUntilChanged()
                .drop(1) // the queue the backend was built with is already on disk
                .collectLatest { (queue, index) ->
                    val snapshot = PlaylistCodec.Saved(queue, index)
                    pendingSave = snapshot
                    delay(SAVE_DEBOUNCE_MS) // a multi-file add lands as one write
                    withContext(io) { storePlaylist(snapshot) }
                }
        }
    }

    private fun storePlaylist(snapshot: PlaylistCodec.Saved) {
        if (!ownsQueue) return
        playlistStore.save(snapshot.tracks, snapshot.currentIndex)
        playlistStore.retainOnly(snapshot.tracks.mapNotNull { it.uri }.toSet())
        savedSnapshot = snapshot
    }

    private fun collectVisuals() {
        scope.launch {
            snapshotFlow { Visuals(state.visMode) }
                .distinctUntilChanged()
                .drop(1) // the restored value is already on disk
                .collectLatest { visuals ->
                    delay(SAVE_DEBOUNCE_MS) // cycling through the modes is one write
                    withContext(io) { visualsStore.save(visuals) }
                }
        }
    }

    private fun collectWindows() {
        scope.launch {
            snapshotFlow { windowMemory() }
                .distinctUntilChanged()
                .drop(1) // the restored value is already on disk
                .collectLatest { memory ->
                    pendingWindows = memory
                    delay(SAVE_DEBOUNCE_MS) // a drag emits per frame; one write when it rests
                    withContext(io) { windowStore.save(memory) }
                    savedWindows = memory
                }
        }
    }

    /** The current layout, as [WindowStore] stores it. */
    private fun windowMemory(): WindowLayoutMemory =
        WindowLayoutMemory(
            placements = WindowStore.WINDOWS.associateWith { id -> state.placementOf(id)?.asMemory() ?: WindowPlacement() },
            order = state.windowOrder,
        )

    /**
     * Puts the windows back where they were left. A placement that is off this screen is
     * not corrected here: every window clamps itself at layout time.
     */
    private fun restoreWindows() {
        val memory = windowStore.load()
        WindowStore.WINDOWS.forEach { id -> state.placementOf(id)?.restore(memory.placementOf(id)) }
        // a playlist with no stored size fills what is left of the screen; a fresh install
        // starts smaller
        if (memory.firstRun) state.plSegments = WindowStore.PLAYLIST_START_SEGMENTS
        // a stored order may name fewer windows than this build has (an upgrade); the ones
        // it does not mention are appended after it
        if (memory.order.isNotEmpty()) {
            state.windowOrder = memory.order + state.windowOrder.filterNot { it in memory.order }
        }
        savedWindows = windowMemory()
    }

    private companion object {
        /** Long enough to coalesce a burst of edits. */
        const val SAVE_DEBOUNCE_MS = 400L
    }
}
