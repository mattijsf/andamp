// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.player.PlayerFacade
import java.time.LocalDate

/**
 * The playlist's file operations: ADD > FILE and ADD > DIR, REM > MISC's dead-file sweep,
 * and Winamp's LIST > SAVE LIST and LOAD LIST.
 *
 * These are the queue operations that touch storage, a document picker or the media
 * library; the queue edits themselves are in [PlaylistOps].
 */
@Suppress("LongParameterList") // storage, library, picker and scope are all needed here
class PlaylistFileOps(
    private val app: Application,
    private val state: WinampState,
    private val facade: PlayerFacade,
    private val store: PlaylistStore,
    private val scope: CoroutineScope,
    private val library: MediaStoreAudio = MediaStoreAudio(app),
    private val mediaFiles: MediaFiles = MediaFiles(app, library),
    private val listsLibrary: PlaylistLibrary = PlaylistLibrary(java.io.File(app.filesDir, "playlists")),
    private val queue: PlaylistOps = PlaylistOps(state, facade),
    /** Where the file and library work happens. */
    private val io: kotlin.coroutines.CoroutineContext = Dispatchers.IO,
) {
    /** True when the device's audio library may be read; drives the permission prompt. */
    fun canReadLibrary() = library.hasPermission()

    /**
     * Re-finds restored rows whose uri does not open, once the library can be read.
     * Called on launch and whenever the permission is granted.
     */
    fun healRestoredEntries() {
        if (!library.hasPermission()) return
        scope.launch {
            // read from the backend: at launch this can run before the render state has
            // received the queue
            val before = facade.state.value.queue
            if (before.isEmpty()) return@launch
            val replacements =
                withContext(io) {
                    val healed =
                        PlaylistHealer.heal(
                            before,
                            playable = { uri -> mediaFiles.isAlive(Track("", "", "", 0, uri = uri)) },
                            lookUp = { name, durationMs -> library.findByName(name, durationMs) },
                        )
                    PlaylistHealer.replacements(before, healed)
                }
            if (replacements.isEmpty()) return@launch
            // applied to the queue as it is by then: tracks may have been added while the
            // lookups ran
            val live = facade.state.value
            val updated = PlaylistHealer.applyTo(live.queue, replacements)
            // only while stopped: replacing the queue mid-track would restart it
            if (updated != live.queue && state.transport == Transport.Stopped) {
                facade.setQueue(updated, live.currentIndex)
            }
        }
    }

    /**
     * ADD > FILE: reads tags off the main thread, then appends to the queue.
     *
     * [thenPlay] is for the file picker that Play opens on an empty playlist, where the
     * picked file should play. ADD > FILE passes false and leaves playback alone.
     */
    fun addAudio(
        uri: Uri,
        thenPlay: Boolean = false,
    ) = audio(uri, replace = false, thenPlay = thenPlay)

    /**
     * Winamp's Play file...: the queue becomes what was picked, and plays from the top.
     * ADD > FILE appends instead.
     */
    fun playAudio(uri: Uri) = audio(uri, replace = true, thenPlay = true)

    private fun audio(
        uri: Uri,
        replace: Boolean,
        thenPlay: Boolean,
    ) {
        scope.launch {
            // persist the grant first: the picker's grant ends with the process
            store.remember(uri)
            val track = withContext(io) { mediaFiles.readAdded(uri) }
            val base = if (replace) emptyList() else state.playlist
            val at = base.size
            facade.setQueue(base + track, if (thenPlay) at else state.currentIndex)
            if (thenPlay) facade.playAt(at)
        }
    }

    /**
     * ADD > DIR: every audio file in a picked folder, in the order a file manager shows
     * them, added at once and named by their files. Tags are read afterwards, one row at
     * a time.
     */
    fun addFolder(treeUri: Uri) = folder(treeUri, replace = false)

    /** Winamp's Play directory...: the folder becomes the queue and plays from the top. */
    fun playFolder(treeUri: Uri) = folder(treeUri, replace = true)

    private fun folder(
        treeUri: Uri,
        replace: Boolean,
    ) {
        scope.launch {
            store.rememberTree(treeUri)
            val tree = SafDocumentTree(app.contentResolver, treeUri)
            val scan = withContext(io) { FolderScan.audioFiles(tree, tree.rootId) }
            if (scan.dropped > 0) {
                Log.w(TAG, "Folder holds more than ${FolderScan.MAX_FILES} tracks; ${scan.dropped} left out")
            }
            if (scan.files.isEmpty()) return@launch

            // The whole folder is added at once, each row named by its file, because the
            // per-file tag read is slow.
            val arriving = FolderScan.arrivals(scan.files, System.nanoTime()) { tree.uriFor(it).toString() }
            val base = if (replace) emptyList() else state.playlist
            facade.setQueue(base + arriving)
            // Play directory... starts at the top of what was picked; ADD > DIR leaves the
            // player where it was
            if (replace) facade.playAt(0)

            // The tags are read one row at a time, so the list stays readable and playable
            // meanwhile.
            arriving.forEach { entry ->
                val uri = entry.uri ?: return@forEach
                // kept under the tree's uri, not the library's; see readInTree
                val read = withContext(io) { mediaFiles.readInTree(Uri.parse(uri)) }
                facade.patchTracks(listOf(read.copy(id = entry.id, defaultName = entry.defaultName)))
            }
        }
    }

    /** REM > MISC, Winamp's "Remove all dead files": drops entries whose media does not open. */
    fun removeDeadFiles() {
        scope.launch {
            val snapshot = state.playlist
            val dead = withContext(io) { snapshot.filterNot(mediaFiles::isAlive).map(Track::id).toSet() }
            queue.removeDead(dead)
        }
    }

    /**
     * LIST > SAVE LIST: asks where to save. In Andamp's own library the list is browsable
     * in the media library's LISTS tab and needs no picker; as a file it is an `.m3u`
     * other players can read.
     */
    fun saveList(exportTo: (String) -> Unit) {
        state.choiceSheet =
            ChoiceSheet(
                title = "Save playlist",
                options =
                    listOf(
                        ChoiceSheet.Choice(
                            "Save in Andamp",
                            "Browsable in the media library's LISTS tab",
                        ) { saveToLibrary() },
                        ChoiceSheet.Choice(
                            "Export as file…",
                            "An .m3u file other players can read",
                        ) { promptForName(exportTo) },
                    ),
            )
    }

    /** LIST > LOAD LIST: asks where to load from, with the same two places. */
    fun loadList(importFrom: () -> Unit) {
        state.choiceSheet =
            ChoiceSheet(
                title = "Load playlist",
                options =
                    listOf(
                        ChoiceSheet.Choice(
                            "Open from Andamp",
                            "One of the lists saved here",
                        ) { loadFromLibrary() },
                        ChoiceSheet.Choice(
                            "Import from file…",
                            "An .m3u file from this phone",
                        ) { importFrom() },
                    ),
            )
    }

    /** Asks for a name and passes it on, with the .m3u extension, to [onNamed]. */
    fun promptForName(onNamed: (String) -> Unit) {
        state.namePrompt =
            NamePrompt(
                title = "Save playlist as",
                initial = PlaylistNaming.fileName(state.playlist, LocalDate.now()).removeSuffix(PlaylistNaming.EXTENSION),
            ) { typed ->
                state.namePrompt = null
                onNamed(PlaylistNaming.withExtension(typed))
            }
    }

    /** Saves the queue as a named list in the app's own library. */
    fun saveToLibrary() {
        promptForName { named ->
            val tracks = state.playlist
            val name = named.removeSuffix(PlaylistNaming.EXTENSION).trim().ifEmpty { "Playlist" }
            scope.launch {
                withContext(io) { listsLibrary.save(name, tracks) }
            }
        }
    }

    /** Writes the queue as an .m3u to a document the listener picked. */
    fun exportTo(uri: Uri) {
        val tracks = state.playlist
        val index = state.currentIndex
        scope.launch {
            withContext(io) {
                runCatching {
                    app.contentResolver.openOutputStream(uri)?.let { out -> store.export(out, tracks, index) }
                }.onFailure { Log.w(TAG, "Could not export the playlist", it) }
            }
        }
    }

    /** Shows the lists saved in the app's library, to open one. The picker opens once they are read. */
    fun loadFromLibrary() {
        scope.launch {
            val saved = withContext(io) { listsLibrary.list() }
            state.presetPicker =
                PresetPicker(
                    title = "Open playlist",
                    entries = saved.map { PresetEntry(it.name, it.name, "${it.trackCount} TRK") },
                    confirmLabel = "Open",
                    emptyMessage = "No lists saved here yet",
                ) { keys ->
                    val name = keys.firstOrNull() ?: return@PresetPicker
                    scope.launch {
                        val tracks = withContext(io) { listsLibrary.load(name) }
                        if (!tracks.isNullOrEmpty()) facade.setQueue(tracks)
                    }
                }
        }
    }

    /**
     * LIST > LOAD LIST: replaces the queue with a picked .m3u.
     *
     * Entries the file names by path (as another player writes them) are listed but may
     * not open: scoped storage grants only the document that was picked.
     */
    fun import(uri: Uri) {
        scope.launch {
            val saved =
                withContext(io) {
                    runCatching {
                        app.contentResolver.openInputStream(uri)?.let { input ->
                            store.import(input)
                        }
                    }.onFailure { Log.w(TAG, "Could not load the playlist", it) }.getOrNull()
                }
            if (saved != null) facade.setQueue(saved.tracks, saved.currentIndex)
        }
    }

    private companion object {
        const val TAG = "PlaylistFileOps"
    }
}
