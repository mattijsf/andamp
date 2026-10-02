// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.SharedPreferences
import nl.mattix.andamp.skin.BundledSkin
import nl.mattix.andamp.skin.BundledSkins
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** A skin in the library: one the listener loaded, or one that ships with the app. */
data class SkinEntry(
    /** Content hash for a loaded skin; a fixed id for one that ships with the app. */
    val id: String,
    val name: String,
    val sizeBytes: Long,
) {
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
 * Every .wsz the listener has loaded, kept so the menu can offer them again after a
 * restart. Files are named by content hash, so loading the same skin twice replaces it,
 * and loading a renamed copy only updates its display name.
 */
class SkinLibrary(
    context: Context,
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

    /** The skins that ship with the app first, then the user's, name-sorted. */
    fun list(): List<SkinEntry> =
        BundledSkins.all.map { SkinEntry.of(it) } +
            (dir.listFiles()?.toList() ?: emptyList())
                .filter { it.isFile && it.name.endsWith(SKIN_EXT) }
                .map { file ->
                    val id = file.name.removeSuffix(SKIN_EXT)
                    SkinEntry(id, prefs.getString(nameKey(id), null) ?: id, file.length())
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
     * overwrite the same file, and the display name is updated.
     */
    fun save(
        bytes: ByteArray,
        name: String,
    ): SkinEntry {
        val id = hash(bytes)
        dir.mkdirs()
        val file = fileFor(id)
        // write-then-rename, so a crash mid-write cannot leave a partial skin
        val tmp = File(dir, "$id$SKIN_EXT.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "could not store skin '$name'" }
        }
        prefs.edit().putString(nameKey(id), name).apply()
        return SkinEntry(id, name, file.length())
    }

    fun delete(id: String) {
        if (BundledSkins.of(id) != null) return // a skin that ships with the app cannot be removed
        fileFor(id).delete()
        prefs.edit().remove(nameKey(id)).apply()
        synchronized(guard) {
            // deleting the skin that is on counts as a choice, so a source's skin requested
            // before it is turned down by [wear]
            val moved = currentId == id || wearing == id
            if (currentId == id) currentId = SkinEntry.BASE_ID
            if (wearing == id) wearing = null
            if (moved) chosen++
        }
    }

    /** Whether [id] is a skin this install can wear: one that ships with the app, or one on disk. */
    fun has(id: String): Boolean = BundledSkins.of(id) != null || fileFor(id).isFile

    /** The listener's own choice of skin, from the Skins menu or the manager. */
    var currentId: String
        get() = prefs.getString(CURRENT_KEY, SkinEntry.BASE_ID) ?: SkinEntry.BASE_ID
        set(value) {
            prefs.edit().putString(CURRENT_KEY, value).apply()
        }

    /**
     * The listener chose [id] themselves (the Skins menu, the manager, a picked file): it
     * becomes their own skin, and a source's skin is taken off.
     */
    fun choose(id: String) {
        synchronized(guard) {
            currentId = id
            wearing = null
            chosen++
        }
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

    private fun fileFor(id: String) = File(dir, "$id$SKIN_EXT")

    private fun nameKey(id: String) = "name:$id"

    private fun hash(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
            .take(HASH_CHARS)

    companion object {
        private const val SKIN_EXT = ".wsz"
        private const val CURRENT_KEY = "current"
        private const val WEARING_KEY = "wearing"

        /** Hex characters of the SHA-256 kept as the id. */
        private const val HASH_CHARS = 32

        /**
         * Guards the two records and [choices]. It is process-wide, like the preferences
         * file, because the player and the widget each make their own [SkinLibrary].
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
    }
}
