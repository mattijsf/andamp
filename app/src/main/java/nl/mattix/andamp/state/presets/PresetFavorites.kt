// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.presets

import android.content.Context
import android.content.SharedPreferences
import nl.mattix.andamp.state.VisPlugin
import java.io.File

/**
 * A preset the listener marked as a favorite: a file inside one installed pack, for one engine.
 *
 * It is filed by the pack's name and the file's place inside the pack, not by an absolute path,
 * so it follows the pack wherever the library keeps it, and it ends with the pack.
 */
data class Favorite(
    val plugin: VisPlugin,
    val pack: String,
    /** The file inside the pack's directory, with `/` between folders. */
    val path: String,
) {
    /** Its file, in the installed [packs]; null when the pack or the file is gone. */
    fun file(packs: List<InstalledPack>): File? =
        packs
            .firstOrNull { it.name == pack }
            ?.let { File(it.dir, path) }
            ?.takeIf { it.isFile }

    companion object {
        /** The favorite for [file], when it lies inside one of [packs]; null otherwise. */
        fun of(
            plugin: VisPlugin,
            file: File,
            packs: List<InstalledPack>,
        ): Favorite? {
            val pack = packs.firstOrNull { file.absolutePath.startsWith(it.dir.absolutePath + File.separator) } ?: return null
            return Favorite(plugin, pack.name, file.relativeTo(pack.dir).invariantSeparatorsPath)
        }
    }
}

/** The favorites that still have a pack to play from: a pack removed, or a file gone from it, takes its favorites along. */
fun List<Favorite>.stillThere(packs: List<InstalledPack>): List<Favorite> = filter { it.file(packs) != null }

/** Keeps the favorites across launches, in the order they were added. */
class FavoriteStore(
    context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<Favorite> =
        prefs
            .getString(LIST, null)
            .orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val (plugin, pack, path) = line.split(SEPARATOR).takeIf { it.size == 3 } ?: return@mapNotNull null
                VisPlugin.entries.firstOrNull { it.name == plugin }?.let { Favorite(it, pack, path) }
            }.toList()

    fun save(favorites: List<Favorite>) {
        prefs
            .edit()
            .putString(
                LIST,
                favorites.joinToString("\n") {
                    listOf(it.plugin.name, it.pack, it.path).joinToString(SEPARATOR)
                },
            ).apply()
    }

    private companion object {
        const val PREFS = "preset_favorites"
        const val LIST = "list"

        // a tab is not in a pack's name or a file's path the library writes
        const val SEPARATOR = "\t"
    }
}
