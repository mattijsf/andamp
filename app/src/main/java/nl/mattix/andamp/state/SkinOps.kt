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
import nl.mattix.andamp.R
import nl.mattix.andamp.modernskin.skin.WalArchive
import nl.mattix.andamp.skin.BundledSkins
import nl.mattix.andamp.skin.LiveSkins
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SkinLoader
import nl.mattix.andamp.widget.MainWindowWidget
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Which skin is on screen: loading a picked .wsz, keeping the library, and switching
 * between stored skins.
 *
 * A skin that parses is applied even if it cannot be stored, and a stored skin that does
 * not parse falls back to the bundled one. A picked modern skin (.wal) is not added, and
 * the listener is told why.
 */
class SkinOps(
    private val context: Context,
    private val state: WinampState,
    private val scope: CoroutineScope,
    private val library: SkinLibrary = SkinLibrary(context),
    /** The skins that follow the phone's palette. */
    private val live: LiveSkins = CurrentSkin.live,
    /** The words of the notices a pick can end in. */
    private val words: Words = Words.of(context),
    /**
     * Whether modern skins can be worn; read on IO. While they cannot, modern rows are not
     * listed, and a modern choice gives way to the last classic one when it would go on.
     */
    private val modernOn: () -> Boolean = { false },
) {
    /**
     * The classic skin on screen and in the app's windows and menus. While a modern skin is
     * worn, it is the listener's last classic choice; see [SkinLibrary.lastClassic].
     */
    var skin by mutableStateOf<Skin?>(null)
        private set

    /**
     * The id of the skin on screen, for the manager's highlight and the Skins menu's tick.
     * While a music source's skin is worn, it is that skin's id.
     */
    var currentId by mutableStateOf(SkinEntry.BASE_ID)
        private set

    /** The skin picked for the home screen widget, or null when it follows the player; see [SkinLibrary.widgetSkin]. */
    var widgetSkin by mutableStateOf<String?>(null)
        private set

    /** The id of the classic skin in [skin]: [currentId] itself, or the last classic choice under a modern one. */
    private var companionId = SkinEntry.BASE_ID

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
                    // a pick cut short by the process ending leaves its copy behind
                    context.cacheDir
                        .listFiles { f -> f.name.startsWith(TEMP_PREFIX) && f.name.endsWith(".tmp") }
                        ?.forEach { it.delete() }
                    lastStamp = live.stamp(context, BundledSkins.BASE)
                    runCatching { CurrentSkin.base(context, live) }.getOrNull()
                }
            if (base == null) {
                Log.w(TAG, "the bundled skin would not decode; there is nothing to draw with")
                return@launch
            }
            baseSkin = base
            put(withContext(Dispatchers.IO) { lock.withLock { loadWorn() } })
            refreshEntries()
            started.complete(Unit)
            // modern skins stored as classic files before the library told them apart get
            // their own extension; one of them may be worn
            val renamed = withContext(Dispatchers.IO) { lock.withLock { library.migrateModern() } }
            if (renamed) {
                refreshEntries()
                wearWorn()
            }
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
            put(worn)
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

    /**
     * Loads a picked skin file and makes it the listener's choice.
     *
     * A file that is not a skin changes nothing and says nothing: the picker offers every
     * file, so a mispick is common. A modern skin, or a skin file too large to load, changes
     * nothing either, and a notice says why, unless [onStored] is given: a caller that takes
     * the result words it.
     *
     * @return the load, which is complete once any notice is set.
     */
    fun load(
        input: InputStream,
        name: String,
        /**
         * Called with the library id the bytes were stored under, or null when they were
         * not a skin or could not be stored.
         */
        onStored: ((String?) -> Unit)? = null,
    ): Job {
        choosing()
        return scope.launch {
            val pick = withContext(Dispatchers.IO) { lock.withLock { readPick(input, name) } }
            onStored?.invoke((pick as? Pick.Stored)?.id)
            if (pick !is Pick.Stored) {
                if (onStored == null) noticeFor(pick, name)?.let { notify(it) }
                // nothing was chosen: what the library says is worn stays on, including a
                // source's skin that went on meanwhile
                wearWorn()
                return@launch
            }
            // a skin that applied but could not be stored has no library row, so no row
            // is highlighted
            val id = pick.id ?: UNSTORED_ID
            put(Worn(pick.skin, id, id))
            wornSkinChanged()
            refreshEntries()
        }
    }

    /** What came of a picked file. */
    private sealed interface Pick {
        /** A classic skin, applied, with the library id it was stored under (null when it was not). */
        class Stored(
            val skin: Skin,
            val id: String?,
        ) : Pick

        data object NotASkin : Pick

        /** A zip larger than a skin file may be. */
        data object TooLarge : Pick

        /** A modern skin (.wal), which cannot be added. */
        data object Modern : Pick
    }

    /**
     * Reads the picked file and, if it is a classic skin, parses and stores it. Call under
     * the lock.
     *
     * Up to [MAX_SKIN_BYTES] the file is read into memory, as a classic skin always was, and
     * told apart by the entries a zip stream finds, the same way the classic loader reads
     * them. A larger file is never added. It is read on into a temporary file, up to
     * [MAX_PICK_BYTES], so that a large modern skin is still named as one; otherwise a zip
     * is too large and anything else is not a skin.
     */
    @Suppress("TooGenericExceptionCaught") // a broken skin file can throw anything; the current skin stays
    private fun readPick(
        input: InputStream,
        name: String,
    ): Pick =
        try {
            input.use { stream ->
                val head = ByteArrayOutputStream()
                if (stream.copyAtMost(head, MAX_SKIN_BYTES.toLong())) {
                    readSmall(head.toByteArray(), name)
                } else {
                    readLarge(head, stream)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load skin $name", e)
            Pick.NotASkin
        }

    /** A file of at most [MAX_SKIN_BYTES]: a modern skin, or parsed and stored as a classic one. */
    private fun readSmall(
        bytes: ByteArray,
        name: String,
    ): Pick {
        if (WalArchive.isModern(streamedNames(bytes))) return Pick.Modern
        val parsed = SkinLoader.load(bytes.inputStream(), fallback = baseSkin, name = name)
        return Pick.Stored(parsed, store(bytes, name))
    }

    /** A file past [MAX_SKIN_BYTES], whose first bytes are [head] and the rest still in [rest]. */
    private fun readLarge(
        head: ByteArrayOutputStream,
        rest: InputStream,
    ): Pick {
        val zip = startsAsZip(head.toByteArray())
        val file = File.createTempFile(TEMP_PREFIX, ".tmp", context.cacheDir)
        try {
            val whole =
                file.outputStream().use { out ->
                    head.writeTo(out)
                    rest.copyAtMost(out, MAX_PICK_BYTES - head.size())
                }
            val names = if (whole) SkinLibrary.directoryNames(file) else null
            return when {
                names != null && WalArchive.isModern(names) -> Pick.Modern
                zip -> Pick.TooLarge
                else -> Pick.NotASkin
            }
        } finally {
            file.delete()
        }
    }

    /** The notice for a pick that changed nothing, or null when it says nothing. */
    private fun noticeFor(
        pick: Pick,
        name: String,
    ): AmpPrompt? {
        val (title, body) =
            when (pick) {
                Pick.Modern -> R.string.skin_modern_title to R.string.skin_modern_body
                Pick.TooLarge -> R.string.skin_too_large_title to R.string.skin_too_large_body
                else -> return null
            }
        return AmpPrompt(
            title = words.get(title, name),
            body = words.get(body),
            confirmLabel = words.get(R.string.prompt_ok),
            dismissLabel = null,
            onConfirm = { state.prompt = null },
        )
    }

    /**
     * Shows [notice] now, or once the prompt on screen is answered (see
     * [WinampState.waitingNotice]). The view model outlives the activity or widget menu that
     * picked, so the notice is not lost when that closes.
     */
    private fun notify(notice: AmpPrompt) {
        if (state.prompt == null) state.prompt = notice else state.waitingNotice = notice
    }

    /**
     * Applies the library skin [id].
     *
     * The skin already on screen is not loaded again, but it is still chosen: if it was
     * on only as a music source's skin, it becomes the listener's own.
     */
    fun apply(id: String) {
        if (id == currentId) {
            adopt(id)
            return
        }
        if (id == SkinEntry.BASE_ID) {
            reset()
            return
        }
        choosing()
        scope.launch {
            val worn = withContext(Dispatchers.IO) { lock.withLock { loadAs(id)?.also { library.choose(id) } } }
            if (worn == null) {
                // a stored skin that does not load changes nothing
                wearWorn()
                return@launch
            }
            put(worn)
            wornSkinChanged()
        }
    }

    /** Called with the id when a skin leaves the library, so the museum browser can drop its record. */
    var onRemoved: ((String) -> Unit)? = null

    /**
     * Removes the skin [id] from the library. A removal is not a choice, so a source's
     * skin that is loading carries on, unless the removed skin was on; the library counts
     * that case itself.
     */
    fun remove(id: String) {
        if (BundledSkins.of(id) != null) return // a skin that ships with the app cannot be removed
        scope.launch {
            // under the lock, so the delete runs after an apply in flight has written its
            // choice
            val widgetDrew =
                withContext(Dispatchers.IO) {
                    lock.withLock {
                        val drew = library.widgetSkin == id || library.lastClassic == id
                        library.delete(id)
                        drew
                    }
                }
            widgetSkin = library.widgetSkin
            // the library has moved every record off the removed id; what it says is worn
            // goes on if the removed skin was on screen. The widget is refreshed after the
            // delete, so it does not redraw the removed skin
            wearWorn()
            // the widget may have drawn the removed skin while the player shows another one
            if (widgetDrew) wornSkinChanged()
            onRemoved?.invoke(id)
            refreshEntries()
        }
    }

    /** Main menu > Skins > AndAmp Dark. The library keeps the files; only the choice changes. */
    fun reset() {
        choosing()
        put(Worn(baseSkin, SkinEntry.BASE_ID, SkinEntry.BASE_ID))
        // the widget reads the worn skin from the library, so it is refreshed after the
        // write; apply awaits its write for the same reason
        scope.launch {
            withContext(Dispatchers.IO) { lock.withLock { library.choose(SkinEntry.BASE_ID) } }
            widgetSkin = library.widgetSkin
            wornSkinChanged()
        }
    }

    /** Chooses the skin already on screen; see [apply]. Nothing is loaded, only the record changes. */
    private fun adopt(id: String) {
        choosing()
        scope.launch {
            // if a removal ran first, there is nothing to choose
            val chosen = withContext(Dispatchers.IO) { lock.withLock { library.has(id).also { if (it) library.choose(id) } } }
            if (chosen) {
                widgetSkin = library.widgetSkin
                wornSkinChanged()
            } else {
                wearWorn()
            }
        }
    }

    /**
     * The listener picked [id], a classic skin, for the home screen widget, or null to have
     * it follow the player.
     */
    fun chooseWidgetSkin(id: String?) {
        scope.launch {
            withContext(Dispatchers.IO) { lock.withLock { library.chooseWidgetSkin(id) } }
            widgetSkin = library.widgetSkin
            wornSkinChanged()
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
        state.skinEntries =
            withContext(Dispatchers.IO) {
                val modern = modernOn()
                library.list().filter { modern || it.kind == SkinKind.CLASSIC }
            }
    }

    /**
     * What goes on screen: [skin], the classic skin drawn, [id], the library row ticked, and
     * [companionId], the id of [skin]. For a classic skin the two ids are the same; for a
     * modern one, [companionId] is the last classic choice.
     */
    private class Worn(
        val skin: Skin?,
        val id: String,
        val companionId: String,
    )

    /** Puts [worn] on screen. Every change of the skin on screen goes through here. */
    private fun put(worn: Worn) {
        skin = worn.skin
        currentId = worn.id
        companionId = worn.companionId
        widgetSkin = library.widgetSkin
    }

    /**
     * The library skin [id] as it goes on screen: the skin itself when it is classic; for a
     * modern one, the last classic choice, or the base when that does not load. Null when a
     * classic skin does not load or is gone.
     */
    private fun loadAs(id: String): Worn? {
        // CurrentSkin holds the fallback order, shared with the home screen widget
        val companion = CurrentSkin.classicOf(library, id)
        val drawn =
            if (companion ==
                SkinEntry.BASE_ID
            ) {
                baseSkin
            } else {
                CurrentSkin.stored(context, library, companion, baseSkin, live)
            }
        return when {
            drawn != null -> Worn(drawn, id, companion)
            companion == id -> null
            else -> Worn(baseSkin, id, SkinEntry.BASE_ID)
        }
    }

    /**
     * Loads what the library says is worn, with the id to highlight. Call under the lock:
     * taking off a source's skin writes, and so does a modern choice giving way while
     * modern skins are off.
     */
    private fun loadWorn(): Worn {
        if (!modernOn()) library.dropModern()
        // loops, because a source's skin that does not load is taken off and the
        // listener's own is tried next
        while (true) {
            val over = library.wearing
            loadAs(over ?: library.currentId)?.let { return it }
            // the listener's own does not load: the base is on screen and is highlighted
            if (over == null) return Worn(baseSkin, SkinEntry.BASE_ID, SkinEntry.BASE_ID)
            // a source's skin that does not load is taken off, and their own is tried
            library.unwear(over)
        }
    }

    /**
     * Wears a music source's skin over the listener's own while its track is the current
     * one; null wears their own again. [SourceSkins.wornFor] decides which skin is asked
     * for.
     *
     * The listener's choice is unchanged. The Skins menu ticks the skin on screen, and
     * picking it there makes it their own; see [apply]. A skin that is gone is not
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
                put(worn)
                wornSkinChanged()
            }
    }

    /**
     * Puts on whatever the library says is worn, when that is not what is on screen: after
     * a removal, or a choice that failed. A skin that could not be stored is left on, as
     * the library has no record of it.
     *
     * The classic skin drawn counts too: removing the last classic choice under a modern
     * skin changes only that.
     */
    private fun wearWorn() {
        scope.launch {
            val onScreen = currentId
            val drawn = companionId
            if (onScreen == UNSTORED_ID) return@launch
            val worn =
                withContext(Dispatchers.IO) {
                    lock.withLock {
                        val id = library.worn
                        if (id == onScreen && CurrentSkin.classicOf(library, id) == drawn) null else loadWorn()
                    }
                } ?: return@launch
            put(worn)
            wornSkinChanged()
        }
    }

    companion object {
        private const val TAG = "SkinOps"

        /** The most that is read of a picked classic skin file. */
        const val MAX_SKIN_BYTES = 8 * 1024 * 1024

        /** The most that is read of any picked file, so a large modern skin is still told apart. */
        const val MAX_PICK_BYTES = 32L * 1024 * 1024

        /** The start of the name of a picked file's temporary copy in the cache. */
        private const val TEMP_PREFIX = "pick"

        /**
         * The names a zip stream finds in [bytes], up to the first entry it cannot read; empty
         * for bytes that are not a zip. ISO 8859-1 reads every name, whatever the archiver
         * wrote; only skin.xml matters.
         */
        private fun streamedNames(bytes: ByteArray): List<String> {
            val names = ArrayList<String>()
            runCatching {
                ZipInputStream(bytes.inputStream(), Charsets.ISO_8859_1).use { zip ->
                    while (true) names += (zip.nextEntry ?: break).name
                }
            }
            return names
        }

        /** Whether [bytes] start the way a zip does. */
        private fun startsAsZip(bytes: ByteArray): Boolean =
            bytes.size >= ZIP_MAGIC.size && bytes.copyOf(ZIP_MAGIC.size).contentEquals(ZIP_MAGIC)

        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4b, 0x03, 0x04)

        /** The id shown when the skin on screen is not in the library. */
        const val UNSTORED_ID = ""
    }
}
