// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.mattix.andamp.skin.BundledSkins
import nl.mattix.andamp.skin.LiveSkins
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SkinLoader
import nl.mattix.andamp.widget.MainWindowWidget
import java.io.InputStream

/**
 * Which skin is on screen: loading a picked .wsz, keeping the library, and switching
 * between stored skins.
 *
 * A skin that parses is applied even if it cannot be stored, and a stored skin that does
 * not parse falls back to the bundled one.
 */
class SkinOps(
    private val context: Context,
    private val state: WinampState,
    private val scope: CoroutineScope,
    private val library: SkinLibrary = SkinLibrary(context),
    /** The skins that follow the phone's palette. */
    private val live: LiveSkins = CurrentSkin.live,
) {
    var skin by mutableStateOf<Skin?>(null)
        private set

    /**
     * The id of the skin on screen, for the manager's highlight and the Skins menu's tick.
     * While a music source's skin is worn, it is that skin's id.
     */
    var currentId by mutableStateOf(SkinEntry.BASE_ID)
        private set

    /** The bundled skin, which a skin missing a sheet borrows from. */
    var baseSkin: Skin? = null
        private set

    // serializes library writes, so two picks, or a pick and a removal, do not interleave
    private val lock = Mutex()

    /** The palette stamp the base was last built from, so an unchanged palette is not rebuilt. */
    private var lastStamp: IntArray? = null

    /** Completed when the base is decoded and the worn skin is on. */
    private val started = CompletableDeferred<Unit>()

    /** The last [wear] still loading; a newer one replaces it and a choice by the listener cancels it. */
    private var wearingJob: Job? = null

    /**
     * Decodes the bundled skin, then whatever is worn: a music source's skin if one was
     * on, the listener's own choice otherwise.
     *
     * If the bundled skin does not decode, the player has no skin and a warning is
     * logged; nothing is thrown.
     *
     * Listening for palette changes starts before the palette is first read, so a change
     * cannot fall between the two. On a fresh install the system applies the package's
     * color overlays shortly after installing it, as a configuration change. See
     * [paletteChanged], which waits for this to finish.
     */
    fun start() {
        listenForPaletteChanges()
        scope.launch {
            val base =
                withContext(Dispatchers.IO) {
                    lastStamp = live.stamp(context, BundledSkins.BASE)
                    runCatching { CurrentSkin.base(context, live) }.getOrNull()
                }
            if (base == null) {
                Log.w(TAG, "the bundled skin would not decode; there is nothing to draw with")
                return@launch
            }
            baseSkin = base
            val (worn, id) = withContext(Dispatchers.IO) { lock.withLock { loadWorn() } }
            skin = worn ?: base
            currentId = id
            refreshEntries()
            started.complete(Unit)
        }
    }

    /**
     * The phone's palette may have changed: rebuilds what was built from it. The base
     * first, because everything else borrows from it, then whatever is worn. The stamp
     * tells whether the palette changed at all (an orientation change arrives here too).
     * The listener's choice is not changed. A source's skin that does not open is taken
     * off.
     *
     * It waits for [start] to finish, so a change during the first build is applied over
     * that build's result and not overwritten by it.
     */
    fun paletteChanged() {
        scope.launch {
            started.await()
            val worn =
                withContext(Dispatchers.IO) {
                    lock.withLock {
                        val stamp = live.stamp(context, BundledSkins.BASE)
                        if (stamp.contentEquals(lastStamp)) return@withLock null
                        lastStamp = stamp
                        val base = runCatching { CurrentSkin.base(context, live) }.getOrNull() ?: return@withLock null
                        baseSkin = base
                        loadWorn()
                    }
                } ?: return@launch
            skin = worn.first
            currentId = worn.second
            wornSkinChanged()
        }
    }

    /**
     * A wallpaper change, or dynamic color switched on or off, arrives as a configuration
     * change. The callback is registered on the application and never removed, because
     * the view model that owns this outlives every activity (see [AppViewModels]).
     */
    private fun listenForPaletteChanges() {
        context.applicationContext.registerComponentCallbacks(
            object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) = paletteChanged()

                @Deprecated("the platform's own; nothing here to drop")
                override fun onLowMemory() = Unit
            },
        )
    }

    // a broken skin file can throw anything; the current skin is kept on any failure
    @Suppress("TooGenericExceptionCaught")
    fun load(
        input: InputStream,
        name: String,
        /**
         * Called with the library id the bytes were stored under, or null when they were
         * not a skin or could not be stored.
         */
        onStored: (String?) -> Unit = {},
    ) {
        choosing()
        scope.launch {
            val loaded =
                withContext(Dispatchers.IO) {
                    lock.withLock {
                        try {
                            // bounded read: the picker filter is */* (.wsz has no MIME
                            // type), so a mispicked large file fails with an IOException,
                            // which the catch below handles
                            val bytes = input.use { it.readAtMost(MAX_SKIN_BYTES) }
                            val parsed = SkinLoader.load(bytes.inputStream(), fallback = baseSkin, name = name)
                            parsed to store(bytes, name)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to load skin $name", e)
                            null
                        }
                    }
                }
            onStored(loaded?.second)
            if (loaded == null) {
                // not a skin, so nothing was chosen: what the library says is worn stays
                // on, including a source's skin that went on meanwhile
                wearWorn()
                return@launch
            }
            skin = loaded.first
            wornSkinChanged()
            // a skin that applied but could not be stored has no library row, so no row
            // is highlighted
            currentId = loaded.second ?: UNSTORED_ID
            refreshEntries()
        }
    }

    /**
     * Applies the skin at [index] of the listed entries.
     *
     * The skin already on screen is not loaded again, but it is still chosen: if it was
     * on only as a music source's skin, it becomes the listener's own.
     */
    fun applyAt(index: Int) {
        val entry = state.skinEntries.getOrNull(index) ?: return
        if (entry.id == currentId) {
            adopt(entry.id)
            return
        }
        if (entry.id == SkinEntry.BASE_ID) {
            reset()
            return
        }
        choosing()
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { lock.withLock { loadStored(entry)?.also { library.choose(entry.id) } } }
            if (loaded == null) {
                // a stored skin that does not load changes nothing
                wearWorn()
                return@launch
            }
            skin = loaded
            currentId = entry.id
            wornSkinChanged()
        }
    }

    /** Removes the skin at [index] from the library. */
    fun removeAt(index: Int) {
        val entry = state.skinEntries.getOrNull(index) ?: return
        remove(entry.id)
    }

    /** Called with the id when a skin leaves the library, so the museum browser can drop its record. */
    var onRemoved: ((String) -> Unit)? = null

    /**
     * Removes the skin [id] from the library. A removal is not a choice, so a source's
     * skin that is loading carries on, unless the removed skin was on; the library counts
     * that case itself.
     */
    fun remove(id: String) {
        val entry = SkinEntry(id, library.nameOf(id), 0)
        if (BundledSkins.of(entry.id) != null) return // a skin that ships with the app cannot be removed
        scope.launch {
            // under the lock, so the delete runs after an apply in flight has written its
            // choice
            withContext(Dispatchers.IO) { lock.withLock { library.delete(entry.id) } }
            // the library has moved both records off the removed id; what it says is worn
            // goes on if the removed skin was on screen. The widget is refreshed after the
            // delete, so it does not redraw the removed skin
            wearWorn()
            onRemoved?.invoke(entry.id)
            refreshEntries()
        }
    }

    /** Main menu > Skins > AndAmp Dark. The library keeps the files; only the choice changes. */
    fun reset() {
        choosing()
        skin = baseSkin
        currentId = SkinEntry.BASE_ID
        // the widget reads the worn skin from the library, so it is refreshed after the
        // write; applyAt awaits its write for the same reason
        scope.launch {
            withContext(Dispatchers.IO) { lock.withLock { library.choose(SkinEntry.BASE_ID) } }
            wornSkinChanged()
        }
    }

    /** Chooses the skin already on screen; see [applyAt]. Nothing is loaded, only the record changes. */
    private fun adopt(id: String) {
        choosing()
        scope.launch {
            // if a removal ran first, there is nothing to choose
            val chosen = withContext(Dispatchers.IO) { lock.withLock { library.has(id).also { if (it) library.choose(id) } } }
            if (chosen) wornSkinChanged() else wearWorn()
        }
    }

    /**
     * The listener is choosing a skin, which overrides any music source's skin requested
     * before this, whether or not the choice loads.
     *
     * The [wear] still loading is cancelled, and [SkinLibrary.choosing] makes the library
     * turn down the same request from the home screen widget's follower. A choice that
     * fails puts back on whatever the library holds.
     */
    private fun choosing() {
        wearingJob?.cancel()
        SkinLibrary.choosing()
    }

    /** Tells the home screen widget that the worn skin changed, so it redraws the whole window. */
    private fun wornSkinChanged() {
        MainWindowWidget.refresh(context)
    }

    // a failure to store is logged and the skin is still applied
    @Suppress("TooGenericExceptionCaught")
    private fun store(
        bytes: ByteArray,
        name: String,
    ): String? {
        val id =
            try {
                library.save(bytes, name).id
            } catch (e: Exception) {
                Log.w(TAG, "Skin '$name' applied but not stored", e)
                null
            }
        // a picked skin is the listener's own choice. One that could not be stored leaves
        // their stored choice as it was, and still overrides a source's skin
        library.choose(id ?: library.currentId)
        return id
    }

    private suspend fun refreshEntries() {
        state.skinEntries = withContext(Dispatchers.IO) { library.list() }
    }

    // CurrentSkin holds the fallback order, shared with the home screen widget
    private fun loadStored(entry: SkinEntry): Skin? = CurrentSkin.stored(context, library, entry.id, baseSkin, live)

    /**
     * Loads what the library says is worn, and returns it with the id to highlight. Call
     * under the lock: taking off a source's skin writes.
     */
    private fun loadWorn(): Pair<Skin?, String> {
        // loops, because a source's skin that does not load is taken off and the
        // listener's own is tried next
        while (true) {
            val over = library.wearing
            val id = over ?: library.currentId
            val loaded = if (id == SkinEntry.BASE_ID) baseSkin else CurrentSkin.stored(context, library, id, baseSkin, live)
            when {
                loaded != null -> return loaded to id

                // the listener's own does not load: the base is on screen and is highlighted
                over == null -> return baseSkin to SkinEntry.BASE_ID

                // a source's skin that does not load is taken off, and their own is tried
                else -> library.unwear(over)
            }
        }
    }

    /**
     * Wears a music source's skin over the listener's own while its track is the current
     * one; null wears their own again. [SourceSkins.wornFor] decides which skin is asked
     * for.
     *
     * The listener's choice is unchanged. The Skins menu ticks the skin on screen, and
     * picking it there makes it their own; see [applyAt]. A skin that is gone is not
     * worn: their own goes back on.
     *
     * The request is stored and loaded under the same lock as the listener's own actions,
     * and is turned down if they chose a skin since it was made. Whether anything is
     * loaded depends on what is on screen, not on what is stored, because the widget's
     * follower may have stored the same request already.
     */
    fun wear(sourceSkin: String?) {
        wearingJob?.cancel()
        val asked = SkinLibrary.choices
        wearingJob =
            scope.launch {
                started.await()
                val onScreen = currentId
                val worn =
                    withContext(Dispatchers.IO) {
                        lock.withLock {
                            val id = library.wear(sourceSkin, asked) ?: return@withLock null
                            if (id == onScreen) null else loadWorn()
                        }
                    } ?: return@launch
                // a choice made while this loaded takes precedence
                if (SkinLibrary.choices != asked) return@launch
                skin = worn.first
                currentId = worn.second
                wornSkinChanged()
            }
    }

    /**
     * Puts on whatever the library says is worn, when that is not what is on screen: after
     * a removal, or a choice that failed. A skin that could not be stored is left on, as
     * the library has no record of it.
     */
    private fun wearWorn() {
        scope.launch {
            val onScreen = currentId
            if (onScreen == UNSTORED_ID) return@launch
            val worn =
                withContext(Dispatchers.IO) { lock.withLock { if (library.worn == onScreen) null else loadWorn() } }
                    ?: return@launch
            skin = worn.first
            currentId = worn.second
            wornSkinChanged()
        }
    }

    companion object {
        private const val TAG = "SkinOps"

        /** The most that is read of a picked skin file. */
        const val MAX_SKIN_BYTES = 8 * 1024 * 1024

        /** The id shown when the skin on screen is not in the library. */
        const val UNSTORED_ID = ""
    }
}
