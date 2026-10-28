// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.ComponentCallbacks2
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
import nl.mattix.andamp.wal.WalFiles
import nl.mattix.andamp.wal.WalLook
import nl.mattix.andamp.widget.MainWindowWidget
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

/**
 * Which skin is on screen: loading a picked .wsz, keeping the library, and switching
 * between stored skins.
 *
 * A skin that parses is applied even if it cannot be stored, and a stored skin that does
 * not parse falls back to the bundled one. A picked modern skin (.wal) is added while modern
 * skins are on, once it has loaded. While they are off the listener is offered to turn them on
 * and add it.
 *
 * A worn modern skin is drawn by its engine ([wal]) over the last classic choice ([skin]). Its
 * look is kept parked while a music source's classic skin is worn over it, and let go when another
 * skin is chosen, when it is removed and when modern skins are turned off. A modern skin whose
 * engine fails gives way to the classic skin under it for the rest of the process (see
 * [fallBack]).
 */
@Suppress("TooManyFunctions") // the picks, the library's verbs and the modern looks they keep
class SkinOps internal constructor(
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
    /** Turns modern skins on, for the offer to add a modern skin picked while they are off. Called on IO. */
    private val turnModernOn: () -> Unit = {},
    /**
     * Makes the look of the stored modern skin [id] from its file, under the name its scripts read
     * (empty takes the name its skininfo gives), with the library's modern skins it may read from
     * as siblings (see [WalFiles.open]). Cheap: a look starts on its first fit, or when an import
     * waits for it. Called on IO.
     */
    private val openModern: (id: String, file: File, name: String, siblings: Map<String, File>) -> WalLook =
        { _, _, _, _ -> error("no modern skins here") },
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

    /**
     * The modern skin on screen, drawn by its engine over [skin]. Null while modern skins are off,
     * while a music source's classic skin is worn over the modern choice (its look is parked), and
     * after the modern skin failed.
     */
    internal var wal by mutableStateOf<WalLook?>(null)
        private set

    /** The modern choice's look while a music source's classic skin is worn over it; set on main. */
    @Volatile
    private var parked: WalLook? = null

    /** The modern skins whose engine failed in this process; see [fallBack]. Read and written on main and on IO. */
    private val failed: MutableSet<String> = ConcurrentHashMap.newKeySet()

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
        listenToTheApp()
        scope.launch {
            val base =
                withContext(Dispatchers.IO) {
                    // a pick cut short by the process ending leaves its copy behind, and an
                    // import of a modern skin its unloaded file
                    context.cacheDir
                        .listFiles { f -> f.name.startsWith(TEMP_PREFIX) && f.name.endsWith(".tmp") }
                        ?.forEach { it.delete() }
                    // under the lock, so a pick made meanwhile is not taken for one
                    lock.withLock { library.dropUnfinished() }
                    lastStamp = live.stamp(context, BundledSkins.BASE)
                    runCatching { CurrentSkin.base(context, live) }.getOrNull()
                }
            if (base == null) {
                Log.w(TAG, "the bundled skin would not decode; there is nothing to draw with")
                return@launch
            }
            baseSkin = base
            loadAndPut { loadWorn() }
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
            val changed =
                loadAndPut {
                    val stamp = live.stamp(context, BundledSkins.BASE)
                    if (stamp.contentEquals(lastStamp)) return@loadAndPut null
                    lastStamp = stamp
                    val base = runCatching { CurrentSkin.base(context, live) }.getOrNull() ?: return@loadAndPut null
                    baseSkin = base
                    loadWorn()
                }
            if (changed) wornSkinChanged()
        }
    }

    /**
     * A wallpaper change, or dynamic color switched on or off, arrives as a configuration
     * change. Once the app is out of sight ([ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN] and
     * above), a modern skin no player surface holds lets go of what it made for drawing; the one
     * a surface holds keeps it, and its menu stays open. The callback is registered on the
     * application and never removed, because the view model that owns this outlives every
     * activity (see [AppViewModels]).
     */
    private fun listenToTheApp() {
        context.applicationContext.registerComponentCallbacks(
            object : ComponentCallbacks2 {
                override fun onConfigurationChanged(newConfig: Configuration) = paletteChanged()

                @Deprecated("the platform's own; nothing here to drop")
                override fun onLowMemory() = Unit

                override fun onTrimMemory(level: Int) {
                    if (level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return
                    parked?.trimDrawing()
                    wal?.trimDrawing()
                }
            },
        )
    }

    /**
     * Loads a picked skin file and makes it the listener's choice.
     *
     * A file that is not a skin changes nothing and says nothing: the picker offers every
     * file, so a mispick is common. A skin file too large to load changes nothing either, and
     * a notice says why, unless [onStored] is given: a caller that takes the result words it.
     *
     * While modern skins are on, a modern skin is added once it has loaded in time; one that does
     * not load changes nothing, and a notice says so. While they are off, it is offered through
     * [WinampState.modernOffer]; see [acceptOffer]. Where [allowModern] is false, it is treated as
     * a file that is not a skin.
     *
     * @return the load, which is complete once any notice or offer is set.
     */
    fun load(
        input: InputStream,
        name: String,
        /** Whether a modern skin may be added; the museum, which offers classic skins, says no. */
        allowModern: Boolean = true,
        /**
         * Called with the library id the bytes were stored under, or null when they were
         * not a skin or could not be stored.
         */
        onStored: ((String?) -> Unit)? = null,
    ): Job {
        choosing()
        return scope.launch {
            val pick = withContext(Dispatchers.IO) { lock.withLock { readPick(input, name, allowModern) } }
            onStored?.invoke((pick as? Pick.Stored)?.id)
            settle(pick, name, worded = onStored != null)
        }
    }

    /**
     * Puts on what a pick added, or shows why nothing was added, unless the caller [worded]
     * it. A modern skin to offer waits in [WinampState.modernOffer].
     */
    private suspend fun settle(
        pick: Pick,
        name: String,
        worded: Boolean,
    ) {
        if (pick is Pick.Stored) {
            put(pick.worn)
            wornSkinChanged()
            refreshEntries()
            return
        }
        if (pick is Pick.ModernOffer) {
            offer(pick)
        } else if (!worded) {
            noticeFor(pick, name)?.let { notify(it) }
        }
        // nothing was chosen: what the library says is worn stays on, including a source's
        // skin that went on meanwhile
        wearWorn()
    }

    /** What came of a picked file. */
    private sealed interface Pick {
        /**
         * A skin, chosen, with what goes on screen, and the library id it was stored under:
         * null for a classic skin that applied but could not be stored.
         */
        class Stored(
            val worn: Worn,
            val id: String?,
        ) : Pick

        data object NotASkin : Pick

        /** A zip larger than a skin file may be. */
        data object TooLarge : Pick

        /** A modern skin that did not load, or not in time; it was not added. */
        data object Failed : Pick

        /** A modern skin picked while modern skins are off, kept in [file] until the offer is answered. */
        class ModernOffer(
            val file: File,
            val id: String,
            val name: String,
        ) : Pick
    }

    /**
     * Reads the picked file, then adds it as a classic or a modern skin. Call under the lock.
     *
     * The file is first copied to a temporary file, up to [MAX_PICK_BYTES], with its hash taken
     * as it streams, which is a modern skin's id. A copy of at most [MAX_SKIN_BYTES] is told
     * apart by the entries a zip stream finds, the same way the classic loader reads them; a
     * larger one by its zip directory. A classic skin larger than that is never added: a zip is
     * too large, and anything else is not a skin.
     */
    @Suppress("TooGenericExceptionCaught") // a broken skin file can throw anything; the current skin stays
    private fun readPick(
        input: InputStream,
        name: String,
        allowModern: Boolean,
    ): Pick {
        var copy: File? = null
        var pick: Pick = Pick.NotASkin
        try {
            val file = File.createTempFile(TEMP_PREFIX, ".tmp", context.cacheDir).also { copy = it }
            val digest = MessageDigest.getInstance("SHA-256")
            val whole =
                input.use { stream ->
                    file.outputStream().use { DigestInputStream(stream, digest).copyAtMost(it, MAX_PICK_BYTES) }
                }
            val small = whole && file.length() <= MAX_SKIN_BYTES
            // to a caller that adds classic skins only (the museum), a modern skin is not a skin
            pick =
                when {
                    !isModern(file, whole, small) -> readClassic(file, small, name)
                    !allowModern -> Pick.NotASkin
                    modernOn() -> addModern(file, SkinLibrary.idOf(digest.digest()), name)
                    else -> Pick.ModernOffer(file, SkinLibrary.idOf(digest.digest()), name)
                }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load skin $name", e)
        } finally {
            // the offer keeps the copy until it is answered
            if (pick !is Pick.ModernOffer) copy?.delete()
        }
        return pick
    }

    /** The picked [copy] as a classic skin: parsed and stored when it is [small] enough. */
    private fun readClassic(
        copy: File,
        small: Boolean,
        name: String,
    ): Pick {
        if (!small) return if (startsAsZip(copy)) Pick.TooLarge else Pick.NotASkin
        val bytes = copy.readBytes()
        val parsed = SkinLoader.load(bytes.inputStream(), fallback = baseSkin, name = name)
        val id = store(bytes, name)
        // a skin that applied but could not be stored has no library row, so no row is
        // highlighted
        return Pick.Stored(Worn(parsed, id ?: UNSTORED_ID, id ?: UNSTORED_ID), id)
    }

    /**
     * Adds the modern skin in [copy] to the library as [id] and chooses it, once its look has
     * started from the stored file, with the real player, within the time it may take. The look
     * goes on screen, so the skin loads once. [turnOn] runs between the two. The skin already
     * worn, picked again, keeps its look; one whose engine failed is tried again. Call under the
     * lock.
     *
     * A skin that does not load is deleted again, its look is let go, and no record changes; the
     * outcome is [Pick.Failed]. The same skin added before stays in the library.
     */
    @Suppress("TooGenericExceptionCaught") // a broken skin, or the engine running out of time, can throw anything
    private fun addModern(
        copy: File,
        id: String,
        name: String,
        turnOn: () -> Unit = {},
    ): Pick {
        val before = library.kindOf(id) == SkinKind.MODERN
        val kept = listOf(wal, parked).firstOrNull { it != null && it.id == id && !it.released }
        val wasFailed = failed.remove(id)
        var stored: File? = null
        var opened: WalLook? = null
        try {
            stored = library.storeModern(copy, id)
            val loaded =
                if (kept != null) {
                    // a look no player surface has started yet has loaded no name: the stored one stays
                    SkinLibrary.modernName(name).ifEmpty { kept.loadedName }.ifEmpty { library.nameOf(id) }
                } else {
                    openLook(id, stored, SkinLibrary.modernName(name)).also { opened = it }.startAndAwait()
                }
            turnOn()
            // named once loaded: a file named ".wal" alone takes the name the skin gives itself
            library.setName(id, loaded.ifEmpty { name })
            library.choose(id)
        } catch (e: Exception) {
            Log.w(TAG, "Modern skin $name did not load", e)
            opened?.release(save = false)
            if (wasFailed) failed += id
            if (!before) stored?.delete()
            return Pick.Failed
        }
        val look = opened ?: kept
        return Pick.Stored(loadAs(id, look = look) ?: Worn(baseSkin, id, SkinEntry.BASE_ID, look), id)
    }

    /** Offers [offered] to the listener; an earlier offer still waiting gives way to it. */
    private fun offer(offered: Pick.ModernOffer) {
        declineOffer()
        pending = offered
        // an answer to an offer that has given way answers nothing
        state.modernOffer =
            ModernSkinOffer(
                offered.name,
                accept = { if (pending === offered) acceptOffer() },
                decline = { if (pending === offered) declineOffer() },
            )
    }

    /** The modern skin [WinampState.modernOffer] offers, with its picked copy. */
    private var pending: Pick.ModernOffer? = null

    /**
     * Turn on and add, on the offer: the offered skin is added as a pick is while modern
     * skins are on. Only once it has loaded are modern skins turned on and the skin chosen;
     * when it does not load they stay off, and a notice says so.
     */
    fun acceptOffer() {
        val offered = pending ?: return
        pending = null
        state.modernOffer = null
        // a source's skin may have gone on since the pick
        choosing()
        scope.launch {
            val pick =
                withContext(Dispatchers.IO) {
                    lock.withLock {
                        try {
                            addModern(offered.file, offered.id, offered.name, turnOn = turnModernOn)
                        } finally {
                            offered.file.delete()
                        }
                    }
                }
            settle(pick, offered.name, worded = false)
        }
    }

    /** Not now, on the offer, or the offer closed another way: the picked copy is deleted. */
    fun declineOffer() {
        val offered = pending ?: return
        pending = null
        state.modernOffer = null
        scope.launch(Dispatchers.IO) { offered.file.delete() }
    }

    /**
     * Modern skins were turned on or off while the app runs. On lists the modern rows again.
     * Off takes them out of the lists, and a modern choice gives way to the last classic one,
     * which goes on screen in its place; a source's skin on screen stays on.
     */
    fun betaChanged(on: Boolean) {
        scope.launch {
            started.await()
            val dropped = !on && withContext(Dispatchers.IO) { lock.withLock { library.dropModern() } }
            // a look parked under a source's skin goes with the switch; one on screen goes as its
            // skin gives way below
            if (!on) {
                parked?.release(save = true)
                parked = null
            }
            refreshEntries()
            if (!dropped) return@launch
            widgetSkin = library.widgetSkin
            if (library.worn == companionId && currentId != companionId) {
                // the classic skin drawn under the modern one is the one that goes on
                put(Worn(skin, companionId, companionId))
            } else {
                wearWorn()
            }
            // an automatic widget skin may have gone
            wornSkinChanged()
        }
    }

    /** The notice for a pick that changed nothing, or null when it says nothing. */
    private fun noticeFor(
        pick: Pick,
        name: String,
    ): AmpPrompt? {
        val (title, body) =
            when (pick) {
                Pick.Failed -> R.string.skin_modern_failed_title to R.string.skin_modern_failed_body
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
     *
     * Picking the modern skin on screen again puts it back where the engine places it, if the
     * listener moved it; picked from another skin, it comes back where it was moved to.
     */
    fun apply(id: String) {
        // a modern skin whose engine failed is tried again when the listener picks it again
        failed.remove(id)
        if (id == currentId) {
            wal?.takeIf { it.id == id }?.places?.clear()
            adopt(id)
            return
        }
        if (id == SkinEntry.BASE_ID) {
            reset()
            return
        }
        choosing()
        scope.launch {
            if (!loadAndPut { loadAs(id)?.also { library.choose(id) } }) {
                // a stored skin that does not load changes nothing
                wearWorn()
                return@launch
            }
            wornSkinChanged()
        }
    }

    /** Called with the id when a skin leaves the library, so the museum browser can drop its record. */
    var onRemoved: ((String) -> Unit)? = null

    /**
     * Removes the skin [id] from the library. A removal is not a choice, so a source's
     * skin that is loading carries on, unless the removed skin was on; the library counts
     * that case itself. A modern skin's look is let go without a save before [onRemoved]
     * runs, so nothing writes its settings back once they are forgotten.
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
            wal?.takeIf { it.id == id }?.release(save = false)
            parked?.takeIf { it.id == id }?.let {
                it.release(save = false)
                parked = null
            }
            failed.remove(id)
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

    /**
     * Chooses the skin already on screen; see [apply]. Nothing is loaded, only the record changes.
     * A modern look parked under a music source's skin chosen this way is let go, as the modern
     * skin is no longer the choice.
     */
    private fun adopt(id: String) {
        choosing()
        scope.launch {
            // if a removal ran first, there is nothing to choose
            val chosen = withContext(Dispatchers.IO) { lock.withLock { library.has(id).also { if (it) library.choose(id) } } }
            if (chosen) {
                parked?.release(save = true)
                parked = null
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
     * modern one, [companionId] is the last classic choice, and [wal] the modern skin's look
     * drawn over it. [source] says a music source's classic skin is worn over the listener's
     * choice.
     */
    private class Worn(
        val skin: Skin?,
        val id: String,
        val companionId: String,
        val wal: WalLook? = null,
        val source: Boolean = false,
    )

    /**
     * Puts [worn] on screen. Every change of the skin on screen goes through here. A look that
     * leaves the screen is parked when a source's classic skin goes on over it, in place of one
     * parked before, and let go otherwise; a parked look is let go once the listener's choice
     * is back on screen. A look let go while [worn] was loading is not shown, and the classic
     * skin under it is ticked, as [fallBack] ticks it.
     */
    private fun put(worn: Worn) {
        val next = worn.wal?.takeUnless { it.released }
        skin = worn.skin
        currentId = if (worn.wal != null && next == null) worn.companionId else worn.id
        companionId = worn.companionId
        widgetSkin = library.widgetSkin
        val off = wal?.takeIf { it !== next }
        wal = next
        // the Skin Settings dialog belongs to the look on screen: it closes when that look leaves
        if (state.skinSettings != null && state.skinSettings !== next) state.skinSettings = null
        if (parked === next) parked = null
        if (off != null && worn.source) {
            parked?.release(save = true)
            parked = off
            off.park()
        } else {
            off?.release(save = true)
        }
        if (!worn.source) {
            parked?.release(save = true)
            parked = null
        }
    }

    /**
     * The library skin [id] as it goes on screen: the skin itself when it is classic; for a
     * modern one, the last classic choice, or the base when that does not load, with [look] or
     * the skin's look from [lookFor]. A modern skin whose engine failed has no look, and the
     * classic skin under it is ticked. Null when a classic skin does not load or is gone.
     * [source] marks a music source's skin.
     */
    private fun loadAs(
        id: String,
        source: Boolean = false,
        look: WalLook? = null,
    ): Worn? {
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
        val modern = companion != id
        val wal = if (modern) look ?: lookFor(id) else null
        val fellBack = modern && wal == null && id in failed
        return when {
            drawn != null -> Worn(drawn, if (fellBack) companion else id, companion, wal, source)
            companion == id -> null
            else -> Worn(baseSkin, if (fellBack) SkinEntry.BASE_ID else id, SkinEntry.BASE_ID, wal, source)
        }
    }

    /**
     * The look of the modern skin [id]: the one on screen or parked, else a new one. Null while
     * modern skins are off, for a skin whose engine failed in this process, and when no look can
     * be made, which counts as a failure. Call under the lock.
     */
    @Suppress("TooGenericExceptionCaught") // whatever making a look throws, the classic skin under it shows
    private fun lookFor(id: String): WalLook? {
        if (id in failed || !modernOn()) return null
        listOf(wal, parked).firstOrNull { it != null && it.id == id && !it.released }?.let { return it }
        val file = library.fileOf(id) ?: return null
        return try {
            openLook(id, file, library.nameOf(id))
        } catch (e: Exception) {
            Log.w(TAG, "Modern skin $id has no look", e)
            failed += id
            null
        }
    }

    /**
     * A new look of the modern skin [id] in [file], under [name], with the library's other modern
     * skins as its siblings. Its failures put the classic skin back on (see [fallBack]).
     */
    private fun openLook(
        id: String,
        file: File,
        name: String,
    ): WalLook {
        val modern = library.list().filter { it.kind == SkinKind.MODERN }
        val siblings = WalFiles.siblings(modern.mapNotNull { e -> library.fileOf(e.id)?.let { e.name to it } })
        return openModern(id, file, name, siblings).also { look ->
            look.onFailed = { stoppedWorking -> fallBack(look, stoppedWorking) }
        }
    }

    /**
     * The engine of [look] failed: its skin did not start, or it [stoppedWorking]. The skin counts
     * as failed for the rest of this process, so nothing opens it again until the listener picks
     * it again. On screen, the classic skin under it goes on with its row ticked, while the
     * listener's choice stays, so the skin is tried again at the next start. A skin that stopped
     * working says so; one that did not start says nothing. On the main thread.
     */
    private fun fallBack(
        look: WalLook,
        stoppedWorking: Boolean,
    ) {
        failed += look.id
        look.release(save = false)
        if (parked === look) parked = null
        if (wal === look) put(Worn(skin, companionId, companionId))
        if (!stoppedWorking) return
        notify(
            AmpPrompt(
                title = words.get(R.string.skin_modern_stopped_title, library.nameOf(look.id)),
                body = words.get(R.string.skin_modern_stopped_body),
                confirmLabel = words.get(R.string.prompt_ok),
                dismissLabel = null,
                onConfirm = { state.prompt = null },
            ),
        )
    }

    /** The row ticked while [id] is worn: for a modern skin whose engine failed, the classic skin under it. */
    private fun shownId(id: String): String = if (id in failed) CurrentSkin.classicOf(library, id) else id

    /**
     * Loads with [load] on IO under the lock, then puts what it gave on screen unless [keep] says
     * no. The look of a load that is not put, because [keep] said no or the coroutine was
     * canceled, is let go, unless it is on screen or parked. Returns whether it put something.
     */
    private suspend fun loadAndPut(
        keep: () -> Boolean = { true },
        load: () -> Worn?,
    ): Boolean {
        var loaded: Worn? = null
        try {
            withContext(Dispatchers.IO) { lock.withLock { loaded = load() } }
            val worn = loaded ?: return false
            if (!keep()) return false
            loaded = null
            put(worn)
            return true
        } finally {
            loaded?.wal?.takeIf { it !== wal && it !== parked }?.release(save = true)
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
            loadAs(over ?: library.currentId, source = over != null)?.let { return it }
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
                // a choice made while this loaded takes precedence
                val changed =
                    loadAndPut(keep = { SkinLibrary.choices == asked }) {
                        val id = library.wear(sourceSkin, asked) ?: return@loadAndPut null
                        if (shownId(id) == onScreen) null else loadWorn()
                    }
                if (changed) wornSkinChanged()
            }
    }

    /**
     * Puts on whatever the library says is worn, when that is not what is on screen: after
     * a removal, or a choice that failed. A skin that could not be stored is left on, as
     * the library has no record of it.
     *
     * The classic skin drawn counts too: removing the last classic choice under a modern
     * skin changes only that. A modern skin whose engine failed counts as the classic skin
     * under it, which is what shows.
     */
    private fun wearWorn() {
        scope.launch {
            val onScreen = currentId
            val drawn = companionId
            if (onScreen == UNSTORED_ID) return@launch
            val changed =
                loadAndPut {
                    val id = library.worn
                    if (shownId(id) == onScreen && CurrentSkin.classicOf(library, id) == drawn) null else loadWorn()
                }
            if (changed) wornSkinChanged()
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
         * The names a zip stream finds in [input], up to the first entry it cannot read; empty
         * for bytes that are not a zip. ISO 8859-1 reads every name, whatever the archiver
         * wrote; only skin.xml matters.
         */
        private fun streamedNames(input: InputStream): List<String> {
            val names = ArrayList<String>()
            runCatching {
                ZipInputStream(input, Charsets.ISO_8859_1).use { zip ->
                    while (true) names += (zip.nextEntry ?: break).name
                }
            }
            return names
        }

        /** Whether the picked [copy] is a modern skin; see [readPick]. */
        private fun isModern(
            copy: File,
            whole: Boolean,
            small: Boolean,
        ): Boolean {
            val names =
                when {
                    small -> copy.inputStream().use { streamedNames(it) }

                    whole -> SkinLibrary.directoryNames(copy) ?: return false

                    // the zip directory, which sits at the end, was not read
                    else -> return false
                }
            return WalArchive.isModern(names)
        }

        /** Whether [file] starts the way a zip does. */
        private fun startsAsZip(file: File): Boolean {
            val head = ByteArray(ZIP_MAGIC.size)
            // a file shorter than the header throws
            val read = runCatching { DataInputStream(file.inputStream()).use { it.readFully(head) } }.isSuccess
            return read && head.contentEquals(ZIP_MAGIC)
        }

        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4b, 0x03, 0x04)

        /** The id shown when the skin on screen is not in the library. */
        const val UNSTORED_ID = ""
    }
}
