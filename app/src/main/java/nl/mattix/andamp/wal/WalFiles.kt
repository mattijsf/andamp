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
     */
    fun open(
        skin: File,
        siblings: List<File>,
        base: SkinFiles = AndampDefaultSet,
    ): SkinFiles {
        val byName = siblings.filter { it != skin }.associateBy { folderName(it.nameWithoutExtension) }
        val opened = HashMap<String, SkinFiles?>()
        // the engine and the screen both read files: the map is guarded, and an archive opens
        // outside the lock, so one thread never waits on the other's reading. Two that open the
        // same sibling at once keep the first.
        val sibling = sibling@{ name: String ->
            synchronized(opened) { if (name in opened) return@sibling opened[name] }
            // a sibling that does not open is read as missing, and not tried again
            val made = byName[name]?.let { runCatching { ZipSkinFiles(it) }.getOrNull() }
            synchronized(opened) { if (name in opened) opened[name] else made.also { opened[name] = it } }
        }
        return LayeredSkinFiles(SkinsFolderFiles(ZipSkinFiles(skin), sibling), base)
    }

    /** How a path names a sibling: its folder name, lowercased, without spaces. */
    fun folderName(name: String): String = name.replace(" ", "").lowercase()
}
