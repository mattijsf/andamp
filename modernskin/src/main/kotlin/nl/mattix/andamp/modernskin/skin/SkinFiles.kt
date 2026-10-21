// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.skin

import java.io.File
import java.util.zip.ZipFile

/** The files of one skin, looked up by path without regard to case or slash direction. */
interface SkinFiles {
    fun read(path: String): ByteArray?

    fun exists(path: String): Boolean = read(path) != null

    fun names(): List<String>

    companion object {
        fun normalize(path: String): String =
            path
                .replace(
                    '\\',
                    '/',
                ).trimStart('/')
                .lowercase()
                .split('/')
                .filter { it.isNotEmpty() && it != "." }
                .joinToString("/")
    }
}

/** A `.wal`, which is a zip. The whole archive is read into memory once; skins are a few megabytes. */
class ZipSkinFiles(
    file: File,
) : SkinFiles {
    private val entries: Map<String, ByteArray> =
        ZipFile(file).use { zip ->
            val all =
                zip.entries().asSequence().filter { !it.isDirectory && it.size < MAX_ENTRY }.associate { e ->
                    SkinFiles.normalize(e.name) to zip.getInputStream(e).use { it.readBytes() }
                }
            // some archives hold the skin in one folder: strip it when skin.xml sits there
            val prefix = all.keys.firstOrNull { it.endsWith("/skin.xml") }?.removeSuffix("skin.xml")
            if ("skin.xml" !in all &&
                prefix != null
            ) {
                all.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
            } else {
                all
            }
        }

    override fun read(path: String): ByteArray? = entries[SkinFiles.normalize(path)]

    override fun names(): List<String> = entries.keys.toList()

    private companion object {
        const val MAX_ENTRY = 32L * 1024 * 1024
    }
}

/**
 * A skin's files with Winamp's default element set behind them: paths under [BASE_PREFIX] read
 * from [base] (the `Plugins\freeform\xml\wasabi` folder, zipped), everything else from [skin].
 * Winamp draws lists, scrollbars and frames a skin does not define from that set.
 */
class LayeredSkinFiles(
    private val skin: SkinFiles,
    private val base: SkinFiles,
) : SkinFiles {
    override fun read(path: String): ByteArray? {
        val n = SkinFiles.normalize(path)
        return if (n.startsWith(BASE_PREFIX)) base.read(n.removePrefix(BASE_PREFIX)) else skin.read(path)
    }

    override fun names(): List<String> = skin.names() + base.names().map { BASE_PREFIX + it }

    companion object {
        const val BASE_PREFIX = "wasabi-base/"
    }
}

/**
 * A skin beside the other skins in a Skins folder: a path `../<name>/...` or `@SKINSPATH@/<name>/...`
 * reads from that sibling (Bento takes its scripts and most of its XML from "Big Bento").
 * Siblings are keyed by their folder name lowercased without spaces ("bigbento").
 */
class SkinsFolderFiles(
    private val skin: SkinFiles,
    private val siblings: Map<String, SkinFiles>,
) : SkinFiles {
    private fun sibling(path: String): Pair<SkinFiles, String>? {
        val n = SkinFiles.normalize(path).removePrefix("@skinspath@/")
        val parts = n.split('/')
        val start =
            when {
                parts.firstOrNull() == ".." -> 1
                n != SkinFiles.normalize(path) -> 0
                else -> return null
            }
        val name = parts.getOrNull(start)?.replace(" ", "") ?: return null
        val files = siblings[name] ?: return null
        return files to parts.drop(start + 1).joinToString("/")
    }

    override fun read(path: String): ByteArray? = sibling(path)?.let { (f, p) -> f.read(p) } ?: skin.read(path)

    override fun names(): List<String> = skin.names()
}
