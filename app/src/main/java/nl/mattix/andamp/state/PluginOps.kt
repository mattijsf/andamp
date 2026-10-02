// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.mattix.andamp.core.player.PlayerFacade
import nl.mattix.andamp.core.plugin.PluginLoader
import java.io.InputStream
import kotlin.coroutines.CoroutineContext

/**
 * Installs and removes plug-ins.
 *
 * A plug-in is loaded before it is stored. The loader is the sandbox, the size cap and the
 * deadline (docs/dsp-plugin-spec.md section 8), and a file it rejects is not written to
 * disk; the reason is shown to the listener in [problem].
 *
 * Removing a plug-in also removes its settings, through [DspOps.remove], so a plug-in
 * installed again starts from its defaults.
 */
class PluginOps(
    context: Context,
    private val facade: PlayerFacade,
    private val dsp: DspOps,
    private val scope: CoroutineScope,
    /** Where the file and the Lua are handled. */
    private val io: CoroutineContext = Dispatchers.IO,
    /** Fetches a plug-in link; see [PluginLinks.download]. */
    private val download: (String) -> String = PluginLinks::download,
) {
    private val library = PluginLibrary(context)
    private val lock = Mutex()

    /** What is installed, for the page that lists it. */
    var entries: List<PluginEntry> by mutableStateOf(emptyList())
        private set

    /** Why the last install was refused, or null when nothing is wrong. */
    var problem: String? by mutableStateOf(null)
        private set

    /**
     * What the last install did, until the listener has read it. It is reported for a
     * picked file and for a .lua handed over by another app alike, because the plug-in
     * lands elsewhere: at the end of the rack, switched off, or in the place of the one it
     * replaced.
     */
    var added: Added? by mutableStateOf(null)
        private set

    /** The result of a successful install. */
    sealed interface Added {
        val entry: PluginEntry

        /** A plug-in that was not installed: last in the rack, switched off. */
        data class New(
            override val entry: PluginEntry,
        ) : Added

        /** Another version of an installed one: it keeps that one's place and settings. */
        data class Updated(
            val previous: PluginEntry,
            override val entry: PluginEntry,
        ) : Added

        /** The same file that is already installed; nothing changed. */
        data class Unchanged(
            override val entry: PluginEntry,
        ) : Added
    }

    /**
     * A plug-in a link offered, loaded and waiting for the listener to press Add. The
     * dialog shows the plug-in's own name, version and author, and the site it came from.
     */
    var offer: Offer? by mutableStateOf(null)
        private set

    /** True while a linked plug-in is being fetched. */
    var fetching: Boolean by mutableStateOf(false)
        private set

    class Offer(
        val entry: PluginEntry,
        /** The site it was fetched from, as the dialog names it. */
        val from: String,
        internal val source: String,
    )

    /** Fetches what a plug-in link points at and offers it, or sets [problem]. */
    fun offerFrom(url: String) {
        offer = null
        added = null
        problem = null
        fetching = true
        scope.launch {
            val outcome =
                withContext(io) {
                    runCatching { download(url) }
                        .fold(
                            onSuccess = { offered(it, url) },
                            onFailure = { Refused("it could not be downloaded: ${whyUnreadable(it)}") },
                        )
                }
            fetching = false
            problem = (outcome as? Refused)?.reason
            offer = (outcome as? Offered)?.offer
        }
    }

    /** Add, on the offer: the same install a picked file gets, including the replacement question. */
    fun acceptOffer() {
        val waiting = offer ?: return
        offer = null
        install(waiting.source.byteInputStream())
    }

    fun declineOffer() {
        offer = null
    }

    /** The offer for [source] when it would install; otherwise the refusal. */
    private fun offered(
        source: String,
        url: String,
    ): Outcome {
        val loaded = PluginLoader().load(source, RATES.first(), CHANNELS)
        if (loaded is PluginLoader.Result.Rejected) {
            return Refused(loaded.errors.joinToString("; ") { "${it.code}: ${it.message}" })
        }
        val plugin = (loaded as PluginLoader.Result.Loaded).plugin
        return elsewhere(source) ?: taken(plugin) ?: Offered(
            Offer(
                library.entryFor(source, plugin),
                java.net
                    .URI(url)
                    .host
                    .orEmpty(),
                source,
            ),
        )
    }

    /** The listener has read the result or the refusal. */
    fun seen() {
        added = null
        problem = null
    }

    /** A file handed over that could not be opened: reported like a refusal. Nothing was installed. */
    fun unreadable(why: String) {
        added = null
        replacement = null
        problem = why
    }

    /**
     * True between handing the backend a changed set of plug-ins and its answer. A
     * plug-in's script is loaded on a worker, so for a moment the list has a plug-in the
     * rack does not; the page does not report that as a refusal while this is true.
     */
    var waiting: Boolean by mutableStateOf(false)
        private set

    /** The backend has answered. */
    fun settled() {
        waiting = false
    }

    /**
     * An install waiting for an answer, because it would replace an installed plug-in.
     *
     * A different file claiming an installed plug-in's id is treated as another version: it
     * takes over that plug-in's place in the rack and its settings. The listener is asked
     * first, and shown both entries.
     */
    var replacement: Replacement? by mutableStateOf(null)
        private set

    /** A plug-in about to be replaced by another file claiming to be it. */
    class Replacement(
        val installed: PluginEntry,
        val incoming: PluginEntry,
        internal val source: String,
    )

    /** Hands the backend the installed plug-ins. Called once at startup, before the rack is pushed. */
    fun start() {
        scope.launch {
            val stored = withContext(io) { lock.withLock { library.list() to library.sources() } }
            entries = stored.first
            facade.setPlugins(stored.second)
            dsp.refresh()
        }
    }

    /** Installs what [input] holds. The name shown is the plug-in's own, not the file's. */
    fun install(input: InputStream) {
        scope.launch {
            val outcome =
                withContext(io) {
                    lock.withLock {
                        // read whole, but only up to the loader's cap
                        runCatching { input.use { it.readAtMost(MAX_SOURCE_BYTES).decodeToString() } }
                            .fold(onSuccess = { store(it) }, onFailure = { Refused(whyUnreadable(it)) })
                    }
                }
            problem = (outcome as? Refused)?.reason
            replacement = (outcome as? Conflict)?.replacement
            added = null
            if (outcome is Installed) published(outcome)
        }
    }

    /** Goes ahead with the install the listener was asked about. */
    fun replace() {
        val waiting = replacement ?: return
        replacement = null
        scope.launch {
            val outcome =
                withContext(io) {
                    lock.withLock {
                        // the file being replaced is deleted first, so the plug-in is not
                        // installed twice under two hashes
                        library.delete(waiting.installed.id)
                        store(waiting.source, replacing = waiting.installed)
                    }
                }
            problem = (outcome as? Refused)?.reason
            if (outcome is Installed) published(outcome)
        }
    }

    /** Leaves what is installed alone. */
    fun keep() {
        replacement = null
    }

    private fun published(outcome: Installed) {
        added = outcome.added
        // the same file again changes nothing for the backend
        if (outcome.added is Added.Unchanged) return
        waiting = true
        entries = outcome.entries
        facade.setPlugins(outcome.sources)
        dsp.refresh()
    }

    fun remove(id: String) {
        scope.launch {
            val after =
                withContext(io) {
                    lock.withLock {
                        val pluginId = library.pluginIdOf(id)
                        library.delete(id)
                        pluginId to (library.list() to library.sources())
                    }
                }
            // the rack's settings for it are removed with the file
            after.first?.let { dsp.remove(it) }
            waiting = true
            entries = after.second.first
            facade.setPlugins(after.second.second)
            dsp.refresh()
            problem = null
        }
    }

    private fun store(
        source: String,
        replacing: PluginEntry? = null,
    ): Outcome {
        val loaded = PluginLoader().load(source, RATES.first(), CHANNELS)
        if (loaded is PluginLoader.Result.Rejected) {
            return Refused(loaded.errors.joinToString("; ") { "${it.code}: ${it.message}" })
        }
        // logged because load time depends on the device, and the loader has a deadline
        Log.i(TAG, "loaded '${(loaded as PluginLoader.Result.Loaded).plugin.name}' in ${loaded.millis} ms")
        return elsewhere(source) ?: taken(loaded.plugin) ?: keep(loaded.plugin, source, replacing)
    }

    /** Stores it, or returns a [Conflict] when another file is installed under the same plug-in id. */
    private fun keep(
        plugin: nl.mattix.andamp.core.plugin.PluginSpec,
        source: String,
        replacing: PluginEntry?,
    ): Outcome {
        val here = library.list().firstOrNull { it.pluginId == plugin.id }
        // the same file again is stored under the same hash and is not a replacement
        val same = here != null && here.id == library.idFor(source)
        if (replacing == null && here != null && !same) {
            return Conflict(Replacement(here, library.entryFor(source, plugin), source))
        }
        return runCatching {
            val entry = library.save(source, plugin)
            val added =
                when {
                    replacing != null -> Added.Updated(replacing, entry)
                    same -> Added.Unchanged(entry)
                    else -> Added.New(entry)
                }
            Installed(library.list(), library.sources(), added)
        }.getOrElse { Refused(it.message ?: "could not be stored") }
    }

    /**
     * Refuses a plug-in that claims the id of an effect the app ships. The rack is keyed by
     * id, so such a plug-in would be installed and never appear in the rack.
     */
    private fun taken(plugin: nl.mattix.andamp.core.plugin.PluginSpec): Refused? =
        if (plugin.id in facade.takenEffectIds) {
            Refused("'${plugin.id}' is an effect Andamp already has; a plug-in cannot take the name of one that ships")
        } else {
            null
        }

    /**
     * Refuses a plug-in that does not load at the other rates in [RATES]. A graph is built
     * for one rate, and a plug-in can load at one and fail at another (a delay sized from
     * the sample rate, for example), so this is checked at install.
     */
    private fun elsewhere(source: String): Refused? {
        RATES.drop(1).forEach { rate ->
            val at = PluginLoader().load(source, rate, CHANNELS)
            if (at is PluginLoader.Result.Rejected) {
                return Refused(
                    "at $rate Hz it will not run: " +
                        at.errors.joinToString("; ") { "${it.code}: ${it.message}" },
                )
            }
        }
        return null
    }

    private sealed interface Outcome

    private class Installed(
        val entries: List<PluginEntry>,
        val sources: List<String>,
        val added: Added,
    ) : Outcome

    private class Refused(
        val reason: String,
    ) : Outcome

    private class Offered(
        val offer: Offer,
    ) : Outcome

    private class Conflict(
        val replacement: Replacement,
    ) : Outcome

    /**
     * Why a file could not be read, in the words shown to the listener. Too large is
     * reported as the format's size limit; anything else uses the exception's message.
     */
    private fun whyUnreadable(failure: Throwable): String =
        if (failure is StreamTooLarge) {
            "a plug-in may not be larger than ${failure.max / 1024} KB"
        } else {
            failure.message ?: "unreadable"
        }

    internal companion object {
        private const val TAG = "AndAmpPlugins"

        /** The most that is read of a plug-in file. */
        const val MAX_SOURCE_BYTES = 256 * 1024

        /**
         * The sample rates a plug-in must load at before it may be installed. The backend
         * builds the graph again for the rate of each stream.
         */
        val RATES = listOf(44_100, 48_000, 22_050)
        const val CHANNELS = 2
    }
}
