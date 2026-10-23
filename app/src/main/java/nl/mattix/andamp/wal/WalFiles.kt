// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import nl.mattix.andamp.modernskin.skin.AndampDefaultSet
import nl.mattix.andamp.modernskin.skin.LayeredSkinFiles
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinsFolderFiles
import nl.mattix.andamp.modernskin.skin.ZipSkinFiles
import java.io.File

/** A modern skin's files as its runtime reads them. */
internal object WalFiles {
    /**
     * The files of [skin]: the skin itself, the other skins in [siblings] it names by folder
     * (Bento reads "Big Bento"), each opened the first time a path names it, and [base], the
     * default element set, behind them.
     *
     * [siblings] maps the names a path may use for a skin to its file: in the library, each skin's
     * name and its skininfo name, since the files there are named by hash. Names are compared as
     * [folderName] gives them. A file under two names is opened once. A name of [skin] itself reads
     * the skin, as Winamp reads its folder (Big Bento names "Big Bento" for its scroll bars and its
     * notifier script).
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
}
