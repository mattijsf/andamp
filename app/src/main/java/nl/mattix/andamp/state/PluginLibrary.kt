// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.SharedPreferences
import nl.mattix.andamp.core.plugin.PluginSpec
import java.io.File
import java.security.MessageDigest

/**
 * One installed plug-in, as it describes itself.
 *
 * The metadata is the plug-in's own (docs/dsp-plugin-spec.md section 1), read when it was
 * installed and stored, so listing the installed plug-ins does not run their Lua.
 *
 * [pluginId] is what the plug-in calls itself and what the rack stores its settings under;
 * [id] is the hash of the file, which the library stores it under. Two files can claim the
 * same plug-in id.
 */
data class PluginEntry(
    val id: String,
    val pluginId: String,
    val name: String,
    val version: String,
    val author: String,
    val about: String,
    val sizeBytes: Long,
)

/**
 * The Lua plug-ins a listener installed, on disk.
 *
 * Storage mirrors [SkinLibrary]: files under `filesDir`, named by the hash of their
 * contents, with the metadata in a preferences file. There is no current plug-in: the rack
 * decides which of them run. Removing a plug-in's rack settings is [DspOps]'s part.
 */
class PluginLibrary(
    context: Context,
) {
    private val dir = File(context.filesDir, "plugins")
    private val prefs: SharedPreferences = context.getSharedPreferences("plugins", Context.MODE_PRIVATE)

    /** What is installed, by name. */
    fun list(): List<PluginEntry> =
        (dir.listFiles()?.toList() ?: emptyList())
            .filter { it.isFile && it.name.endsWith(EXT) }
            .map { file ->
                val id = file.name.removeSuffix(EXT)
                PluginEntry(
                    id = id,
                    pluginId = prefs.getString(key("plugin", id), null) ?: id,
                    name = prefs.getString(key("name", id), null) ?: id,
                    version = prefs.getString(key("version", id), null) ?: "",
                    author = prefs.getString(key("author", id), null) ?: "",
                    about = prefs.getString(key("about", id), null) ?: "",
                    sizeBytes = file.length(),
                )
            }.sortedBy { it.name.lowercase() }

    /** The Lua of everything installed, in the order [list] reports. */
    fun sources(): List<String> = list().mapNotNull { read(it.id) }

    fun read(id: String): String? = fileFor(id).takeIf { it.isFile }?.readText()

    /**
     * Stores [source] under its content hash and returns the entry. Installing the same
     * file again overwrites the file and its metadata.
     */
    fun save(
        source: String,
        plugin: PluginSpec,
    ): PluginEntry {
        val id = hash(source)
        dir.mkdirs()
        val file = fileFor(id)
        // write-then-rename, so a crash mid-write cannot leave half a plug-in
        val tmp = File(dir, "$id$EXT.tmp")
        tmp.writeText(source)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "could not store plug-in '${plugin.name}'" }
        }
        // the plug-in's own metadata: its id, which the rack stores settings under, and what
        // the list shows
        prefs
            .edit()
            .putString(key("plugin", id), plugin.id)
            .putString(key("name", id), plugin.name)
            .putString(key("version", id), plugin.version)
            .putString(key("author", id), plugin.author)
            .putString(key("about", id), plugin.about)
            .apply()
        return PluginEntry(id, plugin.id, plugin.name, plugin.version, plugin.author, plugin.about, file.length())
    }

    /** What the plug-in calls itself, which is what the rack knows it by. */
    fun pluginIdOf(id: String): String? = prefs.getString(key("plugin", id), null)

    /** What [source] would be stored as, without storing it. */
    fun idFor(source: String): String = hash(source)

    /** What [plugin] would list as, without storing it. */
    fun entryFor(
        source: String,
        plugin: PluginSpec,
    ) = PluginEntry(
        id = hash(source),
        pluginId = plugin.id,
        name = plugin.name,
        version = plugin.version,
        author = plugin.author,
        about = plugin.about,
        sizeBytes = source.toByteArray().size.toLong(),
    )

    fun delete(id: String) {
        fileFor(id).delete()
        val edit = prefs.edit()
        FIELDS.forEach { edit.remove(key(it, id)) }
        edit.apply()
    }

    private fun fileFor(id: String) = File(dir, "$id$EXT")

    private fun key(
        field: String,
        id: String,
    ) = "$field:$id"

    private fun hash(source: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(source.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(HASH_CHARS)

    private companion object {
        const val EXT = ".lua"

        /** Every metadata field stored for one file; all are removed with it. */
        val FIELDS = listOf("plugin", "name", "version", "author", "about")

        /** Hex characters of the SHA-256 kept as the id. */
        const val HASH_CHARS = 32
    }
}
