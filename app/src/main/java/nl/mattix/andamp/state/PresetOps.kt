// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.mattix.andamp.state.presets.InstalledPack
import nl.mattix.andamp.state.presets.PresetLibrary
import nl.mattix.andamp.state.presets.runsOn
import nl.mattix.andamp.visualizer.avs.author.AndAmpPack
import nl.mattix.andamp.visualizer.projectm.PresetPack
import java.io.File
import java.io.InputStream

/** What an import is doing, for the progress the window shows. */
data class PresetImport(
    val name: String,
    val filesWritten: Int,
    val failed: Boolean = false,
)

/**
 * The visualizer preset packs (`.milk` for Milkdrop, `.avs` for AVS): importing them,
 * listing them, and remembering which one is chosen for each engine.
 *
 * An import can be thousands of files, so it runs off the main thread and reports
 * progress. A failed import leaves the previous choice alone.
 */
class PresetOps(
    context: Context,
    private val state: WinampState,
    private val scope: CoroutineScope,
    private val library: PresetLibrary = PresetLibrary(File(context.filesDir, PRESETS_DIR).apply { mkdirs() }),
    private val store: VisualizerStore = VisualizerStore(context),
    // injectable, so a test's virtual clock covers the disk work
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    var packs by mutableStateOf<List<InstalledPack>>(emptyList())
        private set

    var importing by mutableStateOf<PresetImport?>(null)
        private set

    /** The pack projectM should be running, or null for its built-in idle preset. */
    val active: PresetPack?
        get() = packs.firstOrNull { it.name == state.presetPack && it.presetCount > 0 }?.toPresetPack()

    /** The directory of `.avs` files AVS should be running, or null for its idle preset. */
    val activeAvsDir: File?
        get() = packs.firstOrNull { it.name == state.presetPack && it.avsCount > 0 }?.dir

    /** The packs [plugin] can run something from. */
    fun packsFor(plugin: VisPlugin): List<InstalledPack> = packs.filter { it.runsOn(plugin) }

    /** Each engine's remembered pack; [WinampState.presetPack] mirrors the current engine's. */
    private var packMemory: Map<VisPlugin, String?> = emptyMap()

    fun start() {
        val remembered = store.load()
        packMemory = remembered.packs
        state.visPlugin = remembered.plugin
        state.presetPack = remembered.packFor(remembered.plugin)
        state.presetShuffle = remembered.shuffle
        scope.launch {
            lock.withLock {
                installBundled()
                refresh()
            }
        }
    }

    /**
     * Installs the pack the app ships ([AndAmpPack]) into the library like any import. On
     * the first install it becomes AVS's pack, unless AVS already has a remembered one.
     */
    private suspend fun installBundled() {
        val first = withContext(io) { library.install(AndAmpPack.NAME, AndAmpPack.files(), AndAmpPack.VERSION) }
        if (!first || packMemory[VisPlugin.Avs] != null) return
        packMemory = packMemory + (VisPlugin.Avs to AndAmpPack.NAME)
        if (state.visPlugin == VisPlugin.Avs) state.presetPack = AndAmpPack.NAME
        remember()
    }

    /** Winamp's "Select plug-in". The choice is stored, with the pack each engine was on. */
    fun selectPlugin(plugin: VisPlugin) {
        if (plugin == state.visPlugin) return
        packMemory = packMemory + (state.visPlugin to state.presetPack)
        state.visPlugin = plugin
        state.presetPack = packMemory[plugin]
        state.presetName = null
        state.presetNames = emptyList()
        remember()
        scope.launch { lock.withLock { refresh() } }
    }

    fun setShuffle(shuffle: Boolean) {
        state.presetShuffle = shuffle
        remember()
    }

    /**
     * Winamp's Start/Stop plug-in, and the window's close button. It only sets the flag;
     * whether a window is open is persisted by [WindowStore].
     */
    fun setWindowOpen(open: Boolean) {
        state.milkdropOn = open
    }

    // a picked zip can fail in any way; the failure is shown as a failed import
    @Suppress("TooGenericExceptionCaught")
    fun import(
        stream: InputStream,
        name: String,
    ) {
        scope.launch {
            lock.withLock {
                importing = PresetImport(name, 0)
                val imported =
                    withContext(io) {
                        try {
                            stream.use { library.importZip(it, name) { written -> report(name, written) } }
                        } catch (e: Exception) {
                            Log.w(TAG, "preset pack \"$name\" would not import", e)
                            null
                        }
                    }
                // the import succeeded if any engine can run the pack
                if (imported == null || VisPlugin.entries.none { imported.runsOn(it) }) {
                    importing = PresetImport(name, 0, failed = true)
                } else {
                    importing = null
                    // selected only when the current engine can run it
                    if (imported.runsOn(state.visPlugin)) select(imported.name)
                }
                refresh()
            }
        }
    }

    fun select(name: String?) {
        state.presetPack = name
        state.presetName = null
        packMemory = packMemory + (state.visPlugin to name)
        remember()
    }

    fun remove(pack: InstalledPack) {
        scope.launch {
            lock.withLock {
                withContext(io) { library.delete(pack) }
                if (state.presetPack == pack.name) select(null)
                refresh()
            }
        }
    }

    private fun report(
        name: String,
        written: Int,
    ) {
        // one state write per PROGRESS_EVERY files, to limit recompositions
        if (written % PROGRESS_EVERY != 0) return
        scope.launch { importing = PresetImport(name, written) }
    }

    private suspend fun refresh() {
        packs = withContext(io) { library.packs() }
        // a selected pack that was deleted, or that the current engine cannot run, is
        // deselected
        if (state.presetPack != null && packsFor(state.visPlugin).none { it.name == state.presetPack }) select(null)
    }

    private fun remember() =
        store.save(
            VisualizerMemory(
                plugin = state.visPlugin,
                packs = packMemory + (state.visPlugin to state.presetPack),
                shuffle = state.presetShuffle,
            ),
        )

    // serializes startup, imports, removals and refreshes
    private val lock = Mutex()

    private companion object {
        const val TAG = "PresetOps"
        const val PRESETS_DIR = "presets"
        const val PROGRESS_EVERY = 100
    }
}

fun InstalledPack.toPresetPack() = PresetPack(dir.absolutePath, textureDirs.map { it.absolutePath })
