// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import nl.mattix.andamp.modernskin.skin.AndampDefaultSet
import nl.mattix.andamp.modernskin.skin.LayeredSkinFiles
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinsFolderFiles
import nl.mattix.andamp.modernskin.skin.WalArchive
import nl.mattix.andamp.modernskin.skin.ZipSkinFiles
import nl.mattix.andamp.modernskin.xml.XmlNode
import nl.mattix.andamp.modernskin.xml.XmlParser
import java.io.File
import java.util.zip.ZipFile

/** A modern skin's files as its runtime reads them. */
internal object WalFiles {
    /**
     * The files of [skin]: the skin itself, the other skins in [siblings] it names by folder
     * (Bento reads "Big Bento"), each opened the first time a path names it, and [base], the
     * default element set, behind them.
     *
     * [siblings] maps the names a path may use for a skin to its file: in the library, each skin's
     * name and its skininfo name, since the files there are named by hash (see [siblings]). Names
     * are compared as [folderName] gives them. A file under two names is opened once. A name of
     * [skin] itself reads the skin, as Winamp reads its folder (Big Bento names "Big Bento" for its
     * scroll bars and its notifier script).
     */
    fun open(
        skin: File,
        siblings: Map<String, File>,
        base: SkinFiles = AndampDefaultSet,
    ): SkinFiles {
        val skinFiles = ZipSkinFiles(skin)
        val byName = siblings.mapKeys { folderName(it.key) }
        val opened = HashMap<File, SkinFiles?>()
        // the engine and the screen both read files: the map is guarded, and an archive opens
        // outside the lock, so one thread never waits on the other's reading. Two that open the
        // same sibling at once keep the first.
        val sibling = sibling@{ name: String ->
            val file = byName[name] ?: return@sibling null
            if (file == skin) return@sibling skinFiles
            synchronized(opened) { if (file in opened) return@sibling opened[file] }
            // a sibling that does not open is read as missing, and not tried again
            val made = runCatching { ZipSkinFiles(file) }.getOrNull()
            synchronized(opened) { if (file in opened) opened[file] else made.also { opened[file] = it } }
        }
        return LayeredSkinFiles(SkinsFolderFiles(skinFiles, sibling), base)
    }

    /** The files of [skin], with the other skins of a folder, [siblings], each named by its file name. */
    fun open(
        skin: File,
        siblings: List<File>,
        base: SkinFiles = AndampDefaultSet,
    ): SkinFiles = open(skin, siblings.associateBy { it.nameWithoutExtension }, base)

    /** How a path names a sibling: its folder name, lowercased, without spaces. */
    fun folderName(name: String): String = name.replace(" ", "").lowercase()

    /**
     * The names a path may use for each of the library's modern [skins], given as each skin's name
     * in the library with its file, as [open] takes them: that name, and the name the skin's
     * skininfo gives (see [infoName]). A name in the library wins over another skin's skininfo name.
     * Reads each skin's skin.xml, so it runs off the main thread.
     */
    fun siblings(skins: List<Pair<String, File>>): Map<String, File> {
        val byName = LinkedHashMap<String, File>()
        for ((_, file) in skins) infoName(file)?.let { byName[folderName(it)] = file }
        for ((name, file) in skins) byName[folderName(name)] = file
        return byName
    }

    /**
     * The name [skin]'s `<skininfo>` gives in its skin.xml, as the runtime reads it, or null when it
     * gives none or the file does not read as a modern skin. Only that one entry is unpacked.
     */
    fun infoName(skin: File): String? =
        runCatching {
            ZipFile(skin).use { zip ->
                val entries = zip.entries().toList()
                val folder = WalArchive.skinFolder(entries.map { it.name }) ?: return@use null
                val entry = entries.firstOrNull { SkinFiles.normalize(it.name) == "${folder}skin.xml" } ?: return@use null
                val text = zip.getInputStream(entry).use(::readHead)
                XmlParser
                    .parse(text)
                    .flatMap { listOf(it) + it.children }
                    .firstOrNull { it.name == "skininfo" }
                    ?.let(::nameIn)
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }

    /** The name a `<skininfo>` element gives: its `<name>` element's text, else that element's text attribute. */
    private fun nameIn(info: XmlNode): String? =
        info.children
            .firstOrNull { it.name == "name" }
            ?.let { it.text.ifEmpty { it["text"] } }

    /** Up to [MAX_SKIN_XML] bytes of [input], as text. */
    private fun readHead(input: java.io.InputStream): String {
        val bytes = ByteArray(MAX_SKIN_XML)
        var n = 0
        while (n < bytes.size) {
            val read = input.read(bytes, n, bytes.size - n)
            if (read < 0) break
            n += read
        }
        return String(bytes, 0, n, Charsets.UTF_8)
    }

    /** The most of a skin.xml read for its name; the corpus's are a few KB. */
    private const val MAX_SKIN_XML = 256 * 1024
}
