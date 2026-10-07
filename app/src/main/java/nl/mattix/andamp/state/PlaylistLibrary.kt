// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.model.Track
import java.io.File

/**
 * The saved playlists a listener can browse: one .m3u per list in an app-owned folder,
 * written by LIST > SAVE LIST and read by the library window's LISTS tab. Lists saved
 * through the document picker are not here, because the app cannot enumerate them.
 *
 * Plain JVM (File + [PlaylistCodec]), so tests run against a temp directory.
 */
class PlaylistLibrary(
    private val dir: File,
) {
    /** One saved list on the shelf: its name and how much it holds. */
    data class Entry(
        val name: String,
        val trackCount: Int,
    )

    /** Every saved list, by name; unreadable files are skipped. */
    fun list(): List<Entry> =
        dir
            .listFiles { f: File -> f.isFile && f.extension == EXTENSION }
            .orEmpty()
            .mapNotNull { file ->
                runCatching {
                    Entry(file.nameWithoutExtension, PlaylistCodec.decode(file.readText()).tracks.size)
                }.getOrNull()
            }.sortedBy { it.name.lowercase() }

    /** Saves (or overwrites) [name], atomically. */
    fun save(
        name: String,
        tracks: List<Track>,
    ): Boolean = runCatching { fileFor(name).writeAtomically(PlaylistCodec.encode(PlaylistCodec.Saved(tracks, 0))) }.isSuccess

    fun load(name: String): List<Track>? =
        runCatching { PlaylistCodec.decode(fileFor(name).readText()).tracks }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }

    /** Deletes [name]; false when it was not there or could not be deleted. */
    fun delete(name: String): Boolean = runCatching { fileFor(name).delete() }.getOrDefault(false)

    /** A list's name as a file name: path characters become spaces. */
    private fun fileFor(name: String): File {
        val safe =
            name
                .map { c -> if (c in UNSAFE || c.isISOControl()) ' ' else c }
                .joinToString("")
                .trim()
                .ifEmpty { "Playlist" }
        return File(dir, "$safe.$EXTENSION")
    }

    private companion object {
        const val EXTENSION = "m3u"
        val UNSAFE = setOf('/', '\\')
    }
}
