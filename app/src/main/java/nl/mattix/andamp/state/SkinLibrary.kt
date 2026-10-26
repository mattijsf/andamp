// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import nl.mattix.andamp.R
import nl.mattix.andamp.modernskin.skin.WalArchive
import nl.mattix.andamp.skin.BundledSkin
import nl.mattix.andamp.skin.BundledSkins
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.zip.ZipFile

/** What a library skin is: a classic Winamp skin (.wsz), or a modern one (.wal). */
enum class SkinKind { CLASSIC, MODERN }

/** A skin in the library: one the listener loaded, or one that ships with the app. */
data class SkinEntry(
    /** Content hash for a loaded skin; a fixed id for one that ships with the app. */
    val id: String,
    val name: String,
    val sizeBytes: Long,
    /** Every skin that ships with the app is classic. */
    val kind: SkinKind = SkinKind.CLASSIC,
) {
    /** The row's words in the Skins menu and the Skin Browser: a modern skin says so after its name. */
    fun label(words: Words): String = if (kind == SkinKind.MODERN) words.get(R.string.skin_row_modern, name) else name

    companion object {
        /** The skin the app falls back to; it cannot be deleted. */
        const val BASE_ID = BundledSkins.BASE_ID
        val BASE_NAME = BundledSkins.BASE.name
        val BASE = of(BundledSkins.BASE)

        /** A skin that ships inside the app, as a library row. */
        fun of(bundled: BundledSkin) = SkinEntry(bundled.id, bundled.name, 0)
    }
}

/**
 * Every skin the listener has loaded, kept so the menu can offer them again after a
 * restart: classic skins as `<id>.wsz`, modern skins as `<id>.wal`. Files are named by
 * content hash, so loading the same skin twice replaces it, and loading a renamed copy only
 * updates its display name.
 *
 * Besides the listener's choice and a source's skin, the "skins" file keeps the last
 * classic choice, which is drawn wherever a modern skin cannot be (see [lastClassic]), and
 * the widget's skin (see [widgetSkin]).
 *
 * When a skin is added, its file reaches the disk whole and under its name before any record
 * names it, so a phone that switches off right after the add comes back with the skin or as
 * it was before. This rests on flushing the skins folder after the rename; a folder that
 * cannot be flushed is logged, and the store goes on. The display name and the choice are
 * kept in the same "skins" file, which is written whole, so a choice kept on the disk comes
 * with its name. A classic skin is named just after it is stored, so a phone that switches
 * off in between keeps the skin with no name, listed under its id.
 */
