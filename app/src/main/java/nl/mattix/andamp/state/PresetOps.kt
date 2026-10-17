// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.mattix.andamp.state.presets.Favorite
import nl.mattix.andamp.state.presets.FavoriteStore
import nl.mattix.andamp.state.presets.InstalledPack
import nl.mattix.andamp.state.presets.PresetDetails
import nl.mattix.andamp.state.presets.PresetFingerprint
import nl.mattix.andamp.state.presets.PresetHealthStore
import nl.mattix.andamp.state.presets.PresetLibrary
import nl.mattix.andamp.state.presets.filesFor
import nl.mattix.andamp.state.presets.runsOn
import nl.mattix.andamp.state.presets.stillThere
import nl.mattix.andamp.visualizer.avs.AvsPresetCheck
import nl.mattix.andamp.visualizer.avs.author.AndAmpPack
import nl.mattix.andamp.visualizer.core.PresetGaps
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
 * An engine plays one source: its idle preset, one pack, or the listener's favorites, which
 * come from any pack and are played like one. A favorite ends with its pack.
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
    private val favoriteStore: FavoriteStore = FavoriteStore(context),
    private val healthStore: PresetHealthStore = PresetHealthStore(context),
    // injectable, so a test's virtual clock covers the disk work
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    var packs by mutableStateOf<List<InstalledPack>>(emptyList())
        private set

    var importing by mutableStateOf<PresetImport?>(null)
        private set

    /** The listener's favorites, both engines', in the order they were added. */
    var favorites by mutableStateOf<List<Favorite>>(emptyList())
        private set

    /**
     * What an engine could not show of each preset, by file: from playing it, for AVS from
     * checking a list ahead ([checkAhead]), and from earlier launches, found again by the
     * preset's content ([PresetHealthStore]).
     */
    val health: SnapshotStateMap<String, PresetGaps> = mutableStateMapOf()

    /**
     * The presets the engines step past on next, previous, shuffle and their own switching:
     * the ones that could not be read or drew only black. Picking one by hand tries it again.
     */
    val skipped: Set<String>
        get() = health.filterValues { it.unreadable || it.blank }.keys

    // what earlier launches learned, by content; the files' fingerprints, worked out once
    private var kept: Map<PresetFingerprint, PresetGaps> = emptyMap()
    private val fingerprints = HashMap<String, PresetFingerprint>()

    /** The presets projectM should be running, or null for its built-in idle preset. */
    val active: PresetPack?
        get() =
            if (state.presetFavorites) {
                val files = favoriteFiles(VisPlugin.Milkdrop)
                // the textures are looked up in every pack a favorite comes from
                val from = favoritesFor(VisPlugin.Milkdrop).map { it.pack }.toSet()
                val textures = packs.filter { it.name in from }.flatMap { pack -> pack.textureDirs.map { it.absolutePath } }
                PresetPack(presetsDir = "", textureDirs = textures.distinct(), files = files).takeIf { files.isNotEmpty() }
            } else {
                packs.firstOrNull { it.name == state.presetPack && it.presetCount > 0 }?.toPresetPack()
            }

    /** The pack AVS is running, where its presets' pictures are; null for its idle preset or the favorites. */
    val activeAvsDir: File?
        get() = if (state.presetFavorites) null else packs.firstOrNull { it.name == state.presetPack && it.avsCount > 0 }?.dir

    /**
     * The `.avs` files AVS should be running, in the order the selector lists them: its
     * favorites when they are on, otherwise [activeAvsDir]'s. Null for its idle preset.
     */
    val activeAvsFiles: List<String>?
        get() =
            if (state.presetFavorites) {
                favoriteFiles(VisPlugin.Avs)
            } else {
                packs.firstOrNull { it.name == state.presetPack && it.avsCount > 0 }?.avsFiles
            }

    /** Every pack AVS can run, which is where a favorite's pictures are looked up. */
    val avsRoots: List<File>
        get() = packsFor(VisPlugin.Avs).map { it.dir }

    /** [plugin]'s favorites. */
    fun favoritesFor(plugin: VisPlugin): List<Favorite> = favorites.filter { it.plugin == plugin }

    /** [plugin]'s favorites' files, in the order they were added; the list Favorites plays. */
    fun favoriteFiles(plugin: VisPlugin): List<String> = favoriteFilesByPlugin[plugin].orEmpty()

    /** Whether the preset at [path] is one of the current engine's favorites. */
    fun isFavorite(path: String?): Boolean = path != null && path in favoriteFiles(state.visPlugin)

    /** The presets of a source as the selector lists them: [pack]'s, or the favorites when it is null. */
    fun listing(pack: String?): List<String> =
        if (pack == null) {
            favoriteFiles(state.visPlugin)
        } else {
            packsFor(state.visPlugin).firstOrNull { it.name == pack }?.filesFor(state.visPlugin).orEmpty()
        }

    // the files, worked out once per change: the engines read the list on every recomposition,
    // and the selector's stars redraw when it changes
    private var favoriteFilesByPlugin by mutableStateOf<Map<VisPlugin, List<String>>>(emptyMap())

    /** Each engine remembers whether it was on its favorites. */
    private var favoritesOn: Set<VisPlugin> = emptySet()

    /** The packs [plugin] can run something from. */
    fun packsFor(plugin: VisPlugin): List<InstalledPack> = packs.filter { it.runsOn(plugin) }

    /** Each engine's remembered pack; [WinampState.presetPack] mirrors the current engine's. */
    private var packMemory: Map<VisPlugin, String?> = emptyMap()

    fun start() {
        val remembered = store.load()
        packMemory = remembered.packs
        favoritesOn = remembered.favorites
        favorites = favoriteStore.load()
        kept = healthStore.load()
        state.visPlugin = remembered.plugin
        state.presetPack = remembered.packFor(remembered.plugin)
        state.presetFavorites = remembered.plugin in favoritesOn
        state.presetShuffle = remembered.shuffle
        state.presetNotices = remembered.notices
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
        state.presetFavorites = plugin in favoritesOn
        state.presetName = null
        state.presetPath = null
        state.presetGaps = PresetGaps.NONE
        state.presetNames = emptyList()
        state.presetPaths = emptyList()
        remember()
        scope.launch { lock.withLock { refresh() } }
    }

    fun setShuffle(shuffle: Boolean) {
        state.presetShuffle = shuffle
        remember()
    }

    /** Whether the window says what a preset is missing. */
    fun setNotices(notices: Boolean) {
        state.presetNotices = notices
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
        // the source playing already: what is showing stays, and so does its name
        if (name == state.presetPack && !state.presetFavorites) return
        state.presetPack = name
        state.presetFavorites = false
        favoritesOn = favoritesOn - state.visPlugin
        state.presetName = null
        state.presetPath = null
        state.presetGaps = PresetGaps.NONE
        packMemory = packMemory + (state.visPlugin to name)
        remember()
    }

    /** Plays the current engine's favorites, from every pack, as one list; nothing happens while it has none. */
    fun selectFavorites() {
        if (state.presetFavorites || favoriteFiles(state.visPlugin).isEmpty()) return
        state.presetFavorites = true
        favoritesOn = favoritesOn + state.visPlugin
        state.presetName = null
        state.presetPath = null
        state.presetGaps = PresetGaps.NONE
        remember()
    }

    /**
     * Marks the preset at [path] as a favorite of the current engine, or unmarks it. Unmarking
     * the last one while the favorites play goes back to the engine's pack.
     */
    fun toggleFavorite(path: String) {
        val plugin = state.visPlugin
        val existing = favoritesFor(plugin).firstOrNull { it.file(packs)?.absolutePath == path }
        val next =
            if (existing != null) {
                favorites - existing
            } else {
                favorites + listOfNotNull(Favorite.of(plugin, File(path), packsFor(plugin)))
            }
        takeFavorites(next)
        if (state.presetFavorites && favoriteFiles(plugin).isEmpty()) select(state.presetPack)
    }

    /**
     * Plays the preset at [path] from the list it was picked in: the favorites when
     * [fromFavorites], the pack it is in otherwise. When that list is not the one playing, it is
     * loaded first and the engine goes to the preset once it has it.
     */
    fun play(
        path: String,
        fromFavorites: Boolean,
    ) {
        // picked by hand: what was learned about it is forgotten, and it is tried again
        forget(path)
        val pack = packsFor(state.visPlugin).firstOrNull { path.startsWith(it.dir.absolutePath + File.separator) }
        val playing = if (fromFavorites) state.presetFavorites else !state.presetFavorites && state.presetPack == pack?.name
        val index = state.presetPaths.indexOf(path)
        if (playing && index >= 0) {
            state.visualCommands?.goToPreset(index)
            return
        }
        state.presetWanted = path
        if (fromFavorites) selectFavorites() else select(pack?.name)
    }

    /**
     * What the engine said it could not show of the preset at [path], kept across launches
     * when it is anything; null is its idle preset, which is not kept.
     */
    fun record(
        path: String?,
        gaps: PresetGaps,
    ) {
        if (path == null) return
        val before = health.put(path, gaps)
        if ((before ?: PresetGaps.NONE) != gaps) keep(path, gaps)
    }

    /** What the preset at [path] is made of, for its page; reads the file, so not on the main thread. */
    fun describe(path: String): PresetDetails = PresetDetails.read(path, packs)

    /** Forgets what is known of the preset at [path], here and across launches. */
    fun forget(path: String) {
        if (health.remove(path) != null) keep(path, PresetGaps.NONE)
    }

    private fun keep(
        path: String,
        gaps: PresetGaps,
    ) {
        scope.launch {
            val fingerprint = fingerprintOf(path) ?: return@launch
            val next = if (gaps.none) kept - fingerprint else kept + (fingerprint to gaps)
            if (next == kept) return@launch
            kept = next
            withContext(io) { healthStore.save(next) }
        }
    }

    // a file that went away has no fingerprint, and nothing is kept for it
    private suspend fun fingerprintOf(path: String): PresetFingerprint? =
        fingerprints[path] ?: withContext(io) {
            try {
                PresetFingerprint.of(File(path))
            } catch (e: java.io.IOException) {
                Log.w(TAG, "cannot fingerprint $path", e)
                null
            }
        }?.also { fingerprints[path] = it }

    /**
     * Finds what earlier launches learned in the packs installed now. Only files of a size
     * something was kept for are read, so a pack of thousands costs a directory listing.
     */
    private suspend fun restoreKept() {
        if (kept.isEmpty()) return
        val sizes = kept.keys.mapTo(HashSet()) { it.size }
        val found =
            withContext(io) {
                packs.flatMap { it.milkFiles + it.avsFiles }.mapNotNull { path ->
                    val file = File(path)
                    if (file.length() !in sizes) return@mapNotNull null
                    try {
                        path to PresetFingerprint.of(file)
                    } catch (e: java.io.IOException) {
                        Log.w(TAG, "cannot fingerprint $path", e)
                        null
                    }
                }
            }
        found.forEach { (path, fingerprint) ->
            fingerprints[path] = fingerprint
            kept[fingerprint]?.let { gaps -> if (path !in health) health[path] = gaps }
        }
    }

    private var checking: Job? = null

    /**
     * Learns ahead what AVS would leave out of each of [paths], so a list can show it before a
     * preset is picked. Off the main thread, one preset at a time, and only for presets not
     * known yet; a newer list replaces the one being checked. projectM cannot do this: it needs
     * a GL context, so it reports what it tried while playing.
     */
    fun checkAhead(paths: List<String>) {
        if (state.visPlugin != VisPlugin.Avs) return
        val todo = paths.filter { it !in health }
        checking?.cancel()
        if (todo.isEmpty()) return
        checking =
            scope.launch {
                val job = coroutineContext[Job]
                withContext(io) {
                    AvsPresetCheck.check(
                        todo,
                        onChecked = { path, gaps -> scope.launch { if (path !in health) record(path, gaps) } },
                        keepGoing = { job?.isActive != false },
                    )
                }
            }
    }

    /** Takes [next] as the favorites, and works their files out again from the packs installed now. */
    private fun takeFavorites(next: List<Favorite>) {
        if (next != favorites) favoriteStore.save(next)
        favorites = next
        favoriteFilesByPlugin =
            VisPlugin.entries.associateWith { plugin ->
                favoritesFor(plugin).mapNotNull { it.file(packs)?.absolutePath }
            }
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
        // a favorite whose pack or file is gone goes with it
        takeFavorites(favorites.stillThere(packs))
        // favorites with none left go back to the engine's pack; a selected pack that was
        // deleted, or that the current engine cannot run, is deselected
        if (state.presetFavorites && favoriteFiles(state.visPlugin).isEmpty()) select(state.presetPack)
        if (state.presetPack != null && packsFor(state.visPlugin).none { it.name == state.presetPack }) select(null)
        restoreKept()
    }

    private fun remember() =
        store.save(
            VisualizerMemory(
                plugin = state.visPlugin,
                packs = packMemory + (state.visPlugin to state.presetPack),
                shuffle = state.presetShuffle,
                notices = state.presetNotices,
                favorites = favoritesOn,
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

/** What projectM plays from this pack: its files by name, the order the selector lists them. */
fun InstalledPack.toPresetPack() = PresetPack(dir.absolutePath, textureDirs.map { it.absolutePath }, files = milkFiles)
