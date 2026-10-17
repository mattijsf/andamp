// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.presets

import nl.mattix.andamp.state.VisPlugin
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Where imported preset packs are stored: `.milk` for Milkdrop, `.avs` for AVS.
 *
 * Both engines load presets by filesystem path, and Android's document picker hands out
 * `content://` URIs, so a pack is copied into app-private storage. One store serves both
 * kinds, and a pack records how many of each it holds.
 *
 * Packs are not in the repository, because they carry their authors' terms (NOTICE.md).
 * They arrive as zips the listener picked.
 */
class PresetLibrary(
    private val root: File,
) {
    /** Installed packs in name order, each with counts of what it contains. */
    fun packs(): List<InstalledPack> =
        (root.listFiles { file -> file.isDirectory } ?: emptyArray())
            .map { inspect(it) }
            .filter { it.presetCount > 0 || it.avsCount > 0 }
            .sortedBy { it.name.lowercase() }

    fun pack(name: String): InstalledPack? = packs().firstOrNull { it.name == name }

    /**
     * Writes the pack the app itself ships, which is generated from code. Returns true
     * only on the first install, when the host may auto-select it, and false on upgrades.
     *
     * The version stamp is stored next to the pack directory, so a pack the listener
     * deleted stays deleted: files are written into an absent directory only when there is
     * no stamp.
     */
    fun install(
        name: String,
        files: Map<String, ByteArray>,
        version: Int,
    ): Boolean {
        val dir = File(root, sanitize(name))
        val stamp = File(root, ".${sanitize(name)}-version")
        val first = !stamp.exists()
        val current = stamp.takeIf { it.exists() }?.readText()?.trim()
        if (!first && (current == version.toString() || !dir.exists())) return false
        dir.deleteRecursively()
        check(dir.mkdirs()) { "could not create $dir" }
        files.forEach { (fileName, bytes) -> File(dir, fileName).writeBytes(bytes) }
        stamp.writeText(version.toString())
        return first
    }

    fun delete(pack: InstalledPack): Boolean = pack.dir.deleteRecursively()

    /**
     * Extracts a `.zip` of presets into a directory named [name].
     *
     * @param onProgress called with the number of files written so far; a large pack is
     *   thousands of files.
     */
    @Throws(IOException::class)
    fun importZip(
        stream: InputStream,
        name: String,
        onProgress: (Int) -> Unit = {},
    ): InstalledPack {
        val dir = File(root, sanitize(name))
        if (dir.exists()) dir.deleteRecursively()
        check(dir.mkdirs()) { "could not create $dir" }
        val target = dir.canonicalFile

        var written = 0
        ZipInputStream(stream.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (extract(zip, entry, dir, target)) onProgress(++written)
                zip.closeEntry()
            }
        }
        return inspect(dir)
    }

    /** Writes one entry. Returns whether a file was written. */
    private fun extract(
        zip: ZipInputStream,
        entry: ZipEntry,
        dir: File,
        target: File,
    ): Boolean {
        val destination = File(dir, entry.name).canonicalFile
        // zip-slip: an entry named ../../ must not be written outside the pack directory
        val insideTarget = destination.path.startsWith(target.path + File.separator)
        if (!insideTarget) return false
        if (entry.isDirectory) {
            destination.mkdirs()
        } else if (isWanted(entry.name)) {
            destination.parentFile?.mkdirs()
            destination.outputStream().use { out -> zip.copyTo(out) }
            return true
        }
        return false
    }

    private fun inspect(dir: File): InstalledPack {
        val milk = mutableListOf<String>()
        val avs = mutableListOf<String>()
        val textureDirs = linkedSetOf<File>()
        dir.walkTopDown().forEach { file ->
            when {
                file.isDirectory && file.name.equals(TEXTURES_DIR, ignoreCase = true) -> textureDirs += file
                file.isFile && file.extension.equals(PRESET_EXT, ignoreCase = true) -> milk += file.absolutePath
                file.isFile && file.extension.equals(AVS_EXT, ignoreCase = true) -> avs += file.absolutePath
            }
        }
        // some packs keep their textures next to the presets, so the pack root is searched too
        textureDirs += dir
        // by the name the selector shows, so a pack that numbers its presets per folder stays grouped
        val byName = compareBy(PresetTitle.ORDER) { path: String -> PresetTitle.of(path) }.thenBy { it.lowercase() }
        return InstalledPack(
            dir.name,
            dir,
            milk.size,
            textureDirs.toList(),
            avs.size,
            milkFiles = milk.sortedWith(byName),
            avsFiles = avs.sortedWith(byName),
        )
    }

    private fun sanitize(name: String) = name.replace(UNSAFE, "_").take(MAX_NAME).ifBlank { "presets" }

    private fun isWanted(entryName: String): Boolean {
        val extension = entryName.substringAfterLast('.', "").lowercase()
        return extension == PRESET_EXT || extension == AVS_EXT || extension in TEXTURE_EXTENSIONS
    }

    private companion object {
        const val PRESET_EXT = "milk"
        const val AVS_EXT = "avs"
        const val TEXTURES_DIR = "textures"
        const val MAX_NAME = 64
        val UNSAFE = Regex("""[^A-Za-z0-9 ._-]""")

        /** Image formats a MilkDrop 2 preset may reference. */
        val TEXTURE_EXTENSIONS = setOf("jpg", "jpeg", "png", "tga", "bmp", "dds", "gif")
    }
}

/** A pack on disk, and what an engine needs to run it. */
data class InstalledPack(
    val name: String,
    val dir: File,
    /** How many `.milk` files it holds, which Milkdrop can run. */
    val presetCount: Int,
    val textureDirs: List<File>,
    /** How many `.avs` files it holds, which AVS can run. */
    val avsCount: Int = 0,
    /** Its `.milk` files, by name: the order the selector lists them and the engine plays them in. */
    val milkFiles: List<String> = emptyList(),
    /** Its `.avs` files, in the same order. */
    val avsFiles: List<String> = emptyList(),
)

/** The files [plugin] plays from this pack, in the order they are listed and played. */
fun InstalledPack.filesFor(plugin: VisPlugin): List<String> =
    when (plugin) {
        VisPlugin.Milkdrop -> milkFiles
        VisPlugin.Avs -> avsFiles
    }

/** Whether this pack holds anything [plugin] can run. */
fun InstalledPack.runsOn(plugin: VisPlugin): Boolean =
    when (plugin) {
        VisPlugin.Milkdrop -> presetCount > 0
        VisPlugin.Avs -> avsCount > 0
    }