class SkinLibrary internal constructor(
    context: Context,
    /**
     * Flushes an open file or folder to the disk: a stored skin's bytes before the rename that
     * gives them their name, and the skins folder after it. A test makes it fail.
     */
    private val flush: (FileChannel) -> Unit = { it.force(true) },
) {
    private val assets = context.assets
    private val dir = File(context.filesDir, "skins")
    private val prefs: SharedPreferences = context.getSharedPreferences("skins", Context.MODE_PRIVATE)
    private val legacyPrefs: SharedPreferences = context.getSharedPreferences("skin", Context.MODE_PRIVATE)

    init {
        migrateLegacySlot()
    }

    /**
     * Migration of a saved value: a single skin stored at skins/current.wsz, with its name
     * in a separate prefs file, becomes a normal library entry and stays selected.
     */
    private fun migrateLegacySlot() {
        val legacy = File(dir, "current.wsz")
        if (!legacy.isFile) return
        val name = legacyPrefs.getString("name", null) ?: "Imported skin"
        // the old file is the only copy of those bytes, so it is deleted only after the
        // import succeeded; a failed migration is retried next launch
        runCatching {
            val entry = save(legacy.readBytes(), name)
            currentId = entry.id
        }.onSuccess {
            legacy.delete()
            legacyPrefs.edit().remove("name").apply()
        }
    }

    /** The skins that ship with the app first, then the user's of both kinds, name-sorted. */
    fun list(): List<SkinEntry> =
        BundledSkins.all.map { SkinEntry.of(it) } +
            (dir.listFiles()?.toList() ?: emptyList())
                .filter { it.isFile }
                .mapNotNull { file ->
                    val (id, kind) =
                        when {
                            file.name.endsWith(CLASSIC_EXT) -> file.name.removeSuffix(CLASSIC_EXT) to SkinKind.CLASSIC
                            file.name.endsWith(MODERN_EXT) -> file.name.removeSuffix(MODERN_EXT) to SkinKind.MODERN
                            else -> return@mapNotNull null
                        }
                    SkinEntry(id, prefs.getString(nameKey(id), null) ?: id, file.length(), kind)
                }.sortedBy { it.name.lowercase() }

    /** The id [bytes] would be stored under; the browser records it against a museum hash. */
    fun idFor(bytes: ByteArray): String = hash(bytes)

    /**
     * The skin's bytes: an asset for a skin that ships with the app, a file for one the
     * listener loaded.
     */
    fun open(id: String): InputStream? =
        BundledSkins.of(id)?.let { assets.open(it.asset) }
            ?: fileFor(id).takeIf { it.exists() }?.inputStream()

    /**
     * Stores [bytes] under its content hash and returns the entry. Identical bytes
     * overwrite the same file, and the display name is updated. When the bytes cannot be
     * written or renamed into place, nothing changes and the failure is thrown.
     */
    fun save(
        bytes: ByteArray,
        name: String,
    ): SkinEntry {
        val id = hash(bytes)
        dir.mkdirs()
        val file = File(dir, "$id$CLASSIC_EXT")
        // write-then-rename, so a crash mid-write cannot leave a partial skin
        val tmp = File(dir, "$id$CLASSIC_EXT.tmp")
        writeFlushed(tmp) { it.write(bytes) }
        moveIntoPlace(tmp, file) { "could not store skin '$name'" }
        prefs.edit().putString(nameKey(id), name).apply()
        return SkinEntry(id, name, file.length())
    }

    /**
     * Stores [tmp], a modern skin streamed to a temporary file, in the library under [id],
     * the content hash taken while it streamed, deletes [tmp] and returns the stored file. The
     * display name is written apart, with [setName], once the skin has been built. When the
     * copy cannot be written or renamed into place, nothing in the library changes and the
     * failure is thrown.
     *
     * [tmp] is copied, not moved: a file made in the cache folder keeps counting as the app's
     * cache wherever it is moved, and clearing the cache would not free it.
     */
    fun storeModern(
        tmp: File,
        id: String,
    ): File {
        dir.mkdirs()
        val file = File(dir, "$id$MODERN_EXT")
        // copied next to the store first, so a crash mid-copy cannot leave a partial skin
        val part = File(dir, "$id$MODERN_EXT.tmp")
        writeFlushed(part) { out -> tmp.inputStream().use { it.copyTo(out) } }
        tmp.delete()
        moveIntoPlace(part, file) { "could not store modern skin '$id'" }
        return file
    }

    /**
     * Writes [part] with [write] and flushes it to the disk, so the whole skin is there before
     * a rename gives it the stored name. A part not written and flushed in full is deleted, and
     * the failure is thrown.
     */
    private fun writeFlushed(
        part: File,
        write: (OutputStream) -> Unit,
    ) {
        var flushed = false
        try {
            FileOutputStream(part).use { out ->
                write(out)
                flush(out.channel)
            }
            flushed = true
        } finally {
            if (!flushed) part.delete()
        }
    }

    /**
     * Renames the flushed [part] to [file], over the copy stored there before, then flushes the
     * skins folder, so the new name is on the disk before a record names it. A folder that
     * cannot be flushed is logged, and the store goes on. A rename that fails deletes [part],
     * leaves [file] as it was, and throws with [message].
     */
    private fun moveIntoPlace(
        part: File,
        file: File,
        message: () -> String,
    ) {
        if (!part.renameTo(file)) {
            part.delete()
            error(message())
        }
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use(flush)
        } catch (e: IOException) {
            // the skin is stored; only its new name may not be on the disk yet
            Log.w(TAG, "the skins folder was not flushed", e)
        }
    }

    /**
     * Deletes what storing a skin leaves when the process ends partway: a part of either kind
     * written before it was renamed, and a modern skin with no display name, stored but not yet
     * loaded, as the name is written once the skin has loaded. A classic skin with no name is
     * whole and stays. Run at start, before anything is stored.
     */
    fun dropUnfinished() {
        val named = { file: File -> prefs.contains(nameKey(file.name.removeSuffix(MODERN_EXT))) }
        val parts = listOf("$CLASSIC_EXT.tmp", "$MODERN_EXT.tmp")
        dir
            .listFiles { file -> file.isFile && parts.any { file.name.endsWith(it) } }
            ?.forEach { it.delete() }
        dir
            .listFiles { file -> file.isFile && file.name.endsWith(MODERN_EXT) && !named(file) }
            ?.forEach { it.delete() }
    }

    /** Sets the display name of the stored skin [id]. */
    fun setName(
        id: String,
        name: String,
    ) {
        prefs.edit().putString(nameKey(id), name).apply()
    }

    fun delete(id: String) {
        if (BundledSkins.of(id) != null) return // a skin that ships with the app cannot be removed
        // read while the file is there: a missing id reads classic
        val modern = kindOf(id) == SkinKind.MODERN
        fileFor(id).delete()
        prefs.edit().remove(nameKey(id)).apply()
        synchronized(guard) {
            // deleting the skin that is on counts as a choice, so a source's skin requested
            // before it is turned down by [wear]
            val moved = currentId == id || wearing == id
            if (prefs.getString(CLASSIC_KEY, null) == id) prefs.edit().putString(CLASSIC_KEY, SkinEntry.BASE_ID).apply()
            if (widgetSkin == id) forgetWidgetSkin()
            if (currentId == id) {
                if (modern) {
                    // a classic skin goes on in place of the modern one
                    currentId = lastClassic
                    clearAutomaticWidgetSkin()
                } else {
                    currentId = SkinEntry.BASE_ID
                }
            }
            if (wearing == id) wearing = null
            if (moved) chosen++
        }
    }

    /** Whether [id] is a skin this install can wear: one that ships with the app, or one on disk. */
    fun has(id: String): Boolean = BundledSkins.of(id) != null || fileFor(id).isFile

    /** Whether [id] is installed and classic: what a widget, Android Auto or a source can wear. */
    fun hasClassic(id: String): Boolean = has(id) && kindOf(id) == SkinKind.CLASSIC

    /** MODERN when [id] is stored as a modern skin; CLASSIC otherwise, also for an id that is not installed. */
    fun kindOf(id: String): SkinKind = if (File(dir, "$id$MODERN_EXT").isFile) SkinKind.MODERN else SkinKind.CLASSIC

    /** The stored file of [id], or null for a skin that ships with the app or is not installed. */
    fun fileOf(id: String): File? = fileFor(id).takeIf { BundledSkins.of(id) == null && it.isFile }

    /**
     * Renames, once, every stored classic file that is really a modern skin to `<id>.wal`, and
     * drops ".wal" from its display name. The id is the hash of the bytes, so every record
     * that names it still names the same file. A modern skin is never a source's skin, the
     * last classic choice or the widget's skin, so those records let go of a renamed id.
     *
     * Returns whether anything was renamed. Each file is read for its zip directory only.
     */
    fun migrateModern(): Boolean {
        if (prefs.getBoolean(MODERN_MIGRATED_KEY, false)) return false
        val renamed =
            (dir.listFiles()?.toList() ?: emptyList())
                .filter { file ->
                    file.isFile && file.name.endsWith(CLASSIC_EXT) &&
                        directoryNames(file)?.let(WalArchive::isModern) == true
                }.mapNotNull { file ->
                    val id = file.name.removeSuffix(CLASSIC_EXT)
                    val target = File(dir, "$id$MODERN_EXT")
                    // the same bytes stored under both names: the modern copy stays
                    val moved = if (target.isFile) file.delete() else file.renameTo(target)
                    id.takeIf { moved }
                }.toSet()
        prefs
            .edit()
            .apply {
                renamed.forEach { id ->
                    prefs.getString(nameKey(id), null)?.let { putString(nameKey(id), modernName(it).ifEmpty { it }) }
                }
            }.apply()
        synchronized(guard) {
            if (wearing in renamed) wearing = null
            if (prefs.getString(CLASSIC_KEY, null) in renamed) prefs.edit().remove(CLASSIC_KEY).apply()
            if (widgetSkin in renamed) forgetWidgetSkin()
            prefs.edit().putBoolean(MODERN_MIGRATED_KEY, true).apply()
        }
        return renamed.isNotEmpty()
    }

    /** The listener's own choice of skin, from the Skins menu or the manager. */
    var currentId: String
        get() = prefs.getString(CURRENT_KEY, SkinEntry.BASE_ID) ?: SkinEntry.BASE_ID
        set(value) {
            prefs.edit().putString(CURRENT_KEY, value).apply()
        }

    /**
     * The listener's last classic choice: the classic skin drawn while their choice is a
     * modern one, by the menus, the windows, the widget and Android Auto. The stored value
     * when it names an installed classic skin; otherwise [currentId] when that is one, and
     * the base skin when neither is.
     */
    val lastClassic: String
        get() =
            prefs.getString(CLASSIC_KEY, null)?.takeIf(::hasClassic)
                ?: currentId.takeIf(::hasClassic)
                ?: SkinEntry.BASE_ID

    /**
     * The listener chose [id] themselves (the Skins menu, the manager, a picked file): it
     * becomes their own skin, and a source's skin is taken off.
     *
     * A classic [id] becomes the last classic choice. A modern [id] over a classic choice
     * keeps that classic choice as the last one, and a widget that follows the player is
     * set to it, marked as set automatically. A classic [id] over a modern choice lets go of
     * such an automatic widget skin, so the widget follows the player again.
     */
    fun choose(id: String) {
        synchronized(guard) {
            // the order matters: the last classic choice is read before the modern id is
            // stored, or it would fall back past that id to the base
            val previous = currentId
            val wasClassic = kindOf(previous) == SkinKind.CLASSIC
            if (kindOf(id) == SkinKind.CLASSIC) {
                prefs.edit().putString(CLASSIC_KEY, id).apply()
                if (!wasClassic) clearAutomaticWidgetSkin()
            } else if (wasClassic) {
                if (!prefs.contains(CLASSIC_KEY) && hasClassic(previous)) {
                    prefs.edit().putString(CLASSIC_KEY, previous).apply()
                }
                if (widgetSkin == null) {
                    prefs
                        .edit()
                        .putString(WIDGET_KEY, lastClassic)
                        .putBoolean(WIDGET_AUTO_KEY, true)
                        .apply()
                }
            }
            currentId = id
            wearing = null
            chosen++
        }
    }

    /**
     * When the listener's choice is a modern skin, the last classic choice takes its place,
     * and a widget skin set automatically is let go. It is not counted as a choice, and a
     * source's skin worn over it stays on. Returns whether the choice changed.
     */
    fun dropModern(): Boolean =
        synchronized(guard) {
            if (kindOf(currentId) != SkinKind.MODERN) return@synchronized false
            currentId = lastClassic
            clearAutomaticWidgetSkin()
            true
        }

    /**
     * The skin the home screen widget wears, or null when it follows the player. It is set
     * on the widget's settings page, or automatically when a modern skin goes on; see
     * [choose].
     */
    val widgetSkin: String? get() = prefs.getString(WIDGET_KEY, null)

    /** Whether [widgetSkin] was set automatically, and so goes when a classic skin goes on again. */
    val widgetSkinAutomatic: Boolean get() = prefs.contains(WIDGET_AUTO_KEY)

    /**
     * The listener picked [id] for the widget, or null to have it follow the player. A skin
     * picked here stays when the player's skin changes.
     */
    fun chooseWidgetSkin(id: String?) {
        synchronized(guard) {
            prefs
                .edit()
                .apply { if (id == null) remove(WIDGET_KEY) else putString(WIDGET_KEY, id) }
                .remove(WIDGET_AUTO_KEY)
                .apply()
        }
    }

    /** Lets go of a widget skin that was set automatically. Call under the guard. */
    private fun clearAutomaticWidgetSkin() {
        if (widgetSkinAutomatic) forgetWidgetSkin()
    }

    /** The widget follows the player again. Call under the guard. */
    private fun forgetWidgetSkin() {
        prefs
            .edit()
            .remove(WIDGET_KEY)
            .remove(WIDGET_AUTO_KEY)
            .apply()
    }

    /**
     * Puts [sourceSkin] on over the listener's own, or takes a source's skin off with
     * null, unless the listener has chosen a skin since [asked], a value of [choices] read
     * when the track changed. A source's skin is decided on one thread and stored on
     * another, and a choice made in between wins.
     *
     * A source asking for the skin that is already the listener's own wears nothing.
     * Returns the id worn after the call, or null when the request was turned down and
     * nothing was written.
     */
    fun wear(
        sourceSkin: String?,
        asked: Int,
    ): String? =
        synchronized(guard) {
            if (chosen != asked) {
                null
            } else {
                wearing = sourceSkin?.takeIf { it != currentId }
                worn
            }
        }

    /** [id] was worn for a source and does not load: the listener's own goes back on. */
    fun unwear(id: String) {
        synchronized(guard) { if (wearing == id) wearing = null }
    }

    /**
     * A skin worn over the listener's own while a music source asks for it, or null; see
     * [SourceSkins]. It is stored apart from [currentId], so wearing it never changes the
     * listener's choice, and it is stored so the widget and a relaunch draw the worn skin.
     */
    var wearing: String?
        get() = prefs.getString(WEARING_KEY, null)
        set(value) {
            prefs.edit().apply { if (value == null) remove(WEARING_KEY) else putString(WEARING_KEY, value) }.apply()
        }

    /** What is on screen: a source's skin while one is worn, the listener's own otherwise. */
    val worn: String get() = wearing ?: currentId

    fun nameOf(id: String): String = BundledSkins.of(id)?.name ?: prefs.getString(nameKey(id), null) ?: id

    /** The stored file of [id]: the modern one when there is one, the classic one otherwise. */
    private fun fileFor(id: String): File = File(dir, "$id$MODERN_EXT").takeIf { it.isFile } ?: File(dir, "$id$CLASSIC_EXT")

    private fun nameKey(id: String) = "name:$id"

    private fun hash(bytes: ByteArray): String = idOf(MessageDigest.getInstance("SHA-256").digest(bytes))

    companion object {
        private const val TAG = "SkinLibrary"
        private const val CLASSIC_EXT = ".wsz"
        private const val MODERN_EXT = ".wal"
        private const val CURRENT_KEY = "current"
        private const val WEARING_KEY = "wearing"
        private const val CLASSIC_KEY = "classic"
        private const val WIDGET_KEY = "widget"
        private const val WIDGET_AUTO_KEY = "widget_auto"
        private const val MODERN_MIGRATED_KEY = "modern_migrated"

        /** Hex characters of the SHA-256 kept as the id. */
        private const val HASH_CHARS = 32

        /**
         * Guards the records of what is worn (the choice, a source's skin, the last classic
         * choice and the widget's skin) and [choices]. It is process-wide, like the
         * preferences file, because the player and the widget each make their own
         * [SkinLibrary].
         */
        private val guard = Any()

        @Volatile
        private var chosen = 0

        /**
         * How many times the listener has chosen a skin in this process, or deleted the
         * one that was on. A caller about to store a source's skin reads this first and
         * passes it to [SkinLibrary.wear].
         */
        val choices: Int get() = chosen

        /**
         * The listener is choosing a skin. Counted at once, before the choice has loaded
         * or been stored, so a source's skin requested before this is turned down.
         */
        fun choosing() {
            synchronized(guard) { chosen++ }
        }

        /** The id of a skin whose bytes have the SHA-256 [digest], as [idFor] gives it. */
        fun idOf(digest: ByteArray): String = digest.joinToString("") { "%02x".format(it) }.take(HASH_CHARS)

        /**
         * The display name of a modern skin picked as [fileName]: the name without a trailing
         * ".wal", in any case. Empty when nothing is left.
         */
        fun modernName(fileName: String): String =
            if (fileName.endsWith(MODERN_EXT, ignoreCase = true)) fileName.dropLast(MODERN_EXT.length) else fileName

        /**
         * The names in [file]'s zip directory, or null when it has none that can be read. ISO
         * 8859-1 reads every name, whatever the archiver wrote; only skin.xml matters.
         */
        internal fun directoryNames(file: File): List<String>? =
            runCatching {
                ZipFile(file, Charsets.ISO_8859_1).use { zip ->
                    zip
                        .entries()
                        .asSequence()
                        .map { it.name }
                        .toList()
                }
            }.getOrNull()
    }
}
